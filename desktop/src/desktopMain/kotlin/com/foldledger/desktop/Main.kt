package com.foldledger.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.foldledger.ServiceLocator
import com.foldledger.data.prefs.AppPreferences
import com.foldledger.ui.nav.AppRoot
import com.foldledger.ui.theme.FoldLedgerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.awt.Frame
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.util.Properties
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

// 桌面壳：初始化共享依赖容器 + 渲染双端共享的 AppRoot。
// 数据落在 ~/.foldledger/（SQLite + DataStore）；窗口几何记忆在同一目录的 window.properties。
fun main(args: Array<String>) {
    // 卸载程序调用（MSI 卸载序列里的 CustomAction，见 scripts/jpackage/main.wxs）：
    // 只清掉运行时写入的开机自启注册表项就退出，不开窗口、不建单实例锁。
    // 与用户在「设置 → 开机自启」里关掉开关走同一条代码路径（AutoStartRegistry.remove）。
    if (args.contains("--uninstall-cleanup")) {
        AutoStartRegistry.remove()
        return
    }
    // 单实例：数据目录文件锁硬防双开（绿色版/开发版共用 ~/.foldledger/ 会并发写坏数据）。
    // 已有实例在跑 → 唤醒它的窗口（尽力而为）→ 本进程直接退出。
    if (!SingleInstance.acquire()) {
        SingleInstance.notifyShow()
        return
    }
    application {
        ServiceLocator.init()
        val icon = remember { loadAppIcon() }
        val saved = remember { loadWindowPrefs() }
        val windowRef = remember { mutableStateOf<ComposeWindow?>(null) }
        val trayScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
        // 托盘可用时：点关闭 → 隐藏到托盘继续同步；托盘不可用（无系统托盘）→ 关闭即退出
        val trayIcon = remember { createTray(windowRef, trayScope) { exitApplication() } }
        val trayReady = trayIcon != null
        val reminder = remember { ReminderSchedulerDesktop(trayIcon) }
        // 开机自启（注册表 Run 值带 --minimized）时后台进托盘，不弹窗
        val minimized = args.contains("--minimized")
        val windowState = rememberWindowState(
            size = DpSize((saved?.w ?: 1080).dp, (saved?.h ?: 760).dp)
        )
        LaunchedEffect(Unit) { reminder.start() }
        LaunchedEffect(Unit) { DesktopAutoUpdater.startBackgroundCheck() }
        LaunchedEffect(Unit) {
            // 「退出并安装」时用来关掉本进程，好让安装器替换文件
            DesktopAutoUpdater.onExit = { exitApplication() }
            // 开机自启开关 ⇄ HKCU Run 注册表（写当前 exe 路径；开发版下无 exe 则跳过）。
            // ⚠️ v16.2.9 起桌面端改为安装包分发后，安装/升级会换掉 exe 路径；
            // 应用每次启动都会重写这个值，所以升级后首次启动即自愈，无需用户重开开关。
            ServiceLocator.preferences.settings.collect { s ->
                if (s.desktopAutoStart) {
                    DesktopAutoUpdater.currentExePath()?.let { AutoStartRegistry.set(it) }
                } else {
                    AutoStartRegistry.remove()
                }
            }
        }
        Window(
            onCloseRequest = {
                windowRef.value?.let { w ->
                    saveWindowFrom(w)
                    if (trayReady) w.isVisible = false else exitApplication()
                } ?: exitApplication()
            },
            title = "滴水记账",
            state = windowState,
            icon = icon
        ) {
            LaunchedEffect(saved) {
                windowRef.value = window
                // 恢复几何：AWT 物理像素设置（比 WindowPosition 构造器 protected 的方案可靠）
                if (saved != null) {
                    if (saved.maximized) {
                        (window as Frame).extendedState = Frame.MAXIMIZED_BOTH
                    } else {
                        window.setLocation(saved.x, saved.y)
                        window.setSize(saved.w, saved.h)
                    }
                }
                // 自启模式：直接藏进托盘；否则若启动瞬间有第二个实例来"唤醒"，把窗口带到前台
                if (minimized && trayReady) {
                    window.isVisible = false
                } else if (SingleInstance.consumePendingShow()) {
                    showWindow(windowRef)
                }
            }
            FoldLedgerTheme {
                AppRoot()
            }
        }
    }
}

/** 单实例锁：数据目录文件锁（硬保证）+ 固定端口唤醒通道（尽力而为）。 */
private object SingleInstance {
    private const val WAKE_PORT = 47620
    private val lockFile = File(System.getProperty("user.home"), ".foldledger/instance.lock")
    @Volatile private var pendingShow = false
    // ⚠️ 必须持有引用：channel/lock 若只剩局部变量，函数返回后被 GC 回收，
    // channel 关闭会立刻释放文件锁 → 双开。挂在 object 上随进程存活。
    private var lockChannel: FileChannel? = null
    private var lock: java.nio.channels.FileLock? = null

    /** 返回 true = 本进程成为主实例；false = 已有实例在跑 */
    fun acquire(): Boolean {
        // 1) 文件锁：tryLock 失败（返回 null 或抛异常）= 别的进程持有
        val ch = runCatching {
            lockFile.parentFile?.mkdirs()
            FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            )
        }.getOrNull() ?: return false
        val l = runCatching { ch.tryLock() }.getOrNull()
        if (l == null) {
            runCatching { ch.close() }
            return false
        }
        lockChannel = ch
        lock = l
        // 2) 唤醒通道：bind 成功 = 可被第二实例唤起；端口被占（无关程序）不影响主实例身份
        runCatching {
            val ss = ServerSocket(WAKE_PORT)
            Thread {
                while (true) {
                    try {
                        ss.accept().use { s ->
                            if (s.getInputStream().read() == 'S'.code) pendingShow = true
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
            }.apply { isDaemon = true; start() }
        }
        return true
    }

    /** 第二实例：尝试通知主实例把窗口带到前台（连不上就算了） */
    fun notifyShow() {
        runCatching {
            Socket("127.0.0.1", WAKE_PORT).use { it.getOutputStream().write('S'.code) }
        }
    }

    fun consumePendingShow(): Boolean {
        val p = pendingShow
        pendingShow = false
        return p
    }
}

/** 开机自启 ⇄ HKCU Run 注册表（写 HKCU 免管理员权限，随用户登录启动）。 */
private object AutoStartRegistry {
    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE_NAME = "滴水记账"

    fun set(exePath: String) {
        // 值 = "exe路径" --minimized（带引号防空格路径；--minimized 让自启时后台进托盘）
        val value = "\"$exePath\" --minimized"
        runCatching {
            ProcessBuilder(
                "reg", "add", RUN_KEY, "/v", VALUE_NAME, "/t", "REG_SZ", "/d", value, "/f"
            ).start()
        }
    }

    fun remove() {
        runCatching {
            ProcessBuilder("reg", "delete", RUN_KEY, "/v", VALUE_NAME, "/f").start()
        }
    }
}

/** 创建系统托盘图标（带菜单）；返回托盘图标（null = 不可用）。 */
private fun createTray(
    windowRef: androidx.compose.runtime.MutableState<ComposeWindow?>,
    scope: CoroutineScope,
    onExit: () -> Unit
): TrayIcon? {
    if (!SystemTray.isSupported()) return null
    val img = loadAppIconAwt() ?: return null
    val popup = PopupMenu()
    val showItem = MenuItem("显示滴水记账").apply {
        addActionListener { showWindow(windowRef) }
    }
    val syncItem = MenuItem("立即同步").apply {
        addActionListener { scope.launch { ServiceLocator.syncManager.syncNow() } }
    }
    val exitItem = MenuItem("退出").apply {
        addActionListener {
            windowRef.value?.let(::saveWindowFrom)
            onExit()
        }
    }
    popup.add(showItem)
    popup.add(syncItem)
    popup.addSeparator()
    popup.add(exitItem)

    val trayIcon = TrayIcon(img, "滴水记账（双击显示窗口）", popup)
    // 双击托盘图标 = 恢复主窗口
    trayIcon.addActionListener { showWindow(windowRef) }
    trayIcon.isImageAutoSize = true
    return runCatching {
        SystemTray.getSystemTray().add(trayIcon)
        trayIcon
    }.getOrNull()
}

private fun showWindow(windowRef: androidx.compose.runtime.MutableState<ComposeWindow?>) {
    SwingUtilities.invokeLater {
        val w = windowRef.value ?: return@invokeLater
        w.isVisible = true
        if (w is Frame && w.extendedState == Frame.ICONIFIED) {
            w.extendedState = Frame.NORMAL
        }
        w.toFront()
    }
}

private fun saveWindowFrom(w: ComposeWindow) {
    val maximized = w is Frame && (w as Frame).extendedState == Frame.MAXIMIZED_BOTH
    val b = w.bounds
    saveWindowPrefs(WindowPrefs(b.x, b.y, b.width, b.height, maximized))
}

/** 窗口几何记忆（AWT 物理像素）。 */
private data class WindowPrefs(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val maximized: Boolean
)

private fun windowPrefsFile(): File = File(System.getProperty("user.home"), ".foldledger/window.properties")

private fun loadWindowPrefs(): WindowPrefs? = runCatching {
    val file = windowPrefsFile()
    if (!file.isFile) return null
    val p = Properties().apply {
        file.inputStream().use { load(it) }
    }
    val x = p.getProperty("x")?.toIntOrNull() ?: return null
    val y = p.getProperty("y")?.toIntOrNull() ?: return null
    val w = p.getProperty("w")?.toIntOrNull() ?: return null
    val h = p.getProperty("h")?.toIntOrNull() ?: return null
    WindowPrefs(x, y, w, h, p.getProperty("maximized")?.toBooleanStrictOrNull() ?: false)
}.getOrNull()

private fun saveWindowPrefs(prefs: WindowPrefs) {
    runCatching {
        val p = Properties()
        p.setProperty("x", prefs.x.toString())
        p.setProperty("y", prefs.y.toString())
        p.setProperty("w", prefs.w.toString())
        p.setProperty("h", prefs.h.toString())
        p.setProperty("maximized", prefs.maximized.toString())
        val file = windowPrefsFile()
        file.parentFile?.mkdirs()
        file.outputStream().use { p.store(it, "drip-ledger window state (awt px)") }
    }
}

private fun loadAppIcon(): Painter? = runCatching {
    val loader = Thread.currentThread().contextClassLoader
        ?: ClassLoader.getSystemClassLoader()
    val stream = loader.getResourceAsStream("icon.png") ?: return null
    BitmapPainter(ImageIO.read(stream).toComposeImageBitmap())
}.getOrNull()

private fun loadAppIconAwt(): Image? = runCatching {
    val loader = Thread.currentThread().contextClassLoader
        ?: ClassLoader.getSystemClassLoader()
    val stream = loader.getResourceAsStream("icon.png") ?: return null
    ImageIO.read(stream)
}.getOrNull()

/**
 * 每日记账提醒（桌面版）：进程级轮询，到设定时间从托盘弹一次气泡（当天不重复）。
 * 托盘不可用时（[trayIcon] == null）静默跳过 —— 提醒本就依赖系统托盘气泡。
 */
private class ReminderSchedulerDesktop(
    private val trayIcon: TrayIcon?,
    private val prefs: AppPreferences = ServiceLocator.preferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastRemindedEpochDay = -1L

    fun start() {
        scope.launch {
            while (true) {
                checkAndNotify()
                delay(30_000)
            }
        }
    }

    private suspend fun checkAndNotify() {
        val tray = trayIcon ?: return
        val s = prefs.settings.first()
        if (!s.reminderEnabled) {
            // 关掉后重置，下次开启当天还能提醒
            lastRemindedEpochDay = -1L
            return
        }
        val now = LocalDateTime.now()
        if (now.hour == s.reminderHour && now.minute == s.reminderMinute) {
            val day = now.toLocalDate().toEpochDay()
            if (day != lastRemindedEpochDay) {
                lastRemindedEpochDay = day
                SwingUtilities.invokeLater {
                    tray.displayMessage(
                        "滴水记账",
                        "到记账时间啦，打开 App 记一笔吧",
                        TrayIcon.MessageType.INFO
                    )
                }
            }
        }
    }
}
