package com.foldledger.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.foldledger.ServiceLocator
import com.foldledger.data.db.GoalEntity
import com.foldledger.data.db.LedgerEntity
import com.foldledger.data.repo.LedgerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 存钱目标（v9.2）——心愿罐。
 *
 * 记账记录"已经花掉的"，目标记录"想攒下的"。两者分开存：
 * 攒钱不是支出，混进流水会污染统计（尤其预算和月度结余）。
 */
class GoalViewModel(
    private val repo: LedgerRepository,
    /** 新建目标时默认归属的账本（0 = 让用户选） */
    defaultLedgerId: Long = 0L
) : ViewModel() {

    /** 全部账本（目标可以挂到任意账本下） */
    val ledgers: StateFlow<List<LedgerEntity>> =
        repo.ledgers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * v9.3：**所有账本**的目标 —— 心愿罐是全局的，不该只在报销账本里看得到。
     *
     * v9.7.1：初值用 null 表示"还没读到"，避免首帧闪一下「还没有存钱目标」的插画。
     */
    val goals: StateFlow<List<GoalEntity>?> =
        repo.allGoals().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * 新建目标。
     *
     * v9.7：**默认独立**（ledgerId = null = 不归属任何账本）。
     * 关联只是可选标签，攒的钱本来就不在账本里。
     */
    fun create(
        name: String,
        emoji: String,
        targetCents: Long,
        targetDate: Long = 0L,
        ledgerId: Long? = null,
        note: String = ""
    ) = viewModelScope.launch {
        repo.createGoal(
            ledgerId = ledgerId,
            name = name,
            emoji = emoji,
            targetCents = targetCents,
            targetDate = targetDate,
            note = note
        )
    }

    fun deposit(goal: GoalEntity, amountCents: Long) =
        viewModelScope.launch { repo.depositGoal(goal, amountCents) }

    fun withdraw(goal: GoalEntity, amountCents: Long) =
        viewModelScope.launch { repo.withdrawGoal(goal, amountCents) }

    fun archive(goal: GoalEntity, archived: Boolean) =
        viewModelScope.launch { repo.archiveGoal(goal, archived) }

    /** v9.7 编辑目标（改名 / 改金额 / 改期限 / 改归属） */
    fun update(
        goal: GoalEntity,
        name: String,
        emoji: String,
        targetCents: Long,
        targetDate: Long,
        ledgerId: Long?
    ) = viewModelScope.launch {
        repo.updateGoal(
            goal.copy(
                name = name,
                emoji = emoji,
                targetCents = targetCents,
                targetDate = targetDate,
                ledgerId = ledgerId
            )
        )
    }

    /** v9.4 删除 → 进回收站（可还原） */
    fun trash(goal: GoalEntity) = viewModelScope.launch { repo.trashGoal(goal.id) }

    /** v9.4 从回收站还原 */
    fun restore(id: Long) = viewModelScope.launch { repo.restoreGoal(id) }

    /** v9.4 彻底删除（不可还原） */
    fun purge(id: Long) = viewModelScope.launch { repo.purgeGoal(id) }

    /** 回收站里的目标 */
    val trashed: StateFlow<List<GoalEntity>> =
        repo.trashedGoals().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    companion object {
        fun factory(defaultLedgerId: Long) = viewModelFactory {
            initializer { GoalViewModel(ServiceLocator.repository, defaultLedgerId) }
        }
    }
}
