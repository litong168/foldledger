package com.foldledger.ui.vm

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.foldledger.ServiceLocator
import com.foldledger.data.db.CategoryEntity
import com.foldledger.data.db.Direction
import com.foldledger.data.repo.LedgerRepository
import com.foldledger.util.BillDedupe
import com.foldledger.util.BillMapper
import com.foldledger.util.BillParser
import com.foldledger.util.CategorySuggester
import com.foldledger.util.NLParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 账单导入（v9.1）——把微信/支付宝导出的账单变成本机流水。
 *
 * 三步：**选文件 → 看预览 → 一键导入**。
 * 导入时的两个讲究：
 *  - **去重**：同一账本里"同分钟 + 同金额 + 同方向 + 同对方"视为已存在，跳过（账单常重复导）；
 *  - **分类**：先问历史（上次把"星巴克"记在哪），再问关键词表，都没有就落到同向的第一个分类。
 */
class BillImportViewModel(
    private val repo: LedgerRepository,
    val ledgerId: Long
) : ViewModel() {

    /** 解析结果预览 */
    data class Preview(
        val fileName: String,
        val items: List<BillMapper.Mapped>,
        /** 与已有流水重复（将被跳过） */
        val duplicates: Int,
        /** 表里存在但识别不了的行 */
        val unreadable: Int,
        val totalRows: Int
    ) {
        val expenseCents: Long get() = items.filter { it.direction == Direction.EXPENSE }.sumOf { it.amountCents }
        val incomeCents: Long get() = items.filter { it.direction == Direction.INCOME }.sumOf { it.amountCents }
    }

    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Ready(val preview: Preview) : State
        data class Done(val imported: Int, val skipped: Int) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 读取并解析文件 */
    fun load(context: Context, uri: Uri, fileName: String) {
        _state.value = State.Loading
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@runCatching null
                    if (bytes.isEmpty()) return@runCatching null
                    val rows = BillParser.parse(bytes, fileName)
                    if (!BillMapper.looksLikeBill(rows)) return@runCatching null
                    val headerIdx = BillMapper.findHeaderRow(rows)
                    val header = rows[headerIdx]
                    val dataRows = rows.drop(headerIdx + 1).filter { r -> r.any { it.isNotBlank() } }
                    val mapped = BillMapper.mapRows(header, dataRows)
                    Triple(mapped, dataRows.size, header)
                }
            }
            val r = result.getOrNull()
            when {
                r == null -> _state.value = State.Failed("认不出这份账单。支持微信/支付宝导出的 Excel(.xlsx) 或 CSV。")
                r.first.isEmpty() -> _state.value = State.Failed("文件里没找到可导入的流水（不含收支的转账行会被自动跳过）。")
                else -> {
                    val (mapped, totalRows, _) = r
                    val dup = countDuplicates(mapped)
                    _state.value = State.Ready(
                        Preview(
                            fileName = fileName,
                            items = mapped,
                            duplicates = dup,
                            unreadable = (totalRows - mapped.size - dup).coerceAtLeast(0),
                            totalRows = totalRows
                        )
                    )
                }
            }
        }
    }

    /**
     * 已有流水里有多少条与待导入的重合。
     *
     * ⚠️ v9.7.1 修：原键里带了"对方"，但库里对应字段是**分类名**（口径不同），
     * 两边永远拼不上 → 去重完全失效，重复导入会把整份账单翻倍。
     * 现在键只取 同分钟 + 同金额 + 同方向，并按多重性计数（见 [BillDedupe]）。
     */
    private suspend fun countDuplicates(mapped: List<BillMapper.Mapped>): Int {
        val existing = repo.transactions(ledgerId).first()
        return BillDedupe.duplicateCount(
            incoming = mapped.map { BillDedupe.keyOf(it.occurredAt, it.amountCents, it.direction) },
            existing = existing.map { BillDedupe.keyOf(it.occurredAt, it.amountCents, it.direction) }
        )
    }

    /** 真正导入 */
    fun import() {
        val ready = _state.value as? State.Ready ?: return
        _state.value = State.Loading
        viewModelScope.launch {
            // v9.7.2：原来 `repo.transactions(ledgerId).first()` 连查 3 次（分类/历史/去重各一次），
            // 同一份全量流水在内存里复制三份。现在查一次复用。
            val existing = repo.transactions(ledgerId).first()
            val cats = repo.categoriesAll(ledgerId).first()
            val history = existing.take(300).map {
                CategorySuggester.Sample(it.note, it.categoryName, it.amountCents, it.occurredAt)
            }

            // 逐行的分类推断 + 关键词匹配是纯 CPU（上千行时在主线程会卡住进度动画），
            // 放到 Default 线程算完，最后一次性入库。
            val planned = withContext(Dispatchers.Default) {
                val isDup = BillDedupe.duplicates(
                    incoming = ready.preview.items.map {
                        BillDedupe.keyOf(it.occurredAt, it.amountCents, it.direction)
                    },
                    existing = existing.map {
                        BillDedupe.keyOf(it.occurredAt, it.amountCents, it.direction)
                    }
                )
                val rows = ArrayList<com.foldledger.data.db.TransactionEntity>(ready.preview.items.size)
                var skipped = 0
                ready.preview.items.forEachIndexed { index, item ->
                    if (isDup.getOrElse(index) { false }) {
                        skipped++
                        return@forEachIndexed
                    }
                    val cat = pickCategory(item, cats, history)
                    rows += com.foldledger.data.db.TransactionEntity(
                        ledgerId = ledgerId,
                        categoryId = cat?.id,
                        categoryName = cat?.name ?: defaultCategoryName(item.direction),
                        categoryEmoji = cat?.emoji ?: defaultCategoryEmoji(item.direction),
                        direction = item.direction,
                        amountCents = item.amountCents,
                        note = buildNote(item),
                        occurredAt = item.occurredAt
                    )
                }
                rows to skipped
            }

            val (rows, skipped) = planned
            // v9.7.2：单事务批量写入 —— 原来逐条 addTransaction，一千行就是一千个事务
            repo.addTransactionsBatch(rows)
            _state.value = State.Done(rows.size, skipped)
        }
    }

    fun reset() {
        _state.value = State.Idle
    }

    /** 分类推断：历史 → 关键词表 → 同向第一个分类 */
    private fun pickCategory(
        item: BillMapper.Mapped,
        cats: List<CategoryEntity>,
        history: List<CategorySuggester.Sample>
    ): CategoryEntity? {
        val same = cats.filter { it.direction == item.direction }
        if (same.isEmpty()) return null
        val probe = "${item.counterparty} ${item.note}"
        val fromHistory = CategorySuggester.suggest(probe, history)
        same.firstOrNull { it.name == fromHistory }?.let { return it }
        val hint = NLParser.extractCategoryHint(probe)
        same.firstOrNull { it.name == hint }?.let { return it }
        // 名称里含线索词的也算（比如分类叫"交通出行"、线索是"交通"）
        if (hint != null) same.firstOrNull { it.name.contains(hint) }?.let { return it }
        return same.firstOrNull { it.name == "其他支出" || it.name == "其他收入" } ?: same.firstOrNull()
    }

    private fun defaultCategoryName(direction: Int) = if (direction == Direction.INCOME) "其他收入" else "其他支出"
    private fun defaultCategoryEmoji(direction: Int) = if (direction == Direction.INCOME) "✨" else "📦"

    private fun buildNote(item: BillMapper.Mapped): String {
        val parts = ArrayList<String>()
        if (item.counterparty.isNotBlank()) parts += item.counterparty
        if (item.note.isNotBlank() && item.note != item.counterparty) parts += item.note
        if (item.payMethod.isNotBlank() && item.payMethod != "-") parts += item.payMethod
        return parts.joinToString(" · ").take(60)
    }

    companion object {
        fun factory(ledgerId: Long) = viewModelFactory {
            initializer { BillImportViewModel(ServiceLocator.repository, ledgerId) }
        }
    }
}
