package com.foldledger.data

import com.foldledger.data.sync.SyncStampProvider

/** 固定时钟 / 设备的打戳器（同步测试用；时钟校正前打戳就是本地墙钟） */
class TestStamps(
    var now: Long = 1_700_000_000_000L,
    var device: Long = 7L
) : SyncStampProvider {
    override fun nowMillis(): Long = now
    override fun deviceId(): Long = device
    fun advance(ms: Long) {
        now += ms
    }
}
