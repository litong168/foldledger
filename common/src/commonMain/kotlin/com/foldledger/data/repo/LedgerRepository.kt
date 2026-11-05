package com.foldledger.data.repo

import com.foldledger.APP_VERSION_NAME
import com.foldledger.data.ScenePreset
import com.foldledger.platform.runInDbTransaction
import com.foldledger.data.RecurringSchedule
import com.foldledger.data.ScenePresets
import com.foldledger.data.backup.BackupSnapshot
import com.foldledger.data.db.AppDatabase
import com.foldledger.data.db.AccountEntity
import com.foldledger.data.db.CategoryEntity
import com.foldledger.data.db.Direction
import com.foldledger.data.db.GoalEntity
import com.foldledger.data.db.LedgerReimburseAgg
import com.foldledger.data.db.LedgerTotalAgg
import com.foldledger.data.db.LedgerEntity
import com.foldledger.data.db.ProjectAgg
import com.foldledger.data.db.ProjectEntity
import com.foldledger.data.db.ProjectStateAgg
import com.foldledger.data.db.ReimburseState
import com.foldledger.data.db.RecurringEntity
import com.foldledger.data.db.TransactionEntity
import com.foldledger.data.sync.SyncIdAllocator
import com.foldledger.data.sync.SyncStampProvider
import com.foldledger.data.sync.SystemSyncStampProvider
import com.foldledger.data.sync.TableNames
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** 恢复结果统计 */
data class RestoreStats(
    val ledgerCount: Int,
    val categoryCount: Int,
    val txnCount: Int
)

/**
 * 唯一数据入口。所有写操作都是 suspend，UI 层通过 ViewModel 调用。
 *
 * v11 同步：所有写路径在这里统一打戳（[stamps]）—— 新增/修改写
 * `updatedAt = now, updatedByDeviceId = deviceId`，删除转软删（墓碑保留）。
 * DAO 层不自行取时间，漏打一戳，该行就进不了水位线增量。
 */
class LedgerRepository(
    private val db: AppDatabase,
    private val stamps: SyncStampProvider = SystemSyncStampProvider(),
    /** M4.3：统一 id 空间分配器（M4.5 注册后置 enabled = true） */
    val idAllocator: SyncIdAllocator = SyncIdAllocator()
) {

    private val ledgerDao = db.ledgerDao()
    private val categoryDao = db.categoryDao()
    private val txnDao = db.transactionDao()
    private val projectDao = db.projectDao()
    private val accountDao = db.accountDao()
    private val recurringDao = db.recurringDao()
    private val goalDao = db.goalDao()

    private fun now() = stamps.nowMillis()
    private fun device() = stamps.deviceId()

    // M4.3：统一 id 空间（未启用同步时返回 0 → Room 自增，行为与老版本一致）
    private suspend fun nextLedgerId() =
        idAllocator.nextId(TableNames.LEDGERS) { from, to -> ledgerDao.maxIdInRange(from, to) }
    private suspend fun nextCategoryId() =
        idAllocator.nextId(TableNames.CATEGORIES) { from, to -> categoryDao.maxIdInRange(from, to) }
    private suspend fun nextTxnId() =
        idAllocator.nextId(TableNames.TRANSACTIONS) { from, to -> txnDao.maxIdInRange(from, to) }
    private suspend fun nextProjectId() =
        idAllocator.nextId(TableNames.PROJECTS) { from, to -> projectDao.maxIdInRange(from, to) }
    private suspend fun nextAccountId() =
        idAllocator.nextId(TableNames.ACCOUNTS) { from, to -> accountDao.maxIdInRange(from, to) }
    private suspend fun nextRecurringId() =
        idAllocator.nextId(TableNames.RECURRING) { from, to -> recurringDao.maxIdInRange(from, to) }
    private suspend fun nextGoalId() =
        idAllocator.nextId(TableNames.GOALS) { from, to -> goalDao.maxIdInRange(from, to) }

    private fun LedgerEntity.touched() = copy(updatedAt = now(), updatedByDeviceId = device())
    private fun CategoryEntity.touched() = copy(updatedAt = now(), updatedByDeviceId = device())
    private fun TransactionEntity.touched() = copy(updatedAt = now(), updatedByDeviceId = device())
    private fun GoalEntity.touched() = copy(updatedAt = now(), updatedByDeviceId = device())
    private fun ProjectEntity.touched() = copy(updatedAt = now(), updatedByDeviceId = device())
    private fun AccountEntity.touched() = copy(updatedAt = now(), updatedByDeviceId = device())
    private fun RecurringEntity.touched() = copy(updatedAt = now(), updatedByDeviceId = device())

    // ---------------- 账本 ----------------

    val ledgers: Flow<List<LedgerEntity>> = ledgerDao.observeActive()
    val allLedgers: Flow<List<LedgerEntity>> = ledgerDao.observeAll()

    fun ledger(id: Long): Flow<LedgerEntity?> = ledgerDao.observeById(id)

    suspend fun getLedger(id: Long): LedgerEntity? = ledgerDao.getById(id)

    suspend fun createLedger(
        name: String,
        sceneKey: String,
        emoji: String,
        colorHex: String,
        budgetCents: Long = 0L,
        note: String = ""
    ): Long {
        val scene = ScenePresets.byKey(sceneKey)
        // ⚠️ v9.7.2：建账本 + 播预置分类必须同一个事务 ——
        // 中途被杀会留下一个「没有任何分类」的空账本，用户记一笔时分类区全空。
        return db.runInDbTransaction {
            // ⚠️ 用 insert 的**实际返回值**做账本 id：分配器未启用时传 0，Room 会生成自增 id，
            // 拿分配结果当账本 id 会让预置分类挂到一个不存在的账本上（FK 失败）。
            val allocatedId = nextLedgerId()
            val id = ledgerDao.insert(
                LedgerEntity(
                    id = allocatedId,
                    name = name.ifBlank { scene.name + "账本" },
                    sceneKey = scene.key,
                    emoji = emoji,
                    colorHex = colorHex,
                    budgetCents = budgetCents,
                    note = note,
                    sortOrder = ledgerDao.nextSortOrder()
                ).touched()
            )
            seedCategories(id, scene)
            id
        }
    }

    suspend fun updateLedger(ledger: LedgerEntity) = ledgerDao.update(ledger.touched())


    // ---------------- 全局聚合（v9.3：跨账本） ----------------

    /** 按账本分组的时间段收支汇总 */
    fun ledgerTotalsInRange(from: Long, to: Long): Flow<List<LedgerTotalAgg>> =
        txnDao.observeLedgerTotalsInRange(from, to)

    /** 按账本分组的报销状态汇总 */
    fun allReimburseStateTotals(): Flow<List<LedgerReimburseAgg>> =
        txnDao.observeAllReimburseStateTotals()

    /** 这段时间里有流水的账本数量 */
    fun ledgerCountInRange(from: Long, to: Long): Flow<Int> =
        txnDao.observeLedgerCountInRange(from, to)

    /** 所有账本里已到期的周期项 */
    /** v9.7.2：只算**活账本**的到期周期项（回收站账本不该再提醒） */
    fun allRecurringDue(now: Long): Flow<List<RecurringEntity>> =
        recurringDao.observeAllDueInAliveLedgers(now)

    // ---------------- 存钱目标（v9.2） ----------------

    /** 某账本下的存钱目标 */
    fun goals(ledgerId: Long): Flow<List<GoalEntity>> = goalDao.observeByLedger(ledgerId)

    /** v9.3 全局：所有账本的存钱目标 */
    fun allGoals(): Flow<List<GoalEntity>> = goalDao.observeAll()

    suspend fun createGoal(
        /** null = 独立目标（不归属任何账本） */
        ledgerId: Long?,
        name: String,
        emoji: String,
        targetCents: Long,
        targetDate: Long = 0L,
        accountId: Long = 0L,
        note: String = ""
    ): Long {
        val id = nextGoalId()
        return goalDao.insert(
            GoalEntity(
                id = id,
                ledgerId = ledgerId,
                name = name,
                emoji = emoji,
                targetCents = targetCents,
                targetDate = targetDate,
                accountId = accountId,
                note = note
            ).touched()
        )
    }

    /**
     * 存入（钱罐里加钱）。
     *
     * ⚠️ v9.7.2：改成**原子 UPDATE**（`savedCents = savedCents + :delta`）。
     * 原来是「读 goal.savedCents → copy → 整行写回」，读-改-写之间没有锁：
     * 连点两下「存 500」时两次都基于同一个旧值，结果只进账 500（另一笔被覆盖）。
     */
    suspend fun depositGoal(goal: GoalEntity, amountCents: Long) {
        if (amountCents <= 0L) return
        goalDao.addSaved(goal.id, amountCents, now(), device())
    }

    /** 取出（钱罐里取钱，永远不会取成负数） */
    suspend fun withdrawGoal(goal: GoalEntity, amountCents: Long) {
        if (amountCents <= 0L) return
        goalDao.subSaved(goal.id, amountCents, now(), device())
    }

    /**
     * v10.3：只改元数据，不碰 savedCents。
     * v10.5：补 ledgerId —— 改归属账本原来被静默丢弃。
     */
    suspend fun updateGoal(goal: GoalEntity) = goalDao.updateMeta(
        id = goal.id,
        name = goal.name,
        emoji = goal.emoji,
        targetCents = goal.targetCents,
        targetDate = goal.targetDate,
        accountId = goal.accountId,
        note = goal.note,
        ledgerId = goal.ledgerId,
        at = now(),
        deviceId = device()
    )

    suspend fun archiveGoal(goal: GoalEntity, archived: Boolean) =
        goalDao.setArchived(goal.id, archived, now(), device())

    /** v9.4 软删进回收站（可还原） */
    suspend fun trashGoal(id: Long) = goalDao.softDelete(id, now(), device())

    /** v9.4 从回收站还原 */
    suspend fun restoreGoal(id: Long) = goalDao.restore(id, now(), device())

    /** v9.4 彻底删除（回收站里清掉，不可还原） */
    suspend fun purgeGoal(id: Long) = goalDao.deleteById(id)

    /** v9.4 回收站里的目标 */
    fun trashedGoals(): Flow<List<GoalEntity>> = goalDao.observeTrash()

    // ---------------- 回收站（v8.1） ----------------

    /** 软删：移入回收站，流水/分类/项目/账户全部保留 */
    suspend fun softDeleteLedger(id: Long) = ledgerDao.softDelete(id, now(), device())

    /** 从回收站恢复 */
    suspend fun restoreLedger(id: Long) = ledgerDao.restore(id, now(), device())

    /** 回收站里的账本（按删除时间倒序） */
    fun trashedLedgers(): Flow<List<LedgerEntity>> = ledgerDao.observeTrash()

    fun trashedCount(): Flow<Int> = ledgerDao.observeTrashCount()

    /** 彻底删除单个回收站账本（FK 级联清掉它的一切） */
    suspend fun purgeLedger(id: Long) = ledgerDao.deleteById(id)

    /** 清空回收站 */
    suspend fun purgeTrashed() = ledgerDao.purgeTrashed()

    // ---------------- 自动记账（v8.1） ----------------

    /** 自动记账账本里待确认（还没移去正式账本）的笔数 */
    fun autoPendingCount(): Flow<Int> = txnDao.observeAutoPendingCount()

    /** 自动记账账本 id（软删的不算）；没预置过返回 null */
    suspend fun findAutoLedgerId(): Long? =
        ledgerDao.listAll().firstOrNull { it.sceneKey == ScenePresets.AUTO_KEY }?.id

    /** 预置自动记账账本（调用方负责"只建一次"的节流） */
    suspend fun ensureAutoLedger(): Long {
        findAutoLedgerId()?.let { return it }
        val s = ScenePresets.byKey(ScenePresets.AUTO_KEY)
        return createLedger("自动记账", s.key, s.emoji, s.colorHex)
    }

    /**
     * 捕获一条自动记账（支付通知/银行短信 → 落进自动记账账本）。
     *
     * v10.6：**账本缺失时自愈重建** —— 原来 `findAutoLedgerId() ?: return null`
     * 意味着用户删过「自动记账」账本（或预置没跑成）之后，所有支付都静默丢失。
     * 现在事务内先 ensure，缺了就建一个，功能不会因为一个账本消失就永久失效。
     *
     * 去重：同金额 5 分钟内已记过就丢弃（**不看方向**）。
     *
     * ⚠️ v10.2.1：检查+插入必须在同一个 `runInDbTransaction` 里。
     */
    suspend fun recordAutoPayment(p: com.foldledger.util.PaymentParser.ParsedPayment): Long? {
        val at = p.atMillis ?: System.currentTimeMillis()
        return db.runInDbTransaction {
            // 事务内 ensure：并发多路也只会建一个（Room 串行化）
            val ledgerId = findAutoLedgerId() ?: run {
                val s = ScenePresets.byKey(ScenePresets.AUTO_KEY)
                createLedger("自动记账", s.key, s.emoji, s.colorHex)
            }
            val dup = txnDao.countRecentSameAmount(
                ledgerId = ledgerId,
                amountCents = p.amountCents,
                since = at - 5 * 60_000
            )
            if (dup > 0) return@runInDbTransaction null
            val catName = if (p.direction == Direction.INCOME) "自动收入" else "自动记账"
            val txnId = nextTxnId()
            txnDao.insert(
                TransactionEntity(
                    id = txnId,
                    ledgerId = ledgerId,
                    categoryId = null,
                    categoryName = catName,
                    categoryEmoji = "🤖",
                    direction = p.direction,
                    amountCents = p.amountCents,
                    note = "${p.merchant} · ${p.source}",
                    occurredAt = at,
                    extraJson = encodeExtra(emptyMap()),
                    reimburseState = ReimburseState.NA
                ).touched()
            )
        }
    }

    /**
     * 移动一笔流水到别的账本，并**适配目标账本的个性化格式**（v8.1）。
     *
     * 适配规则：
     *  - 分类：按名字在目标账本找同向同名分类；找不到用「未分类/其他」；再找不到用第一个同向分类；
     *  - 报销字段：目标是报销场景 → 进「待提交」（报销从垫付开始），否则清回 NA；
     *  - 项目/账户/场景字段：跨账本都归零/清空（项目、账户是账本内实体）。
     */
    suspend fun moveTransactionTo(txnId: Long, targetLedgerId: Long) = db.runInDbTransaction {
        val txn = txnDao.getById(txnId) ?: return@runInDbTransaction
        if (txn.ledgerId == targetLedgerId) return@runInDbTransaction
        val target = ledgerDao.getById(targetLedgerId) ?: return@runInDbTransaction

        val cats = categoryDao.list(targetLedgerId, txn.direction)
        val match = cats.firstOrNull { it.name == txn.categoryName }
            ?: cats.firstOrNull { it.name == "未分类" || it.name == "其他" || it.name == "其他支出" || it.name == "其他收入" }
            ?: cats.firstOrNull()

        val reimburseState = if (
            ScenePresets.byKey(target.sceneKey).supportsReimbursement &&
            txn.direction == Direction.EXPENSE
        ) ReimburseState.PENDING else ReimburseState.NA

        txnDao.update(
            txn.copy(
                ledgerId = targetLedgerId,
                categoryId = match?.id,
                categoryName = match?.name ?: txn.categoryName,
                categoryEmoji = match?.emoji ?: txn.categoryEmoji,
                reimburseState = reimburseState,
                projectId = 0L,
                accountId = 0L,
                extraJson = ""
            ).touched()
        )
    }

    suspend fun setArchived(id: Long, archived: Boolean) =
        ledgerDao.setArchived(id, archived, now(), device())

    suspend fun setBudget(id: Long, budgetCents: Long) =
        ledgerDao.updateBudget(id, budgetCents, now(), device())

    suspend fun nextSortOrder(): Int = ledgerDao.nextSortOrder()

    // ---------------- 分类 ----------------

    fun categories(ledgerId: Long, direction: Int) = categoryDao.observe(ledgerId, direction)

    /** v6.0 预算健康度：不分方向取全部分类（要拿每个分类的 budgetCents） */
    fun categoriesAll(ledgerId: Long) = categoryDao.observeAll(ledgerId)

    fun allCategories(ledgerId: Long) = categoryDao.observeAll(ledgerId)

    suspend fun listCategories(ledgerId: Long, direction: Int) = categoryDao.list(ledgerId, direction)

    suspend fun listAllCategories(ledgerId: Long) = categoryDao.listAll(ledgerId)

    suspend fun addCategory(ledgerId: Long, name: String, emoji: String, direction: Int): Long {
        // ⚠️ v9.7.2：categories 上有 (ledgerId, direction, name) 唯一索引，
        // 重名直接 insert 会抛 SQLiteConstraintException 把页面崩掉（VM 侧没有 catch）。
        // 重名时返回已有的那条 —— 对用户来说"加了个同名的"，等价于没变化，但不该崩。
        // v11：判重必须看**含软删**的全量 —— 软删的行还占着名字；若撞到墓碑，
        // 直接复活它（保留原 id/顺序），而不是插入新行。
        val existing = categoryDao.listIncludingDeleted(ledgerId)
        existing.firstOrNull { it.name == name && it.direction == direction }?.let {
            if (it.deletedAt > 0L) categoryDao.restore(it.id, now(), device())
            return it.id
        }
        val id = nextCategoryId()
        return categoryDao.insert(
            CategoryEntity(
                id = id,
                ledgerId = ledgerId,
                name = name,
                emoji = emoji,
                direction = direction,
                sortOrder = existing.count { it.direction == direction && it.deletedAt == 0L },
                builtIn = false
            ).touched()
        )
    }

    suspend fun updateCategory(category: CategoryEntity) = categoryDao.update(category.touched())

    suspend fun setCategoryBudget(id: Long, budgetCents: Long) =
        categoryDao.updateBudget(id, budgetCents, now(), device())

    /** v11 软删：分类从界面消失（历史流水保留冗余名/图标），墓碑供同步传播 */
    suspend fun deleteCategory(id: Long) = categoryDao.softDelete(id, now(), device())

    /**
     * 把该账本所属场景模板里**缺失**的预置分类补回来。
     *
     * 只做「补齐」，不做「重置」：已经存在（同名同方向）的分类一律不动 ——
     * 用户可能改过名字、换过图标、删过又自己建了一个，覆盖回去就等于把用户的改动抹掉。
     *
     * @return 实际新增的条数，0 表示本来就不缺
     */
    suspend fun restorePresetCategories(ledgerId: Long): Int {
        val ledger = ledgerDao.getById(ledgerId) ?: return 0
        val scene = ScenePresets.byKey(ledger.sceneKey)
        // v11：判重看含软删的全量 —— 用户删过的预置分类不复活（尊重删除意图），
        // 但也绝不能因为它还占着唯一索引名字就往里硬插。
        val existing = categoryDao.listIncludingDeleted(ledgerId)
        val missing = scene.missingRows(existing)
        if (missing.isEmpty()) return 0

        // 新分类排到各自方向的末尾，不打乱用户已经排好的顺序
        val nextOrder = HashMap<Int, Int>(2)
        existing.forEach { c ->
            nextOrder[c.direction] = maxOf(nextOrder[c.direction] ?: 0, c.sortOrder + 1)
        }

        val rows = missing.map { row ->
            val order = nextOrder[row.direction] ?: 0
            nextOrder[row.direction] = order + 1
            CategoryEntity(
                id = nextCategoryId(),
                ledgerId = ledgerId,
                name = row.name,
                emoji = row.emoji,
                direction = row.direction,
                sortOrder = order,
                builtIn = true
            ).touched()
        }
        categoryDao.insertAll(rows)
        return rows.size
    }

    private suspend fun seedCategories(ledgerId: Long, scene: ScenePreset) {
        val rows = mutableListOf<CategoryEntity>()
        scene.expenses.forEachIndexed { i, c ->
            rows += CategoryEntity(
                id = nextCategoryId(),
                ledgerId = ledgerId,
                name = c.name,
                emoji = c.emoji,
                direction = Direction.EXPENSE,
                sortOrder = i,
                builtIn = true
            ).touched()
        }
        scene.incomes.forEachIndexed { i, c ->
            rows += CategoryEntity(
                id = nextCategoryId(),
                ledgerId = ledgerId,
                name = c.name,
                emoji = c.emoji,
                direction = Direction.INCOME,
                sortOrder = i,
                builtIn = true
            ).touched()
        }
        if (rows.isNotEmpty()) categoryDao.insertAll(rows)
    }

    // ---------------- 流水 ----------------

    fun transactions(ledgerId: Long): Flow<List<TransactionEntity>> = txnDao.observeByLedger(ledgerId)

    fun transactionsInRange(ledgerId: Long, from: Long, to: Long) = txnDao.observeByLedgerInRange(ledgerId, from, to)

    fun recent(limit: Int = 40) = txnDao.observeRecent(limit)

    /** v5.9：全部账本的流水（全局搜索） */
    val allTransactionsFlow: Flow<List<TransactionEntity>> = txnDao.observeEvery()

    fun total(ledgerId: Long, direction: Int): Flow<Long> = txnDao.observeTotal(ledgerId, direction)

    fun totalInRange(ledgerId: Long, direction: Int, from: Long, to: Long) =
        txnDao.observeTotalInRange(ledgerId, direction, from, to)

    fun balances() = txnDao.observeBalances()

    fun categoryTotals(ledgerId: Long, direction: Int, from: Long, to: Long) =
        txnDao.observeCategoryTotals(ledgerId, direction, from, to)

    fun monthlyTotals(ledgerId: Long) = txnDao.observeMonthlyTotals(ledgerId)

    fun dailyTotals(ledgerId: Long, direction: Int, from: Long, to: Long) =
        txnDao.observeDailyTotals(ledgerId, direction, from, to)

    fun txnCount(ledgerId: Long) = txnDao.observeCount(ledgerId)

    fun reimburseSum(ledgerId: Long, state: Int) = txnDao.observeReimburseSum(ledgerId, state)

    /** v7.1 垫付/回款：各状态金额+笔数一次拿全 */
    fun reimburseStateTotals(ledgerId: Long) = txnDao.observeReimburseStateTotals(ledgerId)

    fun reimburseList(ledgerId: Long, state: Int) = txnDao.observeReimburseList(ledgerId, state)

    suspend fun getTransaction(id: Long) = txnDao.getById(id)

    suspend fun addTransaction(
        ledgerId: Long,
        categoryId: Long?,
        categoryName: String,
        categoryEmoji: String,
        direction: Int,
        amountCents: Long,
        note: String,
        occurredAt: Long,
        extra: Map<String, String> = emptyMap(),
        reimburseState: Int = ReimburseState.NA,
        projectId: Long = 0L,
        accountId: Long = 0L
    ): Long {
        val id = nextTxnId()
        return txnDao.insert(
            TransactionEntity(
                id = id,
                ledgerId = ledgerId,
                categoryId = categoryId,
                categoryName = categoryName,
                categoryEmoji = categoryEmoji,
                direction = direction,
                amountCents = amountCents,
                note = note,
                occurredAt = occurredAt,
                extraJson = encodeExtra(extra),
                reimburseState = reimburseState,
                projectId = projectId,
                accountId = accountId
            ).touched()
        )
    }

    suspend fun updateTransaction(txn: TransactionEntity) = txnDao.update(txn.touched())

    /**
     * 批量写入（v9.7.2，账单导入用）。
     *
     * 单事务插入：原来导入是逐条 `addTransaction`，一千行账单 = 一千个事务，
     * 进度条会长时间不动（用户以为卡死）。顺带保证「要么全进、要么全不进」。
     */
    suspend fun addTransactionsBatch(items: List<TransactionEntity>) {
        if (items.isEmpty()) return
        db.runInDbTransaction {
            items.forEach { txnDao.insert(it.copy(id = nextTxnId()).touched()) }
        }
    }

    /** v11 软删（SpeedEntry 撤销 / 流水删除；墓碑保留供同步传播） */
    suspend fun deleteTransaction(id: Long) = txnDao.softDelete(id, now(), device())

    suspend fun setReimburseState(ids: List<Long>, state: Int) =
        txnDao.updateReimburseState(ids, state, now(), device())

    // ---------------- v5.6 批量改账 ----------------
    //
    // 「改一笔」要进编辑页存一次，「改十笔」就要重复十遍 —— 这是记账 App 最大的效率黑洞。
    // 批量操作把「多选 → 一次改完」变成可能，尤其适合：事后补记、分类重整、报销状态推进。

    /** 批量改分类（分类名/图标冗余同步，分类被删后历史流水仍可读） */
    suspend fun setCategoryBulk(
        ids: List<Long>,
        categoryId: Long?,
        categoryName: String,
        categoryEmoji: String
    ) = txnDao.setCategoryBulk(ids, categoryId, categoryName, categoryEmoji, now(), device())

    /** 批量归入/移出项目（0 = 未归类） */
    suspend fun setProjectBulk(ids: List<Long>, projectId: Long) =
        txnDao.setProjectBulk(ids, projectId, now(), device())

    /** 批量软删（调用方必须先二次确认；墓碑保留供同步传播） */
    suspend fun deleteTransactions(ids: List<Long>) = txnDao.softDeleteMany(ids, now(), device())

    /** v5.6：该账本最近用过的分类（记一笔时默认带出，省一次点击） */
    suspend fun lastCategoryId(ledgerId: Long, direction: Int): Long? =
        txnDao.lastCategoryId(ledgerId, direction)

    // ---------------- 周期性记账（v5.7）----------------

    /** 该账本全部周期项（启用的排前面） */
    fun recurring(ledgerId: Long): Flow<List<RecurringEntity>> = recurringDao.observeByLedger(ledgerId)

    /** 已到期、等着入账的周期项 */
    fun recurringDue(ledgerId: Long, now: Long): Flow<List<RecurringEntity>> =
        recurringDao.observeDue(ledgerId, now)

    suspend fun createRecurring(item: RecurringEntity): Long =
        recurringDao.insert(item.copy(id = nextRecurringId()).touched())

    suspend fun updateRecurring(item: RecurringEntity) = recurringDao.update(item.touched())

    /** v11 软删（墓碑保留供同步传播） */
    suspend fun deleteRecurring(id: Long) = recurringDao.softDelete(id, now(), device())

    suspend fun setRecurringEnabled(id: Long, enabled: Boolean) =
        recurringDao.setEnabled(id, enabled, now(), device())

    /**
     * 把一条到期项入账。
     *
     * **可能补多期**：如果用户两个月没打开 App，「每月」的房租会积压两期，
     * 这里逐期写入（每笔的 `occurredAt` 用各自那一期的日期，不是今天），上限 12 期防异常。
     *
     * @return 实际入账的笔数
     */
    suspend fun postRecurring(item: RecurringEntity, now: Long): Int = db.runInDbTransaction {
        var next = item.nextDueAt
        var count = 0
        while (next <= now && count < 12) {
            txnDao.insert(
                TransactionEntity(
                    id = nextTxnId(),
                    ledgerId = item.ledgerId,
                    categoryId = item.categoryId,
                    categoryName = item.categoryName,
                    categoryEmoji = item.categoryEmoji,
                    direction = item.direction,
                    amountCents = item.amountCents,
                    note = if (item.note.isBlank()) item.name else item.note,
                    occurredAt = next,
                    reimburseState = ReimburseState.NA,
                    projectId = item.projectId
                ).touched()
            )
            next = RecurringSchedule.advance(next, item.intervalKind)
            count++
        }
        if (count > 0) recurringDao.setNextDue(item.id, next, this@LedgerRepository.now(), device())
        count
    }

    /** 跳过本次：不入账，只把下次日期推到未来 */
    suspend fun skipRecurring(item: RecurringEntity, now: Long) {
        val next = RecurringSchedule.advancePast(item.nextDueAt, item.intervalKind, now)
        recurringDao.setNextDue(item.id, next, this@LedgerRepository.now(), device())
    }

    /** 入账所有到期项，返回总笔数 */
    /**
     * 补记某账本所有到期的周期项。
     *
     * v9.7.2：整体包一个事务 —— 原来每条各自提交，第 3 条失败时前 2 条已经落账，
     * 用户看到的是"部分补记"，而且没法回滚重来。
     */
    suspend fun postAllRecurring(ledgerId: Long, now: Long): Int {
        val due = recurringDao.observeDue(ledgerId, now).first()
        var total = 0
        db.runInDbTransaction {
            due.forEach { total += postRecurring(it, now) }
        }
        return total
    }

    // ---------------- 项目 / 空间（v5.1） ----------------

    fun projects(ledgerId: Long): Flow<List<ProjectEntity>> = projectDao.observeByLedger(ledgerId)

    /** 导出 / 备份用：一次性取全部项目 */
    suspend fun allProjectsOnce(): List<ProjectEntity> = projectDao.listEvery()

    /** 某账本各项目的支出合计与笔数 */
    fun projectTotals(ledgerId: Long): Flow<List<ProjectAgg>> = txnDao.observeProjectTotals(ledgerId)

    /** 某账本各项目 × 报销状态的合计（项目汇总卡的三格） */
    fun projectStateTotals(ledgerId: Long): Flow<List<ProjectStateAgg>> =
        txnDao.observeProjectStateTotals(ledgerId)

    suspend fun createProject(
        ledgerId: Long,
        name: String,
        emoji: String = "📁",
        colorHex: String = "#1D3B57",
        budgetCents: Long = 0L,
        note: String = ""
    ): Long {
        val id = nextProjectId()
        return projectDao.insert(
            ProjectEntity(
                id = id,
                ledgerId = ledgerId,
                name = name,
                emoji = emoji,
                colorHex = colorHex,
                budgetCents = budgetCents,
                note = note
            ).touched()
        )
    }

    suspend fun updateProject(project: ProjectEntity) = projectDao.update(project.touched())

    /** 删项目：先把归属它的流水退回"未归类"，再软删项目（不能连带删用户的钱） */
    suspend fun deleteProject(projectId: Long) = db.runInDbTransaction {
        txnDao.clearProject(projectId, this@LedgerRepository.now(), device())
        projectDao.softDelete(projectId, this@LedgerRepository.now(), device())
    }

    suspend fun setTransactionProject(txnId: Long, projectId: Long) =
        txnDao.setProject(txnId, projectId, now(), device())

    // ---------------- 资产账户（v6.1）----------------
    //
    // 账户管的是**存量**（我现在有多少钱），流水管的是**流量**。
    // 只看流水永远不知道自己手里有多少钱 —— 这是很多记账 App 用户
    // 最终流失到资产管理 App 的原因。余额不在库里存，实时推算（见 accountBalanceCents）。

    fun accounts(ledgerId: Long): Flow<List<AccountEntity>> = accountDao.observeByLedger(ledgerId)

    suspend fun allAccountsOnce(): List<AccountEntity> = accountDao.listEvery()

    suspend fun createAccount(account: AccountEntity): Long =
        accountDao.insert(account.copy(id = nextAccountId()).touched())

    suspend fun updateAccount(account: AccountEntity) = accountDao.update(account.touched())

    /** 删账户：先把它的流水退回「未指定」，再软删账户（不能连带删用户的钱） */
    suspend fun deleteAccount(accountId: Long) = db.runInDbTransaction {
        txnDao.clearAccount(accountId, this@LedgerRepository.now(), device())
        accountDao.softDelete(accountId, this@LedgerRepository.now(), device())
    }

    suspend fun setTransactionAccount(txnId: Long, accountId: Long) =
        txnDao.setAccount(txnId, accountId, now(), device())

    suspend fun setTransactionAccountBulk(ids: List<Long>, accountId: Long) =
        txnDao.setAccountBulk(ids, accountId, now(), device())

    /**
     * 某个账户的当前余额（分）。
     *
     * `初始余额 + Σ收入 − Σ支出`。**不落库**，所以改一笔流水余额立刻跟着变，
     * 不会出现「余额 500 但流水加起来是 620」的对账灾难。
     */
    suspend fun accountBalanceCents(account: AccountEntity): Long {
        val totals = txnDao.accountTotals(account.id)
        val income = totals.firstOrNull { it.direction == Direction.INCOME }?.totalCents ?: 0L
        val expense = totals.firstOrNull { it.direction == Direction.EXPENSE }?.totalCents ?: 0L
        return account.initialBalanceCents + income - expense
    }

    /** 一次算完所有账户的余额（资产总览用，避免 N+1 查询） */
    suspend fun accountBalances(): Map<Long, Long> {
        val sums = txnDao.accountTotalsAll()
            .groupBy { it.accountId }
            .mapValues { (_, v) ->
                val inc = v.firstOrNull { it.direction == Direction.INCOME }?.totalCents ?: 0L
                val exp = v.firstOrNull { it.direction == Direction.EXPENSE }?.totalCents ?: 0L
                inc - exp
            }
        return accountDao.listEvery().associate { acc ->
            acc.id to (acc.initialBalanceCents + (sums[acc.id] ?: 0L))
        }
    }

    // ---------------- 初始化 / 维护 ----------------

    /**
     * 取全量数据（导出用）。
     */
    suspend fun allLedgersOnce(): List<LedgerEntity> = ledgerDao.listAll()

    suspend fun allTransactionsOnce(): List<TransactionEntity> = txnDao.listAll()

    suspend fun isDatabaseEmpty(): Boolean = ledgerDao.count() == 0

    // ---------------- 备份 / 恢复 ----------------

    /** 打一个完整快照 */
    suspend fun snapshot(): BackupSnapshot {
        val ledgers = ledgerDao.listAll() // WHERE deletedAt = 0
        val alive = ledgers.mapTo(HashSet(ledgers.size * 2)) { it.id }
        // ⚠️ v9.7.2 修 transactions 的过滤；v10.3 把 categories/projects/recurring/accounts
        // 也按 alive 过滤 —— 原来 listEvery() 全量导出，软删账本的附属数据进备份后
        // 恢复时 ledgerIdMap miss 被丢弃，「导出 N / 恢复 M」对不上。
        return BackupSnapshot(
            exportedAt = now(),
            appVersion = APP_VERSION_NAME,
            ledgers = ledgers,
            categories = categoryDao.listEvery().filter { it.ledgerId in alive },
            transactions = txnDao.listAll().filter { it.ledgerId in alive },
            projects = projectDao.listEvery().filter { it.ledgerId in alive },
            recurring = recurringDao.listEvery().filter { it.ledgerId in alive },
            accounts = accountDao.listEvery().filter { it.ledgerId in alive },
            goals = goalDao.listEvery() // 已含 deletedAt = 0
        )
    }

    /**
     * 用快照**替换**整库内容。
     *
     * 关键点是 id 重映射：主键都是 autoGenerate，插入后拿到的新 id 未必等于备份里的旧 id，
     * 所以要把 账本id -> 新账本id、分类id -> 新分类id 两层级联映射过去，
     * 否则分类会挂错账本、流水会挂错分类 —— 那就不是「完美恢复」了。
     *
     * 整个过程在单个事务里，中途失败会整体回滚，不会留下半截数据。
     */
    suspend fun restoreFrom(snapshot: BackupSnapshot): RestoreStats = db.runInDbTransaction {
        // ⚠️ v9.7.1 修：原来漏了 goals —— 恢复会把快照里的目标**追加**到现有目标后面，
        // 恢复两次就变成两份，与"用这份备份覆盖当前数据"的承诺不符。
        goalDao.deleteAll()
        recurringDao.deleteAll()
        txnDao.deleteAll()
        accountDao.deleteAll()
        projectDao.deleteAll()
        categoryDao.deleteAll()
        ledgerDao.deleteAll()

        insertSnapshot(snapshot, ledgerNameSuffix = null)
    }

    /**
     * **追加**恢复（v16.2.7）：把备份内容当成**新账本**塞进来，当前数据一条不动。
     *
     * 为什么需要它：恢复只提供"覆盖"这一条路时，用户导出一份旧备份想翻一翻旧账，
     * 代价是把当前账本全换掉 —— 只能先导出当前数据、再恢复、再恢复回来，三步且危险。
     * 有了追加，备份就变成"可以摆在一起看的另一摞账"。
     *
     * 账本名加后缀区分（`报销账本（恢复）`），否则两个同名账本在列表里根本分不清。
     */
    suspend fun restoreAsNewLedgers(
        snapshot: BackupSnapshot,
        suffix: String = "恢复"
    ): RestoreStats = db.runInDbTransaction {
        insertSnapshot(snapshot, ledgerNameSuffix = suffix)
    }

    /**
     * 把快照按 id 重映射写进当前库（**不清库**，由调用方决定是否先清）。
     *
     * 关键点是 id 重映射：主键都是 autoGenerate，插入后拿到的新 id 未必等于备份里的旧 id，
     * 所以要把 账本id -> 新账本id、分类id -> 新分类id 两层级联映射过去，
     * 否则分类会挂错账本、流水会挂错分类 —— 那就不是「完美恢复」了。
     */
    private suspend fun insertSnapshot(
        snapshot: BackupSnapshot,
        /** null = 保持原账本名（覆盖恢复）；非 null = 追加后缀（作为新账本恢复） */
        ledgerNameSuffix: String?
    ): RestoreStats {
        val ledgerIdMap = HashMap<Long, Long>(snapshot.ledgers.size * 2)
        snapshot.ledgers.forEach { old ->
            // v11：恢复出来的行全部打新戳 —— 备份里没有 updatedAt，不打戳就进不了水位线增量，
            // 恢复后首同步会漏发这些数据。
            val name = if (ledgerNameSuffix == null) old.name else "${old.name}（$ledgerNameSuffix）"
            ledgerIdMap[old.id] =
                ledgerDao.insert(old.copy(id = nextLedgerId(), name = name).touched())
        }

        val categoryIdMap = HashMap<Long, Long>(snapshot.categories.size * 2)
        snapshot.categories.forEach { old ->
            val newLedgerId = ledgerIdMap[old.ledgerId] ?: return@forEach
            categoryIdMap[old.id] =
                categoryDao.insert(old.copy(id = nextCategoryId(), ledgerId = newLedgerId).touched())
        }

        // v5.1：项目同样要重映射 id（老备份没有 projects 数组 → 空表，不影响恢复）
        val projectIdMap = HashMap<Long, Long>(snapshot.projects.size * 2)
        snapshot.projects.forEach { old ->
            val newLedgerId = ledgerIdMap[old.ledgerId] ?: return@forEach
            projectIdMap[old.id] =
                projectDao.insert(old.copy(id = nextProjectId(), ledgerId = newLedgerId).touched())
        }

        // v6.1：账户同样要重映射（老备份没有 accounts 数组 → 空表，流水 accountId 退回 0）
        val accountIdMap = HashMap<Long, Long>(snapshot.accounts.size * 2)
        snapshot.accounts.forEach { old ->
            val newLedgerId = ledgerIdMap[old.ledgerId] ?: return@forEach
            accountIdMap[old.id] =
                accountDao.insert(old.copy(id = nextAccountId(), ledgerId = newLedgerId).touched())
        }

        // v9.2：存钱目标同样重映射（账本/账户找不到就退化为未指定，目标本身不丢）
        //
        // ⚠️ v9.7.2 修：v9.7 起目标可以**不关联任何账本**（ledgerId = null）。
        // 原来这里写 `ledgerIdMap[old.ledgerId] ?: return@forEach` —— 独立目标的
        // 键是 null，查 map 必然 miss → **所有独立目标在恢复时被静默丢掉**。
        // 顺手把「关联的账本在快照里不存在」也降级成独立，而不是删掉目标。
        snapshot.goals.forEach { old ->
            val newLedgerId = old.ledgerId?.let { ledgerIdMap[it] }
            val newAccountId = if (old.accountId > 0L) accountIdMap[old.accountId] ?: 0L else 0L
            goalDao.insert(
                old.copy(id = nextGoalId(), ledgerId = newLedgerId, accountId = newAccountId).touched()
            )
        }

        var txnRestored = 0
        snapshot.transactions.forEach { old ->
            val newLedgerId = ledgerIdMap[old.ledgerId] ?: return@forEach
            val newCategoryId = old.categoryId?.let { categoryIdMap[it] }
            // 项目找不到（老备份 / 项目被删）时归为未归类，绝不丢这笔流水
            val newProjectId = if (old.projectId > 0L) projectIdMap[old.projectId] ?: 0L else 0L
            // 账户找不到（老备份 / 账户被删）时归为「未指定」，同样绝不丢流水
            val newAccountId = if (old.accountId > 0L) accountIdMap[old.accountId] ?: 0L else 0L
            txnDao.insert(
                old.copy(
                    id = nextTxnId(),
                    ledgerId = newLedgerId,
                    categoryId = newCategoryId,
                    projectId = newProjectId,
                    accountId = newAccountId
                ).touched()
            )
            txnRestored++
        }

        // v5.7：周期项也要跟着重映射（分类/项目找不到就退化为「无分类/未归类」，
        // 周期项本身绝不丢 —— 那等于让用户的房租从待办里消失）
        snapshot.recurring.forEach { old ->
            val newLedgerId = ledgerIdMap[old.ledgerId] ?: return@forEach
            val newCategoryId = old.categoryId?.let { categoryIdMap[it] }
            val newProjectId = if (old.projectId > 0L) projectIdMap[old.projectId] ?: 0L else 0L
            recurringDao.insert(
                old.copy(
                    id = nextRecurringId(),
                    ledgerId = newLedgerId,
                    categoryId = newCategoryId,
                    projectId = newProjectId
                ).touched()
            )
        }

        return RestoreStats(
            ledgerCount = ledgerIdMap.size,
            categoryCount = categoryIdMap.size,
            txnCount = txnRestored
        )
    }

    /** 只清流水：全表软删（墓碑保留 —— 启用同步后，另一端不会再把它推回来） */
    suspend fun wipeAll() {
        txnDao.softDeleteAll(now(), device())
    }

    suspend fun wipeEverything() {
        // v9.7.1：补上 goals（"清空全部数据"后目标还留着，属于没清干净）；
        // 去掉重复的 accountDao.deleteAll()
        //
        // v9.7.2：包一个事务 —— 7 张表逐条删，中途失败会留下"半清空"的库
        // （比如流水清了但分类还在），用户下次记账直接看到一堆孤立分类。
        db.runInDbTransaction {
            goalDao.deleteAll()
            accountDao.deleteAll()
            recurringDao.deleteAll()
            txnDao.deleteAll()
            projectDao.deleteAll()
            categoryDao.deleteAll()
            ledgerDao.deleteAll()
        }
    }

    companion object {
        fun encodeExtra(map: Map<String, String>): String {
            if (map.isEmpty()) return ""
            val obj = JSONObject()
            map.forEach { (k, v) -> if (v.isNotBlank()) obj.put(k, v) }
            return obj.toString()
        }

        fun decodeExtra(json: String): Map<String, String> {
            if (json.isBlank()) return emptyMap()
            return try {
                val obj = JSONObject(json)
                buildMap {
                    obj.keys().forEach { k -> put(k, obj.optString(k)) }
                }
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }
}
