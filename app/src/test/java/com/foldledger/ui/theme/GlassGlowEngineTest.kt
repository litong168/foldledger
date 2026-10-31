package com.foldledger.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Glass 2.0 滚动光相的**性质测试**。
 *
 * 这两条性质是被真机问题逼出来的，每一条都对应一个具体的"看起来不对"：
 *
 *  1. **静止必须等于 0**（= v13.9 的原位）。
 *     旧写法把滚动位置折成 0..1 相位，位置为 0 时相位为 0，再折成扫掠量就成了 **-1**
 *     —— 顶部静置时底栏高光被推到 0.16 宽度处，与 v13.9 的 0.28 对不上。
 *     那不是效果，是**回归**。
 *
 *  2. **跨行不许跳变**。
 *     旧写法用 `index*1000 + offset` 当连续量，假设每行 1000px；
 *     真实流水行约 140px，于是每换一行连续量猛跳 860px、扫掠量跳 1.4 圈 → 高光闪。
 */
class GlassGlowEngineTest {

    private val ms = 1_000_000L

    @Test
    fun `静止时扫掠量恒为 0`() {
        val engine = GlassGlowEngine()
        // 第一次采样只是建立基准
        engine.onScroll(index = 0, offset = 0, visibleSizes = listOf(0 to 140), nowNanos = 0L)
        assertEquals(0f, engine.sweep, 1e-6f)

        // 停着不动，隔很久再采一次 —— 扫掠量必须还是 0
        engine.onScroll(index = 0, offset = 0, visibleSizes = listOf(0 to 140), nowNanos = 500 * ms)
        assertEquals(0f, engine.sweep, 1e-6f)
    }

    @Test
    fun `同行内匀速滚动时扫掠量稳定`() {
        val engine = GlassGlowEngine()
        engine.onScroll(0, 0, listOf(0 to 140), 0L)
        // 每帧走 20px，间隔 16ms → 1250px/s
        var t = 0L
        for (i in 1..10) {
            t += 16 * ms
            engine.onScroll(0, i * 20, listOf(0 to 140), t)
        }
        // EMA 收敛到约 1250px/s，referenceVel=3200 → 扫掠量约 0.39
        assertTrue("扫掠量应显著非零，实际=${engine.sweep}", engine.sweep > 0.3f)
        assertTrue("扫掠量应在合理范围，实际=${engine.sweep}", engine.sweep <= 0.5f)
    }

    @Test
    fun `跨行时位移被精确补偿不产生跳变`() {
        val engine = GlassGlowEngine()
        val rowH = 140
        // 首项 0 已滚过 130px
        engine.onScroll(0, 130, listOf(0 to rowH, 1 to rowH, 2 to rowH), 0L)
        // 接着滚 20px：第一行还剩 10px，于是 index 变成 1、offset=10
        // 真实总位移 = 10（走完第 0 行）+ 10（进入第 1 行）= 20px
        engine.onScroll(1, 10, listOf(0 to rowH, 1 to rowH, 2 to rowH), 16 * ms)
        assertEquals(20f, engine.lastDeltaPx, 0.01f)
    }

    @Test
    fun `跨行不会把扫掠量打到满量程`() {
        val engine = GlassGlowEngine()
        val rowH = 140
        engine.onScroll(0, 130, listOf(0 to rowH, 1 to rowH, 2 to rowH), 0L)
        // 换行瞬间：真实位移只有 20px，扫掠量不该突变
        engine.onScroll(1, 10, listOf(0 to rowH, 1 to rowH, 2 to rowH), 16 * ms)
        // 旧写法在这里会因为 index*1000 假设而猛跳；现在位移是精确的
        assertTrue("跨行后扫掠量不该爆到满量程，实际=${engine.sweep}", engine.sweep < 0.2f)
    }

    @Test
    fun `向上滚时扫掠量为负`() {
        val engine = GlassGlowEngine()
        val rowH = 140
        engine.onScroll(1, 30, listOf(0 to rowH, 1 to rowH), 0L)
        var t = 0L
        for (i in 1..8) {
            t += 16 * ms
            engine.onScroll(1, 30 - i * 20, listOf(0 to rowH, 1 to rowH), t)
        }
        assertTrue("向上滚时扫掠量应为负，实际=${engine.sweep}", engine.sweep < -0.2f)
    }

    @Test
    fun `超快甩动时扫掠量被夹在 -1 到 1`() {
        val engine = GlassGlowEngine()
        engine.onScroll(0, 0, listOf(0 to 140), 0L)
        var t = 0L
        for (i in 1..20) {
            t += 16 * ms
            engine.onScroll(0, i * 400, listOf(0 to 140), t)
        }
        assertTrue("扫掠量必须被夹住，实际=${engine.sweep}", engine.sweep <= 1f && engine.sweep >= -1f)
    }

    @Test
    fun `复位后扫掠量归零`() {
        val engine = GlassGlowEngine()
        engine.onScroll(0, 0, listOf(0 to 140), 0L)
        var t = 0L
        for (i in 1..10) {
            t += 16 * ms
            engine.onScroll(0, i * 30, listOf(0 to 140), t)
        }
        assertTrue(engine.sweep != 0f)
        engine.reset()
        assertEquals(0f, engine.sweep, 1e-6f)
        assertEquals(0f, engine.velocityPxPerSec, 1e-6f)
    }
}
