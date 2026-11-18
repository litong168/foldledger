#!/usr/bin/env bash
# 滴水记账 · 一键发布到 GitHub Releases（Git Bash 下运行）
# 用法: bash scripts/publish_github_release.sh
#  版本号/versionCode 从 AppInfo.kt 读取（唯一权威），无需手工传参。
# 前置: ~/.github-pat 存放 PAT；~/.git-credentials-github 存放 HTTPS 凭据（仅 github.com）
# 铁律: 不改动签名库/包名/升级码；发布包不进 git，只作为 Release asset；上传前核验 APK 签名指纹。

set -euo pipefail

OWNER="litong168"
REPO="foldledger"
RID_FILE=".github-release-id"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TOKEN_FILE="$HOME/.github-pat"
[ -f "$TOKEN_FILE" ] || { echo "缺少 $TOKEN_FILE"; exit 1; }
TOKEN="$(cat "$TOKEN_FILE")"

APPINFO="common/src/commonMain/kotlin/com/foldledger/AppInfo.kt"
VERSION="$(grep -oE 'APP_VERSION_NAME = "[^"]+"' "$APPINFO" | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')"
VERSION_CODE="$(grep -oE 'APP_VERSION_CODE = [0-9]+' "$APPINFO" | head -1 | grep -oE '[0-9]+$')"
[ -n "$VERSION" ] && [ -n "$VERSION_CODE" ] || { echo "读不到版本号"; exit 1; }
TAG="v${VERSION}"

APK="dist/滴水记账-v${VERSION}-release.apk"
EXE="dist/滴水记账-v${VERSION}-win-x64-setup.exe"
[ -f "$APK" ] || { echo "缺少 $APK"; exit 1; }
[ -f "$EXE" ] || { echo "缺少 $EXE"; exit 1; }

echo "== 1/6 核验 APK 签名（铁律指纹 3F:00:15:53...32:E2）=="
JAVA="C:/Users/16039/.workbuddy/binaries/jdk/17.0.2/bin/java.exe"
SIGNER="$(find /c/UnionAPKBuild/sdk/build-tools -name apksigner.jar 2>/dev/null | sort -V | tail -1)"
FP="$("$JAVA" -jar "$SIGNER" verify --print-certs "$APK" 2>&1 | grep -i 'SHA-256 digest' | head -1 | grep -oiE '[0-9a-f]{64}' | head -1)"
echo "  APK 指纹: $FP"
[ "${FP,,}" = "3f0015531bc7bd6eac7eb106969dd7f17810993e2d839537ca28d20a975132e2" ] || { echo "签名不符，终止"; exit 1; }

echo "== 2/6 计算双端 SHA256 + size，生成 update-manifest.json =="
APK_SHA="$(sha256sum "$APK" | cut -d' ' -f1)"
EXE_SHA="$(sha256sum "$EXE" | cut -d' ' -f1)"
APK_SIZE="$(stat -c%s "$APK")"
EXE_SIZE="$(stat -c%s "$EXE")"
APK_NAME="drip-ledger-v${VERSION}-arm64.apk"
EXE_NAME="drip-ledger-v${VERSION}-win-x64-setup.exe"
# ⚠️ notes 用纯文本：App 的更新弹窗直接把 notes 当纯文本显示（不渲染 Markdown），
#   写 `## 标题` 会原样露出井号。详见 16.6.0 真机验收记录。
NOTES="$(printf '滴水记账 v%s（versionCode %s），双端同版。内置简体中文 / English 界面，下载对应平台的安装包即可。' "$VERSION" "$VERSION_CODE")"
python - "$VERSION" "$VERSION_CODE" "$APK_NAME" "$APK_SIZE" "$APK_SHA" "$EXE_NAME" "$EXE_SIZE" "$EXE_SHA" "$NOTES" <<'PY' > update-manifest.json
import json, sys
v, code, apk_n, apk_s, apk_h, exe_n, exe_s, exe_h, notes = sys.argv[1:10]
m = {
    "versionCode": int(code), "versionName": v, "minVersionCode": 1,
    "notes": notes,
    "assets": {
        "android": {"name": apk_n, "size": int(apk_s), "sha256": apk_h},
        "windows": {"name": exe_n, "size": int(exe_s), "sha256": exe_h},
    },
}
print(json.dumps(m, ensure_ascii=False, indent=2))
PY
echo "  manifest: versionCode=$VERSION_CODE apk=$APK_SIZE exe=$EXE_SIZE"

echo "== 3/6 push 代码（master -> main）=="
GIT_TERMINAL_PROMPT=0 git push github master:main

echo "== 4/6 建或复用 Release ${TAG} =="
RELEASE_JSON="$(curl -sS --max-time 30 -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" "https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/${TAG}")"
RID="$(echo "$RELEASE_JSON" | grep -oE '"id": ?[0-9]+' | head -1 | grep -oE '[0-9]+' || true)"
if [ -z "$RID" ]; then
  RELEASE_JSON="$(curl -sS --max-time 30 -X POST -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/${OWNER}/${REPO}/releases" \
    -d "{\"tag_name\":\"${TAG}\",\"target_commitish\":\"main\",\"name\":\"滴水记账 ${TAG}\",\"body\":$(printf '%s' "$NOTES" | python -c 'import json,sys;print(json.dumps(sys.stdin.read()))'),\"draft\":false,\"prerelease\":false}")"
  RID="$(echo "$RELEASE_JSON" | grep -oE '"id": ?[0-9]+' | head -1 | grep -oE '[0-9]+')"
fi
echo "$RID" > "$RID_FILE"
echo "  Release id: $RID"

echo "== 5/6 删除旧资产（可重跑）=="
ASSETS_JSON="$(curl -sS --max-time 20 -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/${OWNER}/${REPO}/releases/${RID}/assets")"
for NAME in "$APK_NAME" "$EXE_NAME" "update-manifest.json"; do
  for AID in $(echo "$ASSETS_JSON" | python -c 'import json,sys; d=json.load(sys.stdin); [print(a["id"]) for a in d if a["name"]=="'"$NAME"'"]' 2>/dev/null); do
    curl -sS --max-time 20 -X DELETE -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/${OWNER}/${REPO}/releases/assets/${AID}" >/dev/null
    echo "  删除旧资产 $NAME ($AID)"
  done
done

echo "== 6/6 上传资产 =="
upload_asset() {
  local FILE="$1" NAME="$2"
  echo "  上传 $NAME ($(stat -c%s "$FILE") 字节)..."
  curl -sS --max-time 400 -X POST -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
    -H "Content-Type: application/octet-stream" --data-binary "@$FILE" \
    "https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${RID}/assets?name=${NAME}" \
    | grep -oE '"(name|state|size)": ?("[^"]*"|[0-9]+)' | head -3
}
upload_asset "$APK" "$APK_NAME"
upload_asset "$EXE" "$EXE_NAME"
upload_asset "update-manifest.json" "update-manifest.json"
rm -f update-manifest.json

echo "== 完成 =="
echo "  Release: https://github.com/${OWNER}/${REPO}/releases/tag/${TAG}"
