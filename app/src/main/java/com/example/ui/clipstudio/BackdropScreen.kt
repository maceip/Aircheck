package com.example.ui.clipstudio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.sharp.ArrowBack
import androidx.compose.material.icons.automirrored.sharp.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.sharp.Add
import androidx.compose.material.icons.sharp.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.data.NewsroomLook
import com.example.ui.StudioUiState
import com.example.ui.StudioViewModel
import com.example.ui.design.AcDisplay
import com.example.ui.design.AcField
import com.example.ui.design.AcKey
import com.example.ui.design.AcKeySize
import com.example.ui.design.AcTokens
import com.example.ui.design.AcType

/**
 * Backdrop (studio mockup, approved 2026-11-28), reached from Newsroom's backdrop key; Back returns
 * to Newsroom. A set preview, then the look controls that used to be Newsroom's Look tab: room
 * prompt, accent, light, blur and exposure, grain / border, presenter photo, saved looks.
 * "generate set" makes the room plate on the rig. Every edit is saved to the look as you make it.
 * The mockup's window video, objects, outfit and motion rows have no backend yet and are not built.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackdropScreen(
    state: StudioUiState,
    vm: StudioViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    renderGlobalOverlays: Boolean = true
) {
    val looks = state.newsroomLooks
    val look = looks.firstOrNull { it.isDefault } ?: looks.firstOrNull()
    val draftLook = look ?: NewsroomLook(name = "LOOK_01", isDefault = true)
    val update: (NewsroomLook) -> Unit = { updated ->
        if (look == null) {
            // No saved look yet: create one, then apply the edit to it.
            val id = vm.createNewsroomLook(updated.name)
            vm.updateNewsroomLook(updated.copy(id = id, isDefault = true))
            vm.setDefaultNewsroomLook(id)
        } else {
            vm.updateNewsroomLook(updated)
            // The look being edited is the one Newsroom and the next render use.
            if (!look.isDefault) vm.setDefaultNewsroomLook(look.id)
        }
    }
    val anchorPhotoFile = remember(state.activeProject.stageConfig.photoSha, state.settings.persona.defaultPhotoSha) {
        vm.resolveAnchorPhotoFile()
    }
    var roomPrompt by rememberSaveable(draftLook.id) { mutableStateOf(draftLook.platePrompt) }
    LaunchedEffect(draftLook.id, draftLook.platePrompt) {
        if (roomPrompt.isBlank() && draftLook.platePrompt.isNotBlank()) roomPrompt = draftLook.platePrompt
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.importAnchorPhoto(uri)
    }

    Box(
        modifier
            .fillMaxSize()
            .background(AcTokens.Chassis)
            .testTag("cs_backdrop_root")
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .padding(start = 8.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AcKey(
                    icon = Icons.AutoMirrored.Sharp.ArrowBack,
                    contentDescription = "Back to Newsroom",
                    onClick = onBack,
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("cs_backdrop_back")
                )
                Text("Backdrop", style = AcType.Title, maxLines = 1, modifier = Modifier.weight(1f))
                AcKey(
                    icon = Icons.AutoMirrored.Sharp.Undo,
                    contentDescription = "Undo",
                    enabled = state.coreUndoDepth > 0,
                    onClick = vm::undoCoreEdit,
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("cs_backdrop_undo")
                )
            }

            StudioStatusLine(
                error = state.errorMessage,
                notice = state.statusNotice,
                onDismissError = vm::dismissError,
                onDismissNotice = vm::dismissNotice
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AcDisplay(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .testTag("cs_backdrop_preview")
                ) {
                    AspectFrame(state.corePipelineSession.renderOptions.aspect) {
                        LookBackdropLayer(draftLook, anchorPhotoFile, Modifier.fillMaxSize())
                    }
                    Text(
                        "SET PREVIEW",
                        style = AcType.Caption.copy(color = AcTokens.OpGrey),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                    )
                }

                SectionCaption("Room")
                AcField(
                    value = roomPrompt,
                    onValueChange = { roomPrompt = it },
                    placeholder = "Describe the room behind you…",
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cs_backdrop_prompt")
                )

                SectionCaption("Accent")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Swatches show the overlay's real accent hex (rendered into the video), not UI colors.
                    listOf(
                        "crimson" to NewsroomLook.ACCENT_CRIMSON,
                        "amber" to NewsroomLook.ACCENT_AMBER,
                        "cobalt" to NewsroomLook.ACCENT_COBALT,
                        "mint" to NewsroomLook.ACCENT_MINT
                    ).forEach { (name, hex) ->
                        AcKey(
                            onClick = { update(draftLook.copy(accentHex = hex)) },
                            selected = draftLook.accentHex.equals(hex, ignoreCase = true),
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                            size = AcKeySize.Small,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("cs_accent_$name")
                        ) { tint ->
                            Box(Modifier.size(10.dp).clip(CircleShape).background(parseAccentHex(hex)))
                            Text(name, style = AcType.Caption.copy(color = tint), maxLines = 1)
                        }
                    }
                }

                SectionCaption("Light")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        NewsroomLook.LIGHT_WARM to "warm",
                        NewsroomLook.LIGHT_NEUTRAL to "neutral",
                        NewsroomLook.LIGHT_COOL to "cool"
                    ).forEach { (wire, label) ->
                        AcKey(
                            label = label,
                            onClick = { update(draftLook.copy(light = wire)) },
                            selected = draftLook.light == wire,
                            size = AcKeySize.Small,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("cs_light_$label")
                        )
                    }
                }

                StudioTickRuler(
                    label = "BLUR",
                    valueText = "${draftLook.blurPercent}%",
                    normalizedValue = (draftLook.blurPercent / 100f).coerceIn(0f, 1f),
                    enabled = true,
                    onStep = { delta -> update(draftLook.copy(blurPercent = (draftLook.blurPercent + delta * 5).coerceIn(0, 100))) },
                    tag = "cs_scrub_blur"
                )
                val evLabel = "${if (draftLook.brightnessEv > 0) "+" else ""}${"%.1f".format(draftLook.brightnessEv)} EV"
                StudioTickRuler(
                    label = "EXPOSURE",
                    valueText = evLabel,
                    normalizedValue = (((draftLook.brightnessEv + 3.0) / 6.0).toFloat()).coerceIn(0f, 1f),
                    enabled = true,
                    onStep = { delta ->
                        val next = (Math.round((draftLook.brightnessEv + delta * 0.2) * 10.0) / 10.0).coerceIn(-3.0, 3.0)
                        update(draftLook.copy(brightnessEv = next))
                    },
                    tag = "cs_scrub_ev"
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AcKey(
                        label = "film grain",
                        onClick = { update(draftLook.copy(grain = !draftLook.grain)) },
                        selected = draftLook.grain,
                        size = AcKeySize.Small,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_look_grain")
                    )
                    AcKey(
                        label = "frame border",
                        onClick = { update(draftLook.copy(border = !draftLook.border)) },
                        selected = draftLook.border,
                        size = AcKeySize.Small,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_look_border")
                    )
                }

                SectionCaption("Presenter photo")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AcDisplay(radius = 8.dp, contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp)) {
                        if (anchorPhotoFile != null && anchorPhotoFile.isFile) {
                            AsyncImage(model = anchorPhotoFile, contentDescription = "Presenter photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Icon(Icons.Sharp.PhotoCamera, contentDescription = null, tint = AcTokens.OpGrey, modifier = Modifier.size(18.dp))
                        }
                    }
                    Text(
                        if (anchorPhotoFile != null) "Custom phone photo" else "Default studio portrait",
                        style = AcType.BodySmall.copy(color = AcTokens.Ink),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (anchorPhotoFile != null) {
                        AcKey(label = "reset", onClick = vm::clearAnchorPhoto, size = AcKeySize.Small, modifier = Modifier.testTag("cs_reset_photo"))
                    }
                    AcKey(
                        label = "choose photo",
                        onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        size = AcKeySize.Small,
                        modifier = Modifier.testTag("cs_choose_photo")
                    )
                }

                SectionCaption("Saved looks")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    looks.forEach { item ->
                        AcKey(
                            label = item.name,
                            selected = item.id == look?.id,
                            onClick = { vm.setDefaultNewsroomLook(item.id) },
                            size = AcKeySize.Small,
                            modifier = Modifier.testTag("cs_look_tile_${item.id}")
                        )
                    }
                    AcKey(
                        label = "new look",
                        icon = Icons.Sharp.Add,
                        onClick = { vm.setDefaultNewsroomLook(vm.createNewsroomLook("LOOK_%02d".format(looks.size + 1))) },
                        size = AcKeySize.Small,
                        modifier = Modifier.testTag("cs_look_new")
                    )
                    if (look != null) {
                        AcKey(
                            label = "duplicate",
                            onClick = { vm.duplicateNewsroomLook(look.id)?.let(vm::setDefaultNewsroomLook) },
                            size = AcKeySize.Small,
                            modifier = Modifier.testTag("cs_look_duplicate")
                        )
                    }
                }
            }

            StudioDock(
                primaryLabel = "generate set",
                primaryIcon = Icons.Outlined.AutoAwesome,
                primaryTag = "cs_backdrop_generate",
                primaryEnabled = roomPrompt.isNotBlank() && !state.isGeneratingBackdrop,
                isBusy = state.isGeneratingBackdrop,
                busyLabel = "generating set…",
                onPrimaryClick = {
                    val lookId = look?.id ?: vm.createNewsroomLook(draftLook.name).also(vm::setDefaultNewsroomLook)
                    vm.generateBackdropForLook(lookId, roomPrompt)
                }
            )
        }

        if (renderGlobalOverlays) {
            StudioToast(message = state.toastMessage, onDismiss = vm::dismissToast)
        }
    }
}
