package com.foldledger.ui.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 记账弹层的会话级 UI 状态（v5.6「记账提速」）。
 *
 * ### 为什么放在单例里，而不是一路往下传参数
 * 记账弹层有三种布局分支（笔记本姿态 / 分栏 / 底部抽屉），而每一处 `KeyArea` 都要显示
 * 「连续记」开关和「已记 N 笔」回执；同时每次重新打开弹层都会新建 ViewModel。
 * 用单例既避免给三个调用点都加参数，也保证"开关"和"计数"在弹层重建后仍然是同一份。
 *
 * 这是**进程内**状态：杀进程后回到默认（关）。连续记账本来就是"这次一口气记几笔"的
 * 临时模式，不该变成永久设置 —— 否则下次打开记账还会停在"不关闭"的模式里，很反直觉。
 */
object AddTxnUiState {

    /** 连续记账：保存后不关闭弹层，保留分类/日期/项目，只清空金额与备注 */
    var continuous by mutableStateOf(false)

    /** 本次连续记账已经存了几笔（数字键盘上方按钮上做回执） */
    var savedCount by mutableIntStateOf(0)

    /**
     * 打开一次新的记账会话时调用（关闭弹层后归零）。
     *
     * v9.8.5：`continuous` 也一起关掉。
     * 它原本是"这次一口气记几笔"的临时模式，但进程不退出就一直开着 ——
     * 上次开过之后，下次点保存**弹层不关**、只清金额，用户第一反应是"保存失败了"。
     * 想要连记就每次自己开，语义才和开关的名字一致。
     */
    fun resetSession() {
        savedCount = 0
        continuous = false
    }
}
