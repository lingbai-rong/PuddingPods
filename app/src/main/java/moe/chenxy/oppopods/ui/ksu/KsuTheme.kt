package moe.chenxy.oppopods.ui.ksu

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

@Composable
@ReadOnlyComposable
fun isInDarkTheme(): Boolean = isSystemInDarkTheme()
