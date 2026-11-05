package com.foldledger.data.sync

import com.foldledger.data.prefs.AppPreferences
import com.foldledger.data.repo.LedgerRepository
import com.foldledger.platform.scheduleBackgroundSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 同步 UI 状态（双端共享的同步卡直接 collect 它） */
data class SyncUiState(
    val enabled: Boolean = false,
    val serverUrl: String = "",
    val syncToken: String = "",
    val deviceId: Long = 0L,
    val registered: Boolean = false,
    val syncing: Boolean = false,
    val autoSync: Boolean = false,
    val lastSyncAt: Long = 0L,
    val lastResult: String? = null,
    val lastError: String? = null,
    /** v16.2.8：最近若干次同步明细（新的在前），「同步记录」页直接渲染它 */
    val history: List<SyncLogEntry> = emptyList()
)

/**
 * M4.5 同步管理器（双端共享）：注册 / 手动与自动同步 / 状态。
 *
 * 职责链：register 拿到 deviceId+serverTime → 写 [SyncDeviceHolder]/[SyncOffsetHolder]
 * （此后打戳自动对齐服务器时钟）→ 置 [SyncIdAllocator].enabled（新行进入设备前缀 id 空间）
 * → syncNow 走「collect → POST → apply → 水位线持久化」。
 *
 * 自动同步 = App 运行期间的协程循环（5 分钟）；Android 后台级调度（WorkManager）
 * 留作后续，桌面端依赖进程常驻即可。
 */
class SyncManager(
    private val repo: LedgerRepository,
    private val prefs: AppPreferences,
    private val engine: SyncEngine,
    private val scope: CoroutineScope
) {

    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state

    private var watermarks = SyncWatermarks("")
    private var http: SyncHttp? = null
    private var autoJob: Job? = null

    /** 启动时从 prefs 恢复同步状态（ServiceLocator.init 后调用一次） */
    suspend fun initFromPrefs() {
        val s = prefs.settings.first()
        watermarks = SyncWatermarks(s.syncWatermarks)
        _state.update { it.copy(history = SyncLogStore.decode(s.syncLog)) }
        SyncDeviceHolder.deviceId = s.syncDeviceId
        SyncOffsetHolder.offsetMs = s.syncClockOffsetMs
        repo.idAllocator.enabled = s.syncEnabled && s.syncDeviceId > 0L
        if (s.syncServerUrl.isNotBlank()) http = SyncHttp(s.syncServerUrl)
        _state.value = SyncUiState(
            enabled = s.syncEnabled,
            serverUrl = s.syncServerUrl,
            syncToken = s.syncToken,
            deviceId = s.syncDeviceId,
            registered = s.syncDeviceId > 0L,
            autoSync = s.syncAutoSync
        )
        scheduleBackgroundSync(s.syncEnabled && s.syncDeviceId > 0L)
        if (s.syncEnabled && s.syncDeviceId > 0L && s.syncAutoSync) startAutoSync()
    }

    suspend fun setEnabled(enabled: Boolean) {
        prefs.setSyncEnabled(enabled)
        if (enabled && http == null && _state.value.serverUrl.isNotBlank()) {
            http = SyncHttp(_state.value.serverUrl)
        }
        repo.idAllocator.enabled = enabled && _state.value.deviceId > 0L
        _state.update { it.copy(enabled = enabled, lastError = null) }
        scheduleBackgroundSync(enabled && _state.value.deviceId > 0L)
        if (enabled && _state.value.autoSync) startAutoSync() else if (!enabled) stopAutoSync()
    }

    suspend fun setServerUrl(url: String) {
        // v16.4.2：落盘与建客户端都用**整理后**的地址（全角 → 半角、裁空白、补 http://）。
        // 输入框里仍显示用户原样输入的文本，避免打字时光标乱跳；下次启动读到的是干净值。
        val cleaned = normalizeServerUrl(url)
        prefs.setSyncServerUrl(cleaned)
        http = if (cleaned.isNotBlank()) SyncHttp(cleaned) else null
        _state.update { it.copy(serverUrl = url, lastError = null) }
    }

    /** 设置服务端访问令牌（对应 sync-server 的 --token；留空 = 服务端未启用门禁） */
    suspend fun setToken(token: String) {
        prefs.setSyncToken(token)
        _state.update { it.copy(syncToken = token, lastError = null) }
    }

    suspend fun setAutoSync(on: Boolean) {
        prefs.setSyncAutoSync(on)
        _state.update { it.copy(autoSync = on, lastError = null) }
        if (on && _state.value.enabled && _state.value.deviceId > 0L) startAutoSync()
        else if (!on) stopAutoSync()
    }

    /** 注册本设备：拿 deviceId + 时钟偏差，之后新行进入设备前缀 id 空间 */
    suspend fun register() {
        val url = _state.value.serverUrl
        if (url.isBlank()) {
            _state.update { it.copy(lastError = "请先填写服务器地址") }
            return
        }
        val http = http ?: SyncHttp(url).also { this.http = it }
        _state.update { it.copy(syncing = true, lastError = null) }
        try {
            val resp = http.register(_state.value.syncToken)
            SyncDeviceHolder.deviceId = resp.deviceId
            SyncOffsetHolder.offsetMs = resp.serverTime - System.currentTimeMillis()
            prefs.setSyncDeviceId(resp.deviceId)
            prefs.setSyncClockOffset(SyncOffsetHolder.offsetMs)
            repo.idAllocator.enabled = true
            _state.update {
                it.copy(syncing = false, deviceId = resp.deviceId, registered = true, lastError = null)
            }
            scheduleBackgroundSync(true)
            if (_state.value.autoSync) startAutoSync()
        } catch (e: Exception) {
            val msg = describeError(e)
            _state.update { it.copy(syncing = false, lastError = msg) }
            record("注册", ok = false, up = 0, down = 0, stats = null,
                started = System.currentTimeMillis(), error = msg)
        }
    }

    /**
     * 记一条同步记录（v16.2.8）：新的在前、最多 20 条、顺手写回 prefs。
     *
     * 写 prefs 用 runCatching 兜住 —— 日志写失败绝不能让同步本身失败。
     */
    private suspend fun record(
        trigger: String,
        ok: Boolean,
        up: Int,
        down: Int,
        stats: SyncApplyStats?,
        started: Long,
        error: String? = null
    ) {
        val entry = SyncLogEntry(
            at = System.currentTimeMillis(),
            ok = ok,
            trigger = trigger,
            upRows = up,
            downRows = down,
            inserted = stats?.inserted ?: 0,
            updated = stats?.updated ?: 0,
            localWins = stats?.localWins ?: 0,
            skipped = stats?.skipped ?: 0,
            durationMs = (System.currentTimeMillis() - started).coerceAtLeast(0L),
            error = error,
            conflicts = stats?.conflicts ?: emptyList()
        )
        val next = (listOf(entry) + _state.value.history).take(SyncLogStore.LIMIT)
        _state.update { it.copy(history = next) }
        runCatching { prefs.setSyncLog(SyncLogStore.encode(next)) }
    }

    /** 一次完整同步：收集增量 → 上传 → 合并下行 → 持久化水位线 */
    suspend fun syncNow(trigger: String = "手动") {
        val http = http ?: run {
            _state.update { it.copy(lastError = "请先填写服务器地址") }
            record(trigger, ok = false, up = 0, down = 0, stats = null, started = System.currentTimeMillis(),
                error = "请先填写服务器地址")
            return
        }
        val deviceId = _state.value.deviceId
        if (deviceId <= 0L) {
            _state.update { it.copy(lastError = "尚未注册，先点「注册此设备」") }
            record(trigger, ok = false, up = 0, down = 0, stats = null, started = System.currentTimeMillis(),
                error = "尚未注册")
            return
        }
        _state.update { it.copy(syncing = true, lastError = null) }
        val started = System.currentTimeMillis()
        try {
            val outgoing = engine.collectOutgoing(watermarks)
            val resp = http.sync(deviceId, watermarks, outgoing, _state.value.syncToken)
            // 时钟漂移校正：以服务端时间为准，写路径打戳自动对齐
            SyncOffsetHolder.offsetMs = resp.serverTime - System.currentTimeMillis()
            prefs.setSyncClockOffset(SyncOffsetHolder.offsetMs)

            if (resp.rows.isNotEmpty()) {
                val stats = engine.applyIncoming(resp.rows, watermarks)
                // v16.4.1：下行可能写进「本设备前缀」的行（重装后服务端分回同一 deviceId、
                // 或从备份恢复），此时 id 分配器的进程内计数已经落后于库 —— 置脏，
                // 下一次分配重新按本设备区间取 max，避免又撞主键。
                repo.idAllocator.invalidate()
                prefs.setSyncWatermarks(watermarks.toRawString())
                _state.update {
                    it.copy(
                        syncing = false,
                        lastSyncAt = System.currentTimeMillis(),
                        lastResult = "上行 ${outgoing.size} 行 · 下行 ${resp.rows.size} 行（新增 ${stats.inserted} / 更新 ${stats.updated}）",
                        lastError = null
                    )
                }
                record(trigger, ok = true, up = outgoing.size, down = resp.rows.size, stats = stats, started = started)
            } else {
                prefs.setSyncWatermarks(watermarks.toRawString())
                _state.update {
                    it.copy(
                        syncing = false,
                        lastSyncAt = System.currentTimeMillis(),
                        lastResult = "已是最新（${outgoing.size} 行上行）",
                        lastError = null
                    )
                }
                record(trigger, ok = true, up = outgoing.size, down = 0, stats = null, started = started)
            }
        } catch (e: Exception) {
            _state.update { it.copy(syncing = false, lastError = describeError(e)) }
        }
    }

    /**
     * 重置同步身份：清空 deviceId / 时钟偏差 / 水位线并停用 id 分配器。
     * 换服务器、或双端身份疑似冲突（同步报 401/数据错乱）时用，之后需重新「注册此设备」。
     */
    suspend fun resetIdentity() {
        stopAutoSync()
        prefs.setSyncDeviceId(0L)
        prefs.setSyncClockOffset(0L)
        prefs.setSyncWatermarks("")
        SyncDeviceHolder.deviceId = 0L
        SyncOffsetHolder.offsetMs = 0L
        repo.idAllocator.enabled = false
        watermarks = SyncWatermarks("")
        scheduleBackgroundSync(false)
        // 换服务器后旧记录没有参考价值，且容易让人误以为"现在还是这些错误"
        _state.update { it.copy(history = emptyList()) }
        runCatching { prefs.setSyncLog("") }
        _state.update {
            it.copy(
                deviceId = 0L,
                registered = false,
                lastSyncAt = 0L,
                lastResult = null,
                lastError = "已重置同步身份，请重新注册"
            )
        }
    }

    /** 把底层异常翻译成用户能看懂的中文提示 */
    private fun describeError(e: Exception): String = when (e) {
        // 地址不合法 / 空地址：消息本身就是给人看的话，不要再包一层
        is SyncUrlException -> e.message ?: "服务器地址格式不对"
        is SyncHttpException -> when (e.code) {
            401 -> "密钥错误或未授权（401），检查访问密钥"
            404 -> "地址路径不对（404）：应为 http://域名:端口，不要带路径"
            else -> e.message ?: "服务器返回错误 ${e.code}"
        }
        is java.net.UnknownHostException -> "无法解析服务器域名，请检查地址或网络"
        is java.net.SocketTimeoutException -> "连接服务器超时，请检查网络后重试"
        is java.net.ConnectException -> "连不上服务器（连接被拒绝），检查地址、端口与服务是否在运行"
        // 兜底带上异常类型：v16.4.1 遇到过一个只显示
        // 「Error at index 5 in: ':10097'」的 JDK 解析异常，光看消息无法定位
        else -> "${e.javaClass.simpleName}: ${e.message ?: "同步失败"}"
    }

    private fun startAutoSync() {
        if (autoJob?.isActive == true) return
        autoJob = scope.launch {
            while (isActive) {
                delay(AUTO_SYNC_INTERVAL_MS)
                if (_state.value.enabled && _state.value.deviceId > 0L) syncNow(trigger = "自动")
            }
        }
    }

    private fun stopAutoSync() {
        autoJob?.cancel()
        autoJob = null
    }

    companion object {
        const val AUTO_SYNC_INTERVAL_MS = 5 * 60_000L
    }
}
