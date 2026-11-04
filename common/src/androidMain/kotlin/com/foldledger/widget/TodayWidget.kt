package com.foldledger.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.widget.RemoteViews
import com.foldledger.MainActivity
import com.foldledger.R
import com.foldledger.ServiceLocator
import com.foldledger.data.db.Direction
import com.foldledger.util.Money
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Calendar

/**
 * 桌面组件：今日收支 + 本月收支（v14.1）。
 *
 * - 读全账本今日（00:00 至今）与本月（当月 1 号 00:00 至今）支出/收入汇总，
 *   复用既有 `ledgerTotalsInRange`。
 * - 纯 RemoteViews（不引入 Glance，离线可编）。
 * - 视觉统一液态玻璃：磨砂面板 drawable + 顶左高光 sheen + 应用语义色，
 *   由 `widget_today.xml` 承载，本类只负责填数据、切明/暗背景、绑点击。
 * - "速记"按钮发 `QUICK_ADD` intent 由 MainActivity 钩子打开 SpeedEntry 浮层。
 */
class TodayWidget : AppWidgetProvider() {

    private data class Palette(
        val bg: Int, val title: Int, val label: Int,
        val expense: Int, val income: Int, val quickBg: Int, val quickText: Int
    )

    private fun palette(isNight: Boolean) = if (isNight) Palette(
        bg = R.drawable.widget_glass_bg_dark,
        title = 0xFFEDEDEA.toInt(),
        label = 0xFF8A8C91.toInt(),
        expense = 0xFFE3B0A7.toInt(),   // 暗色支出亮版
        income = 0xFFA8D6C0.toInt(),    // 暗色收入亮版
        quickBg = R.drawable.widget_quick_bg_dark,
        quickText = 0xFFA6C2D8.toInt()
    ) else Palette(
        bg = R.drawable.widget_glass_bg_light,
        title = 0xFF17181B.toInt(),
        label = 0xFF6A6D74.toInt(),
        expense = 0xFF96574D.toInt(),   // 暖红
        income = 0xFF427E68.toInt(),    // 冷绿
        quickBg = R.drawable.widget_quick_bg,
        quickText = 0xFF4A6B87.toInt() // 主色蓝
    )

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val isNight = isNightMode(context)
        val t = readTotals(context)
        appWidgetIds.forEach { id ->
            updateWidget(context, appWidgetManager, id, isNight, t)
        }
    }

    override fun onEnabled(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        onUpdate(context, mgr, mgr.getAppWidgetIds(ComponentName(context, TodayWidget::class.java)))
    }

    private fun updateWidget(
        context: Context,
        mgr: AppWidgetManager,
        id: Int,
        isNight: Boolean,
        t: Totals
    ) {
        val p = palette(isNight)
        val rv = RemoteViews(context.packageName, R.layout.widget_today)
        rv.setInt(R.id.widget_root, "setBackgroundResource", p.bg)
        rv.setTextColor(R.id.tv_title, p.title)
        rv.setTextColor(R.id.tv_expense_label, p.label)
        rv.setTextColor(R.id.tv_income_label, p.label)
        rv.setTextColor(R.id.tv_month_label, p.label)
        rv.setTextColor(R.id.tv_expense, p.expense)
        rv.setTextColor(R.id.tv_income, p.income)
        rv.setTextColor(R.id.tv_month_expense, p.expense)
        rv.setTextColor(R.id.tv_month_income, p.income)
        rv.setTextColor(R.id.btn_quick, p.quickText)
        rv.setInt(R.id.btn_quick, "setBackgroundResource", p.quickBg)
        rv.setTextViewText(R.id.tv_expense, Money.format(t.todayExpense))
        rv.setTextViewText(R.id.tv_income, Money.format(t.todayIncome))
        rv.setTextViewText(R.id.tv_month_expense, Money.format(t.monthExpense))
        rv.setTextViewText(R.id.tv_month_income, Money.format(t.monthIncome))

        // 点卡片任意处 → 打开 App
        rv.setOnClickPendingIntent(
            R.id.widget_root,
            PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), FLAG)
        )
        // 「速记」→ QUICK_ADD
        val quick = Intent(context, MainActivity::class.java).apply {
            action = ACTION_QUICK_ADD
        }
        rv.setOnClickPendingIntent(
            R.id.btn_quick,
            PendingIntent.getActivity(context, 1, quick, FLAG)
        )

        mgr.updateAppWidget(id, rv)
    }

    private data class Totals(
        val todayExpense: Long, val todayIncome: Long,
        val monthExpense: Long, val monthIncome: Long
    )

    private fun readTotals(context: Context): Totals {
        return try {
            ServiceLocator.attach(context.applicationContext)
            ServiceLocator.init()
            runBlocking {
                val now = System.currentTimeMillis()
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val dayStart = cal.timeInMillis
                // 当月 1 号 00:00
                cal.set(Calendar.DAY_OF_MONTH, 1)
                val monthStart = cal.timeInMillis

                val dayAggs = ServiceLocator.repository.ledgerTotalsInRange(dayStart, now).first()
                val monthAggs = ServiceLocator.repository.ledgerTotalsInRange(monthStart, now).first()

                val dayExp = dayAggs.filter { it.direction == Direction.EXPENSE }.sumOf { it.totalCents }
                val dayInc = dayAggs.filter { it.direction == Direction.INCOME }.sumOf { it.totalCents }
                val monExp = monthAggs.filter { it.direction == Direction.EXPENSE }.sumOf { it.totalCents }
                val monInc = monthAggs.filter { it.direction == Direction.INCOME }.sumOf { it.totalCents }
                Totals(dayExp, dayInc, monExp, monInc)
            }
        } catch (e: Exception) {
            Totals(0L, 0L, 0L, 0L)
        }
    }

    private fun isNightMode(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    companion object {
        const val ACTION_QUICK_ADD = "com.foldledger.action.QUICK_ADD"
        private val FLAG =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE else 0

        /** App 回前台时调用：让已放置的组件立即重算今日/本月汇总 */
        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, TodayWidget::class.java))
            if (ids.isNotEmpty()) {
                val intent = Intent(context, TodayWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }
    }
}
