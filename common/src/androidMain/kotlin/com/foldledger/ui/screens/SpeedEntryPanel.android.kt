package com.foldledger.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.foldledger.R
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.rememberPressScale
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.vm.SpeedEntryViewModel
import com.foldledger.ui.l10n.t

/** Android 实现：面板本体共享，语音输入 = 系统语音识别（RecognizerIntent）。 */
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
 * Android 语音输入槽：一枚玻璃圆钮，点一下调系统语音识别。
 *
 * 直接调系统语音识别（RecognizerIntent），App 不需要 RECORD_AUDIO 权限 ——
 * 录音和识别都由系统那个「语音服务」App 完成，这里只把识别出的文字拿回来。
 * 识别完通过 [onSpoken] 填进输入框，后面的解析 / 确认卡流程完全不变。
 */
@Composable
internal actual fun SpeedEntryVoiceInput(
    voice: SpeedEntryVoiceState,
    onSpoken: (String) -> Unit,
    onNoRecognition: () -> Unit,
    onVoiceUnavailable: () -> Unit
) {
    val kbController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var listening by remember { mutableStateOf(false) }
    /** 上次拉起失败（无识别服务）：图标转「MicOff」外观，但仍可点重试 */
    var voiceUnavailable by remember { mutableStateOf(false) }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        listening = false
        voice.touched = true
        if (result.resultCode == Activity.RESULT_OK) {
            val raw = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.trim().orEmpty()
            // 识别器常在句尾补标点（「打车35。」），会污染备注，先清掉
            val spoken = raw.trimEnd('。', '，', '.', ',', '!', '！', '?', '？', ' ')
            if (spoken.isNotEmpty()) {
                voiceUnavailable = false
                onSpoken(spoken)
            } else {
                onNoRecognition()
            }
        }
    }
    // 回到前台（含从系统识别界面返回）：只做状态自愈 —— listening 归位、清掉上次的"不可用"。
    // ⚠️ 不在这里用 queryIntentActivities 探活：API 30+ 有包可见性过滤，未声明 <queries> 时
    // 对多数机型返回空 → 会把「不可用」永久钉死。唯一可靠的探测器是真正 launch 一次（try/catch）。
    LifecycleResumeEffect(Unit) {
        listening = false
        voiceUnavailable = false
        if (voice.hint == "没有可用的语音输入，改用键盘") voice.hint = null
        onPauseOrDispose { }
    }
    fun startVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, t("说一句，比如：昨天打车35"))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        voice.hint = null
        listening = true
        // 先把键盘收起来：识别界面返回后，软键盘别盖住下面的确认卡
        kbController?.hide()
        focusManager.clearFocus()
        try {
            voiceLauncher.launch(intent)
            voiceUnavailable = false
        } catch (e: ActivityNotFoundException) {
            // 有些机型/精简系统没有语音识别服务：不让用户以为点坏了，直接说明并让打字。
            // ⚠️ 不 disable 按钮：装好语音服务回来能再点重试；TalkBack 也要能聚焦它。
            listening = false
            voiceUnavailable = true
            onVoiceUnavailable()
        }
    }

    // 命中区固定 48dp；只让 36dp 圆盘按下时缩到 0.93，命中区不跟着缩。
    // 用 clickable + 自己的 InteractionSource（不用 clickableScale：它的缩放作用在外层，
    // 要么缩了命中区、要么把外层变成死区）。不画涟漪、不 disable（TalkBack 仍可聚焦）。
    val micSource = remember { MutableInteractionSource() }
    Box(
        Modifier
            .padding(end = 4.dp)
            .size(48.dp)
            .semantics { role = Role.Button }
            .clickable(
                interactionSource = micSource,
                indication = null,
                onClick = { startVoice() }
            ),
        contentAlignment = Alignment.Center
    ) {
        val discScale = rememberPressScale(micSource, if (voiceUnavailable) 1f else 0.93f)
        Box(
            Modifier
                .size(36.dp)
                .graphicsLayer { scaleX = discScale; scaleY = discScale }
                .glassChip(
                    shape = CircleShape,
                    tint = if (listening) MaterialTheme.colorScheme.primary else null,
                    strength = when {
                        listening -> 1.05f
                        voiceUnavailable -> 0.40f
                        else -> 0.55f
                    }
                )
                .then(
                    if (listening) Modifier.border(
                        1.2.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.42f),
                        CircleShape
                    ) else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            // 可着色的矢量麦克风（不用 emoji：emoji 染不上色、也表达不了状态）
            Icon(
                painter = painterResource(
                    if (voiceUnavailable) R.drawable.ic_mic_off else R.drawable.ic_mic
                ),
                contentDescription = if (voiceUnavailable) t("语音输入不可用，点一下重试") else t("语音输入"),
                tint = when {
                    // 0.85 而不是 0.55：仍要过 3:1 的可辨识门槛，读不出"禁用"就等于没提示
                    voiceUnavailable -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                    listening -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
