package com.foldledger.data.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 统一 id 空间：新建行主键 = `deviceId << 40 | 表内自增号`。
 *
 * 为什么必须统一：双端各自 Room 自增主键从 1 起，离线各自记一笔时
 * 两端会生成相同 id 的不同记录 —— 同步以 (table, id) 为行身份，撞 id 会把
 * 另一条 LWW 覆盖掉（数据丢失）。设备前缀让**不同设备的 id 永不重叠**。
 *
 * 语义：
 *  - 低 40 位 = 该表本地自增号（从表内 max(id) 低位 + 1 起步，避开历史小 id）；
 *  - 高 24 位 = 设备 id（服务端注册分配，个人使用量级下绝不会撞）；
 *  - 未启用同步 / 未注册时返回 0 → Room 走原来自增（老数据空间不变）。
 *
 * M4.5 在注册成功后置 [enabled] = true 并写入 [SyncDeviceHolder.deviceId]。
 * 历史数据（v11 前的小 id 行）通过「手机备份导入桌面」保持双端一致，
 * 之后新建的行全部落在设备前缀空间 —— 首同步 LWW 覆盖只可能发生在
 * 「桌面导入前自己记过账」的窗口内（导入本身会清空）。
 *
 * 每个 [LedgerRepository] 实例持有一个分配器（构造注入，测试隔离）。
 */
class SyncIdAllocator {

    @Volatile
    var enabled: Boolean = false

    private val mutex = Mutex()
    private val counters = HashMap<String, Long>()

    /**
     * v16.4.1：让下一次分配重新读库（同步刚拉下来一批行之后调用）。
     *
     * 计数器是**进程内缓存**：如果同步把「本设备前缀 + 更高低位」的行写进来了
     * （换机重装后服务端又分回同一个 deviceId、或从备份恢复），缓存就会落后于库，
     * 下一次分配又撞 id。置脏标记而不是直接 clear()，避免与 [nextId] 抢 HashMap。
     */
    @Volatile
    private var stale = false

    fun invalidate() {
        stale = true
    }

    private companion object {
        const val LOW_MASK = (1L shl 40) - 1L
    }

    /**
     * @param maxIdInRange `(from, to) -> 该区间内 max(id)`，区间 = 本设备前缀。
     *        **必须是本设备区间**，不能用全表 max —— 见 [stale] 与调用处的说明。
     * @return 分配的主键；未启用返回 0（Room autoGenerate 接管）
     */
    suspend fun nextId(
        table: String,
        maxIdInRange: suspend (from: Long, to: Long) -> Long
    ): Long {
        if (!enabled) return 0L
        val dev = SyncDeviceHolder.deviceId
        require(dev > 0L) { "同步已启用但设备未注册（SyncDeviceHolder.deviceId = 0）" }
        return mutex.withLock {
            if (stale) {
                counters.clear()
                stale = false
            }
            var counter = counters[table]
            if (counter == null) {
                // ⚠️ v16.4.1 修复：原来取的是**全表** maxId 的低 40 位。
                // 同步把别的设备的行拉进来之后，全局最大值常常落在别人的前缀上，
                // 而它的低位可能比本设备已用过的还小 —— 于是新 id 直接撞上本设备
                // 已有的行，表现为「点保存闪退，无法记账」（UNIQUE constraint failed）。
                val lo = dev shl 40
                counter = (maxIdInRange(lo, lo or LOW_MASK) and LOW_MASK) + 1L
            }
            counters[table] = counter + 1L
            (dev shl 40) or counter
        }
    }
}
