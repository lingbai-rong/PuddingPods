package moe.chenxy.oppopods.ui.pages

import android.content.res.Configuration
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.config.ConfigManager
import moe.chenxy.oppopods.pods.NoiseControlMode
import moe.chenxy.oppopods.pods.WearStatus
import moe.chenxy.oppopods.ui.components.AncSwitch
import moe.chenxy.oppopods.ui.components.PodStatus
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import moe.chenxy.oppopods.pods.EqPreset
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun PodDetailPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    bottomContentPadding: Dp = 16.dp,
    podName: String,
    batteryParams: BatteryParams,
    wearStatus: WearStatus = WearStatus(),
    ancMode: NoiseControlMode,
    onAncModeChange: (NoiseControlMode) -> Unit,
    smartAncLevel: NoiseControlMode? = null,
    transparencyVocalEnhancement: Boolean = false,
    onTransparencyVocalEnhancementChange: (Boolean) -> Unit = {},
    spatialAudioMode: Int = ConfigManager.SPATIAL_AUDIO_OFF,
    onSpatialAudioModeChange: (Int) -> Unit = {},
    spatialAudioSupported: Boolean = false,
    spatialSoundSupported: Boolean = false,
    adaptiveModeEnabled: Boolean = true,
    eqPreset: Int = -1,
    onEqPresetChange: (Int) -> Unit = {},
    gainLevel: Int = 1,
    onGainLevelChange: (Int) -> Unit = {},
    ledEnabled: Boolean = true,
    onLedEnabledChange: (Boolean) -> Unit = {},
    boxImagePath: String? = null,
    showAppSettingsEntry: Boolean = false,
    onOpenAppSettings: () -> Unit = {},
    showSystemSettingsEntry: Boolean = false,
    onOpenSystemSettings: () -> Unit = {},
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        Row(
            modifier = modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = rememberPodImagePainter(boxImagePath),
                    contentDescription = "Earphones",
                    modifier = Modifier
                        .fillMaxWidth(0.82f)
                        .widthIn(max = 360.dp),
                    contentScale = ContentScale.FillWidth
                )
                Text(
                    text = podName,
                    modifier = Modifier.padding(top = 12.dp),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                contentPadding = PaddingValues(top = 12.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                podControlItems(
                    batteryParams = batteryParams,
                    wearStatus = wearStatus,
                    ancMode = ancMode,
                    onAncModeChange = onAncModeChange,
                    smartAncLevel = smartAncLevel,
                    transparencyVocalEnhancement = transparencyVocalEnhancement,
                    onTransparencyVocalEnhancementChange = onTransparencyVocalEnhancementChange,
                    spatialAudioMode = spatialAudioMode,
                    onSpatialAudioModeChange = onSpatialAudioModeChange,
                    spatialAudioSupported = spatialAudioSupported,
                    spatialSoundSupported = spatialSoundSupported,
                    adaptiveModeEnabled = adaptiveModeEnabled,
                    eqPreset = eqPreset,
                    onEqPresetChange = onEqPresetChange,
                    gainLevel = gainLevel,
                    onGainLevelChange = onGainLevelChange,
                    ledEnabled = ledEnabled,
                    onLedEnabledChange = onLedEnabledChange,
                    showAppSettingsEntry = showAppSettingsEntry,
                    onOpenAppSettings = onOpenAppSettings,
                    showSystemSettingsEntry = showSystemSettingsEntry,
                    onOpenSystemSettings = onOpenSystemSettings,
                    bottomContentPadding = bottomContentPadding
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Image(
                painter = rememberPodImagePainter(boxImagePath),
                contentDescription = "Earphones",
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .padding(vertical = 16.dp),
                contentScale = ContentScale.FillWidth
            )
        }

        podControlItems(
            batteryParams = batteryParams,
            wearStatus = wearStatus,
            ancMode = ancMode,
            onAncModeChange = onAncModeChange,
            smartAncLevel = smartAncLevel,
            transparencyVocalEnhancement = transparencyVocalEnhancement,
            onTransparencyVocalEnhancementChange = onTransparencyVocalEnhancementChange,
            spatialAudioMode = spatialAudioMode,
            onSpatialAudioModeChange = onSpatialAudioModeChange,
            spatialAudioSupported = spatialAudioSupported,
            spatialSoundSupported = spatialSoundSupported,
            adaptiveModeEnabled = adaptiveModeEnabled,
            eqPreset = eqPreset,
            onEqPresetChange = onEqPresetChange,
            gainLevel = gainLevel,
            onGainLevelChange = onGainLevelChange,
            ledEnabled = ledEnabled,
            onLedEnabledChange = onLedEnabledChange,
            showAppSettingsEntry = showAppSettingsEntry,
            onOpenAppSettings = onOpenAppSettings,
            showSystemSettingsEntry = showSystemSettingsEntry,
            onOpenSystemSettings = onOpenSystemSettings,
            bottomContentPadding = bottomContentPadding
        )
    }
}

/**
 * HyperOriG-style detail page: the product artwork belongs to this APK, not Settings.
 * When the user has not configured a custom picture, always render our bundled
 * transparent MOONDROP PUDDING image (R.drawable.img_box).
 */
@Composable
private fun rememberPodImagePainter(path: String?) = remember(path) {
    path?.let {
        runCatching { BitmapFactory.decodeFile(it) }
            .getOrNull()
            ?.let { bitmap -> BitmapPainter(bitmap.asImageBitmap()) }
    }
} ?: painterResource(R.drawable.img_box)

private fun LazyListScope.podControlItems(
    batteryParams: BatteryParams,
    wearStatus: WearStatus,
    ancMode: NoiseControlMode,
    onAncModeChange: (NoiseControlMode) -> Unit,
    smartAncLevel: NoiseControlMode?,
    transparencyVocalEnhancement: Boolean,
    onTransparencyVocalEnhancementChange: (Boolean) -> Unit,
    spatialAudioMode: Int,
    onSpatialAudioModeChange: (Int) -> Unit,
    spatialAudioSupported: Boolean,
    spatialSoundSupported: Boolean,
    adaptiveModeEnabled: Boolean,
    eqPreset: Int,
    onEqPresetChange: (Int) -> Unit,
    gainLevel: Int,
    onGainLevelChange: (Int) -> Unit,
    ledEnabled: Boolean,
    onLedEnabledChange: (Boolean) -> Unit,
    showAppSettingsEntry: Boolean,
    onOpenAppSettings: () -> Unit,
    showSystemSettingsEntry: Boolean,
    onOpenSystemSettings: () -> Unit,
    bottomContentPadding: Dp
) {
    val spatialAudioValues = listOf(
        ConfigManager.SPATIAL_AUDIO_OFF,
        ConfigManager.SPATIAL_AUDIO_FIXED,
        ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING,
    )

    item {
        Card(
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            PodStatus(
                batteryParams = batteryParams,
                wearStatus = wearStatus,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
            )
        }
    }

    item {
        Card(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            AncSwitch(
                ancStatus = ancMode,
                onAncModeChange = onAncModeChange,
                smartAncLevel = smartAncLevel,
                adaptiveModeEnabled = adaptiveModeEnabled,
                transparencyVocalEnhancement = transparencyVocalEnhancement,
                onTransparencyVocalEnhancementChange = onTransparencyVocalEnhancementChange
            )
        }
    }

    item {
        val gainOptions = listOf(
            stringResource(R.string.pudding_gain_low),
            stringResource(R.string.pudding_gain_medium),
            stringResource(R.string.pudding_gain_high),
        )
        Card(
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            OverlayDropdownPreference(
                title = stringResource(R.string.pudding_gain_title),
                summary = stringResource(R.string.pudding_gain_summary),
                items = gainOptions,
                selectedIndex = gainLevel.coerceIn(0, 2),
                onSelectedIndexChange = { onGainLevelChange(it.coerceIn(0, 2)) }
            )
            SwitchPreference(
                title = stringResource(R.string.pudding_led_title),
                summary = stringResource(R.string.pudding_led_summary),
                checked = ledEnabled,
                onCheckedChange = onLedEnabledChange
            )
        }
    }

    if (showAppSettingsEntry) {
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                BasicComponent(
                    title = stringResource(R.string.app_settings_entry),
                    summary = stringResource(R.string.app_settings_entry_summary),
                    onClick = onOpenAppSettings
                )
            }
        }
    }

    if (showSystemSettingsEntry) {
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 0.dp)
            ) {
                BasicComponent(
                    title = stringResource(R.string.system_headset_settings_entry),
                    summary = stringResource(R.string.system_headset_settings_entry_summary),
                    onClick = onOpenSystemSettings
                )
            }
        }
    }

    item {
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(bottomContentPadding))
    }
}
