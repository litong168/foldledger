package com.foldledger.data.sync

/**
 * M4 同步打戳：所有写路径从这里取「最后修改时间 + 修改设备」。
 *
 * 双端 LWW 合并依赖每行 updatedAt 单调递增、且每次改动都被打上新戳；
 * 若某条写路径漏打，该行在水位线增量里就永远发不出去（首同步除外）。
 * 因此打戳收敛在 [LedgerRepository] 一个入口，DAO 层不自行取时间。
 */
interface SyncStampProvider {
    /** 当前时间戳（毫秒）。未启用同步时就是本机墙钟；启用后由引擎做时钟校正 */
    fun nowMillis(): Long

    /** 本机在同步服务端注册到的设备 id；0 = 未注册 */
    fun deviceId(): Long
}

/**
 * 默认实现：本机墙钟（+ 时钟偏差校正）+ [SyncDeviceHolder]。
 * M4.5 的同步引擎在注册成功后把服务端分配的设备 id 写进 [SyncDeviceHolder]、
 * 把 `serverTime - localTime` 写进 [SyncOffsetHolder] —— 此后所有写路径打出的
 * 时间戳都已在服务端时间轴上（LWW 比较不需要二次校正）。
 */
class SystemSyncStampProvider : SyncStampProvider {
    override fun nowMillis(): Long = System.currentTimeMillis() + SyncOffsetHolder.offsetMs
    override fun deviceId(): Long = SyncDeviceHolder.deviceId
}

/** 全局设备 id 持有者（desktop 与 android 各跑一个实例，进程内单例） */
object SyncDeviceHolder {
    @Volatile
    var deviceId: Long = 0L
}

/** 时钟偏差：serverTime - localTime（毫秒）。注册/每次同步时用服务端时间刷新 */
object SyncOffsetHolder {
    @Volatile
    var offsetMs: Long = 0L
}
