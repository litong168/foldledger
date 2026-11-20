import json, os, sys, urllib.request, hashlib

UA = "DripLedger-Updater/1.0"
GH_LATEST = os.environ.get(
    "GH_OVERRIDE", "https://api.github.com/repos/litong168/foldledger/releases/latest")
GITEE_MANIFEST = "https://gitee.com/litong168/foldledger/raw/master/release/update-manifest.json"
PLATFORM = sys.argv[1] if len(sys.argv) > 1 else "windows"
OLD_CODE = 235  # 上一版 16.7.1

def get(url, timeout=60):
    req = urllib.request.Request(url, headers={"User-Agent": UA,
          "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.getcode(), r.read()

def find_asset_url(release_json, name):
    for a in json.loads(release_json).get("assets", []):
        if a.get("name") == name:
            return a.get("browser_download_url")
    return None

def parse(m):
    return dict(versionCode=m.get("versionCode", 0), versionName=m.get("versionName", ""),
                minVersionCode=m.get("minVersionCode", 0), notes=m.get("notes", ""),
                assets=m.get("assets") or {})

result = None
# 1) GitHub
try:
    code, body = get(GH_LATEST)
    murl = find_asset_url(body.decode(), "update-manifest.json")
    if not murl:
        raise RuntimeError("GitHub latest 里没有 update-manifest.json")
    code, mbody = get(murl)
    man = parse(json.loads(mbody.decode()))
    result = ("GitHub", man, lambda n: find_asset_url(body.decode(), n) or (_ for _ in ()).throw(RuntimeError("找不到资产 " + n)))
    print(f"[GitHub] latest HTTP {code}, manifest {murl}")
except Exception as e:
    print(f"[GitHub] 失败 → {type(e).__name__}: {e}")

# 2) Gitee 兜底
if result is None:
    try:
        code, mbody = get(GITEE_MANIFEST)
        man = parse(json.loads(mbody.decode()))
        result = ("Gitee", man, lambda n: f"https://gitee.com/litong168/foldledger/raw/master/release/{n}")
        print(f"[Gitee] manifest HTTP {code}")
    except Exception as e:
        print(f"[Gitee] 失败 → {type(e).__name__}: {e}")

if result is None:
    print("结论：两个源都不可用（App 会提示检查失败）")
    sys.exit(1)

src, man, resolve = result
asset = man["assets"].get(PLATFORM)
print(f"源={src} versionCode={man['versionCode']} versionName={man['versionName']} 资产={asset and asset['name']}")
has_update = man["versionCode"] > OLD_CODE
print(f"版本判定：本机 {OLD_CODE} vs 远端 {man['versionCode']} → {'发现新版本' if has_update else '已是最新'}")
url = asset.get("url") if asset else ""
print("下载地址来源：manifest.url" if url else f"按源拼接 → {resolve(asset['name']) if asset else '无'}")
