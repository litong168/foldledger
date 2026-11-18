#!/usr/bin/env bash
# 滴水记账 · 同步发版到 Gitee 国内镜像（Git Bash 下运行）
# 用法: GITEE_TOKEN=xxx bash scripts/publish_gitee_mirror.sh
#   版本号/versionCode 从 AppInfo.kt 读取（唯一权威）。
# 前置: ~/.gitee-token 存放私人令牌（需 projects 权限）。
#
# ⚠️ Gitee 的坑（实测 2026-11-18）：
#   1) 建仓时 private=false 常被忽略 → 仓库默认私有，raw 直链对未登录用户 403。必须显式 PATCH。
#   2) 仓库默认分支是 **master**（不是 main）。客户端 GITEE_MANIFEST_URL 必须用 raw/master/。
#   3) **大文件（如 94MB 的 EXE）raw 直链匿名下载会 403：large file require login for access。**
#      所以资产必须走 **Release 附件**（`/releases/download/<tag>/<name>` 匿名可下），
#      manifest 里用 `url` 字段直接写附件地址；只有很小的 manifest 放 raw（小文件可匿名读）。

set -euo pipefail

OWNER="litong168"
REPO="foldledger"
API="https://gitee.com/api/v5"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TOKEN="${GITEE_TOKEN:-}"
if [ -z "$TOKEN" ] && [ -f "$HOME/.gitee-token" ]; then TOKEN="$(cat "$HOME/.gitee-token")"; fi
[ -n "$TOKEN" ] || { echo "缺少 GITEE_TOKEN（环境变量或 ~/.gitee-token）"; exit 1; }

APPINFO="common/src/commonMain/kotlin/com/foldledger/AppInfo.kt"
VERSION="$(grep -oE 'APP_VERSION_NAME = "[^"]+"' "$APPINFO" | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')"
VERSION_CODE="$(grep -oE 'APP_VERSION_CODE = [0-9]+' "$APPINFO" | head -1 | grep -oE '[0-9]+$')"
[ -n "$VERSION" ] && [ -n "$VERSION_CODE" ] || { echo "读不到版本号"; exit 1; }
TAG="v${VERSION}"

APK="dist/滴水记账-v${VERSION}-release.apk"
EXE="dist/滴水记账-v${VERSION}-win-x64-setup.exe"
[ -f "$APK" ] || { echo "缺少 $APK"; exit 1; }
[ -f "$EXE" ] || { echo "缺少 $EXE"; exit 1; }

echo "== 1/6 确认/创建仓库 + 强制公开 =="
CODE="$(curl -sS --max-time 20 -o /dev/null -w '%{http_code}' "${API}/repos/${OWNER}/${REPO}?access_token=${TOKEN}")"
if [ "$CODE" != "200" ]; then
  curl -sS --max-time 20 -X POST "${API}/user/repos" \
    -d "access_token=${TOKEN}" -d "name=${REPO}" -d "private=false" -d "auto_init=true" >/dev/null
  echo "  已创建 ${OWNER}/${REPO}"
else
  echo "  仓库已存在"
fi
curl -sS --max-time 25 -X PATCH "${API}/repos/${OWNER}/${REPO}" \
  --data-urlencode "access_token=${TOKEN}" --data-urlencode "name=${REPO}" \
  --data-urlencode "private=false" >/dev/null
BRANCH="$(curl -sS --max-time 20 "${API}/repos/${OWNER}/${REPO}?access_token=${TOKEN}" | grep -oE '"default_branch":"[^"]*"' | head -1 | cut -d'"' -f4)"
[ -n "$BRANCH" ] || BRANCH="master"
echo "  公开=是，默认分支=${BRANCH}"

echo "== 2/6 计算 SHA256 =="
APK_SHA="$(sha256sum "$APK" | cut -d' ' -f1)"
EXE_SHA="$(sha256sum "$EXE" | cut -d' ' -f1)"
APK_SIZE="$(stat -c%s "$APK")"
EXE_SIZE="$(stat -c%s "$EXE")"
APK_ASSET="drip-ledger-v${VERSION}-arm64.apk"
EXE_ASSET="drip-ledger-v${VERSION}-win-x64-setup.exe"
NOTES="滴水记账 v${VERSION}（versionCode ${VERSION_CODE}），双端同版。"
echo "  apk=$APK_SIZE exe=$EXE_SIZE"

echo "== 3/6 找/建 Release ${TAG} =="
RID="$(curl -sS --max-time 20 "${API}/repos/${OWNER}/${REPO}/releases/tags/${TAG}?access_token=${TOKEN}" | grep -oE '"id":[0-9]+' | head -1 | cut -d':' -f2)"
if [ -z "$RID" ]; then
  curl -sS --max-time 30 -X POST "${API}/repos/${OWNER}/${REPO}/releases" \
    --data-urlencode "access_token=${TOKEN}" \
    --data-urlencode "tag_name=${TAG}" \
    --data-urlencode "name=滴水记账 ${TAG}" \
    --data-urlencode "body=${NOTES}" \
    --data-urlencode "target_commitish=${BRANCH}" >/dev/null
  RID="$(curl -sS --max-time 20 "${API}/repos/${OWNER}/${REPO}/releases/tags/${TAG}?access_token=${TOKEN}" | grep -oE '"id":[0-9]+' | head -1 | cut -d':' -f2)"
fi
[ -n "$RID" ] || { echo "  Release 创建失败"; exit 1; }
echo "  Release id=$RID"

echo "== 4/6 清空旧附件（可重跑）=="
for AID in $(curl -sS --max-time 20 "${API}/repos/${OWNER}/${REPO}/releases/${RID}?access_token=${TOKEN}" | grep -oE '"id":[0-9]+,"name"' | grep -oE '[0-9]+'); do
  curl -sS --max-time 20 -X DELETE "${API}/repos/${OWNER}/${REPO}/releases/${RID}/attach_files/${AID}?access_token=${TOKEN}" >/dev/null
  echo "  删除旧附件 $AID"
done

echo "== 5/6 上传 EXE（94MB，耐心等）=="
EXE_URL="$(curl -sS --max-time 900 -X POST "${API}/repos/${OWNER}/${REPO}/releases/${RID}/attach_files" \
  -F "access_token=${TOKEN}" -F "file=@${EXE}" | grep -oE '"browser_download_url":"[^"]*"' | head -1 | cut -d'"' -f4)"
[ -n "$EXE_URL" ] || { echo "  EXE 附件上传失败"; exit 1; }
echo "  ok"

echo "== 6/6 上传 APK + 生成 manifest（资产写附件 URL）并传 raw =="
APK_URL="$(curl -sS --max-time 300 -X POST "${API}/repos/${OWNER}/${REPO}/releases/${RID}/attach_files" \
  -F "access_token=${TOKEN}" -F "file=@${APK}" | grep -oE '"browser_download_url":"[^"]*"' | head -1 | cut -d'"' -f4)"
[ -n "$APK_URL" ] || { echo "  APK 附件上传失败"; exit 1; }
echo "  ok"

python - "$VERSION" "$VERSION_CODE" "$APK_ASSET" "$APK_SIZE" "$APK_SHA" "$APK_URL" "$EXE_ASSET" "$EXE_SIZE" "$EXE_SHA" "$EXE_URL" "$NOTES" > /tmp/gitee-manifest.json <<'PY'
import json, sys
v, code, apk_n, apk_s, apk_h, apk_u, exe_n, exe_s, exe_h, exe_u, notes = sys.argv[1:12]
m = {
    "versionCode": int(code), "versionName": v, "minVersionCode": 1,
    "notes": notes,
    "assets": {
        "android": {"name": apk_n, "size": int(apk_s), "sha256": apk_h, "url": apk_u},
        "windows": {"name": exe_n, "size": int(exe_s), "sha256": exe_h, "url": exe_u},
    },
}
print(json.dumps(m, ensure_ascii=False, indent=2))
PY

B64="$(mktemp)"; base64 -w0 /tmp/gitee-manifest.json > "$B64"
MSHA="$(curl -sS --max-time 20 "${API}/repos/${OWNER}/${REPO}/contents/release/update-manifest.json?access_token=${TOKEN}" | grep -oE '"sha":"[^"]*"' | head -1 | cut -d'"' -f4)"
METHOD="POST"; [ -n "$MSHA" ] && METHOD="PUT"
curl -sS --max-time 120 -X "$METHOD" "${API}/repos/${OWNER}/${REPO}/contents/release/update-manifest.json" \
  -d "access_token=${TOKEN}" \
  --data-urlencode "content@${B64}" \
  --data-urlencode "message=release ${TAG}: update-manifest.json" \
  ${MSHA:+--data-urlencode "sha=${MSHA}"} >/dev/null
rm -f "$B64" /tmp/gitee-manifest.json

echo "== 完成 =="
echo "  manifest: https://gitee.com/${OWNER}/${REPO}/raw/${BRANCH}/release/update-manifest.json"
echo "  release:  https://gitee.com/${OWNER}/${REPO}/releases/tag/${TAG}"
