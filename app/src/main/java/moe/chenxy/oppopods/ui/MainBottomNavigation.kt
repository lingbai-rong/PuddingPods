package moe.chenxy.oppopods.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.oppopods.ui.ksu.FloatingBottomBar
import moe.chenxy.oppopods.ui.ksu.FloatingBottomBarItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop

private val KSU_BOTTOM_BAR_WIDTH = 252.dp
private val KSU_BOTTOM_BAR_BOTTOM_PADDING = 30.dp

@Composable
internal fun MainBottomNavigation(
    tabs: List<MainTab>,
    selectedTab: MainTab,
    floating: Boolean,
    backdrop: Backdrop?,
    onTabClick: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (floating && backdrop != null) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(bottom = KSU_BOTTOM_BAR_BOTTOM_PADDING),
            contentAlignment = Alignment.Center,
        ) {
            FloatingBottomBar(
                modifier = Modifier.width(KSU_BOTTOM_BAR_WIDTH),
                selectedIndex = { tabs.indexOf(selectedTab).coerceAtLeast(0) },
                onSelected = { index -> tabs.getOrNull(index)?.let(onTabClick) },
                backdrop = backdrop,
                tabsCount = tabs.size,
                isBlurEnabled = true,
                transparentBackdropSurface = true,
            ) {
                tabs.forEach { tab ->
                    FloatingBottomBarItem(onClick = { onTabClick(tab) }) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.title(),
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = tab.title(),
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    } else {
        NavigationBar(
            modifier = modifier,
            showDivider = false,
        ) {
            tabs.forEach { tab ->
                NavigationBarItem(
                    selected = selectedTab == tab,
                    onClick = { onTabClick(tab) },
                    icon = tab.icon,
                    label = tab.title(),
                )
            }
        }
    }
}
