package com.foldledger.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.foldledger.data.update.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Android 端应用内更新（v16.6）。
 *
 * 检查/下载/校验由共享的 [UpdateManager] 完成，这里只负责：
 * 1. 把校验通过的 APK 通过 FileProvider 生成 content:// URI；
 * 2. 拉起系统安装界面（ACTION_VIEW + FLAG_GRANT_READ_URI_PERMISSION）；
 * 3. 首次安装引导用户开启「安装未知应用」权限。
 *
 * ⚠️ 签名一致性由系统覆盖安装把关：只有同一把 drip-ledger-release.jks 签的包才能覆盖，
 * 用户误装了别人签的同名包会被系统拒绝，这是安全底线。
 */
object AndroidUpdateInstaller {

    /** 能否安装未知应用（Android 8+ 需用户授权；targetSdk 35 下必须） */
    fun canInstallUnknownApps(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.packageManager.canRequestPackageInstalls()
        else true

    /** 跳到「安装未知应用」授权页 */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val uri = Uri.parse("package:${context.packageName}")
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 下载目录：App 私有 files/updates/，FileProvider 的 provider_paths 已授权这个路径 */
    fun updateDir(context: Context): File = File(context.filesDir, "updates")

    /**
     * 拉起系统安装界面。返回 false 表示没有可安装的文件。
     * 调用前应先确保 [canInstallUnknownApps] 为 true，否则系统会拦截。
     */
    fun install(context: Context, apk: File): Boolean {
        if (!apk.isFile) return false
        val uri: Uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk
            )
        } catch (_: Exception) {
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /**
     * 下载并安装当前可用更新（供 UI 的「立即更新」调用）。
     * 必须在协程里调用。签名/校验由 UpdateManager 完成。
     */
    suspend fun downloadAndInstall(
        context: Context,
        manager: UpdateManager,
        onProgress: (Long, Long) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!canInstallUnknownApps(context)) {
                return@withContext Result.failure(IllegalStateException("需要开启「安装未知应用」权限"))
            }
            val file = manager.downloadAndVerify(updateDir(context), onProgress)
            // 系统安装界面须在主线程拉起
            withContext(Dispatchers.Main) {
                if (!install(context, file)) {
                    throw IllegalStateException("无法拉起系统安装界面")
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            // 用户点了「取消下载」：别包装成失败结果，要让协程正常取消
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
