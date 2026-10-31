package moe.chenxy.oppopods.ui.pages

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.config.ConfigManager
import moe.chenxy.oppopods.ui.AppLocale
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun SettingsPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    desktopIconHidden: MutableState<Boolean> = mutableStateOf(false),
    onDesktopIconHiddenChange: (Boolean) -> Unit = {},
    logLevel: MutableState<Int> = mutableStateOf(ConfigManager.LOG_LEVEL_BASIC),
    onLogLevelChange: (Int) -> Unit = {},
    islandMode: MutableState<Int> = mutableStateOf(ConfigManager.ISLAND_MODE_OFFICIAL),
    onIslandModeChange: (Int) -> Unit = {},
    islandShowTimings: MutableState<Set<Int>> = mutableStateOf(emptySet()),
    onIslandShowTimingsChange: (Set<Int>) -> Unit = {},
    appLanguage: MutableState<Int> = mutableStateOf(AppLocale.DEFAULT),
    onAppLanguageChange: (Int) -> Unit = {},
    notificationClickAction: MutableState<Int> = mutableStateOf(ConfigManager.NOTIFICATION_CLICK_MODULE_POPUP),
    onNotificationClickActionChange: (Int) -> Unit = {},
    moreClickAction: MutableState<Int> = mutableStateOf(ConfigManager.MORE_CLICK_MODULE),
    onMoreClickActionChange: (Int) -> Unit = {},
    fakeDeviceId: MutableState<String> = mutableStateOf(ConfigManager.DEFAULT_FAKE_DEVICE_ID),
    onFakeDeviceIdChange: (String) -> Unit = {},
    onOpenTheme: () -> Unit = {},
    onOpenAbout: () -> Unit = {}
) {
    val languageValues = listOf(
        AppLocale.TRADITIONAL_CHINESE,
        AppLocale.CHINESE,
        AppLocale.SYSTEM,
        AppLocale.ENGLISH,
    )
    val languageOptions = listOf(
        stringResource(R.string.language_traditional),
        stringResource(R.string.language_chinese),
        stringResource(R.string.language_system),
        stringResource(R.string.language_english),
    )
    val logLevelValues = listOf(ConfigManager.LOG_LEVEL_OFF, ConfigManager.LOG_LEVEL_BASIC, ConfigManager.LOG_LEVEL_DEBUG)
    val logLevelOptions = listOf(
        stringResource(R.string.log_level_off),
        stringResource(R.string.log_level_basic),
        stringResource(R.string.log_level_debug),
    )
    val islandModeValues = listOf(ConfigManager.ISLAND_MODE_NONE, ConfigManager.ISLAND_MODE_OFFICIAL, ConfigManager.ISLAND_MODE_MODULE)
    val islandModeOptions = listOf(
        stringResource(R.string.island_mode_none),
        stringResource(R.string.island_mode_official),
        stringResource(R.string.island_mode_module),
    )
    val islandShowTimingOptions = listOf(
        ConfigManager.ISLAND_SHOW_TIMING_CONNECTED to stringResource(R.string.island_show_timing_connected),
        ConfigManager.ISLAND_SHOW_TIMING_WEARING to stringResource(R.string.island_show_timing_wearing),
        ConfigManager.ISLAND_SHOW_TIMING_REMOVED to stringResource(R.string.island_show_timing_removed),
        ConfigManager.ISLAND_SHOW_TIMING_IN_CASE to stringResource(R.string.island_show_timing_in_case),
    )
    val islandShowTimingEntries = remember(islandShowTimings.value, islandShowTimingOptions) {
        listOf(
            DropdownEntry(
                items = islandShowTimingOptions.map { (value, text) ->
                    DropdownItem(
                        text = text,
                        selected = value in islandShowTimings.value,
                        onClick = {
                            val selected = islandShowTimings.value
                            onIslandShowTimingsChange(
                                if (value in selected) selected - value else selected + value
                            )
                        },
                    )
                }
            )
        )
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
            start = 12.dp,
            end = 12.dp
        ),
    ) {
        item {
            Card(modifier = Modifier.padding(top = 12.dp)) {
                BasicComponent(
                    title = stringResource(R.string.theme_title),
                    summary = stringResource(R.string.theme_color_summary),
                    onClick = onOpenTheme,
                )
            }
        }

        item {
            Card(modifier = Modifier.padding(top = 12.dp)) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.language),
                    summary = stringResource(R.string.language_summary),
                    items = languageOptions,
                    selectedIndex = languageValues.indexOf(appLanguage.value).coerceAtLeast(0),
                    onSelectedIndexChange = { onAppLanguageChange(languageValues[it]) }
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.log_level),
                    summary = stringResource(R.string.log_level_summary),
                    items = logLevelOptions,
                    selectedIndex = logLevelValues.indexOf(logLevel.value).coerceAtLeast(0),
                    onSelectedIndexChange = { onLogLevelChange(logLevelValues[it]) }
                )
                SwitchPreference(
                    title = stringResource(R.string.hide_desktop_icon),
                    summary = stringResource(R.string.hide_desktop_icon_summary),
                    checked = desktopIconHidden.value,
                    onCheckedChange = { onDesktopIconHiddenChange(it) }
                )
            }
        }

        item {
            Card(modifier = Modifier.padding(top = 12.dp)) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.island_mode),
                    summary = stringResource(R.string.island_mode_summary),
                    items = islandModeOptions,
                    selectedIndex = islandModeValues.indexOf(islandMode.value).coerceAtLeast(0),
                    onSelectedIndexChange = { onIslandModeChange(islandModeValues[it]) }
                )
                if (islandMode.value == ConfigManager.ISLAND_MODE_MODULE) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.island_show_timing),
                        summary = stringResource(R.string.island_show_timing_summary),
                        entries = islandShowTimingEntries,
                        collapseOnSelection = false,
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.padding(top = 12.dp)) {
                BasicComponent(
                    title = stringResource(R.string.about),
                    summary = "PuddingPods-Enhanced",
                    onClick = onOpenAbout
                )
            }
        }
    }
}
