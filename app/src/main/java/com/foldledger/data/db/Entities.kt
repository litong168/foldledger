package com.foldledger.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 方向：支出 / 收入 */
object Direction {
    const val EXPENSE = 0
    const val INCOME = 1
}

/** 报销状态 */
object ReimburseState {
    const val NA = -1      // 场景不适用
    const val PENDING = 0  // 待提交
    const val SUBMITTED = 1// 已提交
    const val SETTLED = 2  // 已到账
}

/**
 * 账本（分账本）。每个账本绑定一个「记账场景」，并持有自己的一套分类。
 */
@Entity(tableName = "ledgers")
data class LedgerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    /** 场景 key，对应 ScenePresets */
    val sceneKey: String,
    val emoji: String,
    val colorHex: String,
    /** 预算（分），0 表示未设置 */
    val budgetCents: Long = 0L,
    val note: String = "",
    val archived: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * v8.1 回收站：0 = 正常；>0 = 移入回收站的时间戳。
     *
     * 删账本走软删 —— 误删是记账 App 最不可逆的事故，先丢进回收站，
     * 随时能捞回来（流水/分类/项目都原样保留）；彻底删除才真正级联清掉。
     */
    val deletedAt: Long = 0L
)

/**
 * 分类。归属某个账本，支持收入/支出两个方向。
 */
@Entity(
    tableName = "categories",
    foreignKeys = [
        ForeignKey(
            entity = LedgerEntity::class,
            parentColumns = ["id"],
            childColumns = ["ledgerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("ledgerId"), Index(value = ["ledgerId", "direction", "name"], unique = true)]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ledgerId: Long,
    val name: String,
    val emoji: String,
    val direction: Int,
    val sortOrder: Int = 0,
    val builtIn: Boolean = false,
    /** 分类月度预算（分），0 表示未设置 —— v2.1 新增 */
    val budgetCents: Long = 0L
)

/**
 * v9.2 存钱目标（心愿罐）。
 *
 * 记账是"记录已经花掉的"，存钱目标补上另一半："为将来攒钱"。
 * 目标挂在账本下（每个账本可以有多个目标），进度独立于流水 ——
 * 攒钱不是支出，混进流水里会污染账目，所以单独一张表。
 */
@Entity(
    tableName = "goals",
    foreignKeys = [ForeignKey(
        entity = LedgerEntity::class,
        parentColumns = ["id"],
        childColumns = ["ledgerId"],
        // v9.7：关联是**可选**的，删掉账本不该连带删掉目标 —— 改成 SET_NULL（解绑即可）
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index("ledgerId")]
)
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /**
     * v9.7：**null = 独立目标**（不归属任何账本）。
     *
     * 存钱目标默认是独立的 —— 攒的钱本来就不在账本里（不写进流水），
     * 硬绑一个账本只是徒增心智负担。真要关联（比如"装修目标挂在装修账本"），
     * 这只是个方便筛选的标签，删掉账本时目标会解绑而不是被删掉。
     */
    val ledgerId: Long? = null,
    val name: String,
    val emoji: String = "🎯",
    /** 目标金额（分） */
    val targetCents: Long,
    /** 已存金额（分） */
    val savedCents: Long = 0L,
    /** 目标日期（毫秒），0 = 不设期限 */
    val targetDate: Long = 0L,
    /** 关联账户 id（0 = 不关联） */
    val accountId: Long = 0L,
    val note: String = "",
    /** 达成后归档，不再出现在进行中的列表 */
    val archived: Boolean = false,
    /** v9.4 回收站：0 = 正常，>0 = 删除时间戳（软删，可还原） */
    val deletedAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 项目 / 空间（v5.1）。
 *
 * 报销账本里把「某一次差旅」「某一次招待」这类**一次性的费用归集**单独圈起来：
 * 一个项目 = 一堆流水 + 自己的预算/备注/结算状态，方便整批报销对账。
 * 归属某个账本，账本删了它跟着删。
 */
@Entity(
    tableName = "projects",
    foreignKeys = [
        ForeignKey(
            entity = LedgerEntity::class,
            parentColumns = ["id"],
            childColumns = ["ledgerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("ledgerId")]
)
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ledgerId: Long,
    val name: String,
    val emoji: String = "📁",
    val colorHex: String = "#1D3B57",
    /** 项目预算（分），0 表示未设置 */
    val budgetCents: Long = 0L,
    val note: String = "",
    /** 已结束的项目在切换条里压暗，不再默认参与「进行中」统计 */
    val closed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 资产账户（v6.1）。
 *
 * 现金 / 储蓄卡 / 信用卡 / 电子钱包 —— 一笔流水「从哪里出、进哪里」。
 *
 * 账户的价值不在记账本身，而在**回答「我到底有多少钱、钱在哪」**：
 * 记账管的是流水，账户管的是**存量**。只看流水永远不知道自己现在手里有多少钱，
 * 这是很多记账 App 用户最终流失到资产管理 App 的原因。
 *
 * 当前余额 = [initialBalanceCents] + 该账户全部流水的（收入 − 支出），
 * 由查询实时推算，不落库 —— 避免改流水后余额对不上的经典问题。
 */
@Entity(
    tableName = "accounts",
    foreignKeys = [
        ForeignKey(
            entity = LedgerEntity::class,
            parentColumns = ["id"],
            childColumns = ["ledgerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("ledgerId")]
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ledgerId: Long,
    val name: String,
    /** 账户类型，见 [AccountType] */
    val type: Int = AccountType.SAVINGS,
    val emoji: String = "💳",
    val colorHex: String = "#1D3B57",
    /** 初始余额（分）。可以为负（信用卡欠款、房贷卡） */
    val initialBalanceCents: Long = 0L,
    /** 是否计入「净资产」汇总（负债账户可关掉单独看） */
    val includeInNetWorth: Boolean = true,
    val note: String = "",
    val closed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/** 账户类型 */
object AccountType {
    const val CASH = 0
    const val SAVINGS = 1
    const val CREDIT = 2
    const val WALLET = 3
    const val OTHER = 4

    fun label(t: Int): String = when (t) {
        CASH -> "现金"
        SAVINGS -> "储蓄卡"
        CREDIT -> "信用卡"
        WALLET -> "电子钱包"
        else -> "其他"
    }

    /** 默认 emoji（新建账户时按类型给个贴切的图标） */
    fun defaultEmoji(t: Int): String = when (t) {
        CASH -> "💵"
        SAVINGS -> "💳"
        CREDIT -> "💳"
        WALLET -> "📱"
        else -> "🏦"
    }
}

/**
 * 流水。
 * 分类名称/图标做冗余存储，保证分类被删除后历史流水仍可读。
 */
@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = LedgerEntity::class,
            parentColumns = ["id"],
            childColumns = ["ledgerId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("ledgerId"), Index("categoryId"), Index("occurredAt"), Index("projectId"), Index("accountId")]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ledgerId: Long,
    val categoryId: Long? = null,
    val categoryName: String = "未分类",
    val categoryEmoji: String = "📦",
    val direction: Int = Direction.EXPENSE,
    val amountCents: Long,
    val note: String = "",
    val occurredAt: Long,
    /** 场景专属字段，JSON 字符串（如报销单号、房间、里程） */
    val extraJson: String = "",
    /** 仅报销场景生效 */
    val reimburseState: Int = ReimburseState.NA,
    /** v5.1：归属的项目/空间；0 = 未归入任何项目 */
    val projectId: Long = 0L,
    /** v6.1：这笔钱走的账户；0 = 未指定（历史流水默认未指定） */
    val accountId: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)

// ---------------- 统计投影 ----------------

data class CategoryTotal(
    val name: String,
    val emoji: String,
    val totalCents: Long,
    val count: Int
)

data class MonthTotal(
    val month: String,
    val direction: Int,
    val totalCents: Long
)

data class LedgerBalance(
    val ledgerId: Long,
    val direction: Int,
    val totalCents: Long
)

/**
 * 账户收支汇总（v6.1，余额推算用）。
 *
 * 当前余额 = `initialBalanceCents + Σ(收入) − Σ(支出)`，这个投影就是"Σ"那一段。
 */
data class AccountTotal(
    val accountId: Long,
    val direction: Int,
    val totalCents: Long
)

/** 项目汇总：支出合计 + 笔数（v5.1） */
data class ProjectAgg(
    val projectId: Long,
    val totalCents: Long,
    val count: Int
)

/** 项目 × 报销状态汇总（v5.1） */
data class ProjectStateAgg(
    val projectId: Long,
    val state: Int,
    val totalCents: Long,
    val count: Int
)

/**
 * 账本级「报销状态」汇总（v7.1 垫付/回款）。
 *
 * 报销账本真正的主心骨不是"花了多少"，而是**钱垫出去多少、回来了多少**——
 * 这个投影按状态给出金额+笔数，UI 层据此推「尚余垫付」和「已回款」。
 */
data class ReimburseStateAgg(
    val state: Int,
    val totalCents: Long,
    val count: Int
)

/** 周期（v5.7 固定支出） */
object RecurringInterval {
    const val MONTHLY = 0
    const val WEEKLY = 1
    const val YEARLY = 2

    fun label(kind: Int): String = when (kind) {
        WEEKLY -> "每周"
        YEARLY -> "每年"
        else -> "每月"
    }
}


/** v9.3 全局：某账本在时间段内的收支汇总 */
data class LedgerTotalAgg(
    val ledgerId: Long,
    val direction: Int,
    val totalCents: Long
)

/** v9.3 全局：某账本的报销状态汇总 */
data class LedgerReimburseAgg(
    val ledgerId: Long,
    val state: Int,
    val totalCents: Long,
    val count: Int
)

/**
 * 周期性记账（v5.7）。房租 / 会员 / 房贷这类固定支出。
 *
 * 设计取舍：
 *  - **不自动往流水表塞数据**。到期的项只在明细页顶部提示"3 笔待入账"，
 *    由用户点"全部入账"才落账。理由是固定支出金额偶尔会变（水电费、房租涨跌），
 *    静默写进流水反而要人去翻改；确认一次的成本很低，但账目可信度高得多。
 *  - [nextDueAt] 既是"下次该记的日子"也是"上次该记的日子"—— 入账时用它当
 *    `occurredAt`，账目就落在该落的那天，而不是"我打开 App 的那天"。
 *  - 长期没打开 App 时同一项可能积压多期：入账时**补足每一期**（上限 12 期），
 *    否则房租会漏账。
 */
@Entity(
    tableName = "recurring",
    foreignKeys = [
        ForeignKey(
            entity = LedgerEntity::class,
            parentColumns = ["id"],
            childColumns = ["ledgerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("ledgerId"), Index("nextDueAt")]
)
data class RecurringEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ledgerId: Long,
    /** 显示名，如「房租」「网易云会员」 */
    val name: String,
    val categoryId: Long? = null,
    val categoryName: String = "",
    val categoryEmoji: String = "📦",
    val direction: Int = Direction.EXPENSE,
    val amountCents: Long,
    val note: String = "",
    /** 见 [RecurringInterval] */
    val intervalKind: Int = RecurringInterval.MONTHLY,
    /** 下次应该记账的时间戳（毫秒） */
    val nextDueAt: Long,
    val enabled: Boolean = true,
    val projectId: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)
