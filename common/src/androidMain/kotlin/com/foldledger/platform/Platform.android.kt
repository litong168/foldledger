package com.foldledger.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.room.Transactor
import androidx.room.useWriterConnection
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.foldledger.ServiceLocator
import com.foldledger.data.db.AppDatabase
import com.foldledger.ui.adaptive.findActivity
import com.foldledger.ui.vm.BackupViewModel
import com.foldledger.util.FrameRate
import com.foldledger.util.IconStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

actual fun createAppDatabase(): AppDatabase =
    Room.databaseBuilder(
        ServiceLocator.appContext,
        AppDatabase::class.java,
        AppDatabase.DB_NAME
    )
        .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .addMigrations(*AppDatabase.MIGRATIONS.toTypedArray())
        // ⚠️ 绝不能加 fallbackToDestructiveMigration：缺迁移时它会把老用户整库清空。
        .build()

actual suspend fun <R> AppDatabase.runInDbTransaction(block: suspend () -> R): R =
    useWriterConnection { tx ->
        tx.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { block() }
    }

actual suspend fun loadIconBitmap(fileName: String): ImageBitmap? =
    withContext(Dispatchers.IO) {
        IconStore.load(ServiceLocator.appContext, fileName)?.asImageBitmap()
    }

actual suspend fun loadAssetBitmap(name: String): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            ServiceLocator.appContext.assets.open(name).use { stream ->
                BitmapFactory.decodeStream(stream)?.asImageBitmap()
            }
        }.getOrNull()
    }

actual fun dynamicColorSchemeIfAvailable(dark: Boolean): ColorScheme? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val ctx = ServiceLocator.appContext
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else null

private val prefsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

actual fun createPreferencesDataStore(): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(scope = prefsScope) {
        ServiceLocator.appContext.preferencesDataStoreFile("foldledger_settings")
    }

@Composable
actual fun ApplySystemBarsStyle(dark: Boolean) {
    val context = LocalContext.current
    SideEffect {
        // 状态栏/导航栏图标明暗由主题决定，配合 edge-to-edge
        val window = (context as? Activity)?.window ?: return@SideEffect
        val nav = WindowCompat.getInsetsController(window, window.decorView)
        nav.isAppearanceLightStatusBars = !dark
        // v13.9.2：导航栏对比 scrim 已关（MainActivity），这里只接管 Home 指示条的明暗，
        // 让它在浅色页上是深灰、深色页上是浅灰，始终看得清。
        nav.isAppearanceLightNavigationBars = !dark
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

@Composable
actual fun PlatformClearDialogDim() {
    val view = LocalView.current
    // ⚠️ 必须在 Dialog 的内容里取 LocalView —— 这里的 view 才是对话框窗口自己的
    // DecorView，它的 parent 是 DialogWindowProvider。写在 GlassDialog 外层
    // 拿到的是 Activity 的 view，改了没用。
    androidx.compose.runtime.LaunchedEffect(view) {
        (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
    }
}

actual fun deleteIconFile(fileName: String) {
    // 只删自己上传的图片；预置 Emoji 没有文件可删
    runCatching { IconStore.remove(ServiceLocator.appContext, fileName) }
}

actual fun fileBackupSupported(): Boolean = true

actual fun autoBookkeepingSupported(): Boolean = true

/** 让系统目录选择器默认落在「下载」目录 —— 这是"傻瓜式"的关键一步 */
private val DOWNLOADS_HINT: Uri? = runCatching {
    Uri.parse("content://com.android.external.documents/document/primary%3ADownload")
}.getOrNull()

@Composable
actual fun BackupFolderGrantEffect(trigger: Boolean, backupVm: BackupViewModel) {
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) backupVm.onFolderGranted(uri.toString()) else backupVm.onFolderPickCancelled()
    }
    LaunchedEffect(trigger) {
        if (trigger) folderLauncher.launch(DOWNLOADS_HINT)
    }
}

@Composable
actual fun ApplyHighRefreshRate() {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    LaunchedEffect(activity) {
        activity?.window?.let { FrameRate.apply(it, true) }
    }
}

/** 后台同步专用 Work 名（unique name） */
const val SYNC_WORK_NAME = "foldledger-sync"

actual fun scheduleBackgroundSync(enabled: Boolean) {
    val wm = WorkManager.getInstance(ServiceLocator.appContext)
    if (enabled) {
        wm.enqueueUniquePeriodicWork(
            SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
        )
    } else {
        wm.cancelUniqueWork(SYNC_WORK_NAME)
    }
}
