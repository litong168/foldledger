#!/usr/bin/env bash
# 滴水记账 · 一键发布到 GitHub Releases（Git Bash 下运行）
# 用法: bash scripts/publish_github_release.sh <version>  例如: bash scripts/publish_github_release.sh 16.5.3
# 前置: ~/.github-pat 存放 PAT；~/.git-credentials-github 存放 HTTPS 凭据（仅 github.com）
# 铁律: 不改动签名库/包名/升级码；发布包不进 git，只作为 Release asset；上传前核验 APK 签名指纹。

set -euo pipefail

VERSION="${1:?用法: publish_github_release.sh <version>}"
TAG="v${VERSION}"
OWNER="litong168"
REPO="foldledger"
RID_FILE=".github-release-id"   # 记住本次 release id，便于重传 asset

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TOKEN_FILE="$HOME/.github-pat"
[ -f "$TOKEN_FILE" ] || { echo "缺少 $TOKEN_FILE"; exit 1; }
TOKEN="$(cat "$TOKEN_FILE")"

APK="dist/滴水记账-${VERSION}-release.apk"
EXE="dist/滴水记账-${VERSION}-win-x64-setup.exe"
[ -f "$APK" ] || { echo "缺少 $APK"; exit 1; }
[ -f "$EXE" ] || { echo "缺少 $EXE"; exit 1; }

echo "== 1/5 核验 APK 签名（铁律指纹 3F:00:15:53...32:E2）=="
JAVA="C:/Users/16039/.workbuddy/binaries/jdk/17.0.2/bin/java.exe"
SIGNER="$(find /c/UnionAPKBuild/sdk/build-tools -name apksigner.jar 2>/dev/null | sort -V | tail -1)"
FP="$("$JAVA" -jar "$SIGNER" verify --print-certs "$APK" 2>&1 | grep -i 'SHA-256 digest' | head -1 | grep -oiE '[0-9a-f]{64}' | head -1)"
echo "  APK 指纹: $FP"
[ "${FP,,}" = "3f0015531bc7bd6eac7eb106969dd7f17810993e2d839537ca28d20a975132e2" ] || { echo "签名不符，终止"; exit 1; }

echo "== 2/5 确认版本号与 AppInfo 一致 =="
grep -q "APP_VERSION_NAME = \"${VERSION}\"" common/src/commonMain/kotlin/com/foldledger/AppInfo.kt || { echo "版本号不一致"; exit 1; }

echo "== 3/5 push 代码（master -> main）=="
GIT_TERMINAL_PROMPT=0 git push github master:main

echo "== 4/5 建或复用 Release ${TAG} =="
RELEASE_JSON="$(curl -sS --max-time 30 -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" "https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/${TAG}")"
RID="$(echo "$RELEASE_JSON" | grep -oE '"id": ?[0-9]+' | head -1 | grep -oE '[0-9]+' || true)"
if [ -z "$RID" ]; then
  BODY="$(printf '## 滴水记账 v%s\n\n双端同版。下载 APK / EXE 见下方资产。\n' "$VERSION")"
  RELEASE_JSON="$(curl -sS --max-time 30 -X POST -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/${OWNER}/${REPO}/releases" \
    -d "{\"tag_name\":\"${TAG}\",\"target_commitish\":\"main\",\"name\":\"滴水记账 ${TAG}\",\"body\":$(printf '%s' "$BODY" | python -c 'import json,sys;print(json.dumps(sys.stdin.read()))'),\"draft\":false,\"prerelease\":false}")"
  RID="$(echo "$RELEASE_JSON" | grep -oE '"id": ?[0-9]+' | head -1 | grep -oE '[0-9]+')"
fi
echo "$RID" > "$RID_FILE"
echo "  Release id: $RID"

echo "== 5/5 上传资产（同名先删再传，保证可重跑）=="
for ASSET in "$APK" "$EXE"; do
  NAME="$(basename "$ASSET")"
  URL_NAME="drip-ledger-${VERSION}-$( [[ "$NAME" == *.apk ]] && echo 'arm64.apk' || echo 'win-x64-setup.exe' )"
  EXISTING="$(curl -sS --max-time 20 -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/${OWNER}/${REPO}/releases/${RID}/assets" | grep -oE '"id": ?[0-9]+' | head -1 | grep -oE '[0-9]+' || true)"
  # 精确匹配同名资产并删除（避免重跑时 422）
  for AID in $(curl -sS --max-time 20 -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/${OWNER}/${REPO}/releases/${RID}/assets" | grep -oE '"(id|name)": ?("[^"]*"|[0-9]+)' | paste - - | grep "\"$URL_NAME\"" | grep -oE '[0-9]+'); do
    curl -sS --max-time 20 -X DELETE -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/${OWNER}/${REPO}/releases/assets/${AID}" >/dev/null
  done
  echo "  上传 $URL_NAME ($(stat -c%s "$ASSET") 字节)..."
  curl -sS --max-time 400 -X POST -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
    -H "Content-Type: application/octet-stream" --data-binary "@$ASSET" \
    "https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${RID}/assets?name=${URL_NAME}" \
    | grep -oE '"(name|state|size)": ?("[^"]*"|[0-9]+)' | head -3
done

echo "== 完成 =="
echo "  Release: https://github.com/${OWNER}/${REPO}/releases/tag/${TAG}"
echo "  提示: GitHub 资产下载域在国内约 23KB/s，国内用户下载需镜像（Gitee/COS），见 docs/自动更新方案设计-GiteaRelease.md"
