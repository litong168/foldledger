package com.foldledger.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.foldledger.data.db.AppDatabase
import com.foldledger.ui.vm.BackupViewModel

/** 打开/创建滴水记账数据库（Android 走 Room+Context；桌面走 Room JVM 驱动）。 */
expect fun createAppDatabase(): AppDatabase

/**
 * 事务包装：Android 用 room-ktx 的原生 withTransaction（既有行为完全不变）；
 * 桌面用 Room KMP 的写连接事务（M1 验证语义，M4 同步前再做事务一致性测试）。
 */
expect suspend fun <R> AppDatabase.runInDbTransaction(block: suspend () -> R): R

/** 自定义分类图标加载（Android 走 IconStore 缓存；桌面暂无自定义图标 → null）。 */
expect suspend fun loadIconBitmap(fileName: String): ImageBitmap?

/**
 * 加载内置资产图片（按逻辑名）。目前用于「关于」页的微信收款码。
 * Android 从 assets/ 读，桌面从 classpath resources 读；找不到返回 null。
 */
expect suspend fun loadAssetBitmap(name: String): ImageBitmap?

/** 动态取色（Android S+ 壁纸色；桌面/低版本返回 null → 固定色板）。 */
expect fun dynamicColorSchemeIfAvailable(dark: Boolean): ColorScheme?

/** 偏好 DataStore（Android 存应用私有目录；桌面存用户主目录 .foldledger/）。 */
expect fun createPreferencesDataStore(): DataStore<Preferences>

/** 系统栏图标明暗（Android 状态栏/导航栏；桌面无此概念）。 */
@Composable
expect fun ApplySystemBarsStyle(dark: Boolean)

/** 平台返回键（Android 系统返回手势；桌面 no-op）。 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/** 关掉系统对话框默认遮罩（Android Dialog.setDimAmount(0f)；桌面 no-op）。 */
@Composable
expect fun PlatformClearDialogDim()

/** 删除自定义分类图标文件（上传的图片；桌面端暂 no-op）。 */
expect fun deleteIconFile(fileName: String)

/** 平台是否支持文件型备份（Android SAF / 桌面 JFileChooser 均支持）。 */
expect fun fileBackupSupported(): Boolean

/** 平台是否有自动记账能力（Android 短信/通知监听；桌面端不移植，也不预置「自动记账」账本）。 */
expect fun autoBookkeepingSupported(): Boolean

/** 备份目录授权（Android SAF 目录选择器；桌面端在 BackupViewModel 内部自行弹 JFileChooser，此处 no-op）。 */
@Composable
expect fun BackupFolderGrantEffect(trigger: Boolean, backupVm: BackupViewModel)

/** 高刷新率（Android 顶帧率；桌面 no-op）。 */
@Composable
expect fun ApplyHighRefreshRate()

/**
 * M4.5 Android 后台同步调度：启用同步时由 WorkManager 周期执行（App 被杀也能同步）；
 * 桌面端 no-op —— 进程常驻，SyncManager 的协程循环已覆盖。
 * [SyncManager] 在状态变化（启用/注册/启动恢复）时调用。
 */
expect fun scheduleBackgroundSync(enabled: Boolean)
