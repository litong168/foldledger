package com.foldledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.foldledger.data.db.Direction
import com.foldledger.ui.adaptive.AdaptiveScaffold
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.components.EmptyHint
import com.foldledger.ui.components.SectionCard
import com.foldledger.ui.theme.LocalDirectionColors
import com.foldledger.ui.theme.Layout
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.vm.BillImportViewModel
import com.foldledger.util.DateExt
import com.foldledger.util.Money

/**
 * 账单文件选择器（平台槽）：返回一个「打开文件选择」的动作。
 *
 * Android：SAF OpenDocument（带账单 MIME 过滤）；桌面：JFileChooser。
 * 选中后回调 文件名 + 完整字节（读取由平台完成，解析在共享 VM 里）。
 */
@Composable
expect fun rememberBillFilePicker(onPicked: (name: String, bytes: ByteArray) -> Unit): () -> Unit

/**
 * 账单导入（v9.1）。
 *
 * 微信/支付宝导出的账单（.xlsx / .csv）→ 本机流水。**全程离线**，文件不出设备。
 * 导入前先看预览：多少笔、收入多少、支出多少、有多少是重复的，一目了然再决定。
 */
@Composable
fun BillImportScreen(
    env: FoldEnv,
    ledgerId: Long,
    onBack: () -> Unit
) {
    val vm: BillImportViewModel = viewModel(
        key = "bill-import-$ledgerId",
        factory = BillImportViewModel.factory(ledgerId)
    )
    val state by vm.state.collectAsStateWithLifecycle()

    // v9.7.2：这个 VM 按 "bill-import-$ledgerId" 常驻，会被多次进入复用；
    // 原来只有「失败」分支才 reset，于是第二次进来直接看到上一份文件的预览 / 上次的导入完成页。
    // 每次进入都从空白开始（选文件 → 预览 → 导入）。
    LaunchedEffect(Unit) { vm.reset() }

    val pick = rememberBillFilePicker { name, bytes -> vm.load(name, bytes) }

    AdaptiveScaffold(
        env = env,
        title = "导入账单",
        subtitle = "微信 / 支付宝导出的 Excel 或 CSV",
        onBack = onBack,
        showNavigation = false
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                is BillImportViewModel.State.Idle -> IdlePane(onPick = pick)

                is BillImportViewModel.State.Loading -> CenterPane("正在解析…")

                is BillImportViewModel.State.Failed -> FailPane(s.message) {
                    vm.reset()
                    pick()
                }

                is BillImportViewModel.State.Ready -> ReadyPane(
                    preview = s.preview,
                    onImport = { vm.import() },
                    side = Layout.sidePadding(env.widthDp)
                )

                is BillImportViewModel.State.Done -> DonePane(
                    imported = s.imported,
                    skipped = s.skipped,
                    onClose = onBack
                )
            }
        }
    }
}

@Composable
private fun IdlePane(onPick: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        EmptyHint(
            emoji = "📥",
            title = "把账单文件变成流水",
            subtitle = "微信「我 › 服务 › 钱包 › 账单 › 常见问题 › 下载账单」导出 Excel；\n支付宝「我的 › 账单 › 右上角 › 开具交易流水证明」导出 CSV。"
        )
        Spacer(Modifier.height(8.dp))
        PrimaryButton("选择账单文件", onPick)
        Spacer(Modifier.height(16.dp))
        Text(
            "全程本机解析，文件不会上传。\n转账、退款、已关闭的记录会自动跳过；重复的流水也不会重复导入。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun ReadyPane(
    preview: BillImportViewModel.Preview,
    onImport: () -> Unit,
    /** 页面左右留白（宽屏居中）—— 本 composable 拿不到 env，由调用方算好传进来 */
    side: Dp = 20.dp
) {
    val dc = LocalDirectionColors.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = side,
            end = side,
            top = 8.dp,
            bottom = 28.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard(title = "解析完成", subtitle = preview.fileName) {
                Row {
                    Stat(Modifier.weight(1f), "可导入", "${preview.items.size} 笔")
                    Stat(Modifier.weight(1f), "支出", Money.format(preview.expenseCents), dc.expense)
                    Stat(Modifier.weight(1f), "收入", Money.format(preview.incomeCents), dc.income)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    buildString {
                        append("表中可识别 ${preview.totalRows} 行")
                        if (preview.duplicates > 0) append("，其中 ${preview.duplicates} 笔与已有流水重复（会跳过）")
                        if (preview.unreadable > 0) append("，${preview.unreadable} 笔识别不了（转账/退款等，已跳过）")
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            SectionCard(title = "预览", subtitle = "前 20 笔") {
                preview.items.take(20).forEach { m ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                m.counterparty.ifBlank { "（无对方）" },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                DateExt.shortDateTime(m.occurredAt) +
                                    if (m.note.isNotBlank() && m.note != m.counterparty) " · ${m.note}" else "",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            (if (m.direction == Direction.EXPENSE) "-" else "+") + Money.format(m.amountCents, symbol = false),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (m.direction == Direction.EXPENSE) dc.expense else dc.income
                        )
                    }
                }
                if (preview.items.size > 20) {
                    Text(
                        "…还有 ${preview.items.size - 20} 笔",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item { PrimaryButton("导入 ${preview.items.size} 笔") { onImport() } }
    }
}

@Composable
private fun DonePane(imported: Int, skipped: Int, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(60.dp))
        Text("✅", fontSize = 44.sp)
        Spacer(Modifier.height(10.dp))
        Text("导入完成", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "新增 $imported 笔" + if (skipped > 0) "，跳过重复 $skipped 笔" else "",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton("完成", onClose)
    }
}

@Composable
private fun FailPane(message: String, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(60.dp))
        Text("😕", fontSize = 44.sp)
        Spacer(Modifier.height(12.dp))
        Text(message, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        PrimaryButton("换个文件试试", onRetry)
    }
}

@Composable
private fun CenterPane(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Stat(
    modifier: Modifier,
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color? = null
) {
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontSize = 17.sp,
            fontWeight = FontWeight.ExtraBold,
            color = color ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .glassChip(
                shape = RoundedCornerShape(14.dp),
                tint = MaterialTheme.colorScheme.primary,
                strength = 1.9f
            )
            .clickableScale(pressedScale = 0.96f, clipShape = RoundedCornerShape(14.dp)) { onClick() }
    ) {
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 13.dp)
        )
    }
}
