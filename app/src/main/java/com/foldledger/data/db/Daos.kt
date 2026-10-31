package com.foldledger.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class DayTotal(
    val day: String,
    val totalCents: Long
)

@Dao
interface LedgerDao {

    @Query("SELECT * FROM ledgers WHERE archived = 0 AND deletedAt = 0 ORDER BY sortOrder ASC, createdAt ASC")
    fun observeActive(): Flow<List<LedgerEntity>>

    @Query("SELECT * FROM ledgers WHERE deletedAt = 0 ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<LedgerEntity>>

    /** v8.1 回收站：被软删的账本 */
    @Query("SELECT * FROM ledgers WHERE deletedAt > 0 ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<LedgerEntity>>

    @Query("SELECT COUNT(*) FROM ledgers WHERE deletedAt > 0")
    fun observeTrashCount(): Flow<Int>

    @Query("SELECT * FROM ledgers WHERE id = :id")
    fun observeById(id: Long): Flow<LedgerEntity?>

    @Query("SELECT * FROM ledgers WHERE id = :id")
    suspend fun getById(id: Long): LedgerEntity?

    /** 导出用：一次性取全部**未删除**账本（回收站不进备份） */
    @Query("SELECT * FROM ledgers WHERE deletedAt = 0 ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun listAll(): List<LedgerEntity>

    @Query("SELECT COUNT(*) FROM ledgers WHERE deletedAt = 0")
    suspend fun count(): Int

    @Query("SELECT IFNULL(MAX(sortOrder), -1) + 1 FROM ledgers")
    suspend fun nextSortOrder(): Int

    @Insert
    suspend fun insert(ledger: LedgerEntity): Long

    /** 恢复备份用：批量插入并返回新生成的主键（顺序与入参一致） */
    @Insert
    suspend fun insertAll(items: List<LedgerEntity>): List<Long>

    @Update
    suspend fun update(ledger: LedgerEntity)

    @Upsert
    suspend fun upsert(ledger: LedgerEntity)

    /** v8.1：移入回收站（软删，数据全留） */
    @Query("UPDATE ledgers SET deletedAt = :at WHERE id = :id")
    suspend fun softDelete(id: Long, at: Long)

    /** v8.1：从回收站捞回来 */
    @Query("UPDATE ledgers SET deletedAt = 0 WHERE id = :id")
    suspend fun restore(id: Long)

    /** v8.1：彻底删除回收站里的账本（级联清流水/分类/项目/账户） */
    @Query("DELETE FROM ledgers WHERE deletedAt > 0")
    suspend fun purgeTrashed()

    @Query("DELETE FROM ledgers WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE ledgers SET budgetCents = :budgetCents WHERE id = :id")
    suspend fun updateBudget(id: Long, budgetCents: Long)

    @Query("UPDATE ledgers SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    @Query("DELETE FROM ledgers")
    suspend fun deleteAll()
}

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories WHERE ledgerId = :ledgerId AND direction = :direction ORDER BY sortOrder ASC, id ASC")
    fun observe(ledgerId: Long, direction: Int): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE ledgerId = :ledgerId ORDER BY direction ASC, sortOrder ASC, id ASC")
    fun observeAll(ledgerId: Long): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE ledgerId = :ledgerId AND direction = :direction ORDER BY sortOrder ASC, id ASC")
    suspend fun list(ledgerId: Long, direction: Int): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE ledgerId = :ledgerId ORDER BY direction ASC, sortOrder ASC, id ASC")
    suspend fun listAll(ledgerId: Long): List<CategoryEntity>

    /** 导出 / 备份用：一次性取全部分类 */
    @Query("SELECT * FROM categories ORDER BY ledgerId ASC, direction ASC, sortOrder ASC")
    suspend fun listEvery(): List<CategoryEntity>

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Insert
    suspend fun insertAll(categories: List<CategoryEntity>): List<Long>

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("UPDATE categories SET budgetCents = :budgetCents WHERE id = :id")
    suspend fun updateBudget(id: Long, budgetCents: Long)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM categories")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM categories WHERE ledgerId = :ledgerId")
    suspend fun countForLedger(ledgerId: Long): Int
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE ledgerId = :ledgerId ORDER BY occurredAt DESC, id DESC")
    fun observeByLedger(ledgerId: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE ledgerId = :ledgerId AND occurredAt BETWEEN :from AND :to ORDER BY occurredAt DESC, id DESC")
    fun observeByLedgerInRange(ledgerId: Long, from: Long, to: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransactionEntity>>

    /**
     * v5.9：全部账本的流水（全局搜索用）。
     *
     * 一次性拉全表在内存里过滤，而不是拼一堆 SQL：个人记账的数据量在几千条量级，
     * 内存过滤的延迟几乎为零，却能让**搜索语义与单账本搜索完全一致**（都走 Rust 的
     * `matchesQuery`：多关键词 AND + 金额匹配）。拼 SQL 反而会做成两套语义。
     */
    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC, id DESC")
    fun observeEvery(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    /** 导出用：一次性取全部流水（按时间正序，便于表格阅读） */
    @Query("SELECT * FROM transactions ORDER BY occurredAt ASC, id ASC")
    suspend fun listAll(): List<TransactionEntity>

    @Query("SELECT IFNULL(SUM(amountCents), 0) FROM transactions WHERE ledgerId = :ledgerId AND direction = :direction")
    fun observeTotal(ledgerId: Long, direction: Int): Flow<Long>

    @Query("SELECT IFNULL(SUM(amountCents), 0) FROM transactions WHERE ledgerId = :ledgerId AND direction = :direction AND occurredAt BETWEEN :from AND :to")
    fun observeTotalInRange(ledgerId: Long, direction: Int, from: Long, to: Long): Flow<Long>

    @Query("SELECT ledgerId AS ledgerId, direction AS direction, IFNULL(SUM(amountCents),0) AS totalCents FROM transactions GROUP BY ledgerId, direction")
    fun observeBalances(): Flow<List<LedgerBalance>>

    /**
     * 某账本某方向、某时间段的分类合计。
     *
     * ⚠️ v9.7.2：改成只按 `categoryName` 分组，emoji 取 `MAX()`。
     * 原来 `GROUP BY categoryName, categoryEmoji` —— 同一个分类**改过图标**之后，
     * 历史流水里存的是旧 emoji，于是饼图/预算里同一个分类会分成两行，
     * 而下游 `associate { name to total }` 只保留最后一行 → 「本月已用」少算。
     */
    @Query(
        """
        SELECT categoryName AS name,
               MAX(categoryEmoji) AS emoji,
               IFNULL(SUM(amountCents),0) AS totalCents,
               COUNT(*) AS count
        FROM transactions
        WHERE ledgerId = :ledgerId AND direction = :direction AND occurredAt BETWEEN :from AND :to
        GROUP BY categoryName
        ORDER BY totalCents DESC
        """
    )
    fun observeCategoryTotals(ledgerId: Long, direction: Int, from: Long, to: Long): Flow<List<CategoryTotal>>

    @Query(
        """
        SELECT strftime('%Y-%m', occurredAt / 1000, 'unixepoch', 'localtime') AS month,
               direction AS direction,
               IFNULL(SUM(amountCents),0) AS totalCents
        FROM transactions
        WHERE ledgerId = :ledgerId
        GROUP BY month, direction
        ORDER BY month ASC
        """
    )
    fun observeMonthlyTotals(ledgerId: Long): Flow<List<MonthTotal>>

    @Query(
        """
        SELECT strftime('%Y-%m-%d', occurredAt / 1000, 'unixepoch', 'localtime') AS day,
               IFNULL(SUM(amountCents),0) AS totalCents
        FROM transactions
        WHERE ledgerId = :ledgerId AND direction = :direction AND occurredAt BETWEEN :from AND :to
        GROUP BY day
        ORDER BY day ASC
        """
    )
    fun observeDailyTotals(ledgerId: Long, direction: Int, from: Long, to: Long): Flow<List<DayTotal>>

    @Query("SELECT COUNT(*) FROM transactions WHERE ledgerId = :ledgerId")
    fun observeCount(ledgerId: Long): Flow<Int>

    /** 报销场景：某账本的报销看板数据 */
    @Query("SELECT IFNULL(SUM(amountCents),0) FROM transactions WHERE ledgerId = :ledgerId AND reimburseState = :state")
    fun observeReimburseSum(ledgerId: Long, state: Int): Flow<Long>

    /**
     * v7.1 垫付/回款：账本级 各报销状态 × (金额, 笔数)，一次查全。
     *
     * 只算支出（direction = 0）——垫付永远是"花出去的钱"；和项目口径
     * （[observeProjectStateTotals]）保持一致。NA（-1）状态不参与。
     */
    @Query(
        """
        SELECT reimburseState AS state,
               IFNULL(SUM(amountCents),0) AS totalCents,
               COUNT(*) AS count
        FROM transactions
        WHERE ledgerId = :ledgerId AND direction = 0 AND reimburseState >= 0
        GROUP BY reimburseState
        """
    )
    fun observeReimburseStateTotals(ledgerId: Long): Flow<List<ReimburseStateAgg>>

    @Query("SELECT * FROM transactions WHERE ledgerId = :ledgerId AND reimburseState = :state ORDER BY occurredAt DESC")
    fun observeReimburseList(ledgerId: Long, state: Int): Flow<List<TransactionEntity>>

    /** v8.1 自动记账去重：同向同额短时间内已记过就不再记（通知常重复推送） */
    @Query(
        "SELECT COUNT(*) FROM transactions WHERE ledgerId = :ledgerId AND direction = :direction " +
            "AND amountCents = :amountCents AND occurredAt >= :since"
    )
    suspend fun countRecentSame(ledgerId: Long, direction: Int, amountCents: Long, since: Long): Int

    /**
     * v10.4.1 自动记账去重（不看方向）：同额 5 分钟内已记过就丢弃。
     *
     * 同一笔支付会同时来多条通知（金融 App + 银行短信 + 支付确认），
     * 若方向解析不一致（一条支出一条收入），旧的 `countRecentSame` 要求同方向，
     * 两条各记一笔。去重**只看金额+时间窗**才能兜住方向误判的场景。
     */
    @Query(
        "SELECT COUNT(*) FROM transactions WHERE ledgerId = :ledgerId " +
            "AND amountCents = :amountCents AND occurredAt >= :since"
    )
    suspend fun countRecentSameAmount(ledgerId: Long, amountCents: Long, since: Long): Int

    /**
     * v9.3 全局：按账本分组的时间段收支汇总（记账助手跨账本聚合用）
     *
     * ⚠️ v9.7.2：全局聚合必须排除**回收站里的账本** —— 账本软删后它的流水还在表里，
     * 不加这个条件的话，"全部账本本月花多少"会把已经删掉的账本算进去（用户会以为钱记错了）。
     */
    @Query(
        "SELECT ledgerId AS ledgerId, direction AS direction, " +
            "IFNULL(SUM(amountCents),0) AS totalCents FROM transactions " +
            "WHERE occurredAt >= :from AND occurredAt <= :to " +
            "AND ledgerId IN (SELECT id FROM ledgers WHERE deletedAt = 0) " +
            "GROUP BY ledgerId, direction"
    )
    fun observeLedgerTotalsInRange(from: Long, to: Long): Flow<List<LedgerTotalAgg>>

    /** v9.3 全局：按账本分组的报销状态汇总（v9.7.2 同上，排除回收站账本） */
    @Query(
        "SELECT ledgerId AS ledgerId, reimburseState AS state, " +
            "IFNULL(SUM(amountCents),0) AS totalCents, COUNT(*) AS count FROM transactions " +
            "WHERE direction = 0 AND reimburseState >= 0 " +
            "AND ledgerId IN (SELECT id FROM ledgers WHERE deletedAt = 0) " +
            "GROUP BY ledgerId, reimburseState"
    )
    fun observeAllReimburseStateTotals(): Flow<List<LedgerReimburseAgg>>

    /** v9.3 全局：这段时间里有流水的账本数量（v9.7.2：回收站账本不算"今天记过账"） */
    @Query(
        "SELECT COUNT(DISTINCT ledgerId) FROM transactions " +
            "WHERE occurredAt >= :from AND occurredAt <= :to " +
            "AND ledgerId IN (SELECT id FROM ledgers WHERE deletedAt = 0)"
    )
    fun observeLedgerCountInRange(from: Long, to: Long): Flow<Int>

    /** v9.0：自动记账账本里还挂着多少笔（移去正式账本前都算"待确认"） */
    @Query(
        "SELECT COUNT(*) FROM transactions WHERE ledgerId = " +
            "(SELECT id FROM ledgers WHERE sceneKey = 'auto' AND deletedAt = 0 LIMIT 1)"
    )
    fun observeAutoPendingCount(): Flow<Int>

    @Insert
    suspend fun insert(txn: TransactionEntity): Long

    /** 恢复备份用：批量插入并返回新生成的主键（顺序与入参一致） */
    @Insert
    suspend fun insertAll(items: List<TransactionEntity>): List<Long>

    @Update
    suspend fun update(txn: TransactionEntity)

    @Query("UPDATE transactions SET reimburseState = :state WHERE id IN (:ids)")
    suspend fun updateReimburseState(ids: List<Long>, state: Int)

    /**
     * v5.6：该账本**最近一次**用过这个方向（支出/收入）的分类 id。
     *
     * 记一笔时默认带出来，省掉"每次都要点一次分类"——这是记账 App 最影响手感的一步。
     * 按 `occurredAt DESC, id DESC`：同一天记的多笔里取最后录入的那笔（id 更大）。
     */
    @Query(
        "SELECT categoryId FROM transactions WHERE ledgerId = :ledgerId " +
            "AND direction = :direction AND categoryId IS NOT NULL " +
            "ORDER BY occurredAt DESC, id DESC LIMIT 1"
    )
    suspend fun lastCategoryId(ledgerId: Long, direction: Int): Long?

    /** v5.1：把一笔流水归入/移出项目（0 = 未归类） */
    @Query("UPDATE transactions SET projectId = :projectId WHERE id = :id")
    suspend fun setProject(id: Long, projectId: Long)

    // ================= v5.6 批量改账 =================
    //
    // 记账 App 里"改账"比"记账"更常发生（记错了、事后补记、分类要重整）。
    // 一笔笔点进编辑再保存是最大的效率黑洞 —— 这几个方法让「多选 → 一次改完」成为可能。

    /**
     * 批量改分类。
     *
     * 分类名/图标是**冗余存储**（[TransactionEntity.categoryName] / `categoryEmoji`），
     * 所以批量改分类时必须一起写，否则分类被删后历史流水会显示成旧名字。
     */
    @Query(
        "UPDATE transactions SET categoryId = :categoryId, categoryName = :categoryName, " +
            "categoryEmoji = :categoryEmoji WHERE id IN (:ids)"
    )
    suspend fun setCategoryBulk(
        ids: List<Long>,
        categoryId: Long?,
        categoryName: String,
        categoryEmoji: String
    )

    /** 批量归入/移出项目（0 = 未归类） */
    @Query("UPDATE transactions SET projectId = :projectId WHERE id IN (:ids)")
    suspend fun setProjectBulk(ids: List<Long>, projectId: Long)

    /** 批量删除（调用方要先二次确认，这是不可撤销的） */
    @Query("DELETE FROM transactions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    // ================= v6.1 资产账户 =================

    /** 改一笔流水的账户（0 = 未指定） */
    @Query("UPDATE transactions SET accountId = :accountId WHERE id = :id")
    suspend fun setAccount(id: Long, accountId: Long)

    /** 批量改账户（配合 v5.6 的多选批量改账） */
    @Query("UPDATE transactions SET accountId = :accountId WHERE id IN (:ids)")
    suspend fun setAccountBulk(ids: List<Long>, accountId: Long)

    /** 账户被删除时，把它的流水退回「未指定」（不能连带删用户的钱） */
    @Query("UPDATE transactions SET accountId = 0 WHERE accountId = :accountId")
    suspend fun clearAccount(accountId: Long)

    /** 某账户的收支汇总（余额推算：initial + Σ收 − Σ支） */
    @Query(
        "SELECT accountId AS accountId, direction AS direction, " +
            "IFNULL(SUM(amountCents),0) AS totalCents FROM transactions " +
            "WHERE accountId = :accountId GROUP BY direction"
    )
    suspend fun accountTotals(accountId: Long): List<AccountTotal>

    /** 全部账户的收支汇总（资产总览一次算完，不要每个账户查一遍） */
    @Query(
        "SELECT accountId AS accountId, direction AS direction, " +
            "IFNULL(SUM(amountCents),0) AS totalCents FROM transactions " +
            "WHERE accountId > 0 GROUP BY accountId, direction"
    )
    suspend fun accountTotalsAll(): List<AccountTotal>

    /** v5.1：项目被删除时，把归属它的流水退回「未归类」 */
    @Query("UPDATE transactions SET projectId = 0 WHERE projectId = :projectId")
    suspend fun clearProject(projectId: Long)

    /** v5.1：导出用，全量按项目聚合支出 */
    @Query(
        """
        SELECT projectId AS projectId,
               IFNULL(SUM(amountCents),0) AS totalCents,
               COUNT(*) AS count
        FROM transactions
        WHERE ledgerId = :ledgerId AND direction = 0 AND projectId > 0
        GROUP BY projectId
        """
    )
    fun observeProjectTotals(ledgerId: Long): Flow<List<ProjectAgg>>

    /** v5.1：项目 × 报销状态，用于项目汇总卡的三格 */
    @Query(
        """
        SELECT projectId AS projectId,
               reimburseState AS state,
               IFNULL(SUM(amountCents),0) AS totalCents,
               COUNT(*) AS count
        FROM transactions
        WHERE ledgerId = :ledgerId AND direction = 0 AND projectId > 0
        GROUP BY projectId, reimburseState
        """
    )
    fun observeProjectStateTotals(ledgerId: Long): Flow<List<ProjectStateAgg>>

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}

@Dao
interface GoalDao {

    /** v9.2 存钱目标：某账本下的全部目标（归档的排最后，不含回收站里的） */
    @Query(
        "SELECT * FROM goals WHERE ledgerId = :ledgerId AND deletedAt = 0 " +
            "ORDER BY archived ASC, createdAt ASC"
    )
    fun observeByLedger(ledgerId: Long): Flow<List<GoalEntity>>

    /** 备份用：全部目标（回收站里的不进备份，与账本回收站同理） */
    @Query("SELECT * FROM goals WHERE deletedAt = 0 ORDER BY createdAt ASC")
    suspend fun listEvery(): List<GoalEntity>

    /** v9.3 全局：所有账本的目标（心愿罐跨账本看，不含回收站里的） */
    @Query("SELECT * FROM goals WHERE deletedAt = 0 ORDER BY archived ASC, createdAt ASC")
    fun observeAll(): Flow<List<GoalEntity>>

    /** v9.4 全局回收站：被软删的目标（按删除时间倒序） */
    @Query("SELECT * FROM goals WHERE deletedAt > 0 ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<GoalEntity>>

    /** v9.7.2 存钱：原子加/减，避免读-改-写丢更新（连点「存 500」两次只进一笔的老 bug） */
    @Query("UPDATE goals SET savedCents = savedCents + :delta WHERE id = :id")
    suspend fun addSaved(id: Long, delta: Long)

    /** 取出：不会减成负数 */
    @Query("UPDATE goals SET savedCents = MAX(0, savedCents - :delta) WHERE id = :id")
    suspend fun subSaved(id: Long, delta: Long)

    /** v9.4 软删：进回收站，可还原 */
    @Query("UPDATE goals SET deletedAt = :at WHERE id = :id")
    suspend fun softDelete(id: Long, at: Long)

    /** v9.4 还原 */
    @Query("UPDATE goals SET deletedAt = 0 WHERE id = :id")
    suspend fun restore(id: Long)

    @Insert
    suspend fun insert(goal: GoalEntity): Long

    @Update
    suspend fun update(goal: GoalEntity)

    /**
     * v10.3：只改元数据，**不碰 savedCents**。
     * v10.5：补 ledgerId —— 「编辑目标-改归属账本」原来被静默丢弃。
     */
    @Query(
        "UPDATE goals SET name = :name, emoji = :emoji, targetCents = :targetCents, " +
            "targetDate = :targetDate, accountId = :accountId, note = :note, " +
            "ledgerId = :ledgerId " +
            "WHERE id = :id"
    )
    suspend fun updateMeta(
        id: Long,
        name: String,
        emoji: String,
        targetCents: Long,
        targetDate: Long,
        accountId: Long,
        note: String,
        ledgerId: Long?
    )

    /** v10.3：归档/取消归档，只改一个字段 */
    @Query("UPDATE goals SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    @Query("DELETE FROM goals WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** v9.7.1：恢复备份 / 清空数据时整表清掉（与其它 Dao 一致） */
    @Query("DELETE FROM goals")
    suspend fun deleteAll()
}

/**
 * 项目 / 空间（v5.1）。
 *
 * 只做「一笔流水归到哪个项目」的容器，聚合计算交给 [TransactionDao] 的
 * `observeProjectTotals` / `observeProjectStateTotals`（按 projectId 分组）。
 */
@Dao
interface ProjectDao {

    @Query("SELECT * FROM projects WHERE ledgerId = :ledgerId ORDER BY closed ASC, createdAt ASC")
    fun observeByLedger(ledgerId: Long): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE ledgerId = :ledgerId ORDER BY closed ASC, createdAt ASC")
    suspend fun listByLedger(ledgerId: Long): List<ProjectEntity>

    /** 导出 / 备份用 */
    @Query("SELECT * FROM projects ORDER BY ledgerId ASC, createdAt ASC")
    suspend fun listEvery(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: Long): ProjectEntity?

    @Insert
    suspend fun insert(project: ProjectEntity): Long

    @Insert
    suspend fun insertAll(items: List<ProjectEntity>): List<Long>

    @Update
    suspend fun update(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM projects")
    suspend fun deleteAll()
}

/**
 * 周期性记账 DAO（v5.7）。
 *
 * `observeDue` 的 `nextDueAt <= now` 是到期判定的唯一口径 —— UI 层不再自己算时间，
 * 避免"列表说 3 笔、入账只入 2 笔"这种口径不一致。
 */
@Dao
interface RecurringDao {

    @Query("SELECT * FROM recurring WHERE ledgerId = :ledgerId ORDER BY enabled DESC, nextDueAt ASC")
    fun observeByLedger(ledgerId: Long): Flow<List<RecurringEntity>>

    @Query(
        "SELECT * FROM recurring WHERE ledgerId = :ledgerId AND enabled = 1 " +
            "AND nextDueAt <= :now ORDER BY nextDueAt ASC"
    )
    fun observeDue(ledgerId: Long, now: Long): Flow<List<RecurringEntity>>

    /** v9.3 全局：所有账本里已到期的周期项（助手跨账本聚合用） */
    @Query("SELECT * FROM recurring WHERE enabled = 1 AND nextDueAt <= :now ORDER BY nextDueAt ASC")
    fun observeAllDue(now: Long): Flow<List<RecurringEntity>>

    /** v9.7.2 全局：所有**活账本**里已到期的周期项（回收站账本不该再提醒） */
    @Query(
        "SELECT * FROM recurring WHERE enabled = 1 AND nextDueAt <= :now " +
            "AND ledgerId IN (SELECT id FROM ledgers WHERE deletedAt = 0) ORDER BY nextDueAt ASC"
    )
    fun observeAllDueInAliveLedgers(now: Long): Flow<List<RecurringEntity>>

    @Query("SELECT * FROM recurring ORDER BY ledgerId ASC, nextDueAt ASC")
    suspend fun listEvery(): List<RecurringEntity>

    @Query("SELECT * FROM recurring WHERE id = :id")
    suspend fun getById(id: Long): RecurringEntity?

    @Insert
    suspend fun insert(item: RecurringEntity): Long

    @Insert
    suspend fun insertAll(items: List<RecurringEntity>): List<Long>

    @Update
    suspend fun update(item: RecurringEntity)

    @Query("UPDATE recurring SET nextDueAt = :nextDue WHERE id = :id")
    suspend fun setNextDue(id: Long, nextDue: Long)

    @Query("UPDATE recurring SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM recurring WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM recurring")
    suspend fun deleteAll()
}

/**
 * 资产账户 DAO（v6.1）。
 *
 * 账户管的是**存量**（我现在有多少钱），流水管的是**流量**（钱怎么来的、怎么走的）。
 * 余额不在这里存，而是从 `initialBalanceCents + Σ流水` 实时推算，改流水不会让余额对不上。
 */
@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts WHERE ledgerId = :ledgerId ORDER BY closed ASC, createdAt ASC")
    fun observeByLedger(ledgerId: Long): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE ledgerId = :ledgerId ORDER BY closed ASC, createdAt ASC")
    suspend fun listByLedger(ledgerId: Long): List<AccountEntity>

    /** 备份 / 导出用 */
    @Query("SELECT * FROM accounts ORDER BY ledgerId ASC, createdAt ASC")
    suspend fun listEvery(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Insert
    suspend fun insertAll(items: List<AccountEntity>): List<Long>

    @Update
    suspend fun update(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM accounts")
    suspend fun deleteAll()
}
