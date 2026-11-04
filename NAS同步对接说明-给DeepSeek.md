# 滴水记账 · NAS 双端同步对接说明（供 DeepSeek 改造 Windows 端 / 补全客户端）

> 目标：以家中联想 NAS 上的自托管 `sync-server` 为中转，实现 Android 与 Windows(JVM/Compose Desktop) 双端账本**自动、双向、增量、LWW 合并**的异网同步。
> 本文自包含：含线上实测接入地址、服务端协议、客户端 KMP 工程现状、明确缺口、Windows 端清单与红线。密钥类敏感值一律不写在文档中。

---

## 0. 一句话现状

- **服务端已上线并实测可用**：明文 HTTP 经 Tailscale 私有隧道暴露，双端填**域名**地址即可；当前 `SYNC_TOKEN` 处于**关闭（不校验）**状态，客户端无需带密钥即可注册/同步。
- **客户端同步引擎主体已实现**（commonMain 共享：HTTP、LWW、水位线、增量收集/应用、ID 分配、编解码），`SyncHttp` 网络层**已预留 `token` 形参**，但偏好存储、`SyncManager` 调用、UI 三处尚未接通 token；Windows 桌面端已装配 `SyncManager`，但后台调度为空实现、需做常驻/自启与界面挂载核对。
- **不要改动**已稳定的 LWW、水位线、`id = deviceId<<40|low40`、自动记账账本排除等算法。

---

## 1. 线上服务端接入参数（2026-11-03 实测）

### 1.1 客户端唯一接入地址（填到 App「服务器地址」）

```
http://t2-nas-1.tail2b73fa.ts.net:8451
```

关键约束（实测得出，勿踩坑）：
- **只能用 http 明文**：App 不处理 TLS/证书，不要用 `https://`；明文流量跑在 Tailscale 加密隧道内。
- **必须用 MagicDNS 域名，不能用 Tailscale IP**：
  - `http://100.105.27.52:8451/` 会得到 serve 层 `404 page not found`（http serve 按 Host 头匹配，IP 访问不命中路由）。
  - 域名 `http://t2-nas-1.tail2b73fa.ts.net:8451/` 返回后端 `405 {"error":"only POST allowed"}`，才是通的。
- **不要把密钥拼进 URL**（`?token=`、`#token`、`user:pass@host` 都无效，服务端会忽略 query；token 只在 POST JSON body 的 `token` 字段里）。
- 地址只填根 URL，不带 `/register`、不带结尾斜杠（客户端代码会 `trimEnd('/')` 后拼路径）。
- 客户端设备必须先登录并连接 **Tailscale**（同一账号）；无 Tailscale 时该域名无法解析、也无法直连内网地址。

仅运维使用、**不要填进 App**：
- 内网后端：`http://192.168.110.57:10097`（容器映射 宿主10097 → 容器8787；仅 NAS 内网可达，tailnet 客户端直连宿主映射端口实测不通）。
- NAS 节点：`t2-nas-1 = 100.105.27.52`（Tailscale 节点，active/direct）。

### 1.2 服务端容器部署参数（NAS Docker Web UI）

| 项 | 值 |
|---|---|
| 镜像 | `foldledger/sync-server:1.0.0`（linux/amd64，离线 tar 导入；固定版本，勿用 latest） |
| 网络 | Bridge；开机自启 ON |
| 端口映射 | 宿主 `10097/TCP` → 容器 `8787` |
| 挂载（读写） | 宿主 `个人空间/gitea/ledger-sync/data` → 容器 `/data` |
| 数据文件 | 容器内 `/data/ledger.json`（原子写 `.tmp` + rename） |
| 环境变量 | `SYNC_HOST=0.0.0.0`、`SYNC_PORT=8787`、`SYNC_DATA=/data/ledger.json`；`SYNC_TOKEN` 当前**已清空/未设（门禁关闭）** |
| 服务端实现 | 单文件零依赖 Node：`sync-server/server.js`；`FROM node:20.19-alpine3.20`，`CMD ["node","server.js"]` |

### 1.3 tailscale serve 运维命令（在 tailscale 容器终端执行，必须带 socket 参数）

查看规则（只读）：
```
tailscale --socket=/tmp/tailscaled.sock serve status
```
新增当前这条明文 http 反代（已注册成功）：
```
tailscale --socket=/tmp/tailscaled.sock serve --bg --http=8451 http://192.168.110.57:10097
```
单条关闭（不影响其他服务；不要用 `serve reset`，它会清空全部规则）：
```
tailscale --socket=/tmp/tailscaled.sock serve --http=8451 off
```
注意：serve 规则是运行时配置，**tailscale 容器若重建需重新执行上面的新增命令**；sync-server 容器重建不影响该规则（两个独立容器）。

### 1.4 实测结果（门禁关闭态）

| 探测 | 期望/实测 | 含义 |
|---|---|---|
| `GET http://域名:8451/` | `405 Method Not Allowed` `{"error":"only POST allowed"}` | 链路与后端健康（服务端只接受 POST） |
| `POST /register` 空 body、不带 token | `200 {"deviceId":1,"serverTime":...}` | 注册可用；当前无门禁 |
| `POST /sync` 不带 token（历史开启态） | `401 {"error":"invalid token"}` | token 一旦在服务端设值即生效拦截 |
| 未知路径 `GET /nope` | `404 not found` | 路由符合预期 |

> 说明：健康探测曾占用 `deviceId=1` 且仅产生空库、无账单行；正式双端首次注册会从服务端 `nextDeviceId` 继续编号，不影响数据正确性。如需从零干净起步，可在停服后删除挂载目录里的 `ledger.json` 再启动。

---

## 2. 服务端协议（与 `sync-server/server.js` 1.0.0 完全对齐）

### 2.1 通用规则

- 仅 `POST`；非 POST → `405 {"error":"only POST allowed"}`。
- 仅两个路由：`/register`、`/sync`；其余路径 → `404 {"error":"not found"}`。
- 请求/响应均为 `Content-Type: application/json; charset=utf-8`；请求体上限 64MB。
- 鉴权：服务端 `SYNC_TOKEN` 非空时，两个路由都要求 body 含 `"token"` 且完全相等，否则 `401 {"error":"invalid token"}`；`SYNC_TOKEN` 为空则跳过校验（当前态）。
- 非法 JSON → `400 invalid JSON body`；`/sync` 缺合法 `deviceId` → `400 missing or invalid deviceId (register first)`。
- 服务端无 `/health` 健康检查端点；健康探测只能用 GET 得到 405 来确认存活。
- 时间单位一律 **Unix 毫秒 (epoch ms)**。

### 2.2 `POST /register`

请求体（token 为可选，取决于服务端是否开启）：
```json
{ "token": "可选；不填或空串时省略该字段" }
```
成功响应 `200`：
```json
{ "deviceId": 3, "serverTime": 1793699074835 }
```
客户端职责：
- 持久化 `deviceId`；
- 计算并持久化时钟偏移：`offsetMs = serverTime - System.currentTimeMillis()`；之后本地行时间戳经 `offsetMs` 对齐到服务端时间轴再参与 LWW；
- 注册成功后开启「设备前缀 ID 空间」（见 2.5）。

### 2.3 `POST /sync`

请求体：
```json
{
  "token": "可选",
  "deviceId": 3,
  "watermarks": {
    "ledgers": 0, "categories": 0, "transactions": 0,
    "projects": 0, "accounts": 0, "recurring": 0, "goals": 0
  },
  "rows": [
    {
      "table": "transactions",
      "id": 1288490189056,
      "updatedAt": 1793699000000,
      "updatedByDeviceId": 3,
      "deletedAt": 0,
      "payload": "{ /* 实体全字段 JSON 字符串 */ }"
    }
  ]
}
```
成功响应 `200`：
```json
{
  "serverTime": 1793699074835,
  "rows": [ /* 服务端满足 updatedAt >= 该表 watermark 的增量行（含墓碑） */ ],
  "merged": 1
}
```
客户端职责：
1. 用本地 7 张表水位线组成 `watermarks`；
2. 收集本机 `updatedAt > 本表水位线` 的增量行（含软删墓碑）放入 `rows` 上传；
3. 服务端先按 `(table,id)` + LWW 合并上行，再按请求方水位线返回下行增量；
4. 用响应 `serverTime` 刷新 `offsetMs`；把 `rows` 按依赖表序 LWW 应用到本地 Room；
5. 应用成功（含被本地 LWW 拒绝的行）后把水位线推进到 `row.updatedAt + 1` 并持久化。

### 2.4 行结构 `SyncRow` 与 7 张表

行头（用于裁决/水位线）：`table, id, updatedAt, updatedByDeviceId, deletedAt, payload`。
- `payload`：实体**全字段 JSON 的字符串**（不是对象），自包含；服务端只做中继、不解析业务字段。
- `deletedAt > 0` 为墓碑（软删除）；`=0` 为正常行。

7 张表（服务端 `TABLES` 与客户端 `TableNames.ALL` 必须一致），应用顺序为先父后子：

| 顺序 | 表名 key | 对应 Room 实体 | 说明 |
|---|---|---|---|
| 1 | `ledgers` | LedgerEntity | 账本；`sceneKey=="auto"` 的自动记账账本**整本不同步** |
| 2 | `categories` | CategoryEntity | 分类；含方向、唯一索引(ledgerId,direction,name) |
| 3 | `projects` | ProjectEntity | 项目 |
| 4 | `accounts` | AccountEntity | 账户 |
| 5 | `recurring` | RecurringEntity | 周期记账 |
| 6 | `goals` | GoalEntity | 目标 |
| 7 | `transactions` | TransactionEntity | 流水（最后落，外键依赖前面父行） |

### 2.5 关键语义（客户端必须与服务端一致，勿改）

- **LWW（行级最后写入胜）**：`updatedAt` 大者胜；相等则 `updatedByDeviceId` 大者胜；相等相等幂等不覆盖。
- **水位线 `>=` 语义**：服务端返回 `updatedAt >= watermark` 的行；客户端水位线存 `max(updatedAt)+1`。同毫秒多行更新不漏、已见行不重发。序列化：每行 `表名=毫秒`，`\n` 分隔，空串 = 从未同步（首同步全量上传）。
- **设备前缀主键**：同步启用后新行 `id = (deviceId << 40) | 表内低 40 位自增号`，保证不同设备 id 永不重叠；未注册/未启用同步时返回 0，由 Room 走原自增（兼容历史小 id 数据）。
- **时钟对齐**：`offsetMs = serverTime - localTime`，注册与每次同步后刷新；写入行的 `updatedAt` 换算到服务端时间轴。
- **自动记账账本排除**：`sceneKey=auto` 的账本不参与同步；挂在其下的行/墓碑在客户端入口与服务端应用时双重过滤。
- **损坏/空库**：服务端首次启动或 `ledger.json` 损坏时按空库 `{nextDeviceId:1, rows:{}}` 启动；写入为原子替换。

### 2.6 服务端存储结构 `ledger.json`

```json
{
  "nextDeviceId": 4,
  "rows": {
    "transactions": { "1288490189056": { /* 与上行结构相同的一条 SyncRow */ } },
    "ledgers": { }
  }
}
```
它同时就是 NAS 上的完整数据快照（异地备份点）；可用 File Browser 定期复制到 `ledger-sync/backup/` 做额外兜底。

---

## 3. 客户端工程现状（Kotlin Multiplatform + Compose Multiplatform）

### 3.1 模块结构

```
记账APP/
├─ app/                     # Android 应用壳（androidMain）
├─ desktop/                 # Windows 桌面壳：desktop/src/desktopMain/kotlin/com/foldledger/desktop/Main.kt
├─ common/
│  ├─ src/commonMain/kotlin/com/foldledger/
│  │   ├─ data/sync/       # 共享同步引擎（核心，见 3.2）
│  │   ├─ data/prefs/      # AppPreferences（DataStore）
│  │   ├─ data/db/         # Room：Entities / AppDatabase / Daos
│  │   ├─ data/repo/       # LedgerRepository（持有 idAllocator）
│  │   ├─ ui/screens/SyncSection.kt   # 双端共用同步卡 UI
│  │   └─ ServiceLocator.kt
│  ├─ src/androidMain/      # Android 平台实现 + SyncWorker(WorkManager)
│  └─ src/desktopMain/      # JVM 桌面平台实现 + ServiceLocator.desktop
└─ sync-server/             # Node 服务端源码、Dockerfile、离线测试脚本
```
- 网络层 `SyncHttp` 直接用 `java.net.HttpURLConnection`（JVM，Android 与 Desktop 通用，零新增依赖）。
- Android `AndroidManifest.xml` 已声明 `INTERNET` 且 `android:usesCleartextTraffic="true"`，明文 http 无系统层阻碍。

### 3.2 已实现清单（文件 → 职责）

| 文件（commonMain 下相对 `com/foldledger/`） | 现状职责 |
|---|---|
| `data/sync/SyncHttp.kt` | POST `/register`、`/sync`；**方法签名已带 `token: String? = null`，非空才写入 body.token**；超时 connect 10s/read 30s；非 2xx 抛 `SyncHttpException(code,body)` |
| `data/sync/SyncEngine.kt` | `collectOutgoing`（7 表增量收集、排除 auto 账本）；`applyIncoming`（按 `TableNames.ALL` 依赖序 LWW upsert、auto 防御过滤、分类唯一索引冲突保留本地、推进水位线） |
| `data/sync/EntityCodec.kt` | 7 表实体 ↔ SyncRow 双向编解码；payload 全字段 JSON 字符串；解码一律 `opt*` 兜底不崩 |
| `data/sync/SyncRow.kt` | 行模型 + `TableNames` 7 表常量与应用顺序 |
| `data/sync/Lww.kt` | `aWins` 裁决 + `alignTimestamp(localTs,offsetMs)` |
| `data/sync/SyncWatermarks.kt` | 水位线内存态、`advance`、`表名=毫秒\n` 序列化 |
| `data/sync/SyncIdAllocator.kt` | `deviceId<<40 | low40`；未启用返回 0 |
| `data/sync/SyncManager.kt` | UI 状态流、`register()`、`syncNow()`、5 分钟进程内协程 `startAutoSync/stopAutoSync`、时钟 offset 与水位线持久化、错误写入 `lastError` |
| `data/prefs/AppPreferences.kt` | `syncEnabled / syncServerUrl / syncDeviceId / syncClockOffsetMs / syncWatermarks / syncAutoSync` 六个键及 setter |
| `ui/screens/SyncSection.kt` | `SyncSectionCard`：总开关、服务器地址框、注册按钮、自动同步开关、立即同步按钮、结果/错误文案；双端共用 |
| `src/androidMain/.../platform/SyncWorker.kt` | WorkManager `CoroutineWorker`：启用且已注册才 `syncNow()`，失败 `Result.retry()` 退避重试 |
| `src/desktopMain/.../ServiceLocator.desktop.kt` | 桌面已构造 `SyncManager(_repository,_preferences,SyncEngine(_db),syncScope)` 并 `initFromPrefs()`；桌面数据目录 `~/.foldledger/`（DB + `foldledger_settings.preferences_pb`） |

### 3.3 已确认缺口 / 待办（按优先级，DeepSeek 重点）

1. **token 配置链路未打通（网络层已留形参，缺三层）**
   - `AppPreferences` 无 `syncToken` 键与字段、无 setter；
   - `SyncManager.register()` 调 `http.register()` 未传 token；`syncNow()` 调 `http.sync(...)` 未传 token；
   - `SyncSectionCard` 无密钥输入框；
   - 现状：服务端关闭门禁，不阻塞；**建议补全这条链路（配置持久化 → Manager 透传 → UI 可选输入框）**，以便将来服务端重新开启 `SYNC_TOKEN` 时无缝兼容；空 token 时行为与现在一致（省略 body.token）。
2. **桌面端后台调度为空实现**：`platform/Platform.desktop.kt#scheduleBackgroundSync(enabled){}` 是空函数；桌面目前只靠进程内 5 分钟协程。建议：桌面常驻（最小化到托盘/常驻）+ 开机自启；确认窗口关闭策略避免协程被一起销毁；失败做退避而非固定 5 分钟硬刷。
3. **桌面端同步卡挂载核对**：确认 desktop 的「我的/设置」界面实际渲染了 `SyncSectionCard(syncManager)`；若未渲染，按 Android 同样接入；确认 `ServiceLocator.init()` 在桌面启动时被调用。
4. **默认/占位地址更新**：`SyncSectionCard` placeholder 仍是旧示例 `http://192.168.1.10:8701`；可把占位或首次默认值改为本文 1.1 的域名地址（token 仍留空）。
5. **错误文案可读性**：当前直接展示 `e.message`（含 `Unable to resolve host`、`服务器返回 401` 等英文/堆栈片段）。建议映射为用户友好中文：
   - DNS 解析失败 / 连接超时 → “无法连接服务器，请确认已连接 Tailscale 且地址正确”；
   - 401 → “密钥不匹配或服务端已开启校验”；404 → “服务器地址路径无效”；405 → “地址指向异常，请填根地址”；
   - 网络失败不阻塞本地记账、保持最近一次成功状态。
6. **设备身份重置/解绑**：目前没有“清除同步身份（deviceId/水位线/offset）并重新注册”的入口；建议在同步卡提供“重置同步配置”，用于换服务器、清空配对；重置时清 `syncDeviceId`、`syncWatermarks`、`syncClockOffsetMs` 并关闭 idAllocator。
7. **Android 后台调度核对**：确认 `platform/Platform.android.kt#scheduleBackgroundSync` 真正 enqueue 了周期 `SyncWorker`（如 `PeriodicWorkRequest`，建议 15min、约束 NetworkType.CONNECTED），并在 `enabled && deviceId>0` 时启动、否则 cancel；提醒 MIUI 电池无限制。
8. **首同步历史数据窗口**：历史小 id 行与设备前缀 id 空间的兼容已由既有注释约束（导入迁移保持一致）；不要修改 `SyncIdAllocator` 位运算，不要对老数据强行改写 id。

---

## 4. Windows（desktop/JVM）端落地 Checklist

1. **装配核对**：`ServiceLocator.desktop.kt` 已 `new SyncManager(...)` 并 `initFromPrefs()`；确认 `desktop/Main.kt` 启动路径调用 `ServiceLocator.init()`。
2. **界面接入**：确保桌面“我的/设置”页渲染 `SyncSectionCard(ServiceLocator.syncManager)`，行为与 Android 一致（开关、地址、注册、自动同步、立即同步、状态文案）。
3. **网络要求**：JVM `HttpURLConnection` 支持 http；桌面无 cleartext 限制；依赖本机 Tailscale 在线以解析 MagicDNS 域名；断网/离线时同步失败但本地记账不受影响，恢复后自动重试。
4. **配置持久化位置**：`~/.foldledger/foldledger_settings.preferences_pb`（DataStore）；数据库 `~/.foldledger/<DB_NAME>`；不要硬编码写死路径。
5. **后台常驻**：填充 `scheduleBackgroundSync` 桌面实现或改用托盘常驻 + 进程内协程；关闭窗口最小化而非退出（或提供“退出”显式停止）；开机自启作为可选项。
6. **同步流程对齐 `SyncManager`**：注册→存 deviceId/offset→启用 idAllocator；同步=collectOutgoing→http.sync→应用下行→推进并持久化水位线；每次同步刷新 offset。
7. **token（预留，当前可不启用）**：若实现第 3.3-1，桌面与 Android 共用 `SyncSectionCard` 与 `SyncManager` 后自动一致；token 为空时不发送该字段。
8. **验收**：桌面与手机分别注册、双向各记一笔、确认互见；桌面重启 App 后状态与水位线保留、能继续增量同步。

---

## 5. 红线 / 不要改动

- 不要改用 https、不要引入证书校验/忽略证书逻辑；不要把密钥拼到 URL query/Header basic auth；token 只走 JSON body。
- 不要修改 LWW 规则、水位线 `>=` 与 `+1` 推进、`id=deviceId<<40|low40` 分配、7 表顺序与表名字符串。
- 不要把 `sceneKey=auto`（自动记账）账本纳入同步；不要同步/上传短信、通知等原始内容。
- Room 迁移禁止 `fallbackToDestructiveMigration`；同步相关表结构变更必须走显式 Migration。
- 服务端禁止 `tailscale serve reset`；不要为同步开放公网端口转发；不依赖宿主映射端口对 tailnet 直连（实测不通，统一走 serve）。
- 不要把真实 `SYNC_TOKEN`、其他服务密码、Tailscale authkey 写入任何客户端配置或文档。

---

## 6. 自测与端到端验收

### 6.1 命令行探测（在已连 Tailscale 的电脑上；Windows 用 curl.exe）

```bash
# 1) GET 根路径应 405 only POST allowed（链路存活证据）
curl.exe -i http://t2-nas-1.tail2b73fa.ts.net:8451/

# 2) 注册（当前无门禁 → 200；若服务端开启 token 且不带 → 401）
curl.exe -i -X POST http://t2-nas-1.tail2b73fa.ts.net:8451/register -H "Content-Type: application/json" -d "{}"

# 3) 未注册直接同步 → 400 missing deviceId（验证路由与校验）
curl.exe -i -X POST http://t2-nas-1.tail2b73fa.ts.net:8451/sync -H "Content-Type: application/json" -d "{}"
```
预期：1) 405；2) 200 含 deviceId/serverTime；3) 400。若 2) 返回 401，说明服务端已重新开启 token，客户端必须带相同 token。

### 6.2 双端端到端

1. 桌面端填 1.1 地址 → 注册成功（已注册设备 n）→ 开启自动同步；
2. 手机端（Tailscale 在线、电池无限制）填同一地址 → 注册 → 开启自动同步；
3. 任一端记一笔流水/新建分类 → 立即同步；另一端同步后看到该行；反向再验一次；
4. 验证删除：一端删除一条 → 同步后另一端对应行为墓碑隐藏；
5. 验证断点：断网记一笔不报错，恢复网络后自动上行；
6. 服务端落盘检查（gitea 终端视角）：
   ```
   ls -l /data/ledger-sync/data/
   ```
   应出现 `ledger.json`，且同步后更新时间变化。

---

## 7. 关键源码文件定位（供直接修改）

| 关注点 | 路径（`common/src/commonMain/kotlin/com/foldledger/` 下） |
|---|---|
| HTTP 传输/token 形参 | `data/sync/SyncHttp.kt` |
| 同步编排/状态/注册/自动循环 | `data/sync/SyncManager.kt` |
| 增量收集与下行应用 | `data/sync/SyncEngine.kt` |
| 编解码/payload 字段 | `data/sync/EntityCodec.kt` |
| 行模型/表名 | `data/sync/SyncRow.kt` |
| LWW/时钟 | `data/sync/Lww.kt` |
| 水位线 | `data/sync/SyncWatermarks.kt` |
| 设备前缀 ID | `data/sync/SyncIdAllocator.kt` |
| 偏好键/setter（token 键需新增） | `data/prefs/AppPreferences.kt` |
| 同步卡 UI（token 框/错误文案/重置入口） | `ui/screens/SyncSection.kt` |
| 桌面装配 | `common/src/desktopMain/kotlin/com/foldledger/ServiceLocator.desktop.kt` |
| 桌面平台调度（空实现待补） | `common/src/desktopMain/kotlin/com/foldledger/platform/Platform.desktop.kt` |
| Android 后台 Worker | `common/src/androidMain/kotlin/com/foldledger/platform/SyncWorker.kt` |
| Android 平台调度 | `common/src/androidMain/kotlin/com/foldledger/platform/Platform.android.kt` |
| 服务端实现 | `sync-server/server.js`；`sync-server/Dockerfile` |
| 服务端自测脚本 | `sync-server/smoke.js`、`sync-server/smoke-env.js` |

---

## 8. 服务端交付物与离线镜像元数据（不含密钥）

- 离线镜像包：`C:\Users\16039\WorkBuddy\记账APP\dist\滴水记账-sync-server-1.0.0-amd64.tar`
  - 大小 48,067,072 字节；架构 linux/amd64；RepoTag `foldledger/sync-server:1.0.0`；6 层；暴露 `8787/tcp`；卷 `/data`；Cmd `node server.js`。
  - SHA256：`8164FF7EA1A7A5A3DE7FA435B42084642BC096C0FCC6919433B08E15A3B3F6AC`
- 服务端源码：`sync-server/server.js`（7398B）；`sync-server/Dockerfile`（656B，`FROM node:20.19-alpine3.20`）。
- 镜像内置默认环境仅 `SYNC_HOST=0.0.0.0`、`SYNC_PORT=8787`；`SYNC_DATA`、`SYNC_TOKEN` 必须由部署方显式配置（`SYNC_DATA=/data/ledger.json` 不可漏，否则写容器可写层、重建丢失）。

## 9. 备份、回滚与运维备忘

- 数据备份：`ledger.json` 即完整快照；定期复制到 `ledger-sync/backup/`；回滚 = 停容器 → 替换旧 `ledger.json` → 启动。
- 容器重建：NAS UI「编辑保存」会重建容器但保留挂载目录；务必保留端口 10097→8787、挂载 `/data`、`SYNC_DATA`；重建后后端仍由 serve 8451 转发，无需改 App 地址。
- 门禁恢复（将来客户端补齐 token 后）：UI 给 `SYNC_TOKEN` 赋值并重建；客户端在同步设置填入同值；清空则回到当前无门禁态。
- 单条关闭外网入口：`tailscale --socket=/tmp/tailscaled.sock serve --http=8451 off`；切勿 `serve reset`。
