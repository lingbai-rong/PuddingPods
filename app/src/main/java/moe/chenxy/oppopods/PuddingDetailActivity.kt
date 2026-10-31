package moe.chenxy.oppopods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import moe.chenxy.oppopods.config.ConfigManager
import moe.chenxy.oppopods.hook.SettingsHeadsetHook
import moe.chenxy.oppopods.pods.NoiseControlMode
import moe.chenxy.oppopods.pods.WearStatus
import moe.chenxy.oppopods.ui.AppLocale
import moe.chenxy.oppopods.ui.AppTheme
import moe.chenxy.oppopods.ui.appBackground
import moe.chenxy.oppopods.ui.pages.PodDetailPage
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * One-purpose PUDDING detail screen.
 *
 * Unlike MainActivity this Activity has no module tabs / floating bottom bar. HyperOS entry
 * points can jump here once, then all earbud state is fed directly by com.android.bluetooth.
 * Left/right/case battery values come from the MOONDROP GAIA response rather than Android's
 * single generic Bluetooth battery level.
 */
class PuddingDetailActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        AppLocale.rememberDeviceLocale(newBase)
        AppLocale.apply(
            newBase,
            newBase.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
                .getInt("app_language", AppLocale.DEFAULT)
        )
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        val bluetoothDevice = resolveBluetoothDevice(intent)
        setContent {
            val themeMode = remember { prefs.getInt("theme_mode", 0) }
            val accentMode = remember { prefs.getInt("accent_mode", 0) }
            val colorSchemeMode = when (themeMode) {
                1 -> ColorSchemeMode.Light
                2 -> ColorSchemeMode.Dark
                else -> ColorSchemeMode.System
            }
            val systemDark = isSystemInDarkTheme()
            val darkMode = when (themeMode) {
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
                onDispose { }
            }

            AppTheme(colorSchemeMode = colorSchemeMode, accentMode = accentMode) {
                val context = this@PuddingDetailActivity
                val battery = remember { mutableStateOf(BatteryParams()) }
                val anc = remember { mutableStateOf(NoiseControlMode.OFF) }
                val gain = remember { mutableStateOf(1) }
                val led = remember { mutableStateOf(true) }
                val deviceName = remember { mutableStateOf("MOONDROP Pudding") }
                val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
                val background = appBackground()

                fun sendToBluetooth(action: String, fill: Intent.() -> Unit = {}) {
                    context.sendBroadcast(Intent(action).apply {
                        fill()
                        setPackage("com.android.bluetooth")
                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    })
                }

                DisposableEffect(Unit) {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context?, intent: Intent?) {
                            when (intent?.action) {
                                OppoPodsAction.ACTION_PODS_CONNECTED -> {
                                    intent.getStringExtra("device_name")?.takeIf { it.isNotBlank() }?.let {
                                        deviceName.value = it
                                    }
                                }
                                OppoPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                                    intent.readThreeBatteryValues()?.let { battery.value = it }
                                }
                                OppoPodsAction.ACTION_PODS_ANC_CHANGED -> {
                                    anc.value = when (intent.getIntExtra("status", 1)) {
                                        2 -> NoiseControlMode.NOISE_CANCELLATION       // Basic
                                        3 -> NoiseControlMode.TRANSPARENCY
                                        4 -> NoiseControlMode.ADAPTIVE
                                        5 -> NoiseControlMode.NOISE_CANCELLATION_SMART // Wind
                                        else -> NoiseControlMode.OFF
                                    }
                                }
                                OppoPodsAction.ACTION_PODS_GAIN_LEVEL_CHANGED -> {
                                    gain.value = intent.getIntExtra("level", gain.value).coerceIn(0, 2)
                                }
                                OppoPodsAction.ACTION_PODS_LED_STATE_CHANGED -> {
                                    led.value = intent.getBooleanExtra("enabled", led.value)
                                }
                            }
                        }
                    }
                    context.registerReceiver(receiver, IntentFilter().apply {
                        addAction(OppoPodsAction.ACTION_PODS_CONNECTED)
                        addAction(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED)
                        addAction(OppoPodsAction.ACTION_PODS_ANC_CHANGED)
                        addAction(OppoPodsAction.ACTION_PODS_GAIN_LEVEL_CHANGED)
                        addAction(OppoPodsAction.ACTION_PODS_LED_STATE_CHANGED)
                    }, Context.RECEIVER_EXPORTED)

                    // Push cached state immediately, then query the earbuds for a fresh GAIA snapshot.
                    sendToBluetooth(OppoPodsAction.ACTION_PODS_UI_INIT)
                    sendToBluetooth(OppoPodsAction.ACTION_REFRESH_STATUS)

                    onDispose {
                        runCatching { context.unregisterReceiver(receiver) }
                        sendToBluetooth(OppoPodsAction.ACTION_PODS_UI_CLOSED)
                    }
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = deviceName.value,
                            largeTitle = deviceName.value,
                            scrollBehavior = scrollBehavior,
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
                                }
                            }
                        )
                    }
                ) { padding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(background)
                    ) {
                        PodDetailPage(
                            modifier = Modifier
                                .fillMaxSize()
                                .overScrollVertical(),
                            contentPadding = PaddingValues(
                                top = padding.calculateTopPadding(),
                                bottom = padding.calculateBottomPadding()
                            ),
                            bottomContentPadding = padding.calculateBottomPadding(),
                            podName = deviceName.value,
                            batteryParams = battery.value,
                            wearStatus = WearStatus(),
                            ancMode = anc.value,
                            onAncModeChange = { mode ->
                                anc.value = mode
                                val status = when (mode) {
                                    NoiseControlMode.OFF -> 1
                                    NoiseControlMode.NOISE_CANCELLATION -> 2
                                    NoiseControlMode.TRANSPARENCY -> 3
                                    NoiseControlMode.ADAPTIVE -> 4
                                    NoiseControlMode.NOISE_CANCELLATION_SMART -> 5
                                    else -> 2
                                }
                                sendToBluetooth(OppoPodsAction.ACTION_ANC_SELECT) { putExtra("status", status) }
                            },
                            adaptiveModeEnabled = true,
                            gainLevel = gain.value,
                            onGainLevelChange = { level ->
                                gain.value = level.coerceIn(0, 2)
                                sendToBluetooth(OppoPodsAction.ACTION_GAIN_LEVEL_SET) {
                                    putExtra("level", gain.value)
                                }
                            },
                            ledEnabled = led.value,
                            onLedEnabledChange = { enabled ->
                                led.value = enabled
                                sendToBluetooth(OppoPodsAction.ACTION_LED_STATE_SET) {
                                    putExtra("enabled", enabled)
                                }
                            },
                            showAppSettingsEntry = true,
                            onOpenAppSettings = {
                                startActivity(Intent(context, MainActivity::class.java).apply {
                                    putExtra("open_settings", true)
                                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                })
                            },
                            showSystemSettingsEntry = true,
                            onOpenSystemSettings = {
                                openNativeSystemHeadsetSettings(bluetoothDevice)
                            }
                        )
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openNativeSystemHeadsetSettings(device: BluetoothDevice?) {
        val address = device?.address ?: intent.getStringExtra("bluetoothaddress")
        if (address.isNullOrBlank()) {
            startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
            return
        }

        val fragmentArgs = Bundle().apply { putString("device_address", address) }
        val direct = Intent().apply {
            setClassName("com.android.settings", "com.android.settings.SubSettings")
            putExtra(":settings:show_fragment", "com.android.settings.bluetooth.BluetoothDeviceDetailsFragment")
            putExtra(":settings:show_fragment_args", fragmentArgs)
            putExtra("device_address", address)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        // First try the normal Settings fragment entry directly. If this HyperOS build keeps
        // SubSettings non-exported, wake Settings and ask our injected Settings-process bridge
        // to perform the exact same launch from the system UID.
        runCatching { startActivity(direct) }.onFailure {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                sendBroadcast(Intent(SettingsHeadsetHook.ACTION_OPEN_BT_DEVICE_DETAILS).apply {
                    setPackage("com.android.settings")
                    putExtra(SettingsHeadsetHook.EXTRA_DEVICE_ADDRESS, address)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                })
            }, 350L)
        }
    }

    @SuppressLint("MissingPermission")
    private fun resolveBluetoothDevice(source: Intent): BluetoothDevice? {
        source.parcelableDevice(BluetoothDevice.EXTRA_DEVICE)?.let { return it }
        val address = source.getStringExtra("bluetoothaddress") ?: return null
        return runCatching { BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address) }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableDevice(key: String): BluetoothDevice? {
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(key, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(key)
        }
    }

    private fun Intent.readThreeBatteryValues(): BatteryParams? {
        // Prefer the primitive extras so this also works if a Parcelable class-loader changes
        // across processes / Android versions.
        if (hasExtra("left_connected") || hasExtra("right_connected") || hasExtra("case_connected")) {
            return BatteryParams(
                left = PodParams(
                    battery = getIntExtra("left_battery", 0),
                    isCharging = getBooleanExtra("left_charging", false),
                    isConnected = getBooleanExtra("left_connected", false),
                ),
                right = PodParams(
                    battery = getIntExtra("right_battery", 0),
                    isCharging = getBooleanExtra("right_charging", false),
                    isConnected = getBooleanExtra("right_connected", false),
                ),
                case = PodParams(
                    battery = getIntExtra("case_battery", 0),
                    isCharging = getBooleanExtra("case_charging", false),
                    isConnected = getBooleanExtra("case_connected", false),
                )
            )
        }
        return runCatching { getParcelableExtra("status", BatteryParams::class.java) }.getOrNull()
    }
}
