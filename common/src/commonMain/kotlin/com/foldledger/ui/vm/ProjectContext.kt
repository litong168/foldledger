package com.foldledger.ui.vm

/**
 * 「上次用过的项目」记忆（v5.1）。
 *
 * 报销账本里连续记好几笔同一趟差旅时，不该每笔都重新选项目。
 * 这里按账本记一个进程内的小缓存：明细页选中的项目、记账弹层最后选的项目都写进来，
 * 下次打开「记一笔」自动带出。**不进数据库也不进备份**（属于临时上下文，
 * 重启后回到"全部"，不会污染数据）。
 */
object ProjectContext {

    private val lastByLedger = HashMap<Long, Long>()

    /** 取上次使用的项目（0 = 未归类） */
    fun get(ledgerId: Long): Long = lastByLedger[ledgerId] ?: 0L

    fun set(ledgerId: Long, projectId: Long) {
        if (projectId > 0L) lastByLedger[ledgerId] = projectId else lastByLedger.remove(ledgerId)
    }
}
