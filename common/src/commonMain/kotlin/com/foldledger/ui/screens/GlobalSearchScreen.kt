package com.foldledger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldledger.data.db.Direction
import com.foldledger.data.db.LedgerEntity
import com.foldledger.data.db.TransactionEntity
import com.foldledger.ui.adaptive.AdaptiveScaffold
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.Haptic
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.components.CategoryIcon
import com.foldledger.ui.theme.LocalDirectionColors
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.Space
import com.foldledger.ui.theme.SurfaceCard
import com.foldledger.ui.theme.cardSurface
import com.foldledger.ui.vm.GlobalSearchViewModel
import com.foldledger.util.DateExt
import com.foldledger.ui.theme.Layout
import com.foldledger.util.Money
import com.foldledger.ui.l10n.t

/**
 * 全局搜索（v5.9）：跨账本找一笔账。
 *
 * 为什么单独做一页而不是加强明细页的搜索框：
 * 「我这个月打车一共花了多少」这类问题，用户**不记得那笔账在哪个账本里**。
 * 明细页的搜索天然被当前账本限死，只能一页页翻。全局搜索把范围放开，
 * 再叠上账本 / 收支 / 时间三个筛选 —— 这才是"查账"真正需要的东西。
 */
@Composable
fun GlobalSearchScreen(
    env: FoldEnv,
    onBack: () -> Unit,
    /** 点某条搜索结果：切到那笔所在的账本并打开它的编辑浮层 */
    onPickResult: (ledgerId: Long, txnId: Long) -> Unit
) {
    val vm: GlobalSearchViewModel = viewModel(factory = GlobalSearchViewModel.factory())
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val ledgers by vm.ledgers.collectAsStateWithLifecycle()
    val ledgerFilter by vm.ledgerFilter.collectAsStateWithLifecycle()
    val directionFilter by vm.directionFilter.collectAsStateWithLifecycle()
    val rangeFilter by vm.rangeFilter.collectAsStateWithLifecycle()

    AdaptiveScaffold(
        env = env,
        title = t("搜索"),
        subtitle = if (results.isEmpty()) t("跨账本查找任意一笔") else "找到 ${results.size} 笔",
        onBack = onBack
    ) { padding ->
        val side = Layout.sidePadding(env.widthDp)
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                placeholder = { Text(t("搜分类 / 备注 / 金额，空格分开多个词"), fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(Radius.md),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = side, vertical = 6.dp)
            )

            // 三类筛选：账本 / 收支 / 时间
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = side, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    label = t("全部账本"),
                    selected = ledgerFilter == 0L,
                    onClick = { vm.setLedgerFilter(0L) }
                )
                ledgers.forEach { l ->
                    FilterChip(
                        label = "${l.emoji} ${l.name}",
                        selected = ledgerFilter == l.id,
                        onClick = { vm.setLedgerFilter(l.id) }
                    )
                }
            }
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = side),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(t("全部"), directionFilter < 0) { vm.setDirectionFilter(-1) }
                FilterChip(t("支出"), directionFilter == Direction.EXPENSE) {
                    vm.setDirectionFilter(Direction.EXPENSE)
                }
                FilterChip(t("收入"), directionFilter == Direction.INCOME) {
                    vm.setDirectionFilter(Direction.INCOME)
                }
                Box(Modifier.width(2.dp))
                FilterChip(t("不限时间"), rangeFilter == 0) { vm.setRangeFilter(0) }
                FilterChip(t("近 30 天"), rangeFilter == 30) { vm.setRangeFilter(30) }
                FilterChip(t("近 90 天"), rangeFilter == 90) { vm.setRangeFilter(90) }
                FilterChip(t("今年"), rangeFilter == 365) { vm.setRangeFilter(365) }
            }

            Spacer(Modifier.height(8.dp))

            if (results.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (query.isBlank()) "🔎" else "🙁", fontSize = 44.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (query.isBlank()) t("输入关键词开始查找") else t("没有符合条件的结果"),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (query.isBlank()) t("可以搜分类、备注，或者直接输金额")
                            else t("试试减少筛选条件，或换个说法"),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = side, end = side, top = 4.dp, bottom = 100.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(results, key = { it.txn.id }) { hit ->
                        SearchResultRow(hit = hit, onClick = { onPickResult(hit.txn.ledgerId, hit.txn.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                RoundedCornerShape(50)
            )
            .clickableScale(pressedScale = 0.93f, haptic = Haptic.LIGHT) { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SearchResultRow(hit: GlobalSearchViewModel.SearchHit, onClick: () -> Unit) {
    val dc = LocalDirectionColors.current
    val txn = hit.txn
    SurfaceCard(
        shape = RoundedCornerShape(Radius.md),
        color = cardSurface(),
        elevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickableScale(pressedScale = 0.985f) { onClick() }
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CategoryIcon(ref = txn.categoryEmoji, size = 36.dp, fontSize = 17)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        txn.categoryName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${hit.ledgerEmoji} ${hit.ledgerName}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    buildString {
                        append(DateExt.shortDateTime(txn.occurredAt))
                        if (txn.note.isNotBlank()) {
                            append(" · ")
                            append(txn.note)
                        }
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                (if (txn.direction == Direction.EXPENSE) "-" else "+") +
                    Money.format(txn.amountCents),
                fontSize = 15.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold,
                // v16.3.1：金额靠右且行末对齐，列表里正负号与数字不会左右晃
                textAlign = TextAlign.End,
                color = if (txn.direction == Direction.EXPENSE) dc.expense else dc.income
            )
        }
    }
}
