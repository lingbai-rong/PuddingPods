package moe.chenxy.oppopods

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import moe.chenxy.oppopods.config.ConfigManager
import moe.chenxy.oppopods.ui.App
import moe.chenxy.oppopods.ui.AppLocale

class MainActivity : ComponentActivity() {
    private val externalOpenDetail = mutableStateOf(false)
    private val externalOpenSettings = mutableStateOf(false)
    override fun attachBaseContext(newBase: Context) {
        AppLocale.rememberDeviceLocale(newBase)
        AppLocale.apply(newBase, newBase.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE).getInt("app_language", AppLocale.DEFAULT))
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        externalOpenDetail.value = intent.getBooleanExtra("open_detail", false)
        externalOpenSettings.value = intent.getBooleanExtra("open_settings", false)
        if (externalOpenSettings.value) externalOpenDetail.value = false
        if (externalOpenDetail.value) {
            getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean("open_earphones_once", true).apply()
        }

        setContent {
            val prefs = remember { getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE) }
            val themeMode = remember { mutableStateOf(prefs.getInt("theme_mode", 0)) }
            val accentMode = remember { mutableStateOf(prefs.getInt("accent_mode", 0)) }
            val floatingBottomBar = remember { mutableStateOf(prefs.getBoolean("floating_bottom_bar", true)) }
            val appLanguage = remember { mutableStateOf(prefs.getInt("app_language", AppLocale.DEFAULT)) }
            val systemDark = isSystemInDarkTheme()
            val darkMode = when (themeMode.value) {
                1 -> false
                2 -> true
                else -> systemDark
            }

            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                )

                window.isNavigationBarContrastEnforced = false

                onDispose {}
            }

            App(
                themeMode = themeMode,
                onThemeModeChange = {
                    themeMode.value = it
                    prefs.edit().putInt("theme_mode", it).apply()
                },
                accentMode = accentMode,
                onAccentModeChange = {
                    accentMode.value = it
                    prefs.edit().putInt("accent_mode", it).apply()
                },
                floatingBottomBar = floatingBottomBar,
                onFloatingBottomBarChange = {
                    floatingBottomBar.value = it
                    prefs.edit().putBoolean("floating_bottom_bar", it).apply()
                },
                appLanguage = appLanguage,
                onAppLanguageChange = {
                    appLanguage.value = it
                    prefs.edit().putInt("app_language", it).apply()
                },
                openDetail = externalOpenDetail.value,
                openSettings = externalOpenSettings.value,
            )
        }
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_settings", false)) {
            externalOpenSettings.value = true
            externalOpenDetail.value = false
        } else if (intent.getBooleanExtra("open_detail", false)) {
            externalOpenSettings.value = false
            externalOpenDetail.value = true
            getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean("open_earphones_once", true).apply()
        }
    }

}
