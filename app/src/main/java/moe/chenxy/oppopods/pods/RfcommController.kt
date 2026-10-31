package moe.chenxy.oppopods.pods

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.os.SystemClock
import moe.chenxy.oppopods.hook.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.config.ConfigManager
import moe.chenxy.oppopods.utils.MediaControl
import moe.chenxy.oppopods.utils.SystemApisUtils
import moe.chenxy.oppopods.utils.SystemApisUtils.setIconVisibility
import moe.chenxy.oppopods.utils.miuiStrongToast.MiuiStrongToastUtil
import moe.chenxy.oppopods.utils.miuiStrongToast.MiuiStrongToastUtil.cancelPodsNotificationByMiuiBt
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams
import java.io.IOException
import java.io.InputStream
import android.content.SharedPreferences
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

@SuppressLint("MissingPermission", "StaticFieldLeak")
object RfcommController {
    private const val TAG = "PuddingPods-RfcommController"
    private const val AUTO_RECONNECT_DELAY_MS = 120_000L
    private const val APP_UI_ACTIVE_TIMEOUT_MS = 75_000L

    // Basic Objects
    private var socket: BluetoothSocket? = null
    private var mContext: Context? = null
    lateinit var mDevice: BluetoothDevice
    private lateinit var mPrefs: SharedPreferences

    private var scanToken: MediaRouter2.ScanToken? = null
    var routes: List<MediaRoute2Info> = listOf()
    private lateinit var mediaRouter: MediaRouter2

    // Status
    private var mShowedConnectedToast = false
    private var isConnected = false
    private var lastTempBatt = 0
    lateinit var currentBatteryParams: BatteryParams
    private var currentAnc: Int = 1
    /** -1 = unknown / not in smart mode; otherwise a [NoiseControlMode].ordinal of the
     *  level smart mode is currently auto-applying (light/medium/deep). */
    private var currentSmartAncLevel: Int = -1
    private var currentTransparencyVocalEnhancement: Boolean = false
    private var currentSpatialAudioMode: Int = SpatialAudioMode.OFF
    /** -1 = unknown; otherwise one of [EqPreset.ALL]. */
    private var currentEqPreset: Int = -1
    private var currentGainLevel = PuddingGaia.GainLevel.MEDIUM.uiValue
    private var currentLedEnabled = true
    private var lastKnownLeftBattery: Int = 0
    private var lastKnownRightBattery: Int = 0
    private var lastKnownCaseBattery: Int = 0
    private var lastKnownLeftCharging: Boolean = false
    private var lastKnownRightCharging: Boolean = false
    private var lastKnownCaseCharging: Boolean = false
    private var cachedDeviceName: String = ""
    private var receiverRegistered = false
    private var routeScanStarted = false
    private var appUiActive = false
    private var appUiActiveUntilMs = 0L

    data class StatusSnapshot(
        val battery: BatteryParams?,
        val anc: Int,
        val transparencyVocalEnhancement: Boolean,
        val address: String?,
        val deviceName: String?,
        val connected: Boolean,
        val connecting: Boolean,
        val reconnectPending: Boolean,
    )

    // RFCOMM jobs
    private var connectionJob: kotlinx.coroutines.Job? = null
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private var readerJob: kotlinx.coroutines.Job? = null
    private val reconnectAttempts = AtomicInteger(0)
    private var reconnectPending = false
    private val PUDDING_RFCOMM_UUID: UUID = UUID.fromString(PuddingGaia.SPP_UUID)
    private val puddingDecoder = PuddingGaia.StreamDecoder()

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            handleUIEvent(p1!!)
        }
    }

    private fun changeUIAncStatus(status: Int) {
        if (status < 1 || status > 8) return
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_ANC_CHANGED) {
            if (::mDevice.isInitialized) this.putExtra("address", mDevice.address)
            this.putExtra("status", status)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_ANC_CHANGED) {
            putExtra("status", status)
        }
    }

    private fun changeUIBatteryStatus(status: BatteryParams) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED) {
            if (::mDevice.isInitialized) this.putExtra("address", mDevice.address)
            this.putExtra("status", status)
            putBatteryExtras(status)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED) {
            putExtra("status", status)
            putBatteryExtras(status)
        }
    }

    private var currentWearStatus = WearStatus()

    private fun changeUIWearStatus(status: WearStatus) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_WEAR_STATUS_CHANGED) {
            if (::mDevice.isInitialized) this.putExtra("address", mDevice.address)
            putWearStatusExtras(status)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_WEAR_STATUS_CHANGED) {
            putWearStatusExtras(status)
        }
    }

    private fun changeUITransparencyVocalEnhancementStatus(enabled: Boolean) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED) {
            this.putExtra("enabled", enabled)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED) {
            putExtra("enabled", enabled)
        }
    }

    private fun changeUISpatialAudioStatus(mode: Int) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED) {
            this.putExtra("mode", mode)
        }
    }

    private fun changeUIEqPreset(presetId: Int) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_EQ_PRESET_CHANGED) {
            this.putExtra("preset", presetId)
        }
    }

    private fun changeUISmartAncLevel(ordinal: Int) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_SMART_ANC_LEVEL_CHANGED) {
            this.putExtra("ordinal", ordinal)
        }
    }

    private fun changeUIGainLevel(level: Int) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_GAIN_LEVEL_CHANGED) {
            putExtra("level", level)
        }
    }

    private fun changeUILedState(enabled: Boolean) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_LED_STATE_CHANGED) {
            putExtra("enabled", enabled)
        }
    }

    fun handleUIEvent(intent: Intent) {
        when (intent.action) {
            OppoPodsAction.ACTION_GET_PODS_MAC -> {
                if (::mDevice.isInitialized) {
                    Intent(OppoPodsAction.ACTION_PODS_MAC_RECEIVED).apply {
                        setPackage("com.android.systemui")
                        putExtra("mac", mDevice.address)
                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        mContext?.sendBroadcast(this)
                    }
                    Log.i(TAG, "ACTION_GET_PODS_MAC mac=${mDevice.address}")
                }
            }
            OppoPodsAction.ACTION_PODS_UI_INIT -> {
                markAppUiActive()
                Log.i(TAG, "UI Init")
                changeUIConnectionState(currentConnectionState())
                if (::currentBatteryParams.isInitialized)
                    changeUIBatteryStatus(currentBatteryParams)
                changeUIWearStatus(currentWearStatus)
                changeUIAncStatus(currentAnc)
                changeUISmartAncLevel(currentSmartAncLevel)
                changeUITransparencyVocalEnhancementStatus(currentTransparencyVocalEnhancement)
                changeUISpatialAudioStatus(currentSpatialAudioMode)
                changeUIEqPreset(currentEqPreset)
                changeUIGainLevel(currentGainLevel)
                changeUILedState(currentLedEnabled)
                if (::mDevice.isInitialized && isConnected) {
                    sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                        this.putExtra("address", mDevice.address)
                        this.putExtra("device_name", mDevice.name ?: cachedDeviceName)
                    }
                    sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                        putExtra("device_name", mDevice.name ?: cachedDeviceName)
                    }
                    // The standalone detail page always asks the earbuds themselves for fresh
                    // left/right/case battery instead of trusting Android's single generic battery.
                    queryStatus(immediateReconnect = false)
                }
            }
            OppoPodsAction.ACTION_PODS_UI_CLOSED -> {
                appUiActive = false
                appUiActiveUntilMs = 0L
                Log.i(TAG, "UI Closed")
            }
            OppoPodsAction.ACTION_ANC_SELECT -> {
                val status = intent.getIntExtra("status", 0)
                setANCMode(status)
            }
            OppoPodsAction.ACTION_REFRESH_STATUS -> {
                queryStatus(immediateReconnect = true)
            }
            OppoPodsAction.ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_SET -> {
                val enabled = intent.getBooleanExtra("enabled", false)
                setTransparencyVocalEnhancement(enabled)
            }
            OppoPodsAction.ACTION_SPATIAL_AUDIO_SET -> {
                val mode = intent.getIntExtra("mode", SpatialAudioMode.OFF)
                setSpatialAudioMode(mode)
            }
            OppoPodsAction.ACTION_EQ_PRESET_SET -> {
                val preset = intent.getIntExtra("preset", -1)
                if (preset in EqPreset.ALL) setEqPreset(preset)
            }
            OppoPodsAction.ACTION_GAIN_LEVEL_SET -> {
                setGainLevel(intent.getIntExtra("level", currentGainLevel))
            }
            OppoPodsAction.ACTION_LED_STATE_SET -> {
                setLedEnabled(intent.getBooleanExtra("enabled", currentLedEnabled))
            }
            OppoPodsAction.ACTION_CYCLE_ANC -> {
                cycleAnc()
            }
            OppoPodsAction.ACTION_CONFIG_CHANGED -> {
                ConfigManager.refreshFromPrefs(mPrefs)
                Log.d(TAG, "Config synced")
                if (!currentCapabilities().adaptiveSupported && currentAnc == 4) {
                    setANCMode(2)
                }
            }
            OppoPodsAction.ACTION_RFCOMM_LOG_CONNECT -> {
                if (!RfcommLog.isEnabled()) {
                    RfcommLog.setEnabled(true, mContext)
                }
            }
            OppoPodsAction.ACTION_RFCOMM_LOG_DISCONNECT -> {
                RfcommLog.setEnabled(false)
            }
            OppoPodsAction.ACTION_RFCOMM_LOG_CLEAR -> {
                RfcommLog.clear()
            }
            OppoPodsAction.ACTION_RFCOMM_DEBUG_SEND -> {
                val hex = intent.getStringExtra("hex").orEmpty()
                sendDebugHex(hex)
            }
        }
    }

    fun currentStatusSnapshot(): StatusSnapshot {
        return StatusSnapshot(
            battery = if (::currentBatteryParams.isInitialized) currentBatteryParams else null,
            anc = currentAnc,
            transparencyVocalEnhancement = currentTransparencyVocalEnhancement,
            address = if (::mDevice.isInitialized) mDevice.address else null,
            deviceName = if (::mDevice.isInitialized) mDevice.name ?: cachedDeviceName else cachedDeviceName.takeIf { it.isNotEmpty() },
            connected = isConnected && socket != null,
            connecting = connectionJob?.isActive == true,
            reconnectPending = reconnectPending,
        )
    }

    private fun currentConnectionState(): String = when {
        isConnected && socket != null && ::currentBatteryParams.isInitialized -> "connected"
        connectionJob?.isActive == true || reconnectPending -> "connecting"
        isConnected && socket != null -> "connecting"
        else -> "disconnected"
    }

    private fun changeUIConnectionState(state: String) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED) {
            if (::mDevice.isInitialized) {
                putExtra("address", mDevice.address)
                putExtra("device_name", mDevice.name ?: cachedDeviceName)
            }
            putExtra("state", state)
        }
    }

    private fun sendAppStatusBroadcast(action: String, fill: Intent.() -> Unit = {}) {
        val ctx = mContext ?: return
        if (!isAppUiActive()) return
        Intent(action).apply {
            fill()
            this.`package` = BuildConfig.APPLICATION_ID
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            ctx.sendBroadcast(this)
        }
    }

    private fun isAppUiActive(): Boolean {
        if (!appUiActive) return false
        if (SystemClock.elapsedRealtime() <= appUiActiveUntilMs) return true
        appUiActive = false
        appUiActiveUntilMs = 0L
        Log.d(TAG, "app UI active timeout, stop app status broadcasts")
        return false
    }

    private fun markAppUiActive() {
        appUiActive = true
        appUiActiveUntilMs = SystemClock.elapsedRealtime() + APP_UI_ACTIVE_TIMEOUT_MS
    }


    fun miuiRefreshPayload(battery: BatteryParams?, anc: Int, transparencyVocalEnhancement: Boolean = false): String {
        val values = MutableList(16) { "" }
        values[0] = miuiBatteryValue(battery?.left)
        values[1] = miuiBatteryValue(battery?.right)
        values[2] = miuiBatteryValue(battery?.case)
        values[7] = miuiAncLevel(anc, transparencyVocalEnhancement)
        values[8] = "true"
        values[11] = "00"
        values[13] = "00"
        values[14] = "00"
        return values.joinToString(",")
    }

    private fun miuiBatteryValue(params: PodParams?): String {
        if (params?.isConnected != true) return "255"
        val value = params.battery.coerceIn(0, 100)
        return (if (params.isCharging) value or 128 else value).toString()
    }

    private fun miuiAncLevel(anc: Int, transparencyVocalEnhancement: Boolean): String {
        // Reuse HyperOS' existing ANC level UI as PUDDING's three modes.
        return when (anc) {
            2 -> "0100" // Basic ANC
            4 -> "0101" // Adaptive ANC
            5 -> "0102" // Wind-noise reduction
            3 -> "0200" // Transparency
            else -> "0000" // Off
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun handleBatteryChanged(result: BatteryParser.BatteryResult) {
        // GAIA battery notifications/responses may contain a subset of components. Preserve
        // the last value for components not present in this packet instead of turning them
        // into 0/disconnected. This keeps the three-battery UI stable.
        result.left?.let {
            lastKnownLeftBattery = it.level
            lastKnownLeftCharging = it.isCharging
        }
        result.right?.let {
            lastKnownRightBattery = it.level
            lastKnownRightCharging = it.isCharging
        }
        result.case?.let {
            lastKnownCaseBattery = it.level
            lastKnownCaseCharging = it.isCharging
        }

        val previous = if (::currentBatteryParams.isInitialized) currentBatteryParams else BatteryParams()
        val left = result.left?.let { PodParams(it.level, it.isCharging, true, 0) }
            ?: previous.left?.takeIf { it.isConnected }
            ?: PodParams(lastKnownLeftBattery, lastKnownLeftCharging, lastKnownLeftBattery > 0, 0)
        val right = result.right?.let { PodParams(it.level, it.isCharging, true, 0) }
            ?: previous.right?.takeIf { it.isConnected }
            ?: PodParams(lastKnownRightBattery, lastKnownRightCharging, lastKnownRightBattery > 0, 0)
        val case = result.case?.let { PodParams(it.level, it.isCharging, true, 0) }
            ?: previous.case?.takeIf { it.isConnected }
            ?: PodParams(lastKnownCaseBattery, lastKnownCaseCharging, lastKnownCaseBattery > 0, 0)

        Log.i(
            TAG,
            "PUDDING battery snapshot left=${left.battery}/${left.isConnected} " +
                    "right=${right.battery}/${right.isConnected} case=${case.battery}/${case.isConnected}"
        )

        val shouldShowToast = !mShowedConnectedToast
        if (shouldShowToast) {
            // Wait until at least one connected ear has valid battery data
            val hasValidData = (left.isConnected && left.battery > 0) ||
                    (right.isConnected && right.battery > 0)
            if (!hasValidData) return
        }

        val batteryParams = BatteryParams(left, right, case)
        currentBatteryParams = batteryParams

        if (shouldShowToast) {
            changeUIConnectionState("connected")
            sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                this.putExtra("address", mDevice.address)
                this.putExtra("device_name", mDevice.name ?: cachedDeviceName)
            }
            sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                putExtra("device_name", mDevice.name ?: cachedDeviceName)
            }
            // HyperOriG emits a Focus Island once for every real connection instead of
            // depending on a hidden/legacy island preference. Keep that behaviour for PUDDING.
            MiuiStrongToastUtil.showPodsBatteryToastByMiuiBt(mContext!!, batteryParams, mDevice)
            mShowedConnectedToast = true
        }
        MiuiStrongToastUtil.showPodsNotificationByMiuiBt(mContext!!, batteryParams, mDevice)
        changeUIBatteryStatus(batteryParams)

        lastTempBatt = if (left.isConnected && right.isConnected)
            minOf(left.battery, right.battery)
        else if (left.isConnected)
            left.battery
        else if (right.isConnected)
            right.battery
        else SystemApisUtils.BATTERY_LEVEL_UNKNOWN

        setRegularBatteryLevel(lastTempBatt)
    }

    private val routeCallback = object : MediaRouter2.RouteCallback() {
        override fun onRoutesUpdated(routes: List<MediaRoute2Info>) {
            Log.v(TAG, "routes updated: $routes")
            this@RfcommController.routes = routes
        }
    }

    private fun startRoutesScan() {
        if (routeScanStarted) return
        val executor = Executor { p0 ->
            CoroutineScope(Dispatchers.IO).launch { p0?.run() }
        }
        val preferredFeature = listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO, MediaRoute2Info.FEATURE_LIVE_VIDEO)
        mediaRouter.registerRouteCallback(executor, routeCallback, RouteDiscoveryPreference.Builder(preferredFeature, true).build())
        scanToken = mediaRouter.requestScan(MediaRouter2.ScanRequest.Builder().build())
        routeScanStarted = true
    }

    private fun stopRoutesScan() {
        scanToken?.let { mediaRouter.cancelScanRequest(it) }
        if (routeScanStarted) {
            mediaRouter.unregisterRouteCallback(routeCallback)
            routeScanStarted = false
        }
    }

    private fun createRfcommSocket(device: BluetoothDevice): BluetoothSocket {
        return device.createRfcommSocketToServiceRecord(PUDDING_RFCOMM_UUID)
    }

    fun connectPod(context: Context, device: BluetoothDevice, prefs: SharedPreferences, appRequested: Boolean = false) {
        connectionJob?.cancel()
        reconnectJob?.cancel()
        readerJob?.cancel()
        closeSocketOnly()
        mContext = context
        mDevice = device
        mPrefs = prefs
        cachedDeviceName = device.name ?: ""
        // Never carry a partial battery snapshot from a previous RFCOMM session into a
        // fresh PUDDING connection. The official app polls GAIA battery repeatedly and
        // receives LEFT(1), RIGHT(2) and CASE(3) independently.
        currentBatteryParams = BatteryParams()
        lastKnownLeftBattery = 0
        lastKnownRightBattery = 0
        lastKnownCaseBattery = 0
        lastKnownLeftCharging = false
        lastKnownRightCharging = false
        lastKnownCaseCharging = false
        if (appRequested) {
            markAppUiActive()
        }
        ConfigManager.refreshFromPrefs(mPrefs)
        Log.d(TAG, "Adaptive support initial: ${currentCapabilities().adaptiveSupported}")
        Log.d(TAG, "PUDDING RFCOMM UUID: $PUDDING_RFCOMM_UUID")

        if (!receiverRegistered) {
            context.registerReceiver(broadcastReceiver, IntentFilter().apply {
                this.addAction(OppoPodsAction.ACTION_ANC_SELECT)
                this.addAction(OppoPodsAction.ACTION_PODS_UI_INIT)
                this.addAction(OppoPodsAction.ACTION_PODS_UI_CLOSED)
                this.addAction(OppoPodsAction.ACTION_REFRESH_STATUS)
                this.addAction(OppoPodsAction.ACTION_GET_PODS_MAC)
                this.addAction(OppoPodsAction.ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_SET)
                this.addAction(OppoPodsAction.ACTION_SPATIAL_AUDIO_SET)
                this.addAction(OppoPodsAction.ACTION_EQ_PRESET_SET)
                this.addAction(OppoPodsAction.ACTION_GAIN_LEVEL_SET)
                this.addAction(OppoPodsAction.ACTION_LED_STATE_SET)
                this.addAction(OppoPodsAction.ACTION_CYCLE_ANC)
                this.addAction(OppoPodsAction.ACTION_CONFIG_CHANGED)
                this.addAction(OppoPodsAction.ACTION_RFCOMM_LOG_CONNECT)
                this.addAction(OppoPodsAction.ACTION_RFCOMM_LOG_DISCONNECT)
                this.addAction(OppoPodsAction.ACTION_RFCOMM_LOG_CLEAR)
                this.addAction(OppoPodsAction.ACTION_RFCOMM_DEBUG_SEND)
            }, Context.RECEIVER_EXPORTED)
            receiverRegistered = true
        }

        MediaControl.mContext = mContext
        mediaRouter = MediaRouter2.getInstance(mContext!!)
        startRoutesScan()

        isConnected = true
        changeUIConnectionState("connecting")

        connectRfcomm(initialDelayMs = 500L)

    }

    private fun sendExternalPodsStatusBroadcast(action: String, fill: Intent.() -> Unit = {}) {
        val ctx = mContext ?: return
        listOf("com.milink.service", "com.xiaomi.bluetooth", "com.android.settings").forEach { targetPackage ->
            Intent(action).apply {
                if (::mDevice.isInitialized) {
                    putExtra("address", mDevice.address)
                    putExtra("device_name", mDevice.name ?: cachedDeviceName)
                }
                fill()
                setPackage(targetPackage)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                ctx.sendBroadcast(this)
            }
        }
    }

    private fun Intent.putBatteryExtras(status: BatteryParams) {
        putExtra("left_battery", status.left?.battery ?: 0)
        putExtra("left_charging", status.left?.isCharging == true)
        putExtra("left_connected", status.left?.isConnected == true)
        putExtra("right_battery", status.right?.battery ?: 0)
        putExtra("right_charging", status.right?.isCharging == true)
        putExtra("right_connected", status.right?.isConnected == true)
        putExtra("case_battery", status.case?.battery ?: 0)
        putExtra("case_charging", status.case?.isCharging == true)
        putExtra("case_connected", status.case?.isConnected == true)
    }

    private fun Intent.putWearStatusExtras(status: WearStatus) {
        putExtra("left_wear_status", status.left?.value ?: -1)
        putExtra("right_wear_status", status.right?.value ?: -1)
        putExtra("case_wear_status", status.case?.value ?: -1)
    }

    private fun mergeWearStatus(current: WearStatus, update: WearStatus): WearStatus {
        return WearStatus(
            left = update.left ?: current.left,
            right = update.right ?: current.right,
            case = update.case ?: current.case
        )
    }

    private fun showIslandForWearStatusChange(previous: WearStatus, current: WearStatus) {
        if (!::currentBatteryParams.isInitialized) return
        val changedTimings = setOfNotNull(
            islandShowTimingForChange(previous.left, current.left),
            islandShowTimingForChange(previous.right, current.right),
            islandShowTimingForChange(previous.case, current.case),
        )
        if (changedTimings.any { shouldShowIsland(it) }) {
            MiuiStrongToastUtil.showPodsBatteryToastByMiuiBt(mContext ?: return, currentBatteryParams)
        }
    }

    private fun shouldShowIsland(timing: Int): Boolean {
        return ConfigManager.islandMode() == ConfigManager.ISLAND_MODE_MODULE &&
                timing in ConfigManager.islandShowTimings()
    }

    private fun islandShowTimingForChange(previous: WearState?, current: WearState?): Int? {
        if (previous == current) return null
        return when (current) {
            WearState.WEARING -> ConfigManager.ISLAND_SHOW_TIMING_WEARING
            WearState.REMOVED -> ConfigManager.ISLAND_SHOW_TIMING_REMOVED
            WearState.IN_CASE -> ConfigManager.ISLAND_SHOW_TIMING_IN_CASE
            else -> null
        }
    }

    private fun connectRfcomm(initialDelayMs: Long = 0L) {
        connectionJob?.cancel()
        connectionJob = CoroutineScope(Dispatchers.IO).launch {
            if (initialDelayMs > 0) delay(initialDelayMs)
            if (!isConnected || !::mDevice.isInitialized) return@launch
            closeSocketOnly()
            try {
                val newSocket = createRfcommSocket(mDevice)
                newSocket.connect()
                socket = newSocket
                reconnectAttempts.set(0)
                reconnectPending = false
                Log.d(TAG, "PUDDING RFCOMM connected! uuid=$PUDDING_RFCOMM_UUID")
                RfcommLog.i(mContext, TAG, "connected uuid=$PUDDING_RFCOMM_UUID")
                changeUIConnectionState("connecting")

                startPacketReader(newSocket.inputStream)

                delay(180)
                // Match the official MOONDROP app: negotiate GAIA with a v1 API-version
                // request, then use v4 for PUDDING commands.
                sendPacketSafe(PuddingGaia.v1(PuddingGaia.QUERY_GAIA_VERSION))
                delay(180)
                sendPacketSafe(PuddingGaia.v4(PuddingGaia.QUERY_NOISE_MODE))
                delay(100)
                sendPacketSafe(PuddingGaia.v4(PuddingGaia.QUERY_GAIN_LEVEL))
                delay(100)
                sendPacketSafe(PuddingGaia.v4(PuddingGaia.QUERY_LED_STATE))
                delay(100)
                sendPacketSafe(PuddingGaia.v4(PuddingGaia.QUERY_SUPPORTED_BATTERIES))
                delay(80)
                sendPacketSafe(PuddingGaia.v4(PuddingGaia.QUERY_BATTERY_LEVELS))

                changeUIConnectionState("connected")
                sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                    putExtra("address", mDevice.address)
                    putExtra("device_name", mDevice.name ?: cachedDeviceName)
                }
                sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                    putExtra("device_name", mDevice.name ?: cachedDeviceName)
                }
                mShowedConnectedToast = true
            } catch (e: IOException) {
                Log.e(TAG, "RFCOMM connect failed", e)
                changeUIConnectionState("error")
                scheduleReconnect("connect failed")
            }
        }
    }

    // No periodic RFCOMM polling. ANC changes are consumed from the earbud's GAIA
    // notifications/echoes. Fresh status is queried only on connect, when a UI/system page
    // explicitly opens/refreshes, or when a real ANC change triggers a battery refresh.

    private fun scheduleReconnect(reason: String, immediate: Boolean = false) {
        if (!isConnected || !::mDevice.isInitialized || mContext == null) return
        RfcommLog.w(mContext, TAG, "schedule reconnect reason=$reason immediate=$immediate")
        closeSocketOnly()
        reconnectPending = true
        if (immediate) {
            if (connectionJob?.isActive == true) {
                Log.d(TAG, "immediate RFCOMM reconnect skipped: connecting reason=$reason")
                return
            }
            Log.d(TAG, "immediate RFCOMM reconnect reason=$reason")
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectPending = false
            connectRfcomm()
            return
        }
        if (reconnectJob?.isActive == true) {
            Log.d(TAG, "RFCOMM reconnect already scheduled reason=$reason")
            return
        }
        val attempt = reconnectAttempts.incrementAndGet()
        Log.d(TAG, "schedule RFCOMM reconnect reason=$reason attempt=$attempt delay=${AUTO_RECONNECT_DELAY_MS}ms")
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            delay(AUTO_RECONNECT_DELAY_MS)
            reconnectJob = null
            reconnectPending = false
            connectRfcomm()
        }
    }

    private fun reconnectNowForRequest(reason: String) {
        if (socket != null && !reconnectPending) return
        scheduleReconnect(reason, immediate = true)
    }

    private fun closeSocketOnly() {
        readerJob?.cancel()
        readerJob = null
        puddingDecoder.reset()
        try {
            socket?.close()
        } catch (_: IOException) {}
        socket = null
    }

    private fun startPacketReader(inputStream: InputStream) {
        readerJob?.cancel()
        readerJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(1024)
            try {
                while (isConnected) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        val chunk = buffer.copyOfRange(0, bytesRead)
                        RfcommLog.d(mContext, "RFCOMM/RX", chunk.toHexString(HexFormat.UpperCase))
                        puddingDecoder.feed(chunk).forEach(::handlePuddingPayload)
                    } else if (bytesRead == -1) {
                        Log.d(TAG, "RFCOMM stream ended")
                        RfcommLog.w(mContext, TAG, "stream ended")
                        scheduleReconnect("stream ended")
                        break
                    }
                }
            } catch (e: IOException) {
                if (isConnected) {
                    Log.e(TAG, "RFCOMM read error", e)
                    RfcommLog.e(mContext, TAG, "read error: ${e.message.orEmpty()}")
                    scheduleReconnect("read error")
                }
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun handlePuddingPayload(payload: ByteArray) {
        Log.v(TAG, "PUDDING GAIA payload: ${payload.toHexString(HexFormat.UpperCase)}")

        // SET mode echo/notification: 00 1D 41 04 <mode>
        // GET mode response observed during official-app initialisation: 00 1D 41 03 <mode>
        if (payload.size >= 5 &&
            payload[0] == 0x00.toByte() && payload[1] == 0x1D.toByte() &&
            payload[2] == 0x41.toByte() && (payload[3] == 0x04.toByte() || payload[3] == 0x03.toByte())) {
            val wireValue = payload[4].toInt() and 0xFF
            val mode = PuddingGaia.NoiseMode.fromWire(wireValue)
            if (mode != null) {
                val previousAnc = currentAnc
                currentAnc = mode.uiStatus
                changeUIAncStatus(currentAnc)
                Log.i(TAG, "PUDDING noise mode: $mode (wire=$wireValue ui=${mode.uiStatus}) previous=$previousAnc")
                // If the mode changed outside PuddingPods (for example an earbud long-press),
                // refresh left/right/case battery immediately. App-originated SETs update
                // currentAnc first, so their echo does not trigger an extra battery query.
                if (previousAnc != currentAnc) {
                    queryBattery()
                }
                return
            }
        }

        // Qualcomm Battery feature RESPONSE: 00 1D 1B 01 [batteryId, level]...
        // Battery ids from the official GAIA SDK: 1=left, 2=right, 3=charging case.
        // PUDDING reports percentage only here; charging state is not encoded by this response.
        if (payload.size >= 6 &&
            payload[0] == 0x00.toByte() && payload[1] == 0x1D.toByte() &&
            payload[2] == 0x1B.toByte() && payload[3] == 0x01.toByte()) {
            var left: BatteryParser.BatteryInfo? = null
            var right: BatteryParser.BatteryInfo? = null
            var case: BatteryParser.BatteryInfo? = null
            var i = 4
            while (i + 1 < payload.size) {
                val id = payload[i].toInt() and 0xFF
                val level = payload[i + 1].toInt() and 0xFF
                if (level in 0..100) {
                    val info = BatteryParser.BatteryInfo(level, false)
                    when (id) {
                        1 -> left = info
                        2 -> right = info
                        3 -> case = info
                    }
                }
                i += 2
            }
            if (left != null || right != null || case != null) {
                handleBatteryChanged(BatteryParser.BatteryResult(left, right, case))
                Log.i(TAG, "PUDDING battery: left=${left?.level} right=${right?.level} case=${case?.level}")
                return
            }
        }

        // QTIL DAC gain: 00 1D 1F 01/02 <wire>. PUDDING reverses low/high on wire:
        // wire 2/1/0 = UI low/medium/high.
        if (payload.size >= 5 &&
            payload[0] == 0x00.toByte() && payload[1] == 0x1D.toByte() &&
            payload[2] == 0x1F.toByte() && (payload[3] == 0x01.toByte() || payload[3] == 0x02.toByte())) {
            val wireValue = payload[4].toInt() and 0xFF
            val gain = PuddingGaia.GainLevel.fromWire(wireValue)
            if (gain != null) {
                currentGainLevel = gain.uiValue
                changeUIGainLevel(currentGainLevel)
                Log.i(TAG, "PUDDING gain level: ui=${gain.uiValue} wire=$wireValue")
                return
            }
        }

        // QTIL LED: 00 1D 27 01/02 <0|1>.
        if (payload.size >= 5 &&
            payload[0] == 0x00.toByte() && payload[1] == 0x1D.toByte() &&
            payload[2] == 0x27.toByte() && (payload[3] == 0x01.toByte() || payload[3] == 0x02.toByte())) {
            val value = payload[4].toInt() and 0xFF
            if (value == 0 || value == 1) {
                currentLedEnabled = value == 1
                changeUILedState(currentLedEnabled)
                Log.i(TAG, "PUDDING LED enabled: $currentLedEnabled")
                return
            }
        }

        // The first v1 response is the GAIA API-version response. Nothing else is needed;
        // v4 commands can be sent immediately afterwards.
        if (payload.size >= 4 && payload[0] == 0x00.toByte() && payload[1] == 0x0A.toByte()) {
            Log.d(TAG, "PUDDING GAIA negotiation response: ${payload.toHexString(HexFormat.UpperCase)}")
            return
        }

        Log.d(TAG, "Unhandled PUDDING GAIA payload: ${payload.toHexString(HexFormat.UpperCase)}")
    }

    fun disconnectedPod(context: Context, device: BluetoothDevice) {
        isConnected = false
        connectionJob?.cancel()
        reconnectJob?.cancel()
        readerJob?.cancel()
        reconnectAttempts.set(0)
        reconnectPending = false

        closeSocketOnly()

        mContext?.let {
            stopRoutesScan()
            cancelPodsNotificationByMiuiBt(context, device)
            sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_DISCONNECTED) {
                putExtra("address", device.address)
            }
            if (receiverRegistered) {
                it.unregisterReceiver(broadcastReceiver)
                receiverRegistered = false
            }
        }

        mShowedConnectedToast = false
        currentWearStatus = WearStatus()
        currentAnc = 1
        currentSmartAncLevel = -1
        currentTransparencyVocalEnhancement = false
        currentSpatialAudioMode = SpatialAudioMode.OFF
        currentEqPreset = -1
        currentGainLevel = PuddingGaia.GainLevel.MEDIUM.uiValue
        currentLedEnabled = true
        lastKnownLeftBattery = 0
        lastKnownRightBattery = 0
        lastKnownCaseBattery = 0
        lastKnownLeftCharging = false
        lastKnownRightCharging = false
        lastKnownCaseCharging = false
        currentBatteryParams = BatteryParams()
        changeUIConnectionState("disconnected")
        cachedDeviceName = ""
        mContext = null
        MediaControl.mContext = null
    }

    private fun sendPacketSafe(packet: ByteArray, requestReason: String? = null) {
        if (requestReason != null) reconnectNowForRequest(requestReason)
        try {
            val currentSocket = socket ?: run {
                RfcommLog.w(mContext, "RFCOMM/TX", "socket null: ${packet.toHexString(HexFormat.UpperCase)}")
                scheduleReconnect("socket null before send", immediate = requestReason != null)
                return
            }
            RfcommLog.d(mContext, "RFCOMM/TX", packet.toHexString(HexFormat.UpperCase))
            currentSocket.outputStream.write(packet)
            currentSocket.outputStream.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Send packet failed", e)
            RfcommLog.e(mContext, TAG, "send failed: ${e.message.orEmpty()}")
            scheduleReconnect("send error", immediate = requestReason != null)
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun sendDebugHex(hex: String) {
        val normalized = hex.filterNot { it.isWhitespace() || it == ':' || it == '-' }
        if (normalized.isEmpty() || normalized.length % 2 != 0 || !normalized.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            RfcommLog.e(mContext, "RFCOMM/DEBUG", "invalid HEX: $hex")
            return
        }
        val packet = normalized.hexToByteArray()
        RfcommLog.i(mContext, "RFCOMM/DEBUG", "send ${packet.size} bytes")
        sendPacketSafe(packet, "rfcomm debug send")
    }

    fun setTransparencyVocalEnhancement(enabled: Boolean) { Log.d(TAG, "PUDDING: vocal enhancement unsupported; ignored=$enabled") }

    fun setSpatialAudioMode(mode: Int) { Log.d(TAG, "PUDDING: spatial audio unsupported; ignored=$mode") }

    fun setEqPreset(presetId: Int) { Log.d(TAG, "PUDDING: OPPO EQ preset protocol removed; ignored=$presetId") }

    fun setGainLevel(level: Int) {
        val gain = PuddingGaia.GainLevel.fromUi(level) ?: return
        currentGainLevel = gain.uiValue
        changeUIGainLevel(currentGainLevel)
        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "set PUDDING gain: ui=${gain.uiValue} wire=${gain.wireValue}")
            sendPacketSafe(PuddingGaia.v4(PuddingGaia.setGainLevel(gain)), "PUDDING gain")
            delay(140)
            sendPacketSafe(PuddingGaia.v4(PuddingGaia.QUERY_GAIN_LEVEL))
        }
    }

    fun setLedEnabled(enabled: Boolean) {
        currentLedEnabled = enabled
        changeUILedState(enabled)
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(PuddingGaia.v4(PuddingGaia.setLedEnabled(enabled)), "PUDDING LED")
            delay(140)
            sendPacketSafe(PuddingGaia.v4(PuddingGaia.QUERY_LED_STATE))
        }
    }

    fun cycleAnc() {
        val cycle = listOf(2, 4, 5, 3, 1)
        val currentIndex = cycle.indexOf(currentAnc)
        val next = cycle[(currentIndex + 1).floorMod(cycle.size)]
        setANCMode(next)
    }

    private fun currentCapabilities(): DeviceCapabilities {
        return detectDeviceCapabilities(
            deviceName = if (::mDevice.isInitialized) mDevice.name ?: cachedDeviceName else cachedDeviceName,
            adaptiveOverride = ConfigManager.adaptiveCapabilityOverride(),
            spatialAudioOverride = ConfigManager.spatialAudioCapabilityOverride(),
            spatialSoundSwitchOverride = ConfigManager.spatialSoundSwitchCapabilityOverride(),
            ancImplementationOverride = ConfigManager.ancImplementationCapabilityOverride(),
        )
    }

    private fun Int.floorMod(divisor: Int): Int = ((this % divisor) + divisor) % divisor

    fun setANCMode(mode: Int) {
        val puddingMode = PuddingGaia.NoiseMode.fromUiStatus(mode) ?: return
        Log.d(TAG, "set PUDDING noise mode: $puddingMode (ui=$mode wire=${puddingMode.wireValue})")
        currentAnc = mode
        changeUIAncStatus(mode)
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(PuddingGaia.v4(PuddingGaia.setNoiseMode(puddingMode)), "PUDDING noise control")
            delay(180)
            sendPacketSafe(PuddingGaia.v4(PuddingGaia.QUERY_NOISE_MODE))
        }
    }

    fun queryBattery() {
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(
                PuddingGaia.v4(PuddingGaia.QUERY_SUPPORTED_BATTERIES),
                "PUDDING battery query"
            )
            delay(80)
            sendPacketSafe(PuddingGaia.v4(PuddingGaia.QUERY_BATTERY_LEVELS))
        }
    }

    fun queryStatus(immediateReconnect: Boolean = true) {
        CoroutineScope(Dispatchers.IO).launch {
            sendStatusQueryPackets(immediateReconnect)
        }
    }

    private suspend fun sendStatusQueryPackets(immediateReconnect: Boolean = true) {
        val reason = if (immediateReconnect) "PUDDING status query" else null
        sendPacketSafe(PuddingGaia.v4(PuddingGaia.QUERY_NOISE_MODE), reason)
        delay(100)
        sendPacketSafe(PuddingGaia.v4(PuddingGaia.QUERY_GAIN_LEVEL))
        delay(100)
        sendPacketSafe(PuddingGaia.v4(PuddingGaia.QUERY_LED_STATE))
        delay(100)
        sendPacketSafe(PuddingGaia.v4(PuddingGaia.QUERY_SUPPORTED_BATTERIES))
        delay(80)
        sendPacketSafe(PuddingGaia.v4(PuddingGaia.QUERY_BATTERY_LEVELS))
    }

    fun disconnectAudio(context: Context, device: BluetoothDevice?) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter

        MediaControl.sendPause()

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    try {
                        val method = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                        method.invoke(proxy, device)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                    }
                }
            }
            override fun onServiceDisconnected(profile: Int) { }
        }, BluetoothProfile.HEADSET)

        CoroutineScope(Dispatchers.Default).launch {
            delay(500)
            for (route in routes) {
                if (route.type == MediaRoute2Info.TYPE_BUILTIN_SPEAKER) {
                    Log.d(TAG, "found speaker route $route")
                    mediaRouter.transferTo(route)
                }
            }
        }

        setRegularBatteryLevel(lastTempBatt)
    }

    fun connectAudio(context: Context, device: BluetoothDevice?) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    try {
                        val method = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        method.invoke(proxy, device)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                    }
                }
            }
            override fun onServiceDisconnected(profile: Int) { }
        }, BluetoothProfile.HEADSET)

        for (route in routes) {
            if (route.type == MediaRoute2Info.TYPE_BLUETOOTH_A2DP && route.name == device!!.name) {
                Log.d(TAG, "found bt route $route")
                mediaRouter.transferTo(route)
            }
        }

        val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
        statusBarManager.setIconVisibility("wireless_headset", true)
        setRegularBatteryLevel(lastTempBatt)
    }

    fun setRegularBatteryLevel(level: Int) {
        try {
            val service = getObjectField(mContext, "mAdapterService")
            callMethod(service, "setBatteryLevel", mDevice, level, false)
        } catch (e: Exception) {
            Log.e(TAG, "setRegularBatteryLevel failed", e)
        }
    }

    private fun getObjectField(instance: Any?, fieldName: String): Any? {
        if (instance == null) return null
        var cls: Class<*>? = instance.javaClass
        while (cls != null) {
            runCatching {
                return cls.getDeclaredField(fieldName).apply { isAccessible = true }.get(instance)
            }
            cls = cls.superclass
        }
        throw NoSuchFieldException(fieldName)
    }

    private fun callMethod(instance: Any?, methodName: String, vararg args: Any?): Any? {
        if (instance == null) return null
        var cls: Class<*>? = instance.javaClass
        while (cls != null) {
            cls.declaredMethods.firstOrNull { it.name == methodName && it.parameterTypes.size == args.size }?.let {
                it.isAccessible = true
                return it.invoke(instance, *args)
            }
            cls = cls.superclass
        }
        throw NoSuchMethodException(methodName)
    }
}
