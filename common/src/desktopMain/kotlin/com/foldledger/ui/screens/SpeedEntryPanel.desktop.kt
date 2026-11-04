package com.foldledger.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.vm.SpeedEntryViewModel

/** 桌面实现：键盘输入版（面板本体共享，语音槽留空）。 */
@Composable
actual fun SpeedEntryPanel(
    env: FoldEnv,
    vm: SpeedEntryViewModel,
    modifier: Modifier,
    scrollable: Boolean,
    onSaved: (Long) -> Unit
) {
    SpeedEntryPanelBody(env = env, vm = vm, modifier = modifier, scrollable = scrollable, onSaved = onSaved)
}

/** 桌面暂不支持语音输入：槽位留空，输入框布局与 Android 保持一致。 */
@Composable
internal actual fun SpeedEntryVoiceInput(
    voice: SpeedEntryVoiceState,
    onSpoken: (String) -> Unit,
    onNoRecognition: () -> Unit,
    onVoiceUnavailable: () -> Unit
) {
}
