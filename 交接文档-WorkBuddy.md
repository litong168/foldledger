# 滴水记账项目交接文档（MiMo → WorkBuddy）

> 交接时间：2026-11-04　来源：MiMo Desktop（套餐耗尽，最后一次迭代 v16.2.4）
> 项目主目录：`C:/Users/16039/WorkBuddy/记账APP`（**中文路径，构建需 android.overridePathCheck=true**）
> 完整设计规范见 `docs/design-prompt.md` 与 `.workbuddy/memory/MEMORY.md`（如还在）

---

## 0. 一句话概况

双端记账 App：Android 手机（Compose）+ Windows 桌面（Compose Multiplatform 复用 70%+ 代码），通过自托管 Node 同步服务（NAS 部署）做记录级增量同步。当前双端版本 **16.2.8 (versionCode 221)**。

---

## 1. 铁律（不可违背）

1. **包名 `com.foldledger`、签名 `drip-ledger-release.jks` 绝不允许改**（口令在 `keystore.properties`，已装用户覆盖安装依赖签名一致；缺签名库时 `assembleRelease` 必须失败而非静默用 debug 签名——build.gradle.kts 已有 `doFirst check` 兜底）
2. **不主动 git 提交**（用户明确要求才提交；gitea push 偶发静默失败，提交后必须 `ls-remote` 核对）
3. **中文交流**（代码注释、UI、沟通全中文）
4. **自动记账账本整本不参与同步**：`sceneKey=="auto"` 的 ledgers 及其 FK 行（categories/transactions/projects/accounts/recurring/goals by ledgerId）**发送与接收两端都过滤**，桌面端不出现自动账本
5. **DB schema 改动必须显式迁移**（Room 迁移链，绝不 `fallbackToDestructiveMigration`）；发布前跑 `scripts/verify_migrations.py`、`scripts/check_type_scale.py`（字阶 8 档）
6. **备份格式 VERSION 恒 1**（.foldledger.json / 加密 .foldledger.crypt）
7. 先讲清方案再动手；关键分叉用提问让用户选；交付后简短总结

---

## 2. 工程结构（Kotlin Multiplatform）

```
记账APP/
├── common/                  # KMP 共享模块（核心！）
│   ├── src/commonMain/      # 业务逻辑 + 数据层 + 全部共享 UI
│   │   ├── com/foldledger/AppInfo.kt      # APP_VERSION_NAME/CODE（双端版本唯一来源）
│   │   ├── data/db/ (Room v11: ledgers/categories/transactions/goals/projects/recurring/accounts
│   │   │     + updatedAt + updatedByDeviceId + deletedAt 墓碑)
│   │   ├── data/sync/ (SyncEngine/SyncManager/SyncHttp/SyncWatermarks/Lww/SyncIdAllocator)
│   │   ├── data/prefs/AppPreferences.kt   # DataStore 偏好（含 M 系列桌面专属键）
│   │   ├── ui/…                            # 全部共享 Compose 屏幕
│   │   └── ServiceLocator.kt
│   ├── src/androidMain/    # 自动记账(SmsReceiver等)/WorkManager/SAF/语音——桌面不移植
│   ├── src/desktopMain/    # 桌面平台实现 + DesktopAutoUpdater + PlatformScreens.desktop
│   └── src/desktopTest/    # JVM 单测（41 项，Room 真库 + 同步集成测试 + Compose UI 测试）
├── desktop/                 # 桌面壳（jpackage app-image）
│   └── src/desktopMain/kotlin/com/foldledger/desktop/Main.kt   # 桌面入口（托盘/窗口记忆/单实例/提醒/自启联动）
├── app/                     # Android 壳（已无自身 Kotlin 源码，全依赖 :common）
├── sync-server/server.js    # 同步服务端（单文件零依赖 Node）
├── scripts/                 # 打包/QA 脚本（见 §11）
└── dist/                    # 交付物（zip/apk/app-image/tar）
```

**技术栈**：Kotlin 2.0.21、Compose Multiplatform 1.8.2（⚠️ 不能与 1.7.3 混用，见 §12）、Room KMP 2.7.x、DataStore、kotlinx-coroutines。桌面 runtime **必须排除 kotlinx-coroutines-android**（缺 android.os.Looper 会 NoClassDefFoundError），加 kotlinx-coroutines-swing 1.8.1。desktop release ProGuard 已关闭（jar 合并 META-INF 会炸）。

---

## 3. 当前版本与双端状态（2026-11-04，已更新到 16.3.2）

| 端 | 版本 | 状态 |
|---|---|---|
| 桌面 | 16.3.2 (225) | ✅ **安装包分发**：`dist/滴水记账-v16.3.2-win-x64-setup.exe`（89.9MB），静默升级装到 `%LOCALAPPDATA%\滴水记账`；安装态核验 10 项全绿、用户数据保留 |
| 手机 | 16.3.2 (225) | ✅ 已装机（`adb install -r`，`09029C6247444540`）；`dist/滴水记账-v16.3.2-release.apk` 4.0MB，4 ABI 齐全、签名指纹不变，启动存活无崩溃 |

> **16.3.0 双端一致性 + 性能**（详见 §5 首节）：桌面补齐三处空桩（速记输入槽、自定义分类图标、备份回收站），并合并重复订阅 / 收敛重复重算。全程**不新增 DB 列、不改备份格式、不触发迁移**。测试 app 203 + desktopTest **55**。

> **16.2.9 改分发形态**（详见 §13 末）：桌面端不再提供绿色免安装版，统一走安装包；安装 / 升级 / 卸载三条链路均已实测。唯一随之变化的行为：应用内「更新到 vX 并重启」（解压替换安装目录）会写坏 MSI 组件表，已改为「退出并安装」。

> **16.2.6 修桌面三件事**（详见 §13 末）：① 导出备份点了没反应（JFileChooser 无父窗口 + 在鼠标释放栈里弹出被吞）；② 切页/入场动效提速（实测无掉帧，是动效慢半拍）；③ 桌面图标按矢量原稿重绘成圆角，与手机一致。

> **16.2.5 修了 4 个问题**（详见 §13）：① `check_type_scale.py` 因 KMP 迁移扫空仍打 ✅（字阶闸门失效两个大版本）；② 由此暴露的 16 处字号违规归位；③ 全局搜索点结果 `onPickResult` 是空 TODO → 点了没反应；④ `gen_copyright_source.py` 同样扫空、版本号硬编码 V9.0。
> 接手时的实测差异：文档原写「装机 16.2.1 (214)」，实际装机是 **16.2.3 (216)**。

桌面端运行时数据：`~/.foldledger/`（foldledger.db、DataStore、window.properties 窗口记忆、instance.lock 单实例锁）。**安装版与开发版共用该目录，勿双开**（已加单实例锁防护）。

---

## 4. 关键路径与工具链

- 主目录：`C:/Users/16039/WorkBuddy/记账APP`（中文路径）
- 单测副本：`C:/Users/16039/foldledger-ci`（**纯英文路径，单测只在此跑**；中文路径 ClassNotFoundException）
- JAVA_HOME：`C:/Users/16039/.workbuddy/binaries/jdk/17.0.2`（每次构建前 `$env:JAVA_HOME=...`，PowerShell 会话不持久）
- Android SDK：`C:/Users/16039/Android/Sdk`；adb `C:/Users/16039/Android/Sdk/platform-tools/adb.exe`
- 真机：`09029C6247444540`（小米折叠屏 HyperOS；折叠态内屏 1672x2364，旋转 2）
- WiX Toolset 3.14：`C:/Users/16039/.workbuddy/tools/wix`（jpackage 生成 exe/msi 需要 candle/light；GitHub 直连不通，用 `gh-proxy.com` 镜像下载 wix314-binaries.zip）
- gradle：`gradle.properties` 已设 `kotlin.daemon.jvmargs=-Xmx3072m`（防编译 OOM）、`caching=false`（防复制文件不识别）
- 打包用 `--offline --no-daemon` 时依赖缓存已在；**改 commonMain 后必须 robocopy 同步到 ci 副本再跑单测**

---

## 5. 每次改动后的验证流程（必须）

```powershell
# 1) 同步到 ci 副本（改 commonMain 才需要）
robocopy "C:\Users\16039\WorkBuddy\记账APP\common\src" "C:\Users\16039\foldledger-ci\common\src" /MIR /NFL /NDL /NJH /NJS
Copy-Item "C:\Users\16039\WorkBuddy\记账APP\common\build.gradle.kts" "C:\Users\16039\foldledger-ci\common\build.gradle.kts" -Force
# 2) 双端单测（workdir = foldledger-ci）
$env:JAVA_HOME="C:\Users\16039\.workbuddy\binaries\jdk\17.0.2"
.\gradlew.bat :app:testDebugUnitTest :common:desktopTest --no-daemon
# 预期：app 203 项 + desktopTest 41 项全绿
```

---

### 16.3.2（同日，日期时间选择器排版复核与修复）

用户用**展开横屏**的日期时间选择器截图复核上一轮，要求确认「这类字体排版是否仍未修复」并直接修好，范围限定同类文本的字体/字号/行高/字重/字间距/对齐/换行/窄屏表现，页面结构、视觉风格、交互行为不变。

**复核结论：确实没修，而且上一轮改出了一个新回归。** 全部改动集中在 `DateTimePickerOverlay.kt` 一个文件（17 处）：

1. **「取消 / 确定」文字不居中（截图里最明显）**：`PickerButton` 内层 `Box(Modifier.padding(vertical=13.dp), contentAlignment = Center)` 只有 wrap-content 宽，`contentAlignment` 只居中了 Box 自己的内容，而 Box 本身被外层按 `TopStart` 放好 → 文字靠左。改成 `.fillMaxWidth()` + `Text(textAlign = Center, modifier = fillMaxWidth())` + 15/22 行盒。
2. **上一轮引入的窄屏回归**：`DayCell` 原 `tiny/compact/else = 10/12/13` 三档，v16.3.1 为归档表外字号改成 `11/13/13`，**compact 分支与常规分支同字号，降档语义被抹平**，两位数在窄栏又会顶到圆点边（注释还写着"再降一档"）。恢复成 `if (tiny || compact) 11.sp else 13.sp` 两档，配 15/19 行盒——既保住窄屏可用性，又全在 8 档内。
3. **同类文本层级统一**：全组件 **16 个 `fontSize` 全部补 `lineHeight`**（11/15、13/19、15/22、17/24、20/24 或 28、24/32）；快捷分钟 chip 11.sp → 13.sp，与 `QuickRow` 的同类胶囊一致（原 11.sp 三级字压玻璃上几乎读不出）；3 处 `FontWeight.Bold`（对话框标题、`:`、时分大数字）收回 `SemiBold`，对话框标题改用 `titleLarge` 自带的 24/31 SemiBold。
4. **换行与窄屏**：预览条日期时间 `maxLines = 1` + 省略号 → `maxLines = 2`（宽屏仍单行，窄栏不再截成「2026年11月4日 1…」）；`QuickRow`、`快捷分钟` 的 FlowRow 自动换行、`TimeCard` 的 `stacked` 分支、`StepButton` 按宽度降档全部保持原样。

**验证**：`fontSize` 16 / `lineHeight` 16 一一配对、`FontWeight.Bold` 残留 0、8 档外字号 0；字阶闸门 709 点全绿、迁移 1→11 全绿；双端编译 + assembleRelease + 安装包全绿；**app 203 + desktopTest 55 = 258** 全绿；真机装 225/16.3.2 启动无 FATAL；桌面静默升级 exit=0、安装态 10 项全 OK。日期选择器弹窗本身未能通过 adb 自动复现（入口在记一笔/编辑流水深层路径，折叠态坐标不稳定），改以源码逐项复核 + 编译/单测作为依据。

---

### 16.3.1（同日，UI 精细打磨 · 双端统一标准）

用户本轮只要一件事：**不新增功能、不扩范围**，把现有功能与 UI 逐项打磨到双端同一套标准，重点校正字体排版（含各级二级页面卡）与居中／对齐／层次。

**改动分三组**

1. **选中态 / 主行动键统一到玻璃规范**（原来都是 `primary` 实色块，属 §6 明令禁止项）：
   - 桌面深色模式 chip → 对齐 Android `ChipRow`（`glassChip(tint=primary, strength=2.6, elevation=2.dp)` / 未选中 `wellSurface()` 凹槽）。
   - 桌面 `DirectionChip` → 共享 `glassSelectChip`，Android 同款 `DirectionChip` 补行盒，两端「浓度 + 内棱 + 抬影」一致。
   - Android `IconPickerOverlay` 分组 chip / 图标格 → 玻璃（这是**唯一一处 Android 比桌面更违规**的反向差异）。
   - 共享 `SelectCircle`（22dp 勾选圈）→ 主色染膜玻璃 + 1dp 影；`ReimburseViews` 的「复制催办摘要」→ 与 `AccentPill` 同款主色玻璃键。
2. **字体排版**：备份／回收站／同步／存钱目标四张二级页面卡的主文本统一 **15.sp SemiBold / 22.sp 行盒**，副文本 11.sp；`Widgets.SectionHeader` 副标题去掉内联 18 覆盖、交回 `bodySmall`(13/19)；`StatsScreen` 主数字不再把 `HeroNumber` copy 成 44/44（行盒余量归零会裁中文+tnum 下沿）；分类名 11/13→11/15；归 8 档字阶：日期格 10/12→11/13、`¥` 18/21→17/20、时间数字 23→24；玻璃上 `ExtraBold`→`SemiBold`。
3. **对齐 / 层次 / 精致度**：`DesktopToolRow` 与 `GlobalToolRow` 双端逐项统一（emoji 20 / 标题 13 SemiBold / 说明 11 / 尾部 › / 6dp），并给每级文字固定行盒；补 4 处 `verticalAlignment = CenterVertically`（桌面提醒行、助手提示行、统计月份刻度等）、2 处 `textAlign`（同步错误文案 Start、搜索金额 End）、桌面分类空态补 `fillMaxWidth()` 让居中生效；统计页双栏右栏 16/20 → 20/20 对称；`GoalScreen` 去掉 `SectionCard(title="", subtitle="")` 的约 40dp 空白伪标题栏；桌面「更多设置」三处裸 Text 从默认 `bodyLarge`(17sp) 显式改成 13.sp + 次级墨色。

**明确不动（关键取舍）**：金额阶梯（34/42/28/34…）、`AdaptiveScaffold` 折叠动画的 `lerp` 连续插值、装饰用小圆点 8/12.sp、卡片内边距 14/16/18/20dp 全量重排、欢迎卡整块 primary 面 —— 这些改动会改变既有视觉尺寸或动画手感，回归风险高于打磨收益，本轮保留并登记为例外。

**验证**：字阶闸门 709 点全绿（含 5 个源集、0 文件判失败）、迁移 1→11 全绿、双端编译 + **app 203 / desktopTest 55 = 258** 全绿；APK 224/16.3.1、真机启动无 FATAL，主屏与「我的」页截图复核（工具行层级、箭头对齐、无裁切）；桌面静默升级 exit=0、安装态 10 项全 OK。

---

### 16.3.0（同日，A 双端一致性补齐 + B 列表/统计性能）

用户从 4 个候选中选了 **A+B**。硬约束由接手方定：**零新依赖、不动 DB schema、不动备份格式、不改余额口径与视觉规范**。

**A · 桌面三处空桩（都在 `:common` 的 expect/actual 上，双端共振，必须双端各自 build）**

1. **速记语音槽**：JVM 没有 `RecognizerIntent`，真语音要引本地模型 + 麦克风权限，收益远小于风险 → 做**能力对齐式降级**：`SpeedEntryPanel.desktop.kt` 从空 Composable 换成同尺寸（48dp 命中区 / 36dp 圆盘）的玻璃钮，点击从剪贴板取文本、去句尾标点后走同一个 `onSpoken`；空剪贴板仍回调 `onVoiceUnavailable` 保留共享回执，再换成桌面专属提示。解析 / 确认卡 / 6 秒撤销双端完全一致。
2. **自定义分类图标**：新建 `util/IconStore.desktop.kt`（`ImageIO` 居中裁剪→256×256 PNG，存 `~/.foldledger/category_icons/`，`BufferedImage.toComposeImageBitmap()`）；`Platform.desktop.kt` 的 `loadIconBitmap` / `deleteIconFile` 不再是 null / no-op；`CategoryManageScreen.desktop.kt` 补「上传图片」（JFileChooser 沿用 v16.2.5 的 `invokeLater` + 父窗口防吞机制），列表与编辑框改走共享 `CategoryIcon`。**仍只存 `emoji` 字段里的 `img:<fileName>`**，无迁移、无备份格式变更。
3. **备份回收站**：桌面以前 `confirmDelete()` 直接删 `.json` + `.xlsx`，且 `onOpenRecycleBin` 参数收了却没接（页面根本进不去）→ 新建 `DesktopRecycleStore`（副本在 `~/.foldledger/recycle/backup/`），删除改「写副本 → `mutateRecycledBackups` 原子记清单 → 删原件」；`BackupRecycler.desktop.kt` 接上清单 / 还原 / 铲除；设置页加回收站入口；`BackupSection` 的「此操作不可撤销」改成「可在回收站找回」（commonMain，双端生效）。顺手修了 `.crypt` 加密备份的 xlsx 配对名（原来只剥 `.foldledger.json` 后缀会拼错）。

**B · 取数与重组（不改视觉、字阶、玻璃三条门槛、余额口径、Room schema）**

- `DetailViewModels`：`grouped` 从 `transactions` 派生（**同一条 SQL 的 Room observer 由 2 降到 1**）；`accountBalances` 从 filter→groupBy→每组两次 sum 收敛成**一次遍历**（口径分毫不变：`initial + Σ收 − Σ支`、余额不落库）；`monthlyTotals` 抽出共享 StateFlow，`monthlyTrend` 与 `annual` 复用（observer 2→1）。
- `LedgerDetailScreen`：筛选条的笔数/合计改 `remember(filteredGroups)`（原来两处分支每次重组都 `flatMap` 出中间 List 再全表 sum）；`items` 补 `contentType`、`stickyHeader` 补 contentType；`TxnListViews` 日内行按 `txn.id` 加 `key`，增删改账时未变行可复用。
- `StatsScreen`：分类占比分母、饼图 slices、年度 12 个月索引、年度最大月 —— 全部改成按输入 `remember` 缓存。原来排行里**每一行**都 `totals.sumOf{}` 扫一遍全表，`YearOverviewCard` 每帧做 24 次 `firstOrNull` 线性查找。
- **明确放弃**：把 `donutSweep/trendGrow/yearGrow` 下推到绘制期读取。查过 `YearBars` 是用 `.fillMaxHeight(fraction)` 在**布局期**消费进度的，改成绘制期要重写图表、风险高于收益；也没动 `GlassGlow` 的绘制期 sweep 机制（那本来就是正确实现）。
- **没做**：引入 Paging（会推翻 groupByDay / 内存过滤 / 多选批量改账整套语义）、把余额落库、把搜索改写成拼 SQL（会与 `DripCore.matchesQuery` 形成两套语义）、跨页共享 VM 聚合结果（三页生命周期与锚点不同，容易串状态）。

**验证**：`check_type_scale.py` 706 个字号点无越界、`verify_migrations.py` 1→11 全通过；新增 4 项 `DesktopRecycleStoreTest`（明文+xlsx 成对还原 / `.crypt` 配对名 / 老清单 `xlsxStoredName=null` 回退 / 铲除不误伤别人的副本）→ **app 203 + desktopTest 55 = 258 全绿**；双端编译 + assembleRelease + 安装包全通过；真机 16.3.0 启动存活、logcat 无 FATAL；桌面静默升级 10 项安装态核验全绿、用户数据保留。

---

### 16.2.9（同日，桌面改安装包分发）

用户要求：桌面端打包为**可安装的安装包**，功能不变、不引新 BUG、升级卸载可用。先梳理风险再打包，实测结论：

**风险清单与处置**

| 风险 | 处置 | 验证 |
|---|---|---|
| 安装后路径变化 → 开机自启注册表指向旧 exe | 应用每次启动都重写 Run 值（已有机制），升级后首次启动即自愈 | 见下 |
| MSI 升级/卸载需管理员 | `--win-per-user-install` 装到 `%LOCALAPPDATA%` | 非管理员会话安装/卸载均 exit 0 |
| 卸载后残留自启项（指向已删 exe） | WiX 模板加卸载序列 CustomAction，调应用 `uninstall-cleanup` | 卸载日志「返回值 1」，Run 值确被删除 |
| 应用内「解压替换安装目录」的自更新会写坏 MSI 组件表 | 改为「退出并安装」（先起 setup 再退本进程，也避开文件占用） | 代码走查 + 单测回归 |
| 中文名 / 中文安装目录 → WiX 编译 | jpackage + WiX 实测接受 | 出包成功，装在中文目录下 |
| 开始菜单组名变「未知」 | 加 `--win-menu-group 滴水记账` | `Programs\滴水记账\滴水记账.lnk` |
| 升级码漂移 → 旧版升不上来 | 固定 `--win-upgrade-uuid FA0CF5BB-63FE-4A65-BF65-5A3A3EEF0828`（写进脚本，**绝不改**） | 16.2.8 → 16.2.9 升级实测，ARP 只剩一条 16.2.9 |

**踩到的坑（都写进 §6）**

1. **`RemoveRegistryValue` 不是卸载时执行的** —— MSI 的 RemoveRegistry 表在**安装阶段**跑（用来清上一版旧值）。第一版用它清自启项，结果升级时把用户开关清掉、真卸载反而不跑。改成卸载序列 CustomAction 调应用 `uninstall-cleanup`（条件 `REMOVE="ALL" AND NOT UPGRADINGPRODUCTCODE`）。
2. WiX 注释里出现连续两个短横（例如把 `uninstall-cleanup` 参数写成 `--uninstall-cleanup`）→ candle exit 104。
3. 往 `resource-dir` 塞 `MsiInstallerStrings_zh_CN.wxl` 会与 jpackage 自带的那份重复 `-loc` → light exit 100；语言只需改 `JpProductLanguage=2052`。
4. jar 是压缩包，**扫版本串必须解压后找**（第一版扫原始字节永远校验失败）。
5. `RemoveRegistryValue` 当 KeyPath 会撞 ICE18 / ICE38；该组件必须有一个 HKCU 注册表 KeyPath。

**实测记录**：安装（exit 0）→ 核验 10 项全绿 → 装 16.2.8 再升 16.2.9（ARP 一条、快捷方式与数据不变）→ 置自启值后卸载（目录 / ARP / 快捷方式 / 安装标记全清，Run 值被清，`~/.foldledger` 数据保留）→ 再装回。app 203 + desktopTest 51 全绿。

**环境注意**：这台沙箱把 `reg.exe` 列进程序黑名单，应用因开机自启（内部调 reg.exe）启动会被连进程终止；验证 UI 时用了「复制数据 + 关掉自启开关」的副本。用户真实机器不受影响。

### 16.2.8（同日，同步可观测 · 双端迭代）

方向来源：4 个候选里的「同步可观测」。起因很具体——同步卡只有一行「上行 3 行 · 下行 0 行」，用户想问"我手机上改的那笔为什么没过去"，代码里根本没有答案。

1. **数据**：新增 `data/sync/SyncLog.kt`：`SyncLogEntry`（时间/触发方式/上下行/新增·更新·本地胜·跳过/耗时/结果）+ `SyncConflict`（表、id、两边时间戳、远端设备）+ `SyncLogStore`（JSON 编解码、环形 20 条、单条冲突上限 12）。**不建表**——日志不是业务数据，为它动 Room schema 不划算。
2. **冲突从哪来**：`SyncEngine.applyIncoming` 本来就在算 `localWins`，顺手把明细塞进 `SyncApplyStats.conflicts`（**不新增查询**）。7 个表的 `localWins` 分支统一改成记录一条。
3. **记录点**：`SyncManager.syncNow(trigger)` 计时 + 成功/失败各记一条；`register()` 失败记「注册」；自动同步循环传 `trigger="自动"`。写 prefs 用 `runCatching` 兜住——**日志坏了绝不能让同步失败**。`resetIdentity()` 会清空记录（换服务器后旧记录只会误导）。
4. **页面**：`ui/screens/SyncLogScreen.kt`，入口在同步卡底部「同步记录 › 最近 N 次 · 冲突可展开」。失败条目给**可执行的下一步**（`SyncLogStore.hintFor`：401→去核对访问密钥、域名解析→查 Tailscale、超时→查 NAS 休眠…）。冲突默认收起，点开看「流水 #42：本地 xx 胜 远端 xx（设备 3）」。
5. **导航**：`OverlayTarget.SyncLog`（共享 AppRoot）→ 双端同一套；`SettingsTab` 的 expect/actual 三处签名同步加 `onOpenSyncLog`。

**踩坑**：`applyIncoming` 解码用的是 payload 里的 `updatedAt`，不是 `SyncRow.updatedAt` —— 单测第一版只改 row 字段，结果远端永远"更新"，测试当场抓出来（见 `SyncLogTest` 注释）。

验证：`SyncLogTest` 6 项（编解码往返 / 坏数据兜底 / 环形上限 / 失败提示映射 / 本地胜出记冲突 / 远端胜出不记）→ app 203 + desktopTest **51**。

---

### 16.2.7（同日，备份闭环 · 双端迭代）

方向来源：用户从 4 个候选里选了「备份闭环」。核心是把"备份"从**单向、不可逆**变成**可换目录、可追加、有回执**。

1. **备份目录可换**——老实现里 `folderPath` 一旦写进 prefs 就再没有入口修改：选错目录、换盘、目录被删，用户只能卸载重装。新增 `BackupViewModel.requestFolderChange()`：桌面走 JFileChooser（`suspendCancellableCoroutine + invokeLater`，与导出同一套防吞机制），Android 走 SAF 重新授权（新增 `BackupAction.CHANGE_FOLDER`，`onFolderGranted` 里只刷列表并给回执，不触发导出）。
2. **恢复不再只有"覆盖"**——新增 `LedgerRepository.restoreAsNewLedgers(snapshot, suffix)`：把恢复体抽成 `insertSnapshot(snapshot, ledgerNameSuffix)`，覆盖恢复清库后调用（suffix=null 保持原名），追加恢复不清库（suffix="恢复"，账本名变成 `报销账本（恢复）`）。id 仍是全量重映射，所以不会串账本。**加密备份**的流程也改了：输完密码不再直接恢复，而是把密码暂存进 `pendingRestorePassphrase`（仅内存），接着弹「覆盖 / 作为新账本」——两条路都能选。
3. **导出有回执**——`BackupUiState.lastExportAt`（仅内存），备份卡位置行优先显示「上次导出：HH:mm」，其次才是自动备份时间。
4. **「打开所在文件夹」**——桌面用 `java.awt.Desktop.open(dir)`；Android 没有稳定的"打开某个 SAF 目录"入口，`openFolder()` 返回 false，UI 自动隐藏该按钮（状态里加 `canOpenFolder`）。

验证：`RestoreAsNewTest` 2 项（追加后原账本/原流水都还在、新账本名带后缀且流水挂在新账本上；覆盖恢复仍还原成一份）→ app 203 + desktopTest **45** 全过。**零 DB 改动**。

---

## 3.5 ⚠️ 真实安装位 ≠ dist（2026-11-04 踩过；16.2.9 起形态已变）

**dist 里的产物不是用户在跑的东西。** 16.2.4~16.2.5 两轮只更新了 `dist\滴水记账\`（构建产物），而用户桌面快捷方式指向 `C:\Users\16039\滴水记账\`（11-03 的 16.1.1），于是「图标没变」「导出备份没用」全是老代码的现象，排查方向被彻底带偏。

**v16.2.9 起桌面端是安装包**，真实安装位固定为：

```
C:\Users\16039\AppData\Local\滴水记账\        ← MSI 管理，勿手工改
```

- 桌面 / 开始菜单快捷方式、ARP 卸载项都由安装包创建；
- **不要**手工往安装目录塞文件或覆盖 exe（MSI 会认为文件被改过，卸载留残、升级走修复）；
- 升级 / 更新一律运行新版 `滴水记账-vX.Y.Z-win-x64-setup.exe`，MSI 自己替换旧版；
- 核验安装态：跑 `scripts\verify_desktop_install.ps1`（只读，查 ARP / 快捷方式 / 运行时 / jar 版本串 / 用户数据 / 自启项）。

另外：Windows 图标按**路径**缓存，换过图标后若桌面/任务栏还显示旧的，重启一次资源管理器即可。

---

## 6. 桌面端打包发布流程（安装包，固化）

跑 `scripts\build_installer.ps1`（全量，含 uber jar）；只改了脚本或 WiX 模板时可加 `-SkipJar` 复用现有 jar。

产出：`dist\滴水记账-v<版本>-win-x64-setup.exe`（约 88MB）。脚本内部做的事：

1. 从 `AppInfo.kt` 读版本号（唯一来源）→ 打 uber jar（先删 `desktop\build\compose\jars\*.jar` 防 up-to-date 漏包）；
2. **校验 jar 里含版本串**（jar 是压缩包，必须解压后找；防止忘记重编译）；
3. `jpackage --type exe`，参数固化在脚本里：
   - `--win-per-user-install --win-dir-chooser`：装到 `%LOCALAPPDATA%\滴水记账`，**无需管理员**，允许用户改目录
   - `--win-menu --win-menu-group 滴水记账 --win-shortcut`：开始菜单组 + 桌面图标（不给 `--win-menu-group` 会变成「未知」文件夹）
   - **`--win-upgrade-uuid FA0CF5BB-63FE-4A65-BF65-5A3A3EEF0828`**：升级码。改了就等于换产品、旧版升不上来，**绝不改**
   - `--resource-dir scripts\jpackage`：自定义 WiX 模板（见下）

依赖：JDK 17.0.2 + **WiX 3.14**（`C:\Users\16039\.workbuddy\tools\wix`，jpackage 生成 MSI 需要 candle/light）。GitHub 直连不通，用 `gh-proxy.com` 镜像下载 `wix314-binaries.zip`。

**`scripts\jpackage\main.wxs` 是 jpackage 默认模板的打补丁版**（基线 = JDK 17.0.2 生成物，升级 JDK 后要重新对比），两处改动：

1. `JpProductLanguage=2052` → 安装向导走中文（jpackage 自带 `MsiInstallerStrings_zh_CN.wxl`，**不要**再往 resource-dir 塞 wxl 文件，会重复 `-loc` 导致 light exit 100）；
2. 卸载时清掉运行时写入的开机自启项：`JpCleanupAutoStartOnUninstall` —— `ExeCommand` 调 `[INSTALLDIR]滴水记账.exe` 加 `uninstall-cleanup` 参数（应用内复用 `AutoStartRegistry.remove()`），条件 `REMOVE="ALL" AND NOT UPGRADINGPRODUCTCODE`，只在**真卸载**时跑、升级替换不动用户开关。
   - ⚠️ **不能用 `RemoveRegistryValue`**：MSI 的 RemoveRegistry 表是**安装阶段**执行的（用来清上一版旧值），卸载时根本不跑 —— 实测它反而会在升级时把用户的自启开关清掉。
   - ⚠️ WiX 注释里**不能出现连续两个短横**，否则 candle exit 104。

**自动更新（v16.2.9 起）**：不再解压替换安装目录（那会写坏 MSI 组件表，卸载留残、升级走修复）。改为「检查更新 → 找到 `滴水记账-vX.Y.Z-win-x64-setup.exe` → 按钮「退出并安装」→ 关闭本程序并启动安装包」。默认扫描系统「下载」文件夹，设置项 `desktopUpdateDir` 可改；仍认旧版 `滴水记账-vX.Y.Z-win-x64.zip`（只提示不应用）。

**发版动作**：出包 → 跑 `scripts\verify_desktop_install.ps1` 核验 → 装（双击，或 `setup.exe /quiet`）→ 需要回滚就跑旧 setup（同 UpgradeCode，会自动替换）。

---

## 6.5 代码仓库 / NAS Gitea（2026-11-04 校正并验证）

**远端当前状态**：`gitea` remote = `https://t2-nas-1.tail2b73fa.ts.net:8444/litong/foldledger.git`，master = `f97e8d8`（与本地 HEAD、`backup` 裸仓库一致）；`git push gitea master` 已实测成功。

⚠️ **别再用旧地址** `https://t2-nas.tail2b73fa.ts.net/litong/foldledger.git`：该 MagicDNS 名已不解析；新节点 `t2-nas-1`（100.105.27.52）的 **443 端口是 AdGuard Home**，根路径 302 → `/login.html`、其余路径一律空体 401 —— 看着像「反代登录门」，其实是撞错了服务。服务端口表以 Dashy 的 `https://t2-nas-1.tail2b73fa.ts.net:8443/conf.yml` 为准（8444=Gitea、8445=Uptime Kuma、8446=File Browser、8451=同步服务…）。Gitea 28.2.0 的 `ROOT_URL` / `SSH_DOMAIN` 确实仍写成失效的旧域名（API 401 报错里的 swagger 链接、`ssh_url` 可见），但 **Gitea 会按请求的 Host 头动态生成 HTTPS clone 地址**：用 `https://t2-nas-1...:8444/` 访问时，网页和 API 里的 `clone_url` 都是正确的 8444 新地址（把 Host 改成 `example.com` 会跟着变），所以 push / clone 完全不受影响。真正显示错的只有 SSH 那条 `ssh://git@t2-nas.tail2b73fa.ts.net:10022/...`，而 10022 端口从未打通、SSH 本来就不可用（见下方第 5 点）。

**换令牌 / 配置要点**：

1. PAT 单行存 `~/.gitea-pat`（40-hex + 换行）；建库 API 需 `write:user`，建议直接勾全权限。
2. 针对**带端口的 URL** 单独配置，不能只配主机名：`http.https://t2-nas-1.tail2b73fa.ts.net:8444.proxy` = 空（绕开本机 `https_proxy`）、`.sslVerify=false`、`credential.<URL>.provider=generic`、`.username=litong`。
3. `credential.<URL>.helper` 必须**按顺序 add 两条**：先一条空值（清空此前注册的 helper，把 PortableGit 系统级 `helper=manager` 挤掉），再挂只读函数 `!f() { if [ "$1" = get ]; then echo username=litong; echo password=$(cat "$HOME/.gitea-pat"); fi; }; f`。只挂函数时 GCM 会抢先返回别的账号（实测 `160397620@qq.com`）。
4. 校验：**不要 clone**（DERP 中继太慢）。用 `git ls-remote gitea` 比对 HEAD，再用 `GET /api/v1/repos/litong/foldledger/git/trees/master` 取 `sha` 与本地 `git rev-parse HEAD^{tree}` 比对，相等即逐字节一致。
5. SSH 仍不可行：Tailscale IP 只开 443 + 8443~8451，无 SSH 端口，本机 `~/.ssh/` 为空。

**关于「改 ROOT_URL / SSH_DOMAIN」（2026-11-04 评估，结论：暂不动）**：彻底修需要动 Gitea 容器的 `app.ini` 或环境变量（`GITEA__server__ROOT_URL` / `SSH_DOMAIN`），而 NAS 只放行 443 + 8443~8451，**Docker API 未暴露、SSH 不通、管理界面需要登录**，无法从本机自动完成，只能由用户在 NAS UI 操作。按以往踩坑记录，用「编辑」容器必撞 `Conflict: container name "/gitea" is already in use` 且会把旧容器停在「已停止」，须走「停 → 只删容器 → 新建」；数据在宿主机 `个人空间/gitea/` 挂 `/data`，**挂载路径填错 = 仓库数据永久丢失**。而收益仅是修一个本来就点不通的 SSH 地址 + 一个只出现在错误响应里的 swagger 链接，风险明显大于收益。**当前建议：不动容器，网页里选 HTTPS 即可**。若确要重建，先做镜像备份（本地仓库 + D 盘裸仓库 + Gitea 已是三份，另可 `git bundle create` 出一份便携包），再逐项核对镜像版本、端口映射、`/data` 挂载、UID/GID、重启策略后操作。

---

## 7. 手机端打包发布流程

```powershell
$env:JAVA_HOME="C:\Users\16039\.workbuddy\binaries\jdk\17.0.2"
cd C:\Users\16039\WorkBuddy\记账APP
.\gradlew.bat :app:assembleRelease --offline --no-daemon
# 产物 app\build\outputs\apk\release\app-release.apk → 复制为 dist\滴水记账-v<版本>-release.apk
adb install -r <apk>   # -r 保留数据
```
- 版本号改两处：`common/.../AppInfo.kt`（APP_VERSION_NAME/CODE）+ `app/build.gradle.kts`（versionName/versionCode）——**同步改**
- 验证新代码进 APK：解包 classes.dex 搜字符串（R8 混淆不丢字符串字面量）
- R8 + 资源收缩已开（APK ~4MB）；release 必须用正式签名（keystore.properties 缺 → 构建失败是预期）

---

## 8. 同步系统

- **服务端**：`sync-server/server.js` 单文件零依赖 Node；`node server.js [--port] [--data] [--token] [--host]`；存储单 JSON 文件；协议 `POST /register {token?} → {deviceId, serverTime}`、`POST /sync {token?, deviceId, watermarks, rows} → {serverTime, rows增量, merged}`
- **NAS 部署**（用户已配好）：地址 **`http://t2-nas-1.tail2b73fa.ts.net:8451`**——只能 http、必须域名（IP 访问 404）、**token 只能走 body 不能拼 URL**；容器参数：bridge、宿主 10097→容器 8787、挂载 `gitea/ledger-sync/data`→`/data`、环境变量 `SYNC_HOST/SYNC_PORT/SYNC_DATA/SYNC_TOKEN`；镜像 `dist/滴水记账-sync-server-1.0.0-amd64.tar`（docker load 导入）
- **同步语义**：行级 LWW（updatedAt 大者胜、同刻 deviceId 大者胜）；每表水位线（`>=` 语义防同毫秒多行漏发）；**id 空间 = deviceId<<40 | 表内自增**（注册后 `repo.idAllocator.enabled=true`）；时钟校正 offset = serverTime - localTime
- **客户端**：`SyncManager`（注册/立即同步/自动同步 5 分钟协程 + Android WorkManager 15min 周期）；错误中文化（401→"密钥错误或未授权"等）；`resetIdentity()` 清 deviceId/水位线/offset（换服务器必须重置，否则增量不传历史）
- **⚠️ 换同步服务器必须重置身份**（手机端有"重置同步身份"按钮；桌面同步卡同款）
- **NAS 开 token 门禁（待办，需用户操作 NAS UI）**：编辑容器 → 环境变量加 `SYNC_TOKEN=<密钥>` → 保存重建（ledger.json 数据保留）→ 双端同步卡「访问密钥」填同一 token → 立即同步（已注册设备无需重置身份）

---

## 9. 桌面端已实现功能（desktop/Main.kt + common/desktopMain）

| 功能 | 实现要点 |
|---|---|
| 系统托盘常驻 | 点 X 隐藏到托盘继续同步；双击恢复；右键：显示/立即同步/退出 |
| 窗口记忆 | `~/.foldledger/window.properties`（AWT 物理像素 x/y/w/h/maximized），启动恢复 |
| 每日记账提醒 | 进程级协程每 30s 轮询偏好，到点 `TrayIcon.displayMessage` 气泡，当天一次 |
| 开机自启 | 偏好 `desktopAutoStart` → Main collect 同步 `HKCU\...\Run` 注册表，值 `"exe路径" --minimized`；自启时后台进托盘不弹窗 |
| 单实例锁 | `~/.foldledger/instance.lock` FileLock（**必须持有 channel 引用防 GC 释放锁**）+ 端口 47620 唤醒（第二实例把已运行窗口 toFront 后退出） |
| 自动更新 | `DesktopAutoUpdater`（common/desktopMain）：更新源设置 `desktopUpdateDir` 或默认 exe 上级目录；版本比较用自写 Version Comparable（kotlin.Triple 无 compareTo）；zip 校验含 cfg+exe；应用 = GBK update.bat |
| 「我的」页 | 卡片顺序：全局（速记/助手/目标/导入）→ 外观（深色模式）→ 备份 → 提醒 → **启动（自启+更新）** → 同步 → 关于 |

桌面「一句话记账」（SpeedEntry）、分类管理、账单导入均为共享 UI 的桌面版，可用。

---

## 10. 待办与建议

1. ~~**手机 APK 发版对齐**~~（✅ 2026-11-04 已对齐到 16.2.5(218)，`adb install -r` 覆盖安装，数据保留）
2. **NAS token 门禁**（§8 末，用户 NAS UI 操作，无法代劳）
3. ~~**单文件安装器 exe**~~（✅ 16.2.9 已完成：WiX 3.14 走 `gh-proxy.com` 镜像下载，脚本 `scripts/build_installer.ps1`；绿色版已退役）
4. 用户可选迭代方向：语音记账（v14.2 已做）、预算+超支提醒、年度账单、专项账本统计、同步状态页、导出 CSV/Excel

---

## 11. QA 自动化脚本与经验（scripts/）

- `qa_desktop.ps1 -ProcId <JVM子进程PID> -Out x.png`：PrintWindow 抓窗口内容（**不受遮挡窗口影响，唯一可靠"看窗口"手段**；jpackage launcher 会起子进程承载窗口，MainWindowHandle 在子进程上）
- `fullscreen_shot.ps1 -Out x.png`：物理全屏截图（SetProcessDPIAware + CopyFromScreen，可拍托盘气泡）
- `desktop_input.ps1 -X -Y`（点击）`-Key`（SendKeys）`-Wheel`（滚轮，delta 需放高 16 位）：**已内置 SetProcessDPIAware，传物理坐标**（⚠️ 曾踩 150% DPI 缩放坑：不设 DPI aware 时坐标被按逻辑换算偏移 1.5x，点击全落空）
- `activate_window.ps1 -ProcName 滴水记账`：AttachThreadInput 绕前台锁激活
- `activate_click.ps1 -ProcName -X -Y`：**激活+120ms 内点击一体化——唯一可靠点击路径**（单独激活会被红果短剧/桌面整理等抢 Z 序）
- `ocr.ps1 -Path x.png`：Windows OCR 词级坐标（输出为逻辑坐标，物理=×1.5）
- 视觉子代理模型：`mimo-desktop/mimo-v2.6-flash`（xiaomi/ 前缀曾 APIError）

**本机环境关键事实**：150% DPI（物理=逻辑×1.5）；滴水记账窗口逻辑 (300,150) 1200x800（物理 1800x1200，PrintWindow 输出）；底部导航右下胶囊物理坐标：明细(1781,1229)/账本(1894,1231)/我的(2008,1228)/+(2120,1232)；用户机器常开「红果短剧」全屏遮挡、桌面整理置顶窗口干扰；**滚轮/SendInput/PostMessage 注入对 Compose 窗口实测无效**（UI 滚动验证不可自动，需用户手动或改代码绕过）；点击坐标每次操作前用 vision 子代理重新定位（窗口位置会漂移）。

---

## 12. 迭代历史速查

- **M1（11-01）**：工程拆分 :common/:desktop/:app；CMP 1.8.2 版本对齐铁律；桌面 runtime 排除 coroutines-android
- **M2**：桌面壳（三 Tab、备份/提醒/关于/同步预告）、图标、门控函数
- **M3**：速记键盘版/账单导入/分类管理桌面版
- **M4**：Room v11 同步字段+墓碑；LWW 引擎+水位线+统一 id 空间；Node 同步服务端；客户端 SyncManager+同步卡 UI；WorkManager 后台同步；**自动账本整本排除**
- **M5.1-5.7**：NAS 部署（环境变量化+镜像 tar）；token 门禁全链路；手机真机端到端（重置身份全量重传教训）
- **M5.8**：桌面 jpackage app-image 打包（WiX 不可用下的正解）
- **M5.9**：桌面备份真实实现（JFileChooser/加密/轮转）+ 覆盖安装.bat 闭环
- **M5.10**：桌面外观卡 + 覆盖安装.bat 修稳（CRLF/括号转义/先清空）
- **M5.11**：手机 16.2.1 发版 + 代码清扫 + adb 截图坑
- **M5.12**：窗口记忆 + 打包新坑（gradle up-to-date 漏包 → 删 jars 强制重建 + zipfile 验证）
- **M5.13**：系统托盘常驻
- **M5.14**：记账提醒落地（30s 轮询 + 气泡）
- **M5.15（11-03，最新）**：单实例锁 + 开机自启 + 自动更新（v16.2.4）

---

## 13. WorkBuddy 接手记录（2026-11-04，v16.2.5）

接手第一轮做了「验证 → 修问题 → 双端发版」，全程未提交 git（铁律 §1.2）。

**验证结论（接手时实测）**：`verify_migrations.py` 11 条链全过（v1→v11）；双端单测 **app 203 + desktopTest 41 全绿**（文档里的数字属实）；桌面 zip 顶层目录/cfg/exe/覆盖安装.bat 齐；自动账本排除确实落在 DAO SQL 层（7 表 `incrementalSince` 全带 `sceneKey='auto'` 过滤，发送端没有漏）；签名指纹与文档一致。

**修掉的 4 个问题**：
1. **字阶闸门假通过（最严重）**——`check_type_scale.py` 的 ROOT 还指着 `app/src/main/java`，而 M1 拆 KMP 后那里 **0 个 .kt**。脚本扫 0 个文件、打印「字号使用点：0」、然后打 ✅ —— 整整两个大版本没人发现。修法：扫 `common/src` 三个源集 + `desktop/src` + `app/src`；`total==0` 直接判失败；允许字号改成从 `Type.kt` 的 `TypeScale.allowed` 实时解析（脚本与主题表结构上不可能再漂移）。
2. **16 处字号违规**（闸门修好后立刻现形）：`SyncSection` 14/12.sp、`BackupSection` 12.sp、`PlatformScreens.desktop` 14.sp ×4、`CategoryManageScreen.desktop` 14/18/22/12.sp。按 `Type.kt` 档位归位：12→11（标签/提示）/13（错误文案，同 `AddTxnOverlay` 既有惯例）；14→15（行标题）；18/22（emoji）→17/20。
3. **全局搜索点结果毫无反应**——`AppRoot.kt` 里 `onPickResult` 一直是 `/* TODO */` 空实现，且只回传 ledgerId（拿不到是哪一笔）。改成 `(ledgerId, txnId)` 双参：`closePage() → selectLedger() → overlay = AddTxn(ledgerId, txnId)`，即点结果直接打开「编辑这一笔」。真机验证：搜「交通费」→ 点第一条 → 弹出编辑浮层，金额 ¥88.00 / 账本 报销账本 / 分类 交通费 全对。
4. **软著源程序脚本也扫空了**——`gen_copyright_source.py` 同样只扫 `app/src/main/java`，生成出来只剩 rust；版本号还硬编码 `V9.0`。改扫 `common/desktop/app + rust`，版本从 `AppInfo.kt` 自动取，支持传参试跑（`python scripts/gen_copyright_source.py out.txt` 不覆盖正式文档）。试跑：35543 有效行 → 711 页，标题 `滴水记账 V16.2.4`（升级后为 V16.2.5）。

**发版**：双端 16.2.5(218)。手机 `adb install -r` 覆盖安装后冒烟：数据完好（报销账本 5 笔待回款、流水在）、同步卡「本机设备 ID：3」、服务器地址 `http://t2-nas-1.tail2b73fa.ts.net:8451`、新字号的「服务器地址/访问密钥」标签正常。桌面：杀进程 → 删 jars → uber jar（校验 jar 内含 `16.2.5` 常量）→ jpackage → 加覆盖安装.bat → zip（88MB）→ 从 zip 解包部署回 `dist/滴水记账/`。


### 16.2.6（同日，桌面三修）

用户报三件**桌面端**问题，逐条取证后的处理：

1. **「导出备份」点了没反应（不弹任何窗）**——真凶在 `BackupViewModel.desktop.pickFolderBlocking()`：
   - `showOpenDialog(null)` **没有父窗口** → 不模态、不置顶，很容易被主窗口盖住；
   - 它是在 **AWT EDT 的鼠标释放事件派发栈里同步弹的** → 同一次 release 落到新对话框上，弹出来就被吞掉。
   改法：`suspendCancellableCoroutine` + `SwingUtilities.invokeLater`（排到当前事件之后弹出）+ `showOpenDialog(主窗口)` + 挂起等结果，不阻塞 EDT。另外取消现在会显示「已取消选择备份文件夹」，自动备份开关在没选到目录时会回弹并说明原因。
   **先证明后端没问题**：新增 `common/src/desktopTest/.../BackupExportTest.kt`（2 项）跑真库 → `repo.snapshot()` + `BackupFormat.encode` + `ExcelExporter` + AES 信封解密全通；UI 侧实测点「导出备份」→ 密码框 → 直接导出，Downloads 里确实产出 `滴水记账-备份-*.foldledger.json`（23695B）+ `.xlsx`（18009B）。所以坏的是**弹窗那一步**，不是导出本身。
2. **界面切换卡顿不流畅（整个窗口都迟钝）**——实测**没有掉帧**：点击→首帧像素变化 **129ms**、空闲 2s CPU **0ms**、12 次滚动 CPU **94ms**、GPU 占用无变化、进程已加载 `opengl32 + nvd3dumx`（走 GPU，不是软件渲染回退）。结论：帧不缺，是**动效慢半拍**——切页 280ms 位移 + 220ms 淡入 + 内容错落（`STAGGER_MAX 7 × STEP 36`）+ 淡入 260ms，叠起来 ~0.5s 才"落定"。`Motion` 收紧：PAGE_ENTER 280→220、PAGE_EXIT 210→160、STAGGER_STEP 36→22、STAGGER_MAX 7→4。
   ⚠️ 这几个常量是**双端共享**的，手机下次发版也会跟着变快。
   📌 取证脚本留在 `scripts/`：`LatencyProbe.java`（Robot 点一下 + 高频抓屏量"点击→首帧"）、`ScrollOne.java`（打真实滚轮）、`CropZoom.java`（截小块放大看细节）。
3. **桌面图标和手机不一致（要倒角）**——原 `icon.png` 是满铺直角方块。新增 `scripts/GenIcon.java`：按 `ic_launcher_background/foreground.xml` 的同一份 108vp 矢量坐标用 Java2D 重绘（渐变/暖光/双涟漪/水滴/¥ 全照抄），套 23.5% 圆角遮罩，输出 `desktop/src/desktopMain/resources/icon.png`（1024，窗口+托盘）+ `scripts/app-icon.ico`（7 帧：256 PNG、128/64/48/32/24/16 BMP）。验证：`dist/滴水记账/滴水记账.ico` 与脚本产出一致（同 MD5），从 exe 里抽出的 32×32 帧四角透明。

**一个环境限制（不是产品 bug）**：WorkBuddy 的沙箱禁止拉起 `reg.exe`，而桌面端启动时会同步「开机自启」到 HKCU Run，于是**我在会话内没法把桌面 GUI 跑起来做截图验证**（可执行文件一启动就被掐）。已验证到「进程能起、Room 打开并写出 `~/.foldledger/foldledger.db-shm` 与 `preferences_pb`」这一步，剩下需要用户双击 `dist/滴水记账/滴水记账.exe` 亲眼确认。

---

## 14. 数据与隐私

- 桌面数据 `~/.foldledger/`；手机应用私有目录；备份默认 Downloads/UCDownloads（手机可设 SAF 目录，桌面 JFileChooser 选目录，自动备份轮转 3 份）
- 备份文件格式与手机完全互通（同命名/同格式，appVersion 字段）；加密备份 `.foldledger.crypt`（AES-256-GCM+PBKDF2，MIME 必须 `application/octet-stream` 防 SAF 加后缀）
- 同步会把账本上传到用户自托管服务器（隐私政策 `app/src/main/assets/store/隐私政策.html` 已更新）；`usesCleartextTraffic="true"` 仅发往用户自填服务器
- 用户真机造数后必须清理测试数据；备份轮转会在 UCDownloads 累积文件

---

*最后状态（2026-11-04 19:55）：双端 16.3.2 (225) 已发版装机（桌面安装包 + 手机 APK）；本轮修日期时间选择器排版（按钮居中、窄屏字号梯度回归、全组件行盒/字重/层级），未新增功能；app 203 + desktopTest 55 = 258 全绿；字阶 709 点 / 迁移 1→11 闸门通过；安装态 10 项核验全绿、真机启动无崩溃；代码已推送 `backup` 与 NAS Gitea（8444），tree SHA 校验一致；NAS 同步正常（无 token 门禁）。祝顺利。*
