package com.foldledger.data.sync

/**
 * M4 同步核心裁决规则：Last-Write-Wins（行级）。
 *
 * 双端与 Node 服务端必须实现同一套规则：
 *  1. `updatedAt` 大者胜（时间戳已按服务端时钟对齐，见 [alignTimestamp]）；
 *  2. 同刻按 `updatedByDeviceId` 大者胜 —— 设备 id 由服务端注册时分配，全局唯一。
 */
object Lww {

    /** true = A 赢（B 被覆盖）。时间戳相等且设备也相等时 A 赢（幂等，不产生覆盖写） */
    fun aWins(updatedAtA: Long, deviceA: Long, updatedAtB: Long, deviceB: Long): Boolean =
        updatedAtA > updatedAtB ||
            (updatedAtA == updatedAtB && deviceA >= deviceB)

    /**
     * 时钟校正：把本地时间戳换算到服务端时间轴。
     * `offset = serverTime - localTime`（注册/每次同步时测量，存偏好）；
     * 校正后所有设备的行时间戳在同一时间轴上，LWW 比较不再需要二次校正。
     */
    fun alignTimestamp(localTs: Long, offsetMs: Long): Long = localTs + offsetMs
}
