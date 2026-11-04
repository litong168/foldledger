package com.foldledger.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.foldledger.data.TxnTemplate
import com.foldledger.data.TxnTemplateCodec
import com.foldledger.data.backup.RecycledBackup
import com.foldledger.data.backup.RecycledBackupCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 收支配色习惯 */
object ColorConvention {
    /** 记账习惯：支出红 / 收入绿 */
    const val LEDGER = 0

    /** 股市习惯：涨红跌绿 → 收入红 / 支出绿 */
    const val MARKET = 1
}

/** 深色模式 */
object DarkMode {
    const val FOLLOW_SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
}

data class AppSettings(
    val darkMode: Int = DarkMode.FOLLOW_SYSTEM,
    val dynamicColor: Boolean = false,
    val colorConvention: Int = ColorConvention.LEDGER,
    val lastLedgerId: Long = -1L,
    /** 备份目录的 SAF tree uri（持久化授权） */
    val backupTreeUri: String? = null,
    val backupFolderName: String? = null,
    /** 桌面端备份目录绝对路径（Android 用 SAF uri，桌面用文件路径） */
    val desktopBackupFolderPath: String? = null,
    /** 每次打开 App 自动备份一次 */
    val autoBackupEnabled: Boolean = true,
    val lastAutoBackupAt: Long = 0L,
    /**
     * 高刷新率模式。
     * 开启时向平台请求设备最高刷新率（通常 120Hz）并关闭自适应刷新的省电压制，
     * 让过渡动画真的跑满帧；关闭则交回系统默认（更省电）。
     */
    val highRefreshRate: Boolean = true,
    /**
     * 账本页「合计」的范围。
     * [aggregateAll] 为 true 时统计全部账本（含以后新建的）；
     * 为 false 时只统计 [aggregateLedgerIds] 里的账本（可以为空 = 合计为 0）。
     */
    val aggregateAll: Boolean = true,
    val aggregateLedgerIds: Set<Long> = emptySet(),
    /**
     * v13：账本列表的手动顺序（长按拖拽排序）。
     *
     * ⚠️ 存的是**偏好**不是数据：只在展示时用来给账本排个序，
     * 没出现在列表里的 id（新建的）一律排在后面、保持原有相对顺序。
     */
    val ledgerOrder: List<Long> = emptyList(),
    /** 每日记账提醒（v2.1）：默认关，默认 20:00 */
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
    /**
     * 触感强度（v5.0）：0 关 / 1 轻 / 2 中 / 3 强。
     * 数字键盘每按一下都会给一次短震动，力度按这里缩放。
     */
    val hapticStrength: Int = HapticStrength.MEDIUM,
    /** 数字键盘是否震动（v5.0）：单独开关，避免有人只要按钮反馈不要键盘震动 */
    val hapticKeypad: Boolean = true,
    // ⚠️ v13.9：`auroraEnabled` 已删除。它控制的是背景四团漂移色光，
    // 而那四团光在 v13.9 整层退役了 —— 偏好项跟着一起退役，
    // 老用户 DataStore 里残留的 aurora_enabled 键会被忽略（不迁移、不报错）。
    /**
     * v10.1.0：明细页概览卡是否收起（只留主角大数字）。
     *
     * 手机 6 寸屏上「概览卡 + 资产条 + 工具条 + 搜索」正好占满一屏，
     * 打开 App 看不到任何一笔流水。收起后首屏就能看到账。
     * 记住用户的选择：习惯看大数字的人不会被反复折叠打扰。
     */
    val detailOverviewCompact: Boolean = false,
    /** v8.1 自动记账：监听支付通知/银行短信，自动进「自动记账」账本 */
    val autoBookkeepingEnabled: Boolean = true,
    /** v8.1：自动记账账本是否已预置过（只建一次，用户删了就不再打扰） */
    val autoLedgerSeeded: Boolean = false,
    /** v15：自动记账「快速入账」记住上次确认到的目标账本（0 = 还没记过） */
    val lastConfirmLedgerId: Long = 0L,
    // ---------- M4 双端同步 ----------
    /** 同步开关（默认关；开启后由同步引擎读写下面几个键） */
    val syncEnabled: Boolean = false,
    /** 自托管同步服务地址（如 http://192.168.1.10:8701） */
    val syncServerUrl: String = "",
    /** 服务端注册时分配的设备 id（0 = 未注册） */
    val syncDeviceId: Long = 0L,
    /** 时钟偏差校正：serverTime - localTime（毫秒），客户端用它对齐 LWW 时间戳 */
    val syncClockOffsetMs: Long = 0L,
    /** 各表同步水位线（引擎内部格式 "表名=毫秒" 换行分隔；空 = 从未同步） */
    val syncWatermarks: String = "",
    /** 服务端访问令牌（对应 sync-server 的 --token；空 = 服务端未启用门禁） */
    val syncToken: String = "",
    /** 自动同步（App 运行期间每 5 分钟一次） */
    val syncAutoSync: Boolean = false,
    /** 同步记录（最近 N 次的 JSON，见 SyncLogStore；空 = 从没同步过） */
    val syncLog: String = "",
    // ---------- M 桌面专属（Android 端忽略） ----------
    /** 开机自启：桌面端写入 HKCU Run 注册表，带 --minimized 后台进托盘 */
    val desktopAutoStart: Boolean = false,
    /** 桌面端自动更新源目录（null = 默认：安装目录同级目录，即 exe 旁的 dist） */
    val desktopUpdateDir: String? = null
)

/** 触感强度档位（v5.0） */
object HapticStrength {
    const val OFF = 0
    const val LIGHT = 1
    const val MEDIUM = 2
    const val STRONG = 3

    fun label(v: Int): String = when (v) {
        OFF -> "关"
        LIGHT -> "轻"
        MEDIUM -> "中"
        else -> "强"
    }
}

class AppPreferences(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val DARK_MODE = intPreferencesKey("dark_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val COLOR_CONVENTION = intPreferencesKey("color_convention")
        val LAST_LEDGER = longPreferencesKey("last_ledger")
        val BACKUP_TREE = stringPreferencesKey("backup_tree_uri")
        val BACKUP_FOLDER_NAME = stringPreferencesKey("backup_folder_name")
        val DESKTOP_BACKUP_FOLDER = stringPreferencesKey("desktop_backup_folder_path")
        val AUTO_BACKUP = booleanPreferencesKey("auto_backup_enabled")
        val LAST_AUTO_BACKUP = longPreferencesKey("last_auto_backup_at")
        val HIGH_REFRESH = booleanPreferencesKey("high_refresh_rate")
        val AGGREGATE_IDS = stringSetPreferencesKey("aggregate_ledger_ids")
        val AGGREGATE_ALL = booleanPreferencesKey("aggregate_all")
        val TEMPLATES = stringPreferencesKey("txn_templates")
        val REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
        val REMINDER_HOUR = intPreferencesKey("reminder_hour")
        val REMINDER_MINUTE = intPreferencesKey("reminder_minute")
        // v5.0 触感与动效
        val HAPTIC_STRENGTH = intPreferencesKey("haptic_strength")
        val HAPTIC_KEYPAD = booleanPreferencesKey("haptic_keypad")
        val DETAIL_OVERVIEW_COMPACT = booleanPreferencesKey("detail_overview_compact")
        // v8.1 自动记账
        val AUTO_BOOKKEEPING = booleanPreferencesKey("auto_bookkeeping_enabled")
        val AUTO_LEDGER_SEEDED = booleanPreferencesKey("auto_ledger_seeded")
        // v15 自动记账「快速入账」的上次目标账本
        val LAST_CONFIRM_LEDGER = longPreferencesKey("last_confirm_ledger")
        // v9.4 回收站：被删掉的备份文件清单（文件本体在 App 私有目录）
        val RECYCLED_BACKUPS = stringPreferencesKey("recycled_backups")
        // v13 账本列表的手动排序（长按拖拽）。存 id 的 CSV，不落库 ——
        // 加一列要动 Room 迁移，而这个顺序纯属"界面偏好"，丢了不影响任何数据。
        val LEDGER_ORDER = stringPreferencesKey("ledger_order")
        // M4 双端同步
        val SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
        val SYNC_SERVER_URL = stringPreferencesKey("sync_server_url")
        val SYNC_DEVICE_ID = longPreferencesKey("sync_device_id")
        val SYNC_CLOCK_OFFSET = longPreferencesKey("sync_clock_offset_ms")
        val SYNC_WATERMARKS = stringPreferencesKey("sync_watermarks")
        val SYNC_TOKEN = stringPreferencesKey("sync_token")
        val SYNC_AUTO = booleanPreferencesKey("sync_auto")
        val SYNC_LOG = stringPreferencesKey("sync_log")
        // 桌面专属：开机自启 / 更新源目录
        val DESKTOP_AUTO_START = booleanPreferencesKey("desktop_auto_start")
        val DESKTOP_UPDATE_DIR = stringPreferencesKey("desktop_update_dir")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { p ->
        AppSettings(
            darkMode = p[Keys.DARK_MODE] ?: DarkMode.FOLLOW_SYSTEM,
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: false,
            colorConvention = p[Keys.COLOR_CONVENTION] ?: ColorConvention.LEDGER,
            lastLedgerId = p[Keys.LAST_LEDGER] ?: -1L,
            backupTreeUri = p[Keys.BACKUP_TREE],
            backupFolderName = p[Keys.BACKUP_FOLDER_NAME],
            desktopBackupFolderPath = p[Keys.DESKTOP_BACKUP_FOLDER],
            autoBackupEnabled = p[Keys.AUTO_BACKUP] ?: true,
            lastAutoBackupAt = p[Keys.LAST_AUTO_BACKUP] ?: 0L,
            highRefreshRate = p[Keys.HIGH_REFRESH] ?: true,
            aggregateAll = p[Keys.AGGREGATE_ALL] ?: true,
            aggregateLedgerIds = (p[Keys.AGGREGATE_IDS] ?: emptySet())
                .mapNotNull { it.toLongOrNull() }
                .toSet(),
            reminderEnabled = p[Keys.REMINDER_ENABLED] ?: false,
            reminderHour = p[Keys.REMINDER_HOUR] ?: 20,
            reminderMinute = p[Keys.REMINDER_MINUTE] ?: 0,
            hapticStrength = p[Keys.HAPTIC_STRENGTH] ?: HapticStrength.MEDIUM,
            hapticKeypad = p[Keys.HAPTIC_KEYPAD] ?: true,
            detailOverviewCompact = p[Keys.DETAIL_OVERVIEW_COMPACT] ?: false,
            autoBookkeepingEnabled = p[Keys.AUTO_BOOKKEEPING] ?: true,
            autoLedgerSeeded = p[Keys.AUTO_LEDGER_SEEDED] ?: false,
            lastConfirmLedgerId = p[Keys.LAST_CONFIRM_LEDGER] ?: 0L,
            ledgerOrder = (p[Keys.LEDGER_ORDER] ?: "")
                .split(',')
                .mapNotNull { it.trim().toLongOrNull() },
            syncEnabled = p[Keys.SYNC_ENABLED] ?: false,
            syncServerUrl = p[Keys.SYNC_SERVER_URL] ?: "",
            syncDeviceId = p[Keys.SYNC_DEVICE_ID] ?: 0L,
            syncClockOffsetMs = p[Keys.SYNC_CLOCK_OFFSET] ?: 0L,
            syncWatermarks = p[Keys.SYNC_WATERMARKS] ?: "",
            syncToken = p[Keys.SYNC_TOKEN] ?: "",
            syncAutoSync = p[Keys.SYNC_AUTO] ?: false,
            syncLog = p[Keys.SYNC_LOG] ?: "",
            desktopAutoStart = p[Keys.DESKTOP_AUTO_START] ?: false,
            desktopUpdateDir = p[Keys.DESKTOP_UPDATE_DIR]
        )
    }

    suspend fun setDarkMode(value: Int) = dataStore.edit { it[Keys.DARK_MODE] = value }

    suspend fun setDynamicColor(value: Boolean) = dataStore.edit { it[Keys.DYNAMIC_COLOR] = value }

    suspend fun setColorConvention(value: Int) = dataStore.edit { it[Keys.COLOR_CONVENTION] = value }

    suspend fun setLastLedger(id: Long) = dataStore.edit { it[Keys.LAST_LEDGER] = id }

    suspend fun setBackupFolder(uri: String?, name: String?) = dataStore.edit { p ->
        if (uri == null) p.remove(Keys.BACKUP_TREE) else p[Keys.BACKUP_TREE] = uri
        if (name == null) p.remove(Keys.BACKUP_FOLDER_NAME) else p[Keys.BACKUP_FOLDER_NAME] = name
    }

    /** 桌面端备份目录（绝对路径；null = 清除） */
    suspend fun setDesktopBackupFolder(path: String?) = dataStore.edit { p ->
        if (path == null) p.remove(Keys.DESKTOP_BACKUP_FOLDER) else p[Keys.DESKTOP_BACKUP_FOLDER] = path
    }

    suspend fun setAutoBackupEnabled(value: Boolean) =
        dataStore.edit { it[Keys.AUTO_BACKUP] = value }

    suspend fun markAutoBackupDone(at: Long) =
        dataStore.edit { it[Keys.LAST_AUTO_BACKUP] = at }

    suspend fun setHighRefreshRate(value: Boolean) =
        dataStore.edit { it[Keys.HIGH_REFRESH] = value }

    /** 设置合计范围：[all] 为 true 表示统计全部账本 */
    suspend fun setAggregate(all: Boolean, ids: Set<Long>) = dataStore.edit { p ->
        p[Keys.AGGREGATE_ALL] = all
        if (ids.isEmpty()) p.remove(Keys.AGGREGATE_IDS)
        else p[Keys.AGGREGATE_IDS] = ids.map { it.toString() }.toSet()
    }

    // ---- 常用记账模板（v2.1，JSON 存 DataStore，不建表）----

    val templates: Flow<List<TxnTemplate>> =
        dataStore.data.map { p -> TxnTemplateCodec.decode(p[Keys.TEMPLATES] ?: "") }

    suspend fun setTemplates(list: List<TxnTemplate>) =
        dataStore.edit { it[Keys.TEMPLATES] = TxnTemplateCodec.encode(list) }

    // ---- 每日记账提醒（v2.1）----

    suspend fun setReminder(enabled: Boolean, hour: Int, minute: Int) =
        dataStore.edit { p ->
            p[Keys.REMINDER_ENABLED] = enabled
            p[Keys.REMINDER_HOUR] = hour
            p[Keys.REMINDER_MINUTE] = minute
        }

    // ---- v5.0 触感与动效 ----

    suspend fun setHapticStrength(v: Int) =
        dataStore.edit { it[Keys.HAPTIC_STRENGTH] = v.coerceIn(0, 3) }

    suspend fun setHapticKeypad(v: Boolean) =
        dataStore.edit { it[Keys.HAPTIC_KEYPAD] = v }

    /** v10.1.0：明细页概览卡收起/展开 */
    suspend fun setDetailOverviewCompact(v: Boolean) =
        dataStore.edit { it[Keys.DETAIL_OVERVIEW_COMPACT] = v }

    suspend fun setAutoBookkeepingEnabled(v: Boolean) =
        dataStore.edit { it[Keys.AUTO_BOOKKEEPING] = v }

    suspend fun setAutoLedgerSeeded(v: Boolean) =
        dataStore.edit { it[Keys.AUTO_LEDGER_SEEDED] = v }

    suspend fun setLastConfirmLedger(id: Long) =
        dataStore.edit { it[Keys.LAST_CONFIRM_LEDGER] = id }

    /** v13：账本列表手动顺序（长按拖拽的结果） */
    suspend fun setLedgerOrder(ids: List<Long>) = dataStore.edit { p ->
        if (ids.isEmpty()) p.remove(Keys.LEDGER_ORDER)
        else p[Keys.LEDGER_ORDER] = ids.joinToString(",")
    }

    // ---- v9.4 回收站：被删掉的备份文件 ----

    val recycledBackups: Flow<List<RecycledBackup>> =
        dataStore.data.map { p ->
            RecycledBackupCodec.decode(p[Keys.RECYCLED_BACKUPS] ?: "")
        }

    suspend fun setRecycledBackups(list: List<RecycledBackup>) =
        dataStore.edit { it[Keys.RECYCLED_BACKUPS] = RecycledBackupCodec.encode(list) }

    /**
     * v10.3：在 DataStore edit 事务内原子读改写回收站清单。
     *
     * 原来 `loadRecycled()` 读失败当空表、然后整表覆盖写回 ——
     * 一次瞬时读失败就把全部历史清空。现在读与写在同一个 edit 块里，
     * 读不到就整次操作失败，绝不拿空表覆盖旧数据。
     */
    suspend fun mutateRecycledBackups(transform: (List<RecycledBackup>) -> List<RecycledBackup>) {
        dataStore.edit { prefs ->
            val current = RecycledBackupCodec.decode(prefs[Keys.RECYCLED_BACKUPS] ?: "")
            prefs[Keys.RECYCLED_BACKUPS] = RecycledBackupCodec.encode(transform(current))
        }
    }

    // ---- M4 双端同步 ----

    suspend fun setSyncEnabled(v: Boolean) =
        dataStore.edit { it[Keys.SYNC_ENABLED] = v }

    suspend fun setSyncServerUrl(v: String) =
        dataStore.edit { it[Keys.SYNC_SERVER_URL] = v.trim() }

    suspend fun setSyncDeviceId(id: Long) =
        dataStore.edit { it[Keys.SYNC_DEVICE_ID] = id }

    suspend fun setSyncClockOffset(offsetMs: Long) =
        dataStore.edit { it[Keys.SYNC_CLOCK_OFFSET] = offsetMs }

    suspend fun setSyncWatermarks(serialized: String) =
        dataStore.edit { it[Keys.SYNC_WATERMARKS] = serialized }

    suspend fun setSyncToken(v: String) =
        dataStore.edit { it[Keys.SYNC_TOKEN] = v.trim() }

    suspend fun setSyncAutoSync(on: Boolean) =
        dataStore.edit { it[Keys.SYNC_AUTO] = on }

    suspend fun setSyncLog(json: String) =
        dataStore.edit { it[Keys.SYNC_LOG] = json }

    // ---- 桌面专属：开机自启 / 自动更新 ----

    suspend fun setDesktopAutoStart(v: Boolean) =
        dataStore.edit { it[Keys.DESKTOP_AUTO_START] = v }

    /** 桌面自动更新源目录（null/空 = 清除，回落到默认：安装目录同级目录） */
    suspend fun setDesktopUpdateDir(path: String?) = dataStore.edit { p ->
        if (path.isNullOrBlank()) p.remove(Keys.DESKTOP_UPDATE_DIR)
        else p[Keys.DESKTOP_UPDATE_DIR] = path.trim()
    }
}
