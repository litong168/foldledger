# 滴水记账 · 双端自动更新方案设计（基于 Gitea Release）

> 状态：设计稿，待评审后实现。当前版本 16.5.3 / versionCode 232。
> 决策（2026-11-17 已确认）：① 更新源 = Gitea Release；② Gitea 单独暴露公网；③ 先评审文档再动手。

---

## 1. 目标与现状

**目标**：达到 GitHub 桌面软件那种体验——应用启动后自动检查、发现新版本提示、用户确认后自动下载并覆盖安装，桌面端与手机端都支持。

**GitHub 那套的本质**就三步，缺一不可：

1. **发版**：建 Release（关联 git tag，如 `v16.5.3`），把 `.exe` / `.apk` 传成 asset，写更新说明；
2. **查版本**：客户端 `GET /releases/latest`，拿回 `tag_name` + `assets[]` + `body`；
3. **下载覆盖**：比版本 → 提示 → 下载 asset → 校验 → 覆盖安装。

关键是「有个 Release API 端点」，这个端点在 github.com 还是自建 Gitea，客户端不挑。

**当前现状（代码级）**：

| 端 | 现状 | 缺口 |
|---|---|---|
| 桌面 | `DesktopAutoUpdater`（common/desktopMain）：启动延迟 12s 扫本地「下载」文件夹找 `滴水记账-vX.Y.Z-win-x64-setup.exe`，自写 Version Comparable 比三段号；按钮「退出并安装」先起 setup 再退进程 | 无网络检查、无自动下载、无签名校验；靠用户手动把安装包放进目录 |
| 手机 | **零更新代码**；Manifest 无 `REQUEST_INSTALL_PACKAGES`、无 FileProvider | 检查、下载、安装、提示、权限全缺 |

**网络实测（2026-11-17，本机）**：

| 目标 | 结果 |
|---|---|
| `api.github.com` | 通（0.8s）——查版本可行 |
| `github.com` / 真实 asset 下载域 `release-assets.githubusercontent.com` | **超时**（12s 0 字节）——从 GitHub 下包不可行 |
| Gitea 内网 `192.168.110.57:10091` | 通（0.08s） |
| `git.litong168.cn:10443`（对外域名） | 本机返回 200 但 IP 是 `127.0.0.1`、DNS 为保留段（`198.18.9.37`/`fc00::`）——**本机代理伪造，手机外网到不了** |

结论：**直连 GitHub 下包这条路堵死；自建 Gitea Release 是正解**。Gitea 兼容 GitHub 的 Release API，内网秒下，公网暴露后可覆盖外网手机。

---

## 2. 服务端：Gitea Release 契约

### 2.1 仓库与可达性

- 仓库：`litong/foldledger`（Gitea `http://192.168.110.57:10091`）。
- 内网：客户端默认 `http://192.168.110.57:10091`（连 OpenVPN 可达）。
- 公网：需把 Gitea 单独映射出来（用户已确认），例如 `https://git.litong168.cn:10443`。**必须配 HTTPS**，否则明文下载 APK/EXE 会被中间人替换——这是整条链的安全底座。

### 2.2 鉴权（Gitea API 文档已核实）

Gitea 支持三种 API 鉴权，本项目用 **`Authorization: token <token>` 头**（query 参数 `access_token=` 在新版标记废弃，不用）：

```
GET /api/v1/repos/litong/foldledger/releases/latest
Authorization: token <只读 token>
```

- token 权限只需 **`read:repository`**（只读），单独生成、别用现有推送 PAT。
- **私有仓库的 asset 下载同样要带这个头**：`browser_download_url` 直接裸 GET 会 401。下载器必须把 token 头一路带到 asset 请求。
- 若把仓库/Release 设为公开，则查询和下载都免 token——**这是更省事的选项**，但要公开仓库存在（源码也在里面）。取舍见 §9 待确认项。

### 2.3 Release 结构约定

每次发版建一个 Release：

- `tag_name` = `v16.6.0`（与 `APP_VERSION_NAME` 一致）
- `name` = `滴水记账 v16.6.0`
- `body` = 更新说明（给用户看，直接显示在提示弹窗）
- `draft = false`, `prerelease = false`
- **assets（文件名固定，客户端按名字精确匹配）**：

| asset 文件名 | 平台 |
|---|---|
| `滴水记账-v16.6.0-win-x64-setup.exe` | 桌面安装包 |
| `滴水记账-v16.6.0-release.apk` | 手机安装包 |
| `滴水记账-v16.6.0-manifest.json` | 机器可读清单（校验用） |

**manifest.json**（防止只看 tag 被改、并提供校验值与强制档）：

```json
{
  "versionCode": 233,
  "versionName": "16.6.0",
  "minVersionCode": 232,
  "force": false,
  "notes": "修复同步冲突；优化展开态布局",
  "assets": {
    "windows": {
      "name": "滴水记账-v16.6.0-win-x64-setup.exe",
      "size": 94371840,
      "sha256": "3f00..."
    },
    "android": {
      "name": "滴水记账-v16.6.0-release.apk",
      "size": 52428800,
      "sha256": "a1b2..."
    }
  }
}
```

`versionCode` 是权威比较字段；`minVersionCode` / `force` 用于强制更新（低于该 code 不可跳过）。

### 2.4 版本比较规则（纯函数，可单测）

```
remote.versionCode > local  → 有更新
remote.versionCode <= local → 无更新
remote.skipped(本地记住的忽略码) == remote.versionCode 且 !force → 静默，不提示
local < remote.minVersionCode 或 force → 强制档（弹窗只留「立即更新 / 退出应用」）
```

统一用 `versionCode` 整数比较，弃用桌面现有的三段 versionName 比较（versionCode 天然单调、无歧义）。`AppInfo.kt` 的 `APP_VERSION_CODE` 与 `app/build.gradle.kts` 必须始终一致——发版脚本要校验这一点。

---

## 3. 客户端：检查触发时机

| 端 | 自动检查 | 手动检查 | 节流 |
|---|---|---|---|
| 桌面 | 启动后延迟 12s（沿用现有）＋ 前台每 6h 一次 | 「启动与更新」卡的「检查更新」按钮 | `lastCheckAt` 6h 内不重复请求 |
| 手机 | 冷启动后延迟约 15s（避开首帧与账本恢复）＋ `onResume` 距上次 ≥6h | 设置页「版本」行点击 | 同上 |

- 检查走 `withContext(Dispatchers.IO)`，失败静默降级，绝不影响记账主流程。
- 自动检查只在 `updateCheckEnabled = true` 时进行（默认开）。

## 4. 用户提示

- 复用 `GlassOverlays.kt` 的 `GlassDialog`（玻璃材质符合总规范，别用实色 Material Dialog）。
- 内容：标题「发现新版本 v16.6.0」＋ manifest 的 `notes` ＋ 大小提示。
- 按钮三态：
  - 普通档：**立即更新** / **稍后**（仅本次会话不再提示）/ **跳过此版本**（写 `skippedVersionCode`）。
  - 强制档：仅 **立即更新** / **退出应用**。
- 桌面：复用 `StartupCard`（`PlatformScreens.desktop.kt`）改成显示检查结果与下载进度。
- 手机：设置页「版本」行下方加更新卡（与桌面同构），点击触发检查。
- 下载进度用线性进度条（在弹窗内或常驻卡内），百分比＋已下/总量。

## 5. 下载 / 安装流程

### 5.1 下载器（common 共享，`data/update/UpdateDownloader.kt`）

- 基于 `HttpURLConnection`（项目既有网络栈，零新依赖；参照 `SyncHttp.kt`）。
- GET `browser_download_url`，**带 `Authorization: token` 头**（私有仓库必须）。
- 边下边算 SHA-256（`java.security.MessageDigest`，双端 JVM 可用），落盘到临时文件 `.part`，完成后比对 manifest 的 `sha256` 与 `size`。
- 支持 `Range` 断点续传（可选，一期可不做；失败删 `.part` 重下即可）。
- 下载目录：桌面 `%LOCALAPPDATA%\滴水记账\updates\`；手机应用私有 `filesDir/updates/`（用 FileProvider 共享）。
- 进度回传用 `Flow<Int>` 或 callback，UI 层节流刷新。

### 5.2 桌面覆盖安装

复用现有 `DesktopAutoUpdater.installUpdate()` 的**正确姿势**（先起 setup 再退进程，避开 MSI 文件占用）：

1. 下载 `...-setup.exe` → 校验通过 → 记下路径；
2. 点「立即更新」→ `ProcessBuilder(exe).start()` → 成功则 `onExit()`（`exitApplication()`）；
3. MSI 靠固定升级码 `FA0CF5BB-63FE-4A65-BF65-5A3A3EEF0828` 覆盖旧版，快捷方式/卸载项一并更新；同版本或更低版本 MSI 不会覆盖，故发版必须递增 versionName。
4. ⚠️ 铁律：**不解压替换安装目录**（会写坏 MSI 组件表）。下载物只放更新目录。

### 5.3 手机覆盖安装

1. 下载 `.apk` → 校验 sha256/size 通过；
2. 经 **FileProvider** 把 `files/updates/x.apk` 转成 `content://` URI；
3. `ACTION_VIEW` + `setDataAndType(uri, "application/vnd.android.package-archive")` + `FLAG_GRANT_READ_URI_PERMISSION` 拉起系统安装界面；
4. 覆盖安装要求**包名与签名一致**（`com.foldledger` / `drip-ledger-release.jks`）——这是项目铁律，签名库绝不重生成，否则老用户无法覆盖升级。
5. 首次需引导用户开启「安装未知应用」授权（见 §6）。

---

## 6. 权限与兼容性

### 6.1 AndroidManifest（`app/src/main/AndroidManifest.xml`）

新增：

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />

<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="com.foldledger.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

`res/xml/file_paths.xml`：`files-path` 指向 `updates/` 子目录。

- `INTERNET` 与 `usesCleartextTraffic="true"` 已存在（内网 http 需要）；公网域名若走 HTTPS 则不受明文限制影响。
- `minSdk 26 / targetSdk 35`：未知来源安装从 Android 8 起需用户手动授权；targetSdk 35 下 `REQUEST_INSTALL_PACKAGES` 为 normal 权限（清单声明即可），但**系统安装器仍会检查用户是否给了该来源安装权限**——首次点击安装时若 `packageManager.canRequestPackageInstalls()` 为 false，跳转 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 引导开启。
- Android 14+ 对 sideload 无额外根权限要求；覆盖安装靠签名一致。

### 6.2 桌面

- 安装/启动 setup 均 per-user（`--win-per-user-install`），**无需管理员**。
- 需确保 `%LOCALAPPDATA%\滴水记账\updates\` 可写；杀软可能拦截下载物执行，属已知风险，文档标注。
- 窗口/托盘常驻，检查与下载在后台协程，不阻塞 UI。

### 6.3 安全

- **公网 Gitea 必须 HTTPS**；否则传输中的 APK/EXE 可被替换。
- token 只给 `read:repository`，单独生成、不入库、不写进 APK 明文常量——客户端 token 放本地配置（设置页填入或构建期注入），源码里留空。
- sha256 校验是最后一道闸：即使 CDN/仓库被污染，校验不过就拒绝安装。
- 公网暴露面：限制 Gitea 只映射必要端口，开失败登录限流，禁匿名注册（若实例支持）。

---

## 7. 失败处理

| 场景 | 处理 |
|---|---|
| 网络超时 / 无网 | 静默失败，仅设置页显示「检查失败，点击重试」；不弹窗、不打断记账 |
| API 404（无 Release） | 视为无更新（首次发版前正常），不报错 |
| token 失效 / 401 | 提示「更新源鉴权失败，请检查 token」，去设置页 |
| 版本解析失败 / manifest 坏 | 记日志，视为无更新，绝不提示 |
| 下载中断 | 保留 `.part`，可续传；重下时校验前先删旧 `.part` |
| sha256 / size 不符 | 立即删除文件，提示「安装包校验失败，已放弃」，**绝不安装** |
| 手机取消安装 / 安装失败 | 保留已下载 APK，提供「重试安装」；显示系统返回的错误 |
| 桌面 setup 启动失败 | 提示手动打开更新目录，不退出进程 |

---

## 8. 需要修改的模块 / 配置项

### 8.1 代码模块

| 位置 | 文件 | 改动 |
|---|---|---|
| common/commonMain | `data/update/UpdateChecker.kt`（新） | 清单模型、版本比较、强制判定（**纯函数，单测覆盖**） |
| common/commonMain | `data/update/UpdateRepository.kt`（新） | 拼 `/releases/latest`、解析 assets + manifest、带 token 头 |
| common/commonMain | `data/update/UpdateDownloader.kt`（新） | HttpURLConnection 流式下载 + sha256 + 进度 |
| common/commonMain | `data/prefs/AppPreferences.kt` | 加 `updateCheckEnabled` / `updateSkippedCode` / `updateServerUrl` / `updateToken` / `lastCheckAt` |
| common/commonMain | `AppInfo.kt` | 版本常量沿用；发版脚本校验与 gradle 一致 |
| common/desktopMain | `desktop/DesktopAutoUpdater.kt` | 从「扫本地目录」改为「走共享 UpdateRepository + 下载器」；保留本地目录作离线兜底 |
| common/desktopMain | `ui/nav/PlatformScreens.desktop.kt` 的 `StartupCard` | 换成网络检查 + 下载进度 + 立即更新 |
| desktop | `Main.kt` | 挂 `UpdateChecker` 周期检查；`onExit` 注入复用现有 |
| common/androidMain | `ui/screens/SettingsScreen.kt` | 设置页加更新卡 + 版本行点击检查 |
| common/androidMain | `data/update/UpdateInstaller.android.kt`（新） | FileProvider + 安装 Intent + 未知来源引导 |
| app | `AndroidManifest.xml` + `res/xml/file_paths.xml` | 权限与 FileProvider（§6.1） |
| scripts | `publish_release.ps1`（新） | 出包 → 算 sha256 → 生成 manifest → 建 Release + 传三个 asset |

### 8.2 配置项（`AppPreferences`）

| 键 | 默认 | 说明 |
|---|---|---|
| `updateCheckEnabled` | true | 自动检查开关 |
| `updateServerUrl` | `http://192.168.110.57:10091` | Gitea 基址（不带 `/api/v1`） |
| `updateToken` | 空 | 只读 token；公开仓库可留空 |
| `updateSkippedCode` | 0 | 用户跳过的版本码 |
| `lastCheckAt` | 0 | 上次检查时间戳（节流） |

### 8.3 发版脚本动作（`publish_release.ps1`）

1. 出双端包（复用现有 `build_installer.ps1` + `assembleRelease`）；
2. 校验 `AppInfo.kt` 与 `app/build.gradle.kts` 的 versionName/Code 一致；
3. 算两个安装包 sha256 + size，写 manifest.json；
4. Gitea 建 Release（tag `vX.Y.Z`），上传 exe/apk/manifest 三个 asset；
5. 核验 `/releases/latest` 返回的新 tag 与 asset 列表。

---

## 9. 验证步骤

1. **单测**（`common/src/desktopTest`）：`UpdateCheckerTest` —— 版本高低/相等、跳过、强制档、manifest 坏数据兜底、asset 缺失、tag 与 versionCode 不一致。基线从 app 203 + desktopTest 80 起。
2. **API 联通**（curl）：
   - `GET /releases/latest` 带 token → 返回正确 tag、三个 asset；
   - 带 token 下 apk → 200 且字节数 = manifest.size；不带 token → 401（私有仓库）。
3. **桌面端到端**：装旧版（如 16.5.3）→ 启动 → 弹窗提示 → 下载 → 进度 → 「立即更新」→ setup 启动、旧进程退出 → MSI 覆盖完成 → 跑 `scripts/verify_desktop_install.ps1` 核验 ARP 单条、用户数据保留、jar 版本串为新版本。
4. **手机端到端**：装旧 APK → 冷启动 → 延迟检查 → 弹窗 → 下载 → 系统安装界面 → 覆盖安装 → 签名指纹不变（`3F:00:15:53…32:E2`）→ 数据不丢、账本恢复到上次。
5. **失败路径**：断网、401、坏 sha256、取消安装、API 404 五种场景逐一验证不崩不打断。
6. **双端一致**：同一 versionCode 双端都识别为「已是最新」；强制档下手机/桌面都无法跳过。
7. **公网演练**：手机断 OpenVPN、走公网域名复查一遍 §4~§5（验证公网映射与 HTTPS 真的通，不是本机代理假象）。

---

## 10. 待确认项（评审时拍板）

1. **仓库公开还是私有**：私有 → 客户端必须内置只读 token 且 asset 下载带头；公开 → 免 token 但源码仓库内容对外可见。也可另建一个仅放发布包的公开仓库（推荐，兼顾二者）。
2. **公网域名与证书**：`git.litong168.cn:10443` 是否真做了端口映射＋有效证书？本机测到的是代理假象，需要以手机 4G 实测为准。
3. **是否要强制档**：一期是否启用 `minVersionCode` / `force`（DB 破坏性迁移时有用）。
4. **下载确认策略**：自动下载到完成只等用户点安装，还是检查到即弹「立即下载」由用户触发（建议后者，省流量）。
5. **是否保留桌面本地目录兜底**：一期可保留（离线手动升级入口），后续可去。
6. **断点续传**：一期是否做（不做则失败整包重下，实现更简单）。

---

## 11. 铁律提醒（不可触碰）

- 包名 `com.foldledger`、签名库 `drip-ledger-release.jks`、DB 文件 `foldledger.db` 一律不改。
- 桌面升级码 `FA0CF5BB-63FE-4A65-BF65-5A3A3EEF0828` 绝不改，也绝不解压替换安装目录。
- 新增共享逻辑放 `common/src/commonMain`（别扫 `app/src/main/java`，拆 KMP 后是空目录）。
- 不新增 DB 列/表、不改备份格式（`.foldledger.json` VERSION 恒 1）——本次更新纯配置＋网络层，不触发 Room 迁移。
