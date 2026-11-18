package com.foldledger.ui.l10n

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldledger.ui.components.SectionCard
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.theme.wellSurface

/**
 * 双端共用的「界面语言」选择卡（v16.7.0）。
 *
 * 用中文原文作 key 的文案表（见 [t]），这里只负责让用户在中文 / English 之间切换；
 * 切换通过 [onSelect] 写回偏好，AppRoot 同步后所有 t() 调用点会自动重组。
 */
@Composable
fun LanguageCard(current: Language, onSelect: (Language) -> Unit) {
    SectionCard(overline = t("偏好"), title = t("界面语言"), subtitle = t("选择应用显示的语言")) {
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            Language.entries.forEach { lang ->
                val active = lang == current
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .then(
                            if (active) Modifier.glassChip(
                                shape = RoundedCornerShape(50),
                                tint = MaterialTheme.colorScheme.primary,
                                strength = 2.6f,
                                elevation = 2.dp
                            ) else Modifier
                                .clip(RoundedCornerShape(50))
                                .background(wellSurface())
                        )
                        .clip(RoundedCornerShape(50))
                        .clickable { onSelect(lang) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        lang.displayName,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}
