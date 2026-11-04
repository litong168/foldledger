package com.foldledger.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 小米 HyperOS / MIUI 系统管控的深链工具（v8.1.1）。
 *
 * 小米对第三方 App 的后台有两道常设管控：**自启动**（不给就收不到广播、监听服务被杀）
 * 和**省电策略**（不给"无限制"就被冻结）。设置页各机型入口不一样，这里按
 * 「机型专用 Activity → 通用广播 → 系统兜底」三级降级，保证总有一个打得开。
 */
object MiuiSettings {

    private const val PKG_SECURITY = "com.miui.securitycenter"

    /** 逐个尝试一组 Intent，第一个能打开的就算成功 */
    private fun tryStart(context: Context, candidates: List<Intent>): Boolean {
        for (i in candidates) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                return true
            } catch (_: ActivityNotFoundException) {
                // 下一个
            } catch (_: Exception) {
                // 有的 ROM 会抛 SecurityException 之类，同样降级
            }
        }
        return false
    }

    /** 打开「自启动管理」（不给自启动：短信广播收不到、通知监听被杀） */
    fun openAutostart(context: Context): Boolean = tryStart(
        context,
        listOf(
            Intent("miui.intent.action.OP_AUTO_START"),
            Intent().setClassName(PKG_SECURITY, "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            Intent().setClassName(PKG_SECURITY, "com.miui.permcenter.PrivacyAndAppsManagerActivity"),
            appDetails(context)
        )
    )

    /** 打开「省电策略 / 后台无限制」（被省电策略管住的 App 会被冻结，捕获全停） */
    fun openBatteryPolicy(context: Context): Boolean {
        val pkg = context.packageName
        return tryStart(
            context,
            listOf(
                Intent().setClassName(PKG_SECURITY, "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                    .putExtra("package_name", pkg)
                    .putExtra("package_label", appName(context)),
                Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST"),
                Intent().setClassName(PKG_SECURITY, "com.miui.powerkeeper.PowerSavePolicyActivity"),
                appDetails(context)
            )
        )
    }

    /** 电池优化白名单（标准 Android 通道，HyperOS 也认） */
    fun openBatteryOptimizationSettings(context: Context): Boolean =
        tryStart(context, listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails(context)))

    /** 请求直接加入电池优化白名单（会弹系统确认框） */
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean =
        tryStart(
            context,
            listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                appDetails(context)
            )
        )

    /** 安装未知应用（首次侧载安装会拦；给「文件管理/浏览器」开权限） */
    fun openInstallUnknownApps(context: Context): Boolean =
        tryStart(
            context,
            listOf(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}")),
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
                appDetails(context)
            )
        )

    /** 通知使用权列表 */
    fun openNotificationAccess(context: Context): Boolean =
        tryStart(context, listOf(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), appDetails(context)))

    /** 本应用详情页（所有设置的兜底入口） */
    fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))

    /** 是否已在电池优化白名单 */
    fun ignoringBatteryOptimizations(context: Context): Boolean =
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)

    private fun appName(context: Context): String = runCatching {
        val info = context.applicationInfo
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault("滴水记账")
}
