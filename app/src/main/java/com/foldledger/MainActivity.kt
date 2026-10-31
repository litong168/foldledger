package com.foldledger

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.foldledger.ui.nav.AppRoot
import com.foldledger.util.FrameRate

/**
 * 唯一 Activity。
 *
 * 折叠屏适配要点：
 *  1. `enableEdgeToEdge()` —— 展开后内屏比例特殊，交给系统 inset 分发而不是硬编码状态栏高度
 *  2. Manifest 中声明了 `configChanges` —— 折叠/展开时 Activity **不重建**，
 *     仅触发 Compose 重组，因此 ViewModel、滚动位置、输入框内容全部保留
 *  3. 不锁定 `screenOrientation` —— 否则展开后无法响应横竖变化
 *  4. 首帧之前就把刷新率顶到设备最高 —— 否则启动动画会先跑在 60Hz 上
 */
class MainActivity : ComponentActivity() {

    // v14：来自桌面组件「速记」按钮的 QUICK_ADD intent，透传给 AppRoot 打开速记浮层
    private val pendingQuickAdd = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        // v13.9.2：去掉导航栏后面那层不透明的对比 scrim。
        // enableEdgeToEdge 已经让内容画到屏幕最底边（含手势栏区域），但系统默认会在
        // 导航栏背后垫一层浅色对比带 —— 读起来就是"浮层下面还有一堵墙"。
        // 关掉强制对比，页面背景一路透到屏幕最边缘；Home 指示条的明暗交由主题接管。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)

        handleIntent(intent)

        // 越早越好：setContent 之前就把渲染意图交给平台
        FrameRate.apply(window, highRefresh = true)

        setContent {
            AppRoot(pendingQuickAdd = pendingQuickAdd)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * 处理外部 intent。
     *
     * 两类：
     *  1. `com.foldledger.action.QUICK_ADD` —— 桌面组件的「速记」按钮（v14）。
     *  2. `com.foldledger.action.SET_GLOW` —— QA 钩子：把玻璃光相**钉死**在
     *     某个值上（`--ef glow 0.5`）。用来在**内容完全不动**的条件下量出
     *     "高光调制到底产生了多少像素差"——否则滚动时内容本身就在变，
     *     像素 diff 里混着内容位移，分不清是玻璃动了还是内容动了。
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            "com.foldledger.action.QUICK_ADD" -> pendingQuickAdd.value = true
            "com.foldledger.action.SET_GLOW" -> {
                com.foldledger.ui.theme.GlassGlowDebug.forced.value =
                    if (intent.hasExtra("glow")) intent.getFloatExtra("glow", 0f) else null
            }
            // v15：QA 钩子 —— 把折叠姿态钉死（悬停布局无法用 adb 伪造物理折叠）
            "com.foldledger.action.SET_POSTURE" -> {
                com.foldledger.ui.adaptive.FoldPostureDebug.forcedPosture =
                    if (intent.hasExtra("posture")) intent.getIntExtra("posture", -1) else null
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // v10.6：系统可能已杀掉通知监听服务 —— 回前台请求重绑，
        // 没授权时内部会静默失败，不影响其它逻辑
        com.foldledger.auto.AutoBookkeepingListener.requestRebind(this)
        // v14：回到前台时刷新桌面组件，让「今日收支」在记完一笔后立即更新
        com.foldledger.widget.TodayWidget.refresh(this)
    }
}
