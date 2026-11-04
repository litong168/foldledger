package com.foldledger.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.foldledger.ui.adaptive.AdaptiveScaffold
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.vm.SpeedEntryViewModel

/**
 * 一句话记账（v8.6）页面壳。
 *
 * 面板本体在 commonMain（[SpeedEntryPanel]），语音输入由平台槽提供 ——
 * Android 走系统语音识别，桌面为键盘版。
 */
@Composable
fun SpeedEntryScreen(
    env: FoldEnv,
    ledgerId: Long,
    onBack: () -> Unit
) {
    val vm: SpeedEntryViewModel = viewModel(
        key = "speed-$ledgerId",
        factory = SpeedEntryViewModel.factory(ledgerId)
    )
    AdaptiveScaffold(
        env = env,
        title = "一句话记账",
        subtitle = "说一句「昨天打车35」就记完",
        onBack = onBack,
        showNavigation = false
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            SpeedEntryPanel(
                env = env,
                vm = vm,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
