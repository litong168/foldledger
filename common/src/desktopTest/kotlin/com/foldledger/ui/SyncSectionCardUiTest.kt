package com.foldledger.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAwtImage
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.foldledger.data.db.AppDatabase
import com.foldledger.data.db.AppDatabase_Impl
import com.foldledger.data.prefs.AppPreferences
import com.foldledger.data.repo.LedgerRepository
import com.foldledger.data.sync.SyncEngine
import com.foldledger.data.sync.SyncIdAllocator
import com.foldledger.data.sync.SyncManager
import com.foldledger.data.sync.SystemSyncStampProvider
import com.foldledger.ui.screens.SyncSectionCard
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * M4.5 同步卡 UI 渲染验证（桌面 Compose UI 测试）。
 *
 * 桌面「我的」页同步卡在备份卡之后的视口下方、本机合成滚轮对 Skiko 无效，
 * 无法交互目检 —— 这里直接渲染组件断言文字，并把渲染结果存成 PNG
 * （配合 scripts/ocr.ps1 做像素级确认）。
 */
class SyncSectionCardUiTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private lateinit var db: AppDatabase
    private lateinit var manager: SyncManager
    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        // UI 测试环境无 Dispatchers.Main：映射到 UnconfinedTestDispatcher（lifecycle-compose 内部依赖）
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        dir = File(System.getProperty("java.io.tmpdir"), "foldledger-ui-${System.nanoTime()}")
        dir.mkdirs()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        db = Room.databaseBuilder(
            name = File(dir, "db.sqlite").absolutePath,
            factory = { AppDatabase_Impl() }
        ).setDriver(BundledSQLiteDriver()).build()
        prefs = AppPreferences(
            PreferenceDataStoreFactory.create(scope = scope) { File(dir, "prefs.preferences_pb") }
        )
        val repo = LedgerRepository(db, SystemSyncStampProvider(), SyncIdAllocator())
        manager = SyncManager(repo, prefs, SyncEngine(db), scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        dir.deleteRecursively()
    }

    /** 预置「已启用 + 已注册」状态（绕过网络 register，直接写 prefs 再恢复） */
    private fun seedRegisteredState() = runBlocking {
        prefs.setSyncEnabled(true)
        prefs.setSyncDeviceId(5L)
        prefs.setSyncServerUrl("http://192.168.1.10:8701")
        manager.initFromPrefs()
    }

    @Test
    fun disabledStateShowsSwitchAndHint() {
        compose.setContent { card() }
        compose.onNodeWithText("启用双端同步").assertExists()
        compose.onNodeWithText("手机 ↔ 电脑").assertExists()
        // 未启用：地址输入框与注册按钮都不该出现
        compose.onNodeWithText("服务器地址").assertDoesNotExist()
        compose.onNodeWithText("注册此设备").assertDoesNotExist()
        compose.onNodeWithText("自动记账", substring = true).assertExists()
    }

    @Test
    fun registeredStateShowsControls() {
        seedRegisteredState()
        compose.setContent { card() }
        compose.onNodeWithText("启用双端同步").assertExists()
        compose.onNodeWithText("本机设备 ID：5").assertExists()
        compose.onNodeWithText("服务器地址").assertExists()
        compose.onNodeWithText("自动同步（每 5 分钟）").assertExists()
        compose.onNodeWithText("立即同步").assertExists()
        saveScreenshot("sync-card-registered.png")
    }

    /** UI 测试环境没有 LifecycleOwner，手动注入（collectAsStateWithLifecycle 需要） */
    @Composable
    private fun card() {
        CompositionLocalProvider(LocalLifecycleOwner provides NoopOwner) {
            SyncSectionCard(manager)
        }
    }

    private object NoopOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() {
                registry.currentState = Lifecycle.State.RESUMED
                return registry
            }
    }

    private fun saveScreenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage()
        val awt = bitmap.asAwtImage()
        val out = File(System.getProperty("java.io.tmpdir"), "foldledger-ui-$name")
        ImageIO.write(awt, "png", out)
        assertNotNull("截图应成功写入 $out", out)
        println("SCREENSHOT_SAVED: ${out.absolutePath}")
    }
}
