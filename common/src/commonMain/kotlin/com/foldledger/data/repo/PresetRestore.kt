package com.foldledger.data.repo

import com.foldledger.data.ScenePreset
import com.foldledger.data.db.CategoryEntity
import com.foldledger.data.db.Direction

/** 一条「该有但当前没有」的预置分类 */
data class PresetRow(
    val name: String,
    val emoji: String,
    val direction: Int
)

/**
 * 算出某个场景模板里**当前还没有**的预置分类。
 *
 * 判重口径是 `名称 + 方向`：同一个账本里「餐饮」既是支出也可能是收入，
 * 只按名称判重会把两个方向的同名分类混起来。
 *
 * 这个函数是纯函数，ViewModel 可以用它算「待恢复数量」做预览，
 * Repository 用同一份结果真正落库 —— 两边口径保持一致，不会出现
 * 「提示恢复 5 个、实际插入 3 个」这种对不上的情况。
 */
fun ScenePreset.missingRows(existing: List<CategoryEntity>): List<PresetRow> {
    val have = existing.mapTo(HashSet()) { it.name to it.direction }
    val out = mutableListOf<PresetRow>()
    expenses.forEach { c ->
        if (have.add(c.name to Direction.EXPENSE)) {
            out += PresetRow(c.name, c.emoji, Direction.EXPENSE)
        }
    }
    incomes.forEach { c ->
        if (have.add(c.name to Direction.INCOME)) {
            out += PresetRow(c.name, c.emoji, Direction.INCOME)
        }
    }
    return out
}
