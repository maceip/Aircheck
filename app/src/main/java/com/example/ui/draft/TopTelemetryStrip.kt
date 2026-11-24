package com.example.ui.draft

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.draft.Stage
import com.example.ui.theme.AcidVoltLime
import com.example.ui.theme.AcidVoltLimeDim
import com.example.ui.theme.ConsoleElevated
import com.example.ui.theme.EvidenceAmber
import com.example.ui.theme.GraphiteHairline
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.OledVoid
import com.example.ui.theme.OnAirCrimson
import com.example.ui.theme.StudioCrisp
import com.example.ui.theme.TelemetryMuted

private val TelemetryStripTextStyle = TextStyle(
    fontFamily = JetBrainsMonoFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 10.sp,
    lineHeight = 14.sp,
    fontFeatureSettings = "tnum"
)

/**
 * Fixed 36dp tabular monospace telemetry strip anchored at the top of the Tactile Drafting Deck.
 *
 * Left:   Breadcrumb stage stepper (`01 SEED · 02 PICK · 03 CUT · 04 SHIP`).
 * Center: Active candidate slot toggle (`v1 | v2 | v3`) once 3 candidates exist.
 * Right:  Live character counter (`218 / 280`, amber 265..280, crimson +overflow + `[ Split 1/2 ]`)
 *         and compact System Deck settings gear icon.
 */
@Composable
fun TopTelemetryStrip(
    currentStage: Stage,
    candidatesCount: Int,
    activeCandidateIndex: Int,
    charCount: Int,
    isSplitActive: Boolean = false,
    isRigOnline: Boolean = false,
    onSelectStage: (Stage) -> Unit,
    onSelectCandidateSlot: (Int) -> Unit,
    onSplitOverflow: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val isOverflow = charCount > 280
    val counterColor = when {
        isOverflow -> OnAirCrimson
        charCount >= 265 -> EvidenceAmber
        else -> StudioCrisp
    }
    val counterLabel = if (isOverflow) {
        "$charCount / 280 (+${charCount - 280})"
    } else {
        "$charCount / 280"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(OledVoid)
            .drawBehind {
                drawLine(
                    color = GraphiteHairline,
                    start = Offset(0f, size.height - 1.dp.toPx()),
                    end = Offset(size.width, size.height - 1.dp.toPx()),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Breadcrumb stage stepper (horizontally scrollable so narrow widths never clip)
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val stages = Stage.entries
            stages.forEachIndexed { idx, stage ->
                val isCurrent = stage == currentStage
                val isReachable = stage == Stage.SEED || isCurrent || candidatesCount > 0
                val tag = when (stage) {
                    Stage.SEED -> "stage_step_seed"
                    Stage.PICK -> "stage_step_pick"
                    Stage.CUT -> "stage_step_cut"
                    Stage.SHIP -> "stage_step_ship"
                }

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .testTag(tag)
                        .semantics {
                            selected = isCurrent
                        }
                        .clickable(
                            enabled = isReachable,
                            role = Role.Tab
                        ) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onSelectStage(stage)
                        }
                        .padding(horizontal = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${stage.code} ${stage.label}",
                        style = TelemetryStripTextStyle.copy(
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                        ),
                        color = when {
                            isCurrent -> AcidVoltLime
                            isReachable -> StudioCrisp.copy(alpha = 0.72f)
                            else -> TelemetryMuted.copy(alpha = 0.45f)
                        },
                        maxLines = 1
                    )
                }

                if (idx < stages.lastIndex) {
                    Text(
                        text = "·",
                        style = TelemetryStripTextStyle,
                        color = TelemetryMuted.copy(alpha = 0.5f),
                        maxLines = 1
                    )
                }
            }
        }

        // Center: Active candidate slot toggle (v1 | v2 | v3) visible once 3 candidates exist
        if (candidatesCount == 3) {
            Spacer(modifier = Modifier.width(4.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(ConsoleElevated)
                    .border(1.dp, GraphiteHairline, RoundedCornerShape(4.dp))
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (slot in 0..2) {
                    val isSelected = slot == activeCandidateIndex
                    Box(
                        modifier = Modifier
                            .testTag("slot_toggle_$slot")
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (isSelected) AcidVoltLimeDim else OledVoid.copy(alpha = 0f))
                            .semantics { selected = isSelected }
                            .clickable(role = Role.Tab) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onSelectCandidateSlot(slot)
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "v${slot + 1}",
                            style = TelemetryStripTextStyle.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            ),
                            color = if (isSelected) AcidVoltLime else TelemetryMuted,
                            maxLines = 1
                        )
                    }
                    if (slot < 2) {
                        Text(
                            text = "|",
                            style = TelemetryStripTextStyle,
                            color = GraphiteHairline,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Right: Live character counter + overflow Split 1/2 pill + Settings gear
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = counterLabel,
                style = TelemetryStripTextStyle.copy(
                    fontWeight = if (isOverflow || charCount >= 265) FontWeight.Bold else FontWeight.Medium
                ),
                color = counterColor,
                maxLines = 1,
                modifier = Modifier.testTag("live_char_counter")
            )

            if (isOverflow && candidatesCount > 0) {
                Box(
                    modifier = Modifier
                        .testTag("overflow_split_pill")
                        .clip(RoundedCornerShape(4.dp))
                        .background(OnAirCrimson.copy(alpha = 0.18f))
                        .border(1.dp, OnAirCrimson, RoundedCornerShape(4.dp))
                        .clickable(role = Role.Button) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onSplitOverflow()
                        }
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isSplitActive) "1/2" else "Split 1/2",
                        style = TelemetryStripTextStyle.copy(fontWeight = FontWeight.Bold),
                        color = StudioCrisp,
                        maxLines = 1
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .testTag("top_settings_button")
                    .semantics { contentDescription = "Open settings" }
                    .clickable(role = Role.Button) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onOpenSettings()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = if (isRigOnline) AcidVoltLime else TelemetryMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
