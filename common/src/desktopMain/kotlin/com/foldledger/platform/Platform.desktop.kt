package com.foldledger.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.room.Transactor
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.foldledger.data.db.AppDatabase
import com.foldledger.data.db.AppDatabase_Impl
import com.foldledger.ui.vm.BackupViewModel
import com.foldledger.util.DesktopIconStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File

/** 桌面数据根目录：~/ .foldledger/（与 Android 的私有目录语义一致） */
private val dataDir: File by lazy {
    File(System.getProperty("user.home"), ".foldledger").apply { mkdirs() }
}

actual fun createAppDatabase(): AppDatabase =
    Room.databaseBuilder(
        name = File(dataDir, AppDatabase.DB_NAME).absolutePath,
        factory = { AppDatabase_Impl() }
    )
        .setDriver(BundledSQLiteDriver())
        .addMigrations(*AppDatabase.MIGRATIONS.toTypedArray())
        // ⚠️ 绝不能加 fallbackToDestructiveMigration：缺迁移时它会把老用户整库清空。
        .build()

actual suspend fun <R> AppDatabase.runInDbTransaction(block: suspend () -> R): R =
    withContext(Dispatchers.IO) {
        useWriterConnection { tx ->
            tx.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { block() }
        }
    }

/** v16.3：桌面自定义分类图标走本地文件存储，与 Android 的 `img:` 引用完全兼容。 */
actual suspend fun loadIconBitmap(fileName: String): ImageBitmap? =
    withContext(Dispatchers.IO) { DesktopIconStore.load(fileName) }

/** 内置资产图片（classpath resources，如收款码）。 */
actual suspend fun loadAssetBitmap(name: String): ImageBitmap? =
    withContext(Dispatchers.IO) {
        try {
            val loader: ClassLoader? = Thread.currentThread().contextClassLoader
                ?: java.awt.image.BufferedImage::class.java.classLoader
            val stream = loader?.getResourceAsStream(name) ?: return@withContext null
            val buffered = stream.use { javax.imageio.ImageIO.read(it) }
            buffered?.toComposeImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

actual fun dynamicColorSchemeIfAvailable(dark: Boolean): ColorScheme? =
    null // 桌面无壁纸取色，恒走固定色板

private val prefsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

actual fun createPreferencesDataStore(): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(scope = prefsScope) {
        File(dataDir, "foldledger_settings.preferences_pb")
    }

@Composable
actual fun ApplySystemBarsStyle(dark: Boolean) {
    // 桌面无系统栏概念
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // 桌面无系统返回键；窗口关闭由 :desktop 的 Window onCloseRequest 处理
}

@Composable
actual fun PlatformClearDialogDim() {
    // 桌面对话框无系统遮罩概念
}

actual fun deleteIconFile(fileName: String) {
    DesktopIconStore.remove(fileName)
}

actual fun fileBackupSupported(): Boolean = true

// 桌面端不移植自动记账：不监听短信/通知，也不预置「自动记账」账本（M4 同步按 sceneKey=auto 排除该账本）
actual fun autoBookkeepingSupported(): Boolean = false

@Composable
actual fun BackupFolderGrantEffect(trigger: Boolean, backupVm: BackupViewModel) {
    // 桌面端目录选择在 BackupViewModel 内部完成（JFileChooser），AppRoot 无需触发授权
}

@Composable
actual fun ApplyHighRefreshRate() {
    // 桌面无刷新率顶帧需求
}

// 桌面进程常驻：SyncManager 的协程自动循环已覆盖同步，无需系统级后台调度
actual fun scheduleBackgroundSync(enabled: Boolean) {
}
