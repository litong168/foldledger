package com.foldledger.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.rememberPressScale
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.vm.SpeedEntryViewModel
import com.foldledger.ui.l10n.t

/** 桌面实现：面板本体共享，语音槽位用「文本填入」对齐（见 [SpeedEntryVoiceInput]）。 */
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

/**
 * 桌面「一句话记账」的输入槽位。
 *
 * JVM 桌面没有 Android 的 `RecognizerIntent`，也不该为了一个槽位引入本地语音模型
 * （新依赖 + 模型分发 + 麦克风权限，收益远小于风险）。这里做**能力对齐式降级**：
 * 槽位的位置、尺寸、玻璃材质、点击回执与 Android 完全一致，只是把「说一句」换成
 * 「把复制好的一句话填进来」——点一下从剪贴板取文本，去句尾标点后走同一个
 * [onSpoken]，后面的解析 / 确认卡 / 撤销流程双端完全一样。
 *
 * 剪贴板为空时仍回调 [onVoiceUnavailable]（保留共享回执），再把提示换成桌面专属说明，
 * 免得用户对着一个"点了没反应"的按钮发愣。
 */
@Composable
internal actual fun SpeedEntryVoiceInput(
    voice: SpeedEntryVoiceState,
    onSpoken: (String) -> Unit,
    onNoRecognition: () -> Unit,
    onVoiceUnavailable: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var unavailable by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }

    Box(
        Modifier
            .padding(end = 4.dp)
            .size(48.dp)
            .semantics { role = Role.Button }
            .clickable(
                interactionSource = source,
                indication = null,
                onClick = {
                    val raw = clipboard.getText()?.text?.trim().orEmpty()
                    // 与 Android 侧一致：清掉句尾标点，别让它污染备注
                    val spoken = raw.trimEnd('。', '，', '.', ',', '!', '！', '?', '？', ' ')
                    if (spoken.isNotEmpty()) {
                        unavailable = false
                        onSpoken(spoken)
                    } else {
                        unavailable = true
                        onVoiceUnavailable()
                        voice.hint = t("桌面没有系统语音识别；复制一句话后点这里填入，或直接键盘输入")
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        val discScale = rememberPressScale(source, 0.93f)
        Box(
            Modifier
                .size(36.dp)
                .graphicsLayer { scaleX = discScale; scaleY = discScale }
                .glassChip(
                    shape = CircleShape,
                    strength = if (unavailable) 0.40f else 0.55f
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = t("填入剪贴板里的一句话（桌面无系统语音识别）"),
                tint = if (unavailable) {
                    // 与 Android voiceUnavailable 同款 0.85：仍要过 3:1 可辨识门槛
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                } else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
