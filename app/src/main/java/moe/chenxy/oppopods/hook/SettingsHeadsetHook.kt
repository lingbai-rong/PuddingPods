package moe.chenxy.oppopods.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.pods.isPuddingDeviceName
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams
import java.util.WeakHashMap

@SuppressLint("MissingPermission")
object SettingsHeadsetHook : HookContext() {
    private const val TAG = "PuddingPods-Settings"
    const val ACTION_OPEN_BT_DEVICE_DETAILS = "rongyi.puddingpods.action.OPEN_BT_DEVICE_DETAILS"
    const val EXTRA_DEVICE_ADDRESS = "device_address"
    private val MODULE_PACKAGE = BuildConfig.APPLICATION_ID
    private const val PUDDING_IMAGE_NAME = "builtin_img_box.png"
    private const val PREFS_NAME = "puddingpods_milink_state"
    private val knownOppoAddresses = linkedSetOf<String>()
    private val batteryViews = WeakHashMap<Any, BluetoothDevice>()
    private val headsetFragments = WeakHashMap<Any, Boolean>()
    private val redirectedNativeActivities = WeakHashMap<Activity, Boolean>()
    private var context: Context? = null
    private var receiverRegistered = false
    private var deviceDetailsBridgeRegistered = false
    private var currentAddress: String? = null
    private var currentName: String? = null
    private var currentBattery: BatteryParams = BatteryParams()
    private var currentAnc = 1
    private var currentTransparencyVocalEnhancement = false
    private var proxyCheckSupportCalls = 0
    private var proxySetCommonCommandCalls = 0
    private var proxyGetDeviceConfigCalls = 0
    private var proxyGetCommonConfigCalls = 0
    private val refreshHandler = Handler(Looper.getMainLooper())

    override fun onHook() {
        hookDeviceDetailsBridge()
        hookActivityEntry()
        hookSupportChecks()
        hookServiceProxy()
        hookBatteryView()
        hookFragmentState()
    }


    /**
     * Bridge into the Settings process so PuddingPods can open the real generic Bluetooth
     * device-details fragment (the page containing media/call audio, volume sync, LHDC and
     * low-latency switches on HyperOS). Launching SubSettings directly from a third-party UID
     * is restricted on some builds, so the actual startActivity call is performed from Settings.
     */
    private fun hookDeviceDetailsBridge() {
        runCatching {
            hookAfter(findMethod("android.app.Application", "attach", Context::class.java)) {
                val base = args[0] as? Context ?: return@hookAfter
                if (base.packageName != "com.android.settings") return@hookAfter
                registerDeviceDetailsBridge(base)
            }
        }.onFailure { Log.w(TAG, "hook Settings Application.attach bridge skipped", it) }
    }

    private fun registerDeviceDetailsBridge(base: Context) {
        if (deviceDetailsBridgeRegistered) return
        val filter = IntentFilter(ACTION_OPEN_BT_DEVICE_DETAILS)
        base.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val ctx = context ?: base
                val address = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS).orEmpty()
                if (address.isBlank()) return
                openGenericBluetoothDetailsInsideSettings(ctx, address)
            }
        }, filter, Context.RECEIVER_EXPORTED)
        deviceDetailsBridgeRegistered = true
        Log.d(TAG, "generic Bluetooth details bridge registered")
    }

    private fun openGenericBluetoothDetailsInsideSettings(context: Context, address: String) {
        val args = Bundle().apply { putString("device_address", address) }
        val subSettings = Intent().apply {
            setClassName("com.android.settings", "com.android.settings.SubSettings")
            putExtra(":settings:show_fragment", "com.android.settings.bluetooth.BluetoothDeviceDetailsFragment")
            putExtra(":settings:show_fragment_args", args)
            putExtra("device_address", address)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { context.startActivity(subSettings) }
            .onSuccess { Log.d(TAG, "opened BluetoothDeviceDetailsFragment address=$address") }
            .onFailure { first ->
                Log.w(TAG, "SubSettings Bluetooth details failed, falling back to Bluetooth settings", first)
                runCatching {
                    context.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }.onFailure { Log.e(TAG, "Bluetooth settings fallback failed", it) }
            }
    }

    private fun hookActivityEntry() {
        runCatching {
            hookBefore(findMethod("com.android.settings.bluetooth.MiuiHeadsetActivity", "onCreate", Bundle::class.java)) {
                val activity = instance as? Context ?: return@hookBefore
                registerStatusReceiver(activity)
                val intent = callMethod(instance, "getIntent") as? Intent ?: return@hookBefore
                val device = intent.parcelableDevice("android.bluetooth.device.extra.DEVICE")
                Log.d(TAG, "Activity.onCreate before device=${device.describe()} support=${intent.getStringExtra("MIUI_HEADSET_SUPPORT")} comeFrom=${intent.getStringExtra("COME_FROM")} btAddress=${intent.getStringExtra("bluetoothaddress")} known=$knownOppoAddresses current=$currentAddress")
                if (!isOppoPod(device)) return@hookBefore
                intent.putExtra("MIUI_HEADSET_SUPPORT", fakeSupport())
                intent.putExtra("COME_FROM", intent.getStringExtra("COME_FROM") ?: "MIUI_BLUETOOTH_SETTINGS")
                intent.putExtra("DEVICE_ID", fakeDeviceId())
                Log.d(TAG, "MiuiHeadsetActivity intent patched address=${device?.address} fakeDeviceId=${fakeDeviceId()}")

            }
            hookActivityStringGetter("getDeviceID") { fakeDeviceId() }
            hookActivityStringGetter("getSupport") { fakeSupport() }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetActivity skipped", it) }

        // HyperOS owns the native headset page, but the product artwork is replaced with
        // our built-in transparent MOONDROP PUDDING image. This is the safe fix5 route:
        // patch only the final large ImageView and never globally hook ImageView setters.
        runCatching {
            hookAfter(findMethod("com.android.settings.bluetooth.MiuiHeadsetActivity", "onCreate", Bundle::class.java)) {
                val activity = instance as? Activity ?: return@hookAfter
                if (activity.isFinishing) return@hookAfter
                val device = activity.intent.parcelableDevice("android.bluetooth.device.extra.DEVICE")
                if (!isOppoPod(device)) return@hookAfter
                scheduleActivityProductImagePatch(activity)
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetActivity product image skipped", it) }

        // Keep HyperOS' official headset ENTRY, but replace the page it lands on with the
        // standalone PUDDING detail screen. Focus Island is independent and still opens
        // PopupActivity; only MiuiHeadsetActivity is redirected here.
        runCatching {
            hookAfter(findMethod("com.android.settings.bluetooth.MiuiHeadsetActivity", "onCreate", Bundle::class.java)) {
                val activity = instance as? Activity ?: return@hookAfter
                if (activity.isFinishing || redirectedNativeActivities.containsKey(activity)) return@hookAfter
                val device = activity.intent.parcelableDevice("android.bluetooth.device.extra.DEVICE")
                val address = runCatching { device?.address }.getOrNull()
                    ?: activity.intent.getStringExtra("bluetoothaddress")
                    ?: currentAddress
                val looksLikePudding = isOppoPod(device)
                    || (!address.isNullOrBlank() && isOppoAddress(address))
                    || activity.intent.getStringExtra("DEVICE_ID") == fakeDeviceId()
                    || activity.intent.getStringExtra("MIUI_HEADSET_SUPPORT")?.startsWith(fakeDeviceId()) == true
                if (!looksLikePudding) return@hookAfter
                redirectOfficialHeadsetPage(activity, device, address)
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetActivity redirect skipped", it) }

        runCatching {
            hookBefore(findMethod("com.android.settings.bluetooth.MiuiHeadsetActivityPlugin", "onCreate", Bundle::class.java)) {
                val activity = instance as? Context ?: return@hookBefore
                registerStatusReceiver(activity)
                val intent = callMethod(instance, "getIntent") as? Intent ?: return@hookBefore
                val device = intent.parcelableDevice("android.bluetooth.device.extra.DEVICE")
                Log.d(TAG, "Plugin.onCreate before device=${device.describe()} support=${intent.getStringExtra("MIUI_HEADSET_SUPPORT")} comeFrom=${intent.getStringExtra("COME_FROM")} btAddress=${intent.getStringExtra("bluetoothaddress")} known=$knownOppoAddresses current=$currentAddress")
                if (!isOppoPod(device)) return@hookBefore
                intent.putExtra("MIUI_HEADSET_SUPPORT", fakeSupport())
                intent.putExtra("DEVICE_ID", fakeDeviceId())
                Log.d(TAG, "MiuiHeadsetActivityPlugin intent patched address=${device?.address}")
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetActivityPlugin skipped", it) }
    }

    private fun redirectOfficialHeadsetPage(
        activity: Activity,
        device: BluetoothDevice?,
        address: String?,
    ) {
        redirectedNativeActivities[activity] = true
        val target = Intent().apply {
            setClassName(MODULE_PACKAGE, "moe.chenxy.oppopods.PuddingDetailActivity")
            device?.let { putExtra(BluetoothDevice.EXTRA_DEVICE, it) }
            if (!address.isNullOrBlank()) putExtra("bluetoothaddress", address)
            putExtra("from_hyperos_headset_entry", true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching {
            activity.startActivity(target)
            Log.d(TAG, "official MiuiHeadsetActivity -> PuddingDetailActivity address=$address")
            activity.finish()
        }.onFailure { error ->
            redirectedNativeActivities.remove(activity)
            Log.e(TAG, "redirect official headset page failed", error)
        }
    }

    private fun hookActivityStringGetter(methodName: String, value: () -> String) {
        runCatching {
            hookAfter(findMethodByParamCount("com.android.settings.bluetooth.MiuiHeadsetActivity", methodName, 0)) {
                val device = runCatching { getObjectField(instance, "mDevice") as? BluetoothDevice }.getOrNull()
                Log.d(TAG, "Activity.$methodName old=$result device=${device.describe()} isOppo=${isOppoPod(device)}")
                if (!isOppoPod(device)) return@hookAfter
                result = value()
                Log.d(TAG, "Activity.$methodName forced=$result")
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetActivity.$methodName skipped", it) }
    }

    private fun hookSupportChecks() {
        hookStringStaticResult("com.android.settings.bluetooth.HeadsetIDConstants", "checkSupport") { support ->
            support.startsWith(fakeDeviceId()) || support.contains(fakeDeviceId())
        }
        hookStringStaticResult("com.android.settings.bluetooth.HeadsetIDConstants", "isTWS01Headset") { it == fakeDeviceId() }
        hookStringStaticResult("com.android.settings.bluetooth.HeadsetIDConstants", "isK77sHeadset") { false }
        hookBleMmaConnectByContext()
        hookBleMmaConnectByService()
    }

    private fun hookStringStaticResult(className: String, methodName: String, resultForValue: (String) -> Any) {
        runCatching {
            hookAfter(findMethod(className, methodName, String::class.java)) {
                val value = args[0] as? String ?: return@hookAfter
                Log.d(TAG, "$className.$methodName value=$value old=$result")
                val deviceId = fakeDeviceId()
                if (value != deviceId && !value.startsWith(deviceId)) return@hookAfter
                result = resultForValue(value)
                Log.d(TAG, "$className.$methodName forced value=$value result=$result")
            }
        }.onFailure { Log.w(TAG, "hook $className.$methodName(String) skipped", it) }
    }

    private fun hookBleMmaConnectByContext() {
        runCatching {
            hookAfter(findMethod("com.android.settings.bluetooth.HeadsetIDConstants", "isBleMmaConnect", Context::class.java, BluetoothDevice::class.java, String::class.java)) {
                val device = args[1] as? BluetoothDevice
                val deviceId = args[2] as? String
                Log.d(TAG, "isBleMmaConnect(Context) old=$result device=${device.describe()} deviceId=$deviceId service=${runCatching { callMethod(args[0], "getService") }.getOrNull()}")
                if (deviceId == fakeDeviceId() || isOppoPod(device)) {
                    result = true
                    Log.d(TAG, "isBleMmaConnect(Context) forced true")
                }
            }
        }.onFailure { Log.w(TAG, "hook HeadsetIDConstants.isBleMmaConnect(Context) skipped", it) }
    }

    private fun hookBleMmaConnectByService() {
        runCatching {
            val serviceClass = findClass("com.android.bluetooth.ble.app.IMiuiHeadsetService")
            hookAfter(findMethod("com.android.settings.bluetooth.HeadsetIDConstants", "isBleMmaConnect", serviceClass, BluetoothDevice::class.java, String::class.java)) {
                val device = args[1] as? BluetoothDevice
                val deviceId = args[2] as? String
                Log.d(TAG, "isBleMmaConnect(Service) old=$result service=${args[0]} device=${device.describe()} deviceId=$deviceId")
                if (deviceId == fakeDeviceId() || isOppoPod(device)) {
                    result = true
                    Log.d(TAG, "isBleMmaConnect(Service) forced true")
                }
            }
        }.onFailure { Log.w(TAG, "hook HeadsetIDConstants.isBleMmaConnect(Service) skipped", it) }
    }

    private fun hookServiceProxy() {
        val proxyClass = "com.android.bluetooth.ble.app.IMiuiHeadsetService\$Stub\$Proxy"
        hookProxyStringResult(proxyClass, "checkSupport", BluetoothDevice::class.java) { fakeSupport() }
        hookProxyStringArgResult(proxyClass, "getDeviceInfo") { fakeSupport() }
        hookProxyStringArgResult(proxyClass, "isSupportAudioSwitch") { "1" }
        hookProxyStringArgResult(proxyClass, "setCommonCommand", Int::class.java, String::class.java, BluetoothDevice::class.java) { commandArgs ->
            val command = commandArgs[0] as? Int
            if (command == 102) "0" else "1"
        }
        hookProxyVoidDeviceNoop(proxyClass, "connect", BluetoothDevice::class.java)
        hookProxyVoidDeviceNoop(proxyClass, "getDeviceConfig", BluetoothDevice::class.java)
        hookProxyVoidDeviceStringNoop(proxyClass, "getCommonConfig", BluetoothDevice::class.java, String::class.java)
        hookProxyBooleanStringResult(proxyClass, "isMiTWS") { true }
        hookProxyBooleanStringResult(proxyClass, "checkIsMiTWS") { true }
        hookProxyBooleanStringResult(proxyClass, "getRingFindState") { false }
        hookProxyVoidDeviceCommand(proxyClass, "changeAncMode", Int::class.java, BluetoothDevice::class.java) { commandArgs ->
            val miMode = commandArgs[0] as? Int ?: return@hookProxyVoidDeviceCommand null
            oppoAncFromSettings(miMode)
        }
        hookProxyVoidDeviceCommand(proxyClass, "changeAncLevel", String::class.java, BluetoothDevice::class.java) { commandArgs ->
            val level = commandArgs[0] as? String ?: return@hookProxyVoidDeviceCommand null
            oppoAncFromLevelCommand(level)
        }
    }

    private fun hookProxyStringResult(className: String, methodName: String, vararg parameterTypes: Class<*>, result: () -> String) {
        runCatching {
            hookBefore(findMethod(className, methodName, *parameterTypes)) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                val isOppo = isOppoPod(device)
                if (methodName == "checkSupport") proxyCheckSupportCalls++
                Log.d(TAG, "$methodName proxy call#${if (methodName == "checkSupport") proxyCheckSupportCalls else -1} device=${device.describe()} isOppo=$isOppo")
                if (!isOppo) return@hookBefore
                this.result = result()
                Log.d(TAG, "$methodName proxy forced result=${this.result} address=${device?.address}")
            }
        }.onFailure { Log.w(TAG, "hook proxy $methodName skipped", it) }
    }

    private fun hookProxyStringArgResult(className: String, methodName: String, vararg parameterTypes: Class<*>, result: (List<Any?>) -> String) {
        runCatching {
            hookBefore(findMethod(className, methodName, *parameterTypes)) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                val address = args.firstOrNull { it is String } as? String
                val isOppo = isOppoPod(device) || (address != null && isOppoAddress(address))
                if (methodName == "setCommonCommand") proxySetCommonCommandCalls++
                Log.d(TAG, "$methodName proxy call#${if (methodName == "setCommonCommand") proxySetCommonCommandCalls else -1} args=${args.describeArgs()} device=${device.describe()} addressArg=$address isOppo=$isOppo")
                if (!isOppo) return@hookBefore
                this.result = result(args)
                Log.d(TAG, "$methodName proxy forced result=${this.result} address=${device?.address ?: address}")
            }
        }.onFailure { Log.w(TAG, "hook proxy $methodName skipped", it) }
    }

    private fun hookProxyBooleanStringResult(className: String, methodName: String, result: () -> Boolean) {
        runCatching {
            hookBefore(findMethod(className, methodName, String::class.java)) {
                val address = args[0] as? String ?: return@hookBefore
                val isOppo = isOppoAddress(address)
                Log.d(TAG, "$methodName proxy string call address=$address isOppo=$isOppo oldKnown=$knownOppoAddresses current=$currentAddress")
                if (!isOppo) return@hookBefore
                this.result = result()
                Log.d(TAG, "$methodName proxy forced result=${this.result} address=$address")
            }
        }.onFailure { Log.w(TAG, "hook proxy $methodName skipped", it) }
    }

    private fun hookProxyVoidDeviceCommand(className: String, methodName: String, vararg parameterTypes: Class<*>, mode: (List<Any?>) -> Int?) {
        runCatching {
            hookBefore(findMethod(className, methodName, *parameterTypes)) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                Log.d(TAG, "$methodName proxy command args=${args.describeArgs()} device=${device.describe()} isOppo=${isOppoPod(device)}")
                if (!isOppoPod(device)) return@hookBefore
                val oppoMode = mode(args) ?: return@hookBefore
                currentAnc = oppoMode
                sendOppoAnc(oppoMode)
                sendAncChanged(oppoMode)
                this.result = null
                Log.d(TAG, "$methodName proxy command handled address=${device?.address} oppoMode=$oppoMode")
            }
        }.onFailure { Log.w(TAG, "hook proxy $methodName skipped", it) }
    }

    private fun hookProxyVoidDeviceNoop(className: String, methodName: String, vararg parameterTypes: Class<*>) {
        runCatching {
            hookBefore(findMethod(className, methodName, *parameterTypes)) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                if (methodName == "getDeviceConfig") proxyGetDeviceConfigCalls++
                val isOppo = isOppoPod(device)
                Log.d(TAG, "$methodName proxy before#${if (methodName == "getDeviceConfig") proxyGetDeviceConfigCalls else -1} device=${device.describe()} isOppo=$isOppo")
                if (!isOppo) return@hookBefore
                this.result = null
                Log.d(TAG, "$methodName proxy swallowed for virtual Oppo device")
            }
        }.onFailure { Log.w(TAG, "hook proxy noop $methodName skipped", it) }
    }

    private fun hookProxyVoidDeviceStringNoop(className: String, methodName: String, vararg parameterTypes: Class<*>) {
        runCatching {
            hookBefore(findMethod(className, methodName, *parameterTypes)) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                proxyGetCommonConfigCalls++
                val isOppo = isOppoPod(device)
                Log.d(TAG, "$methodName proxy before#$proxyGetCommonConfigCalls args=${args.describeArgs()} device=${device.describe()} isOppo=$isOppo")
                if (!isOppo) return@hookBefore
                this.result = null
                Log.d(TAG, "$methodName proxy swallowed for virtual Oppo device")
            }
        }.onFailure { Log.w(TAG, "hook proxy noop $methodName skipped", it) }
    }

    private fun hookBatteryView() {
        runCatching {
            hookConstructorAfter(findConstructorByParamCount("com.android.settings.bluetooth.tws.MiuiHeadsetBattery", 4)) {
                val device = args[0] as? BluetoothDevice ?: return@hookConstructorAfter
                val ctx = args[1] as? Context
                registerStatusReceiver(ctx)
                Log.d(TAG, "Battery.<init> device=${device.describe()} isOppo=${isOppoPod(device)} ctx=$ctx currentBattery=${settingsBatteryString()}")
                if (!isOppoPod(device)) return@hookConstructorAfter
                batteryViews[instance ?: return@hookConstructorAfter] = device
                requestBluetoothStatus("battery-init")
                updateBatteryView(instance)
                Log.d(TAG, "MiuiHeadsetBattery registered address=${device.address}")
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetBattery constructor skipped", it) }

        runCatching {
            hookBefore(findMethod("com.android.settings.bluetooth.tws.MiuiHeadsetBattery", "onBatteryChanged", String::class.java)) {
                val device = batteryViews[instance]
                Log.d(TAG, "Battery.onBatteryChanged(String) original=${args[0]} mappedDevice=${device.describe()} isOppo=${isOppoPod(device)} forced=${settingsBatteryString()}")
                if (!isOppoPod(device)) return@hookBefore
                result = null
                updateBatteryView(instance)
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetBattery.onBatteryChanged(String) skipped", it) }
    }

    private fun hookFragmentState() {
        runCatching {
            hookAfter(findMethodByParamCount("com.android.settings.bluetooth.MiuiHeadsetFragment", "onCreateView", 3)) {
                registerStatusReceiver(runCatching { getObjectField(instance, "mActivity") as? Context }.getOrNull())
                Log.d(TAG, "Fragment.onCreateView after ${fragmentDebug(instance)} isOppo=${isOppoFragment(instance)}")
                if (!isOppoFragment(instance)) return@hookAfter
                instance?.let { headsetFragments[it] = true }
                requestBluetoothStatus("fragment-create")
                injectFragmentStatus(instance)
                scheduleFragmentProductImagePatch(instance)
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetFragment.onCreateView skipped", it) }

        runCatching {
            hookAfter(findMethodByParamCount("com.android.settings.bluetooth.MiuiHeadsetFragment", "onServiceConnected", 0)) {
                Log.d(TAG, "Fragment.onServiceConnected after ${fragmentDebug(instance)} isOppo=${isOppoFragment(instance)}")
                if (!isOppoFragment(instance)) return@hookAfter
                instance?.let { headsetFragments[it] = true }
                requestBluetoothStatus("service-connected")
                injectFragmentStatus(instance)
                scheduleFragmentProductImagePatch(instance)
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetFragment.onServiceConnected skipped", it) }

        runCatching {
            hookBefore(findMethod("com.android.settings.bluetooth.MiuiHeadsetFragment", "refreshStatus", String::class.java, String::class.java)) {
                val key = args[0] as? String
                val data = args[1] as? String
                Log.d(TAG, "Fragment.refreshStatus before key=$key data=$data ${fragmentDebug(instance)} isOppo=${isOppoFragment(instance)}")
                if (isOppoFragment(instance) && key?.startsWith("MMA_CONNECTION_FAILED") == true) {
                    Log.w(TAG, "Fragment.refreshStatus swallowed MMA failure for virtual Oppo device key=$key")
                    injectFragmentStatus(instance)
                    result = null
                }
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetFragment.refreshStatus skipped", it) }

        runCatching {
            hookBefore(findMethod("com.android.settings.bluetooth.MiuiHeadsetFragment", "handleConnectMmaFailed", String::class.java)) {
                Log.w(TAG, "Fragment.handleConnectMmaFailed arg=${args[0]} ${fragmentDebug(instance)} isOppo=${isOppoFragment(instance)}")
                if (isOppoFragment(instance)) {
                    injectFragmentStatus(instance)
                    result = null
                    Log.w(TAG, "Fragment.handleConnectMmaFailed swallowed for virtual Oppo device")
                }
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetFragment.handleConnectMmaFailed skipped", it) }

        hookFragmentAncCommand("updateAncMode", Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!) { commandArgs ->
            oppoAncFromSettings(commandArgs[0] as? Int ?: 0)
        }
        hookFragmentAncCommand("updateAncLevel", String::class.java, Boolean::class.javaPrimitiveType!!) { commandArgs ->
            val level = commandArgs[0] as? String ?: ""
            oppoAncFromLevelCommand(level)
        }
    }

    private fun hookFragmentAncCommand(methodName: String, vararg parameterTypes: Class<*>, mode: (List<Any?>) -> Int?) {
        runCatching {
            hookBefore(findMethod("com.android.settings.bluetooth.MiuiHeadsetFragment", methodName, *parameterTypes)) {
                Log.d(TAG, "MiuiHeadsetFragment.$methodName before args=${args.describeArgs()} ${fragmentDebug(instance)} isOppo=${isOppoFragment(instance)}")
                if (!isOppoFragment(instance)) return@hookBefore
                val updateDevice = args.getOrNull(1) as? Boolean ?: true
                if (!updateDevice) return@hookBefore
                val oppoMode = mode(args) ?: return@hookBefore
                currentAnc = oppoMode
                sendOppoAnc(oppoMode)
                sendAncChanged(oppoMode)
                runCatching { callMethod(instance, "updateAncUi", settingsAncLevel(), false) }
                injectFragmentStatus(instance)
                result = null
                Log.d(TAG, "MiuiHeadsetFragment.$methodName handled oppoMode=$oppoMode")
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetFragment.$methodName skipped", it) }
    }

    private fun registerStatusReceiver(ctx: Context?) {
        if (ctx == null || receiverRegistered) return
        context = ctx.applicationContext ?: ctx
        loadState()
        val filter = IntentFilter().apply {
            addAction(OppoPodsAction.ACTION_PODS_CONNECTED)
            addAction(OppoPodsAction.ACTION_PODS_DISCONNECTED)
            addAction(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_ANC_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED)
            addAction(OppoPodsAction.ACTION_CONFIG_CHANGED)
        }
        context?.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    OppoPodsAction.ACTION_CONFIG_CHANGED -> {
                        refreshConfig()
                        updateFragments()
                    }
                    OppoPodsAction.ACTION_PODS_CONNECTED -> {
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentName = intent.getStringExtra("device_name") ?: currentName
                        currentAddress?.let { knownOppoAddresses.add(it.uppercase()) }
                    }
                    OppoPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentBattery = intent.batteryStatusFromExtras() ?: intent.parcelableStatus() ?: currentBattery
                        currentAddress?.let { knownOppoAddresses.add(it.uppercase()) }
                        saveState(context)
                        updateBatteryViews()
                        updateFragments()
                    }
                    OppoPodsAction.ACTION_PODS_ANC_CHANGED -> {
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentAnc = intent.getIntExtra("status", currentAnc)
                        currentAddress?.let { knownOppoAddresses.add(it.uppercase()) }
                        saveState(context)
                        updateFragments()
                    }
                    OppoPodsAction.ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED -> {
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentTransparencyVocalEnhancement = intent.getBooleanExtra("enabled", currentTransparencyVocalEnhancement)
                        currentAddress?.let { knownOppoAddresses.add(it.uppercase()) }
                        saveState(context)
                        updateFragments()
                    }
                }
                Log.d(TAG, "state action=${intent?.action} address=$currentAddress anc=$currentAnc battery=${settingsBatteryString()}")
            }
        }, filter, Context.RECEIVER_EXPORTED)
        receiverRegistered = true
        requestBluetoothStatus("receiver-register")
        Log.d(TAG, "registered status receiver context=$context")
    }

    private fun requestBluetoothStatus(reason: String) {
        val ctx = context ?: return
        // UI_INIT publishes cached state and performs one fresh GAIA status query. Do not pair
        // it with ACTION_REFRESH_STATUS here or run a timer: that would duplicate RFCOMM traffic.
        ctx.sendBroadcast(Intent(OppoPodsAction.ACTION_PODS_UI_INIT).apply {
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
        Log.d(TAG, "requested one-shot bluetooth status reason=$reason")
    }

    private fun updateBatteryViews() {
        batteryViews.keys.toList().forEach { view ->
            runCatching { updateBatteryView(view) }
                .onFailure { Log.w(TAG, "update battery view failed", it) }
        }
    }

    private fun updateBatteryView(view: Any?) {
        val values = settingsBatteryValues()
        callMethod(view, "onBatteryChanged", values[0], values[1], values[2])
        Log.d(TAG, "Battery.onBatteryChanged(int,int,int) forced=${values.joinToString(",")}")
    }

    private fun updateFragments() {
        headsetFragments.keys.toList().forEach { fragment ->
            if (isOppoFragment(fragment)) {
                injectFragmentStatus(fragment)
            }
        }
    }

    private fun injectFragmentStatus(fragment: Any?) {
        runCatching {
            val payload = "${settingsAncMode()}|0100;0101;0102;0200|${settingsBatteryString()}|00"
            Log.d(TAG, "injectFragmentStatus payload=$payload ${fragmentDebug(fragment)}")
            callMethod(fragment, "updateAtUiInfo", payload)
            callMethod(fragment, "updateAncUi", settingsAncLevel(), false)
            val device = runCatching { getObjectField(fragment, "mDevice") as? BluetoothDevice }.getOrNull()
            val address = device?.address
            if (address != null) {
                val refreshPayload = settingsRefreshPayload()
                Log.d(TAG, "injectFragmentStatus refreshPayload=$refreshPayload address=$address")
                callMethod(fragment, "refreshStatus", address, refreshPayload)
            }
            ensurePuddingThreeModeUi(fragment)
            ensureAppSettingsPreference(fragment)
            Log.d(TAG, "fragment status injected anc=$currentAnc battery=${settingsBatteryString()}")
        }.onFailure { Log.w(TAG, "inject fragment status failed", it) }
    }


    private data class PuddingAncUi(
        val controlRow: LinearLayout,
        val labels: List<TextView>,
        val hiddenFrameworkViews: List<View>,
    )

    private val puddingAncUi = WeakHashMap<Any, PuddingAncUi>()
    private const val ANC_LABEL_ROW_TAG = "puddingpods_anc_three_labels_v2"
    private const val APP_SETTINGS_KEY = "puddingpods_app_settings"

    /**
     * Device id 01010607 selects a native HyperOS template whose framework control is
     * physically four-step.  Feeding only three ANC codes therefore cannot turn the four dots
     * into three.  Keep the framework objects alive for Settings' internal references, but hide
     * their visual row and slider, then overlay one simple three-segment control in the same
     * card.  This avoids both the old removeView crash and the 4-dot regression.
     */
    private fun ensurePuddingThreeModeUi(fragment: Any?) {
        val root = runCatching { callMethod(fragment, "getView") as? View }.getOrNull() ?: return
        val apply = { installPuddingThreeModeUi(fragment, root) }
        root.post(apply)
        listOf(120L, 320L, 700L, 1_500L, 3_000L).forEach { root.postDelayed(apply, it) }
    }

    private fun installPuddingThreeModeUi(fragment: Any?, root: View) {
        if (fragment == null || !isOppoFragment(fragment)) return

        puddingAncUi[fragment]?.let { existing ->
            existing.hiddenFrameworkViews.forEach { it.visibility = View.GONE }
            updatePuddingThreeModeUi(fragment, existing)
            return
        }

        val all = mutableListOf<View>()
        walkViews(root) { all += it }

        val knownAncLabels = setOf(
            "自适应", "轻度", "均衡", "深度",
            "自適應", "輕度", "均衡", "深度",
            "Smart", "Light", "Medium", "Deep",
            // Include labels left by earlier PuddingPods patches so an upgrade can repair
            // an already-mutated Settings process without requiring another reboot.
            "基础", "基礎", "抗风噪", "抗風噪",
        )
        val namedLabels = all.filterIsInstance<TextView>().filter {
            it.tag != ANC_LABEL_ROW_TAG && it.text?.toString()?.trim() in knownAncLabels
        }
        if (namedLabels.isEmpty()) return

        // Pick the parent that owns most ANC labels.  On the user's HyperOS build the current
        // broken state can expose only 基础/抗风噪, so do not require three named labels.
        val labelsParent = namedLabels
            .mapNotNull { it.parent as? ViewGroup }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key ?: return

        val anchorY = namedLabels.map { screenY(it) }.average().toFloat()
        val density = root.resources.displayMetrics.density
        val rootWidth = root.width.takeIf { it > 0 } ?: root.resources.displayMetrics.widthPixels

        // Preserve objects, only remove them from layout/drawing.
        val frameworkLabelViews = mutableListOf<View>()
        for (i in 0 until labelsParent.childCount) {
            val child = labelsParent.getChildAt(i)
            if (child is TextView || kotlin.math.abs(screenY(child) - anchorY) < 42f * density) {
                frameworkLabelViews += child
            }
        }
        if (frameworkLabelViews.isEmpty()) frameworkLabelViews.addAll(namedLabels)
        frameworkLabelViews.forEach { it.visibility = View.GONE }

        fun isAncestor(candidate: View, target: View): Boolean {
            var p = target.parent
            while (p is View) {
                if (p === candidate) return true
                p = p.parent
            }
            return false
        }

        // Hide the real four-dot slider.  Prefer actual SeekBar/vendor slider classes; use a
        // geometry fallback only for non-container views immediately above the label row.
        val sliderCandidates = all.filter { view ->
            if (view === root || view === labelsParent || view is TextView || isAncestor(view, labelsParent)) return@filter false
            val className = view.javaClass.name.lowercase()
            val namedSlider = className.contains("seek") || className.contains("slider") || className.contains("progress")
            val y = screenY(view)
            val nearLabels = y >= anchorY - 145f * density && y <= anchorY + 18f * density
            val wideAndShort = view !is ViewGroup &&
                view.width >= (rootWidth * 0.42f).toInt() &&
                view.height in (6f * density).toInt()..(90f * density).toInt()
            nearLabels && (namedSlider || wideAndShort)
        }
        val frameworkSlider = sliderCandidates.minByOrNull { kotlin.math.abs(screenY(it) - anchorY) }
        frameworkSlider?.visibility = View.GONE

        val host = labelsParent.parent as? ViewGroup ?: labelsParent
        val existingRow = (0 until host.childCount)
            .map { host.getChildAt(it) }
            .filterIsInstance<LinearLayout>()
            .firstOrNull { it.tag == ANC_LABEL_ROW_TAG }

        val row = existingRow ?: LinearLayout(host.context).apply {
            tag = ANC_LABEL_ROW_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(18, 127, 127, 127))
            }
            layoutParams = if (host is LinearLayout) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(6)
                }
            } else {
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
            }
            val titles = listOf("基础", "自适应", "抗风噪")
            titles.forEachIndexed { index, title ->
                addView(TextView(context).apply {
                    text = title
                    gravity = Gravity.CENTER
                    textSize = 15f
                    setPadding(dp(5), dp(8), dp(5), dp(8))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    setOnClickListener { selectPuddingAncLevel(fragment, index) }
                })
            }
            val insertAt = (host.indexOfChild(labelsParent) + 1).coerceIn(0, host.childCount)
            host.addView(this, insertAt)
        }

        val customLabels = (0 until row.childCount).mapNotNull { row.getChildAt(it) as? TextView }
        if (customLabels.size != 3) return

        val hidden = (frameworkLabelViews + listOfNotNull(frameworkSlider)).distinct()
        val ui = PuddingAncUi(row, customLabels, hidden)
        puddingAncUi[fragment] = ui
        updatePuddingThreeModeUi(fragment, ui)
        Log.d(TAG, "installed native-page PUDDING 3-segment ANC control hiddenFramework=${hidden.map { it.javaClass.simpleName }}")
    }

    private fun selectPuddingAncLevel(fragment: Any?, index: Int) {
        val mode = when (index.coerceIn(0, 2)) {
            0 -> 2 // BASIC
            1 -> 4 // ADAPTIVE
            else -> 5 // WIND_REDUCTION
        }
        currentAnc = mode
        sendOppoAnc(mode)
        sendAncChanged(mode)
        puddingAncUi[fragment]?.let { updatePuddingThreeModeUi(fragment, it) }
        // Keep the native fragment's cached value in sync, but never let it redraw the old
        // four-dot widget without immediately hiding it again.
        runCatching { callMethod(fragment, "updateAncUi", settingsAncLevel(), false) }
        ensurePuddingThreeModeUi(fragment)
        Log.d(TAG, "PUDDING native 3-mode selected index=$index mode=$mode")
    }

    private fun updatePuddingThreeModeUi(fragment: Any?, ui: PuddingAncUi) {
        ui.hiddenFrameworkViews.forEach { it.visibility = View.GONE }
        val selected = when (currentAnc) {
            2 -> 0
            4 -> 1
            5 -> 2
            else -> 1
        }
        val active = currentAnc == 2 || currentAnc == 4 || currentAnc == 5
        ui.controlRow.visibility = if (active) View.VISIBLE else View.GONE

        val colors = ui.controlRow.context.obtainStyledAttributes(
            intArrayOf(android.R.attr.colorAccent, android.R.attr.textColorPrimary)
        )
        val accent = colors.getColor(0, Color.rgb(0, 122, 255))
        val normal = colors.getColor(1, Color.DKGRAY)
        colors.recycle()

        ui.labels.forEachIndexed { index, label ->
            val isSelected = active && index == selected
            label.setTextColor(if (isSelected) accent else normal)
            label.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            label.background = GradientDrawable().apply {
                cornerRadius = label.dp(13).toFloat()
                setColor(if (isSelected) Color.argb(32, Color.red(accent), Color.green(accent), Color.blue(accent)) else Color.TRANSPARENT)
            }
        }
    }

    private fun screenY(view: View): Float {
        val location = IntArray(2)
        runCatching { view.getLocationOnScreen(location) }
        return location[1].toFloat()
    }

    private fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Leave HyperOS' own "更多设置" preference completely untouched. Add a separate
     * "应用设置" row directly after it which opens PuddingPods' Settings tab.
     */
    private fun ensureAppSettingsPreference(fragment: Any?) {
        if (fragment == null || !isOppoFragment(fragment)) return
        val screen = runCatching { callMethod(fragment, "getPreferenceScreen") }.getOrNull() ?: return
        val ctx = runCatching { callMethod(fragment, "getContext") as? Context }.getOrNull() ?: context ?: return

        data class PrefLocation(val pref: Any, val parent: Any)
        var existing: Any? = null
        var more: PrefLocation? = null

        fun walk(group: Any) {
            val count = runCatching { callMethod(group, "getPreferenceCount") as? Int }.getOrNull() ?: return
            for (i in 0 until count) {
                val pref = runCatching { callMethod(group, "getPreference", i) }.getOrNull() ?: continue
                val key = runCatching { callMethod(pref, "getKey")?.toString().orEmpty() }.getOrDefault("")
                val title = runCatching { callMethod(pref, "getTitle")?.toString().orEmpty().trim() }.getOrDefault("")
                if (key == APP_SETTINGS_KEY) existing = pref
                if (title == "更多设置" || title == "更多設定" || title.equals("More settings", true)) {
                    more = PrefLocation(pref, group)
                }
                runCatching { callMethod(pref, "getPreferenceCount") as? Int }.getOrNull()?.let { nested ->
                    if (nested >= 0) walk(pref)
                }
            }
        }
        walk(screen)
        if (existing != null) return

        runCatching {
            val prefClass = Class.forName("androidx.preference.Preference", false, appClassLoader)
            val pref = prefClass.getConstructor(Context::class.java).newInstance(ctx)
            callMethod(pref, "setKey", APP_SETTINGS_KEY)
            callMethod(pref, "setTitle", "应用设置")
            callMethod(pref, "setSummary", "打开 PuddingPods 设置")
            val target = Intent().apply {
                setClassName(BuildConfig.APPLICATION_ID, "moe.chenxy.oppopods.MainActivity")
                putExtra("open_settings", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            callMethod(pref, "setIntent", target)
            val parent = more?.parent ?: screen
            val order = more?.pref?.let { runCatching { callMethod(it, "getOrder") as? Int }.getOrNull() }
            if (order != null && order < Int.MAX_VALUE - 1) callMethod(pref, "setOrder", order + 1)
            callMethod(parent, "addPreference", pref)
            Log.d(TAG, "added native-page 应用设置 preference below system 更多设置")
        }.onFailure { Log.w(TAG, "failed to add 应用设置 preference", it) }
    }

    /**
     * Re-apply the PUDDING artwork a few times because HyperOS can asynchronously replace
     * the product drawable after the Activity/Fragment is initially created.
     */
    private fun scheduleActivityProductImagePatch(activity: Activity) {
        listOf(80L, 250L, 600L, 1_200L, 2_500L, 5_000L).forEach { delay ->
            refreshHandler.postDelayed({
                val root = runCatching { activity.window.decorView }.getOrNull() ?: return@postDelayed
                patchProductImage(root)
            }, delay)
        }
    }

    private fun scheduleFragmentProductImagePatch(fragment: Any?) {
        listOf(120L, 500L, 1_500L, 3_000L).forEach { delay ->
            refreshHandler.postDelayed({
                val root = runCatching { callMethod(fragment, "getView") as? View }.getOrNull()
                if (root != null) patchProductImage(root)
                val activity = runCatching { getObjectField(fragment, "mActivity") as? Activity }.getOrNull()
                activity?.window?.decorView?.let { patchProductImage(it) }
            }, delay)
        }
    }

    /**
     * Replace only the large product image in HyperOS' native headset page.
     * The bitmap is served from our exported read-only ContentProvider so Settings does not
     * need direct resource access to the module APK.
     */
    private fun patchProductImage(root: View) {
        val ctx = root.context ?: return
        val density = ctx.resources.displayMetrics.density
        val screenHeight = ctx.resources.displayMetrics.heightPixels
        val images = mutableListOf<ImageView>()
        walkViews(root) { if (it is ImageView && it.visibility == View.VISIBLE) images += it }

        val candidate = images.mapNotNull { image ->
            val pos = IntArray(2)
            if (runCatching { image.getLocationOnScreen(pos) }.isFailure) return@mapNotNull null
            val y = pos[1]
            if (image.width < 150 * density || image.height < 110 * density) return@mapNotNull null
            if (y < 48 * density || y > screenHeight * 0.64f) return@mapNotNull null
            val idName = runCatching {
                if (image.id != View.NO_ID) image.resources.getResourceEntryName(image.id) else ""
            }.getOrDefault("")
            val nameBonus = if (listOf("headset", "ear", "device", "product", "image", "picture", "pic")
                    .any { idName.contains(it, ignoreCase = true) }) 5_000_000_000L else 0L
            val score = image.width.toLong() * image.height.toLong() + nameBonus +
                (screenHeight - y).coerceAtLeast(0).toLong() * 1_000L
            image to score
        }.maxByOrNull { it.second }?.first ?: return

        val uri = Uri.parse("content://$MODULE_PACKAGE.podimages/$PUDDING_IMAGE_NAME")
        runCatching {
            candidate.adjustViewBounds = false
            candidate.scaleType = ImageView.ScaleType.CENTER_INSIDE
            candidate.setImageURI(null)
            candidate.setImageURI(uri)
            candidate.tag = "PuddingPodsProductImage"
            Log.d(TAG, "native headset product image replaced uri=$uri view=${candidate.javaClass.name} ${candidate.width}x${candidate.height}")
        }.onFailure { Log.w(TAG, "replace native headset product image failed uri=$uri", it) }
    }

    private fun walkViews(root: View, action: (View) -> Unit) {
        action(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) walkViews(root.getChildAt(i), action)
        }
    }

    private fun isOppoFragment(fragment: Any?): Boolean {
        val device = runCatching { getObjectField(fragment, "mDevice") as? BluetoothDevice }.getOrNull()
        val deviceId = runCatching { getObjectField(fragment, "mDeviceId") as? String }.getOrNull()
        val support = runCatching { getObjectField(fragment, "mSupport") as? String }.getOrNull()
        val fakeDeviceId = fakeDeviceId()
        return isOppoPod(device) || deviceId == fakeDeviceId || support?.startsWith(fakeDeviceId) == true
    }

    private fun isOppoPod(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val address = runCatching { device.address }.getOrNull()
        if (address != null && isOppoAddress(address)) return true
        val name = runCatching { device.name ?: device.alias }.getOrNull().orEmpty()
        val result = isPuddingDeviceName(name)
        if (result && address != null) {
            knownOppoAddresses.add(address.uppercase())
            currentAddress = address
            currentName = name
        }
        return result
    }

    private fun BluetoothDevice?.describe(): String {
        if (this == null) return "null"
        val address = runCatching { this.address }.getOrNull()
        val name = runCatching { this.name }.getOrNull()
        val alias = runCatching { this.alias }.getOrNull()
        return "BluetoothDevice(address=$address,name=$name,alias=$alias)"
    }

    private fun List<Any?>.describeArgs(): String {
        return joinToString(prefix = "[", postfix = "]") { arg ->
            when (arg) {
                is BluetoothDevice -> arg.describe()
                else -> arg?.toString() ?: "null"
            }
        }
    }

    private fun fragmentDebug(fragment: Any?): String {
        val device = runCatching { getObjectField(fragment, "mDevice") as? BluetoothDevice }.getOrNull()
        val deviceId = runCatching { getObjectField(fragment, "mDeviceId") as? String }.getOrNull()
        val support = runCatching { getObjectField(fragment, "mSupport") as? String }.getOrNull()
        val service = runCatching { getObjectField(fragment, "mService") }.getOrNull()
        val hfp = runCatching { getObjectField(fragment, "mBluetoothHfp") }.getOrNull()
        val cached = runCatching { getObjectField(fragment, "mCachedDevice") }.getOrNull()
        val supportAnc = runCatching { getObjectField(fragment, "mSupportAnc") }.getOrNull()
        val ancCached = runCatching { getObjectField(fragment, "mAncCached") }.getOrNull()
        val pendingAnc = runCatching { getObjectField(fragment, "mPendingAnc") }.getOrNull()
        val ancPendingStatus = runCatching { getObjectField(fragment, "mAncPendingStatus") }.getOrNull()
        return "fragment(device=${device.describe()},deviceId=$deviceId,support=$support,service=$service,hfp=$hfp,cached=$cached,supportAnc=$supportAnc,ancCached=$ancCached,pendingAnc=$pendingAnc,ancPending=$ancPendingStatus)"
    }

    private fun isOppoAddress(address: String): Boolean {
        val normalized = address.uppercase()
        return normalized == currentAddress?.uppercase() || normalized in knownOppoAddresses
    }

    private fun settingsBatteryString(): String {
        return settingsBatteryValues().joinToString(",")
    }

    private fun settingsBatteryValues(): List<Int> {
        loadState()
        return listOf(
            batteryValue(currentBattery.left),
            batteryValue(currentBattery.right),
            batteryValue(currentBattery.case)
        )
    }

    private fun batteryValue(params: PodParams?): Int {
        if (params?.isConnected != true) return 255
        val value = params.battery.coerceIn(0, 100)
        return if (params.isCharging) value or 128 else value
    }

    private fun settingsAncMode(): String {
        loadState()
        return when (currentAnc) {
            2, 4, 5 -> "1" // BASIC / ADAPTIVE / WIND_REDUCTION => ANC
            3 -> "2"       // transparency
            else -> "0"    // off
        }
    }

    private fun settingsAncLevel(): String {
        loadState()
        // Reuse three stable HyperOS ANC level codes, but map them to PUDDING modes.
        return when (currentAnc) {
            2 -> "0100" // PUDDING basic ANC
            4 -> "0101" // PUDDING adaptive ANC
            5 -> "0102" // PUDDING wind-noise reduction
            3 -> "0200" // transparency
            else -> "0000"
        }
    }

    private fun settingsRefreshPayload(): String {
        val battery = settingsBatteryString().split(",")
        val left = battery.getOrNull(0).orEmpty()
        val right = battery.getOrNull(1).orEmpty()
        val box = battery.getOrNull(2).orEmpty()
        val values = MutableList(16) { "" }
        values[0] = left
        values[1] = right
        values[2] = box
        values[7] = settingsAncLevel()
        values[8] = "false"
        values[11] = "00"
        values[13] = "00"
        values[14] = "00"
        return values.joinToString(",")
    }

    private fun oppoAncFromSettings(mode: Int): Int {
        return when (mode) {
            1 -> when (currentAnc) {
                2, 4, 5 -> currentAnc
                else -> 4 // entering ANC defaults to PUDDING adaptive ANC
            }
            2 -> 3 // transparency
            else -> 1 // off
        }
    }

    private fun oppoAncFromLevel(level: String): Int {
        return when {
            level.startsWith("0100") -> 2 // basic
            level.startsWith("0101") -> 4 // adaptive
            level.startsWith("0102") -> 5 // wind-noise reduction
            level.startsWith("01") -> 4
            level.startsWith("02") -> 3
            else -> 1
        }
    }

    private fun sendOppoTransparencyVocalEnhancementFromLevel(level: String) {
        when {
            level.startsWith("0201") -> sendOppoTransparencyVocalEnhancement(true)
            level.startsWith("0200") -> sendOppoTransparencyVocalEnhancement(false)
        }
    }

    private fun oppoAncFromLevelCommand(level: String): Int? {
        return oppoAncFromLevel(level)
    }

    private fun sendOppoAnc(mode: Int) {
        val ctx = context ?: run {
            Log.w(TAG, "sendOppoAnc skipped: context is null mode=$mode")
            return
        }
        ctx.sendBroadcast(Intent(OppoPodsAction.ACTION_ANC_SELECT).apply {
            putExtra("status", mode)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
    }

    private fun sendOppoTransparencyVocalEnhancement(enabled: Boolean) {
        val ctx = context ?: run {
            Log.w(TAG, "sendOppoTransparencyVocalEnhancement skipped: context is null enabled=$enabled")
            return
        }
        currentTransparencyVocalEnhancement = enabled
        ctx.sendBroadcast(Intent(OppoPodsAction.ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_SET).apply {
            putExtra("enabled", enabled)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
        ctx.sendBroadcast(Intent(OppoPodsAction.ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED).apply {
            putExtra("enabled", enabled)
            setPackage(BuildConfig.APPLICATION_ID)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
        ctx.sendBroadcast(Intent(OppoPodsAction.ACTION_REFRESH_STATUS).apply {
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
        Log.d(TAG, "sendOppoTransparencyVocalEnhancement broadcast sent enabled=$enabled")
    }

    private fun sendAncChanged(mode: Int) {
        val ctx = context ?: return
        listOf(BuildConfig.APPLICATION_ID, "com.android.settings", "com.milink.service").forEach { targetPackage ->
            ctx.sendBroadcast(Intent(OppoPodsAction.ACTION_PODS_ANC_CHANGED).apply {
                putExtra("status", mode)
                setPackage(targetPackage)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableDevice(key: String): BluetoothDevice? {
        return runCatching { getParcelableExtra(key, BluetoothDevice::class.java) }.getOrNull()
            ?: runCatching { getParcelableExtra<BluetoothDevice>(key) }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableStatus(): BatteryParams? {
        return runCatching { getParcelableExtra("status", BatteryParams::class.java) }.getOrNull()
            ?: runCatching { getParcelableExtra<BatteryParams>("status") }.getOrNull()
    }

    private fun Intent.batteryStatusFromExtras(): BatteryParams? {
        if (!hasExtra("left_connected") && !hasExtra("right_connected") && !hasExtra("case_connected")) return null
        return BatteryParams(
            left = PodParams(
                getIntExtra("left_battery", 0),
                getBooleanExtra("left_charging", false),
                getBooleanExtra("left_connected", false),
                0
            ),
            right = PodParams(
                getIntExtra("right_battery", 0),
                getBooleanExtra("right_charging", false),
                getBooleanExtra("right_connected", false),
                0
            ),
            case = PodParams(
                getIntExtra("case_battery", 0),
                getBooleanExtra("case_charging", false),
                getBooleanExtra("case_connected", false),
                0
            )
        )
    }

    private fun saveState(ctx: Context?) {
        val prefs = (ctx ?: context)?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        prefs.edit()
            .putString("address", currentAddress)
            .putString("name", currentName)
            .putInt("anc", currentAnc)
            .putBoolean("transparency_vocal_enhancement", currentTransparencyVocalEnhancement)
            .putInt("left_battery", currentBattery.left?.battery ?: 0)
            .putBoolean("left_charging", currentBattery.left?.isCharging == true)
            .putBoolean("left_connected", currentBattery.left?.isConnected == true)
            .putInt("right_battery", currentBattery.right?.battery ?: 0)
            .putBoolean("right_charging", currentBattery.right?.isCharging == true)
            .putBoolean("right_connected", currentBattery.right?.isConnected == true)
            .putInt("case_battery", currentBattery.case?.battery ?: 0)
            .putBoolean("case_charging", currentBattery.case?.isCharging == true)
            .putBoolean("case_connected", currentBattery.case?.isConnected == true)
            .apply()
    }

    private fun loadState() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val hasSavedBattery = prefs.getBoolean("left_connected", false) ||
            prefs.getBoolean("right_connected", false) ||
            prefs.getBoolean("case_connected", false)
        currentAddress = prefs.getString("address", currentAddress)
        currentName = prefs.getString("name", currentName)
        currentAnc = prefs.getInt("anc", currentAnc)
        currentTransparencyVocalEnhancement = prefs.getBoolean("transparency_vocal_enhancement", currentTransparencyVocalEnhancement)
        currentAddress?.let { knownOppoAddresses.add(it.uppercase()) }
        if (!hasSavedBattery && hasCurrentBattery()) return
        currentBattery = BatteryParams(
            left = PodParams(
                prefs.getInt("left_battery", currentBattery.left?.battery ?: 0),
                prefs.getBoolean("left_charging", currentBattery.left?.isCharging == true),
                prefs.getBoolean("left_connected", currentBattery.left?.isConnected == true),
                0
            ),
            right = PodParams(
                prefs.getInt("right_battery", currentBattery.right?.battery ?: 0),
                prefs.getBoolean("right_charging", currentBattery.right?.isCharging == true),
                prefs.getBoolean("right_connected", currentBattery.right?.isConnected == true),
                0
            ),
            case = PodParams(
                prefs.getInt("case_battery", currentBattery.case?.battery ?: 0),
                prefs.getBoolean("case_charging", currentBattery.case?.isCharging == true),
                prefs.getBoolean("case_connected", currentBattery.case?.isConnected == true),
                0
            )
        )
    }

    private fun hasCurrentBattery(): Boolean {
        return currentBattery.left?.isConnected == true ||
            currentBattery.right?.isConnected == true ||
            currentBattery.case?.isConnected == true
    }
}
