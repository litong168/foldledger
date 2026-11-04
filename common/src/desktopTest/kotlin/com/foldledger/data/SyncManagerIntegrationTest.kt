package com.foldledger.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.foldledger.data.db.AppDatabase
import com.foldledger.data.db.AppDatabase_Impl
import com.foldledger.data.db.Direction
import com.foldledger.data.prefs.AppPreferences
import com.foldledger.data.repo.LedgerRepository
import com.foldledger.data.sync.SyncDeviceHolder
import com.foldledger.data.sync.SyncEngine
import com.foldledger.data.sync.SyncIdAllocator
import com.foldledger.data.sync.SyncManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * M4.5 端到端集成：真实启动 sync-server/server.js，
 * 两台"设备"（独立 db / prefs / repo / SyncManager）注册、同步、合并、墓碑传播。
 *
 * v16.1.1：服务端全程带 `--token`（门禁开启形态），每个设备注册前先 setToken ——
 * token 全链路（密钥框 → SyncManager → SyncHttp → 服务器）在真实 HTTP 下验证。
 */
class SyncManagerIntegrationTest {

    private lateinit var tempDir: File
    private lateinit var dataFile: File
    private var server: Process? = null

    private val TEST_TOKEN = "integration-secret"

    /** 每个测试独立端口：避免上一个测试的 server 进程未退出导致连错实例 */
    private val port: Int by lazy {
        java.net.ServerSocket(0).use { it.localPort }
    }
    private val url: String get() = "http://127.0.0.1:$port"

    private class DeviceFixture(
        val name: String,
        val dir: File,
        val stamps: TestStamps
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db: AppDatabase = Room.databaseBuilder(
            name = File(dir, "db.sqlite").absolutePath,
            factory = { AppDatabase_Impl() }
        ).setDriver(BundledSQLiteDriver()).build()
        val prefs: AppPreferences = AppPreferences(
            PreferenceDataStoreFactory.create(scope = scope) { File(dir, "prefs.preferences_pb") }
        )
        val repo: LedgerRepository = LedgerRepository(db, stamps, SyncIdAllocator())
        val engine = SyncEngine(db)
        val syncManager = SyncManager(repo, prefs, engine, scope)

        fun close() {
            scope.cancel()
            db.close()
        }
    }

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "foldledger-mgr-${System.nanoTime()}")
        tempDir.mkdirs()
        dataFile = File(tempDir, "data.json")
        // Gradle test 的 workingDir 是 :common 模块目录，server.js 在项目根
        val serverJs = listOf(
            File("sync-server/server.js"),
            File("../sync-server/server.js"),
            File("../../sync-server/server.js")
        ).firstOrNull { it.exists() }
        assertTrue("server.js 未找到（向上探测过 common/ 与项目根）", serverJs != null)
        // node 不在系统 PATH（由 MiMo 运行时提供 MIMO_NODE）
        val node = System.getenv("MIMO_NODE")?.takeIf { File(it).exists() } ?: "node"
        server = ProcessBuilder(
            node, serverJs!!.absolutePath,
            "--port", port.toString(),
            "--data", dataFile.absolutePath,
            "--token", TEST_TOKEN
        ).start()
        awaitServer()
    }

    @After
    fun tearDown() {
        // v16.1.1：MiMo 的 node 是转发器（内部转给 Xiaomi MiMo.exe 包装），
        // destroy 只杀转发器、包装会成孤儿残留监听端口。
        // ProcessHandle 在 Windows 拿不到 GUI 进程的命令行 → 用 CIM
        // （Win32_Process.CommandLine 可靠）按 server.js + 本测试端口匹配整树强杀。
        server?.let { it.destroy() }
        try {
            val ps = "Get-CimInstance Win32_Process | Where-Object { \$_.CommandLine -match 'server\\.js' -and " +
                "\$_.CommandLine -match '--port $port' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }"
            Runtime.getRuntime().exec(arrayOf("powershell", "-NoProfile", "-Command", ps))
                .waitFor(5, TimeUnit.SECONDS)
        } catch (_: Exception) {}
        server?.let { it.waitFor(3, TimeUnit.SECONDS) }
        tempDir.deleteRecursively()
        SyncDeviceHolder.deviceId = 0L
        com.foldledger.data.sync.SyncOffsetHolder.offsetMs = 0L
    }

    private fun awaitServer() {
        repeat(50) {
            try {
                java.net.Socket("127.0.0.1", port).use { }
                return
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
        fail("sync server 未在 $port 端口就绪")
    }

    private fun newDevice(name: String, device: Long): DeviceFixture {
        val dir = File(tempDir, name)
        dir.mkdirs()
        return DeviceFixture(name, dir, TestStamps(device = device))
    }

    @Test
    fun registerAssignsDeviceAndEnablesAllocator() = runBlocking {
        val d1 = newDevice("d1", 1L)
        try {
            d1.syncManager.initFromPrefs()
            d1.syncManager.setServerUrl(url)
            d1.syncManager.setToken(TEST_TOKEN)
            d1.syncManager.register()

            val st = d1.syncManager.state.value
            assertTrue(st.registered)
            assertEquals(1L, st.deviceId)
            assertNull(st.lastError)
            assertTrue(d1.repo.idAllocator.enabled)

            // 注册后新建行落设备前缀 id 空间
            val lid = d1.repo.createLedger("日常", "daily", "📒", "#1D3B57")
            assertEquals((1L shl 40) or 1L, lid)
        } finally {
            d1.close()
        }
    }

    @Test
    fun fullRoundTripBetweenTwoDevices() = runBlocking {
        val d1 = newDevice("d1", 1L)
        val d2 = newDevice("d2", 2L)
        try {
            // ---- 设备1：注册 + 建数据 + 首同步 ----
            d1.syncManager.initFromPrefs()
            d1.syncManager.setServerUrl(url)
            d1.syncManager.setToken(TEST_TOKEN)
            d1.syncManager.register()
            val lid = d1.repo.createLedger("日常", "daily", "📒", "#1D3B57")
            d1.repo.addTransaction(lid, null, "餐饮", "🍜", Direction.EXPENSE, 1000, "早", 0L)
            d1.repo.addTransaction(lid, null, "交通", "🚌", Direction.EXPENSE, 500, "地铁", 0L)
            d1.syncManager.syncNow()
            assertNull(d1.syncManager.state.value.lastError)
            assertTrue(d1.syncManager.state.value.lastResult?.contains("上行") == true)
            // 水位线已持久化
            assertTrue(d1.prefs.settings.first().syncWatermarks.isNotBlank())

            // ---- 设备2：注册（deviceId=2）+ 首同步拉到全部 ----
            d2.syncManager.initFromPrefs()
            d2.syncManager.setServerUrl(url)
            d2.syncManager.setToken(TEST_TOKEN)
            d2.syncManager.register()
            assertEquals(2L, d2.syncManager.state.value.deviceId)
            d2.syncManager.syncNow()
            assertNull(d2.syncManager.state.value.lastError)
            assertEquals(1, d2.repo.allLedgersOnce().size)
            assertEquals(2, d2.repo.allTransactionsOnce().size)
            assertEquals("早", d2.repo.allTransactionsOnce().first { it.amountCents == 1000L }.note)

            // ---- 设备2 改一笔 → 同步 → 设备1 拉到合并 ----
            d2.stamps.advance(1_000)
            val txn = d2.repo.allTransactionsOnce().first { it.amountCents == 1000L }
            d2.repo.updateTransaction(txn.copy(note = "电脑端改过"))
            d2.syncManager.syncNow()
            d1.syncManager.syncNow()
            assertEquals("电脑端改过", d1.repo.allTransactionsOnce().first { it.amountCents == 1000L }.note)

            // ---- 设备1 删一笔 → 同步 → 设备2 收到墓碑（不可见） ----
            // 删除必须发生在水位线（T0+1001）之后：真实时钟下新操作必然晚于上次同步
            d1.stamps.advance(2_000)
            val toDelete = d1.repo.allTransactionsOnce().first { it.amountCents == 500L }
            d1.repo.deleteTransaction(toDelete.id)
            d1.syncManager.syncNow()
            d2.syncManager.syncNow()
            assertEquals(1, d2.repo.allTransactionsOnce().size)

            // ---- 设备2 再同步不应报错（水位线已推进） ----
            d2.syncManager.syncNow()
            assertNull(d2.syncManager.state.value.lastError)
        } finally {
            d1.close()
            d2.close()
        }
    }

    /**
     * v16.1.1：服务端开启门禁（--token）时，未填密钥的设备注册必须被拒，
     * 错误文案中文化为「密钥错误或未授权」。
     */
    @Test
    fun registerWithoutTokenRejected() = runBlocking {
        val d1 = newDevice("d1", 1L)
        try {
            d1.syncManager.initFromPrefs()
            d1.syncManager.setServerUrl(url)
            // 故意不 setToken —— 服务端已开 --token 门禁
            d1.syncManager.register()
            assertTrue(!d1.syncManager.state.value.registered)
            val err = d1.syncManager.state.value.lastError
            assertTrue("lastError 应中文化提示密钥错误，实际=$err", err?.contains("密钥错误或未授权") == true)
        } finally {
            d1.close()
        }
    }
}
