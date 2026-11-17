# 滴水记账 · GitHub 开源发布与捐赠入口方案

> 状态：方案稿（2026-11-17）。目标：源码与安装包公开发布到 GitHub，供免费使用，软件内提供自愿捐赠入口。
> 现状：双端 16.5.3 / versionCode 232；已有隐私政策、用户协议、软著材料（均在 `store/`）。

---

## 0. 开源前现状审计（已完成）

| 检查项 | 结果 | 结论 |
|---|---|---|
| `.gitignore` | 已排除 `*.jks`、`keystore.properties`、`local.properties`、`dist/*.apk/exe/zip`、`.workbuddy/` | ✅ 守住第一红线 |
| git 历史 | 从未跟踪 `.jks` / `keystore.properties` / token 文件 | ✅ 无需洗历史 |
| `storePassword` 等字样 | 只是代码里 `getProperty("storePassword")` 的**键名**，无真实口令 | ✅ 误报，非泄露 |
| LICENSE | 无 | ❌ 待补（第 1 步） |
| README | 0.3 MB 开发流水账（13 章内部约定） | ❌ 需重写为面向用户的版本（第 2 步） |
| 隐私政策 / 用户协议 / 软著 | `store/` 下齐全 | ✅ 需微调（第 5 步） |
| 敏感权限 | `RECEIVE_SMS`、通知使用权、`POST_NOTIFICATIONS` | ⚠️ 开源后必被审视，需如实说明（第 5 步） |

---

## 1. 第一步：确定许可证（决定后面所有事）

**推荐 Apache-2.0**，理由：

- 明确授予专利使用权（比 MIT 多了专利保护，对"免费给大家用"更稳妥）；
- 要求保留版权声明与许可证副本，防止有人改包名直接闭源售卖；
- 与项目依赖（Compose / Room / sqlite / Rust 核心）兼容。

取舍表：

| 许可证 | 适合 | 代价 |
|---|---|---|
| **Apache-2.0（推荐）** | 想免费开放、保留署名、防专利纠纷 | 需在 NOTICE 列第三方声明 |
| MIT | 想要最宽松、最少约束 | 无专利条款；别人可闭源再分发 |
| GPL-3.0 | 想强制衍生项目也开源 | 与部分商业分发冲突；对普通用户不必要 |

操作：

1. 仓库根加 `LICENSE`（Apache-2.0 全文，版权行写 `Copyright 2026 李通` 或你的主体名）；
2. 加 `NOTICE` 文件，列本项目与主要第三方依赖的版权/许可（Compose、Room、SQLite Bundled、Rust 核心用到的 crate）；
3. **软著与开源许可证不冲突**：软著是权利登记，许可证是你主动授权大家使用的方式，两者并存。软著证书本身不必公开。

⚠️ 一旦 push 到 GitHub 公开仓库，许可证即不可撤回地生效——先想清楚选哪个。

---

## 2. 第二步：仓库发布准备

### 2.1 重写 README（面向用户，不是开发日志）

现在 0.3MB 的内部流水账移到 `docs/开发说明.md`，根目录 README 重写成 150 行以内：

1. 一句话介绍 + 平台徽章（Android / Windows）；
2. 功能亮点（一句话记账、多账本、报销垫付、折叠屏、纯本地）；
3. **下载**：GitHub Releases 链接（见 2.3 国内可达性）；
4. 截图（真机图，复用 `store/图标` 与小米材料文案）；
5. 隐私承诺（数据只存本地；同步是用户自托管、默认关）；
6. 构建方式（JDK 17.0.2 / Gradle 8.13 / AGP 8.9.2；⚠️ 现有构建脚本走 `--offline`，GitHub Actions 上要去掉离线旗标首次联网拉依赖）；
7. 捐赠（指向第 4 步的配置）；
8. 许可证与联系（GitHub Issues，别放私人邮箱）。

配套加 `CHANGELOG.md`、`CONTRIBUTING.md`（可选 `SECURITY.md` 说明漏洞怎么私下报告）。

### 2.2 仓库设置

- Description / Topics：`android, kotlin, compose, kotlin-multiplatform, personal-finance, bookkeeping, offline`；
- Website：隐私政策/用户协议的公网托管页（GitHub Pages 即可，见第 5 步）；
- Branch protection：master 开 PR review（若只有你一人可跳过）。

### 2.3 ⚠️ 国内可达性现实（必须先认清）

实测：本机 `api.github.com` 通，但 **GitHub 的 Release 下载域 `release-assets.githubusercontent.com` 超时（12 秒 0 字节）**。所以：

- GitHub 适合做**源码主页 + Release 发布记录**；
- 国内用户下 APK/EXE 需要镜像：Gitee 镜像仓库、对象存储（腾讯云 COS）、或网盘直链；
- README 的"下载"区同时给 GitHub Releases 与国内镜像两个链接；
- 这与上一份 `docs/自动更新方案设计-GiteaRelease.md` 的判断一致：**发布源和下载源可以分离**。

### 2.4 发版动作

每次发版：打 tag `vX.Y.Z` → 建 GitHub Release（写 notes）→ 上传 `滴水记账-vX.Y.Z-release.apk` 与 `滴水记账-vX.Y.Z-win-x64-setup.exe` → 同步到国内镜像。

---

## 3. 第三步：软件内捐赠入口设计

### 3.1 设计原则（比功能本身更重要）

- **不打扰**：绝不做启动弹窗、记账流程插屏、红点角标；只在用户主动进入"关于"页时出现；
- **不捆绑**：免费就是免费，捐赠不解锁任何功能，不出现"捐赠去广告"（本来就没广告）；
- **克制展示**：一行工具卡，说明一句"如果帮到了你，可以请作者喝杯茶"，不要大图、不要动效。

### 3.2 展示位置（双端一致）

| 端 | 文件 | 位置 |
|---|---|---|
| 手机 | `common/.../androidMain/.../SettingsScreen.kt` 的 `AboutCard()`（约 1180 行） | 「关于」卡片**最后一行**，在"最低版本"之下 |
| 桌面 | `common/.../desktopMain/.../ui/nav/PlatformScreens.desktop.kt` 的 `AboutCard()`（约 462 行） | 「关于」卡片底部，同构 |

复用项目已有的双端工具行组件（记忆中的 `GlobalToolRow` / `DesktopToolRow` 规范：emoji 20 / 标题 13 SemiBold / 说明 11 / 尾部 ›），改一边必须改另一边。视觉走玻璃材质 `glassChip` / `SectionCard`，不用实色块（用户明确否过实色选中块）。

点击行为：打开一个玻璃弹窗（`GlassDialog`），列出渠道（第 4 步）；收款码在弹窗内展示，链接类点击跳系统浏览器。

---

## 4. 第四步：可配置捐赠渠道（关键：不要硬编码）

### 4.1 远程配置 `donation.json`

放在仓库根（或 `docs/`），App 通过 HTTPS raw 链接读取。为什么要远程：收款渠道/二维码图片要能换，不能每次改都发版。

```json
{
  "version": 1,
  "message": "如果滴水记账帮到了你，可以请作者喝杯茶",
  "channels": [
    { "id": "wechat",  "label": "微信赞赏", "type": "qr",   "url": "https://<可访问的图片地址>/wechat-qr.png" },
    { "id": "alipay",  "label": "支付宝",   "type": "qr",   "url": "https://<...>/alipay-qr.png" },
    { "id": "github",  "label": "GitHub Sponsors", "type": "link", "url": "https://github.com/sponsors/<你的ID>" },
    { "id": "kofi",    "label": "Ko-fi",    "type": "link", "url": "https://ko-fi.com/<你的ID>" }
  ]
}
```

- `type=qr`：弹窗内展示图片（长按保存后用微信/支付宝扫）；
- `type=link`：跳外部浏览器；
- 不要在 App 内嵌微信/支付宝支付 SDK（要资质、回调、合规），也不要接任何广告 SDK。

### 4.2 渠道取舍

| 渠道 | 国内用户 | 门槛 | 建议 |
|---|---|---|---|
| 微信赞赏码 / 支付宝收款码 | ✅ 最顺 | 无（个人码即可） | **首选**，图片走远程配置可随时换 |
| 爱发电 / B站充电 | ✅ | 需实名开页 | 可选，做 `link` 渠道 |
| GitHub Sponsors | ❌ 国内访问差 | 需 GitHub 账号 + 收款资质 | 放进去作为"海外用户"选项 |
| Ko-fi / Buy Me a Coffee | ❌ | 需海外收款 | 同上，锦上添花 |

### 4.3 客户端实现要点

1. 新增 `common/.../data/donation/DonationConfig.kt` + `DonationRepository.kt`：
   - `load()`：GET 配置（复用 `HttpURLConnection`，与 `SyncHttp.kt` 同栈，零新依赖）；
   - 解析 `channels` 为强类型列表，坏数据兜底；
   - 双端共享解析逻辑（纯函数）放 `commonMain`，平台 UI 放各 `androidMain`/`desktopMain`。
2. **多源 fallback**（应对 raw.githubusercontent.com 国内不可达）：
   - 顺序：内置默认常量 → GitHub raw → Gitea/对象存储镜像；
   - 任一源成功即缓存，24 小时内不重复拉取（节流键 `lastDonationFetch` 写 `AppPreferences`）；
   - 全部失败时**静默用内置默认**（至少一个 GitHub 仓库链接），绝不弹错误。
3. `AppPreferences` 新增：`donationConfig`（缓存 JSON）、`lastDonationFetch`（时间戳）、`donationEnabled`（默认 true，给用户一个"不再显示"的开关）。
4. 二维码图片懒加载，失败显示"长按链接在浏览器打开"兜底文案。
5. 这是 App 第三个联网点（同步 + 自动更新 + 捐赠配置），**只拉静态 JSON/图片，不上传任何数据**——第 5 步要在隐私政策里写明。

---

## 5. 第五步：开源合规与用户隐私

### 5.1 敏感权限如实说明（开源后必被审视）

| 权限 | 用途 | 要在 README / 隐私政策写清的话术 |
|---|---|---|
| `RECEIVE_SMS` | 自动识别银行动账短信记账 | "仅本机解析，不上传、不存储短信内容；可在设置中关闭" |
| 通知使用权 | 读支付通知自动记账 | "仅本机匹配金额，不上传" |
| `POST_NOTIFICATIONS` | 每日提醒 | "本地通知" |
| `INTERNET` | 用户自托管同步 + 捐赠配置拉取 + 更新检查 | "默认不同步；联网仅发生在你主动配置的服务器上" |

现有 `store/隐私政策.html` 要补两句：① 捐赠配置会访问静态资源地址；② GitHub 开源后代码公开但用户数据仍只在本地。用户协议同步更新版本号。

### 5.2 第三方许可证

- Apache-2.0 项目需在 `NOTICE` 列第三方版权；检查 `rust/Cargo.toml` 的所有 crate 许可证，确认无 GPL 类传染性依赖；
- 4 个 ABI 的 `libdrip_core.so` 是本地编解码核心（记忆中备份格式不动、纯 Rust 实现），无第三方数据外传；
- 不要在开源仓库里放真实头像、手机号、私人邮箱——联系统一走 GitHub Issues。

### 5.3 "免费 + 捐赠"的边界

- 捐赠入口必须自愿，不得诱导、不得 ads-like  frequent 弹窗（开源社区对"伪装成开源的引流软件"极其敏感）；
- 不承诺付费功能，不搞"捐赠群/捐赠解锁"；
- README 里明确"软件永久免费，捐赠仅用于支持开发"。

### 5.4 备案与分发的现实

- 只在 GitHub Release 提供下载、不建国内官网：不需要 ICP 备案；
- 若做官网落地页提供下载：国内服务器需 ICP 备案；
- GitHub 上公开发布 ≠ 上架应用商店：小米/华为等商店仍需软著 + 企业主体 + APP 备案（见 `store/小米商店上架材料包.md`）——本次方案不依赖上架。

---

## 6. 需要修改的文件 / 新增清单

| 类别 | 文件 | 动作 |
|---|---|---|
| 法律 | `LICENSE`、`NOTICE`（新） | Apache-2.0 全文 + 第三方声明 |
| 文档 | `README.md`（重写）、`docs/开发说明.md`（迁移）、`CHANGELOG.md`、`CONTRIBUTING.md`（新） | 面向用户/面向开发者分离 |
| 配置 | `donation.json`（新） | 远程捐赠配置，渠道可改 |
| 共享逻辑 | `common/.../data/donation/DonationConfig.kt`、`DonationRepository.kt`（新） | 拉取 + 解析 + 兜底（纯函数可单测） |
| 偏好 | `common/.../data/prefs/AppPreferences.kt` | 加 `donationConfig` / `lastDonationFetch` / `donationEnabled` |
| 手机 UI | `SettingsScreen.kt` 的 `AboutCard()` | 底部加捐赠工具行 + 玻璃弹窗 |
| 桌面 UI | `PlatformScreens.desktop.kt` 的 `AboutCard()` | 同构 |
| 合规 | `store/隐私政策.html`、`store/用户协议.html` | 补联网说明与版本号 |
| 发布 | 发版脚本 / 手动流程 | 打 tag + Release + 国内镜像 |

---

## 7. 验证步骤

1. **合规**：`git log --all -- '*.jks' 'keystore.properties'` 为空；GitHub 仓库 Settings → Security 打开 secret scanning；`LICENSE`/`NOTICE` 内容无误。
2. **README**：新人照 README 能独立 clone → 构建出双端包（GitHub Actions 上首次联网拉依赖，验证去掉 `--offline` 后可复现）。
3. **捐赠配置**：改一次 `donation.json` 的 `message`，App 不升级、24h 后能显示新文案；把所有源改坏，验证静默降级到内置默认、不弹错、不崩。
4. **双端 UI**：手机/桌面"关于"页都显示捐赠行，位置/字号一致；点开弹窗收款码能长按保存、链接能跳浏览器。
5. **隐私**：抓包确认捐赠配置只 GET 静态 JSON/图片，无用户数据上行；关闭捐赠开关后不再拉取。
6. **发版闭环**：GitHub Release 与国内镜像都能下到同版本包，签名指纹 `3F:00:15:53…32:E2` 一致。

---

## 8. 建议实施顺序

1. **先合规**（`LICENSE` + `NOTICE` + README 重写 + 隐私政策补充）——不改代码，风险最低；
2. **再捐赠**（远程配置 + 双端关于页入口 + 单测）——小步、可回退；
3. **最后发布**（建 GitHub 仓库 → 传源码 → 建 Release → 配国内镜像）；
4. 自动更新（上一份 `docs/自动更新方案设计-GiteaRelease.md`）可作为独立后续项，别和首发捆在一起。

> 待你确认：许可证选 Apache-2.0 还是 MIT？捐赠渠道实际要用哪几个（决定 `donation.json` 内容）？收款码图片托管在哪（需一个国内可访问的静态地址）？
