package com.foldledger.data

import com.foldledger.data.db.CategoryEntity
import com.foldledger.data.db.Direction
import com.foldledger.data.repo.missingRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「恢复预置分类」的判重口径。
 *
 * 这段逻辑决定用户点「恢复预置分类」时到底补哪几条、补几条，
 * 判错就会出现「明明已经有的分类又被插一份」这种脏数据，所以单独测。
 */
class PresetRestoreTest {

    private fun cat(name: String, direction: Int, builtIn: Boolean = true) = CategoryEntity(
        id = 0L,
        ledgerId = 1L,
        name = name,
        emoji = "🏷️",
        direction = direction,
        sortOrder = 0,
        builtIn = builtIn
    )

    @Test
    fun emptyLedgerMissesEverything() {
        val scene = ScenePresets.byKey("car")
        val missing = scene.missingRows(emptyList())
        assertEquals(scene.expenses.size + scene.incomes.size, missing.size)
    }

    @Test
    fun fullSetMissesNothing() {
        val scene = ScenePresets.byKey("car")
        val existing = scene.expenses.map { cat(it.name, Direction.EXPENSE) } +
            scene.incomes.map { cat(it.name, Direction.INCOME) }
        assertTrue(scene.missingRows(existing).isEmpty())
    }

    @Test
    fun onlyMissingOnesAreReported() {
        val scene = ScenePresets.byKey("baby")
        // 只留第一个支出分类，其余全删掉
        val existing = listOf(cat(scene.expenses.first().name, Direction.EXPENSE))
        val missing = scene.missingRows(existing)
        assertEquals(scene.expenses.size + scene.incomes.size - 1, missing.size)
    }

    @Test
    fun sameNameDifferentDirectionIsNotDeduped() {
        val scene = ScenePresets.byKey("daily")
        // 「其他支出」是支出、「其他收入」是收入，两者名字不同；
        // 这里构造一个「名字相同但方向不同」的场景来验证判重带方向
        val existing = listOf(
            cat("餐饮", Direction.INCOME)   // 收入方向有个同名分类，不该顶掉支出方向的「餐饮」
        )
        val missing = scene.missingRows(existing)
        assertTrue(missing.any { it.name == "餐饮" && it.direction == Direction.EXPENSE })
    }

    @Test
    fun userCreatedCategoryWithSameNameAlsoCounts() {
        val scene = ScenePresets.byKey("car")
        // 用户自建的「加油」（builtIn=false）也应被视为已存在，不该再补一条预置的
        val existing = listOf(cat("加油", Direction.EXPENSE, builtIn = false))
        val missing = scene.missingRows(existing)
        assertTrue(missing.none { it.name == "加油" && it.direction == Direction.EXPENSE })
    }

    @Test
    fun customSceneHasOnlyFallbackCategories() {
        val scene = ScenePresets.byKey(ScenePresets.CUSTOM_KEY)
        val missing = scene.missingRows(emptyList())
        assertEquals(2, missing.size)
    }
}
