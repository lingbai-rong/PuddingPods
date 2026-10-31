package moe.chenxy.oppopods.hook

import android.annotation.SuppressLint
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Bundle
import com.xzakota.hyper.notification.focus.FocusNotification
import moe.chenxy.oppopods.utils.FocusIslandUtil
import moe.chenxy.oppopods.utils.PodImageLoader
import moe.chenxy.oppopods.utils.SystemApisUtils
import moe.chenxy.oppopods.utils.SystemApisUtils.cancelAsUser
import moe.chenxy.oppopods.utils.SystemApisUtils.notifyAsUser
import moe.chenxy.oppopods.config.ConfigManager
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.pods.detectDeviceCapabilities

@SuppressLint("MissingPermission")
object MiBluetoothToastHook : HookContext() {

    // ANC 模式本地缓存，用于循环切换和状态同步（1=关 2=降噪 3=通透 4=自适应）
    // 通过接收 ACTION_PODS_ANC_CHANGED 广播与 RfcommController 保持同步
    private var localAncMode = 1

    override fun onHook() {

        fun deleteIntent(context: Context, bluetoothDevice: BluetoothDevice): PendingIntent? {
            val intent = Intent("com.android.bluetooth.headset.notification.cancle")
            intent.putExtra("android.bluetooth.device.extra.DEVICE", bluetoothDevice)
            return PendingIntent.getBroadcast(context, 0, intent, 201326592)
        }

        @SuppressLint("WrongConstant")
        fun createPodsNotification(bluetoothDevice: BluetoothDevice?, context: Context, batteryParams: BatteryParams) {
            val miheadset_notification_Box = context.resources.getIdentifier("miheadset_notification_Box", "string", "com.xiaomi.bluetooth")
            val miheadset_notification_LeftEar = context.resources.getIdentifier("miheadset_notification_LeftEar", "string", "com.xiaomi.bluetooth")
            val miheadset_notification_RightEar = context.resources.getIdentifier("miheadset_notification_RightEar", "string", "com.xiaomi.bluetooth")
            val miheadset_notification_Disconnect = context.resources.getIdentifier("miheadset_notification_Disconnect", "string", "com.xiaomi.bluetooth")
            val system_notification_accent_color = context.resources.getIdentifier("system_notification_accent_color", "color", "android")
            if (bluetoothDevice == null) {
                Log.e("PuddingPods", "createPodsNotification: btDevice null")
                return
            }
            try {
                val address: String = bluetoothDevice.address
                var alias: String? = bluetoothDevice.alias
                if (alias?.isEmpty() == true) {
                    alias = bluetoothDevice.name
                }

                val caseBattStr = if (batteryParams.case != null && batteryParams.case!!.isConnected)
                    "${context.resources.getString(miheadset_notification_Box)}${batteryParams.case!!.battery}%" +
                            "${if (batteryParams.case!!.isCharging) "⚡ " else " "}\n"
                else ""
                val leftEar = if (batteryParams.left != null && batteryParams.left!!.isConnected)
                    "${context.resources.getString(miheadset_notification_LeftEar)}${batteryParams.left!!.battery}%" +
                        (if (batteryParams.left!!.isCharging) "⚡" else "")
                else ""
                val leftToRight = if (batteryParams.left?.isConnected == true && batteryParams.right?.isConnected == true) " " else ""
                val rightEar = if (batteryParams.right != null && batteryParams.right!!.isConnected)
                    "$leftToRight${context.resources.getString(miheadset_notification_RightEar)}${batteryParams.right!!.battery}%" +
                        (if (batteryParams.right!!.isCharging) "⚡ " else " ")
                else ""

                val contentText: String = caseBattStr + leftEar + rightEar
                val notificationManager = context.getSystemService("notification") as NotificationManager
                notificationManager.createNotificationChannel(
                    NotificationChannel(
                        "BTHeadset$address",
                        alias,
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        setSound(null, null)
                        setAllowBubbles(true)
                    }
                )
                val bundle = Bundle()
                bundle.putParcelable("Device", bluetoothDevice)
                val intent = Intent("com.android.bluetooth.headset.notification")
                intent.putExtra("btData", bundle)
                intent.putExtra("disconnect", "1")
                intent.setIdentifier("BTHeadset$address")
                val disconnectAction = Notification.Action(
                    285737079,
                    context.resources.getString(miheadset_notification_Disconnect),
                    PendingIntent.getBroadcast(context, 0, intent, 201326592)
                )
                // 循环切换降噪模式，指定 package 确保广播路由到 com.android.bluetooth 进程
                val ancCycleIntent = Intent(OppoPodsAction.ACTION_CYCLE_ANC)
                ancCycleIntent.setPackage("com.android.bluetooth")
                ancCycleIntent.setIdentifier("BTHeadset$address")
                ancCycleIntent.putExtra("device_name", alias ?: bluetoothDevice.name ?: "")
                val moduleContext = context.createPackageContext(
                    BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY
                )
                val headsetBitmap = PodImageLoader.loadBoxBitmap(context, prefs, address)
                    ?: BitmapFactory.decodeResource(moduleContext.resources, R.drawable.img_box)
                if (headsetBitmap == null) {
                    Log.e("PuddingPods", "createPodsNotification: headset bitmap null")
                    return
                }
                val headsetIcon = Icon.createWithBitmap(headsetBitmap)
                // Restore OppoPods' proven island route exactly: the Focus notification itself
                // stays owned by com.xiaomi.bluetooth, while a tap opens our lightweight popup.
                // Do not point the notification directly at Settings; some HyperOS builds reject
                // that PendingIntent while parsing focus extras and the whole island disappears.
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    Intent("chen.action.oppopods.show_pods_ui").apply {
                        setClassName(BuildConfig.APPLICATION_ID, "moe.chenxy.oppopods.PopupActivity")
                        putExtra("android.bluetooth.device.extra.DEVICE", bluetoothDevice)
                        putExtra("bluetoothaddress", bluetoothDevice.address)
                        putExtra("device_name", alias)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val focusExtras = FocusNotification.buildV3 {
                    val logo = createPicture("key_headset", headsetIcon)
                    enableFloat = true
                    ticker = alias ?: ""
                    updatable = true
//                    tickerPic = logo

                    iconTextInfo {
                        animIconInfo{
                            type = 0
                            src = logo
                        }
                        title = alias ?: ""
                        content = contentText
                    }

                    island {
                        islandProperty = 1
                        bigIslandArea {
                            imageTextInfoLeft {
                                type = 1
                                picInfo {
                                    type = 1
                                    pic = logo
                                }
                            }
                            imageTextInfoRight {
                                type = 2
                                textInfo {
                                    title = alias ?: ""
                                    content = contentText
                                }
                            }
                        }
                    }


                    textButton {
                        addActionInfo {
                            val ancLabel = moduleContext.getString(R.string.cycle_anc)
                            val ancAction = Notification.Action.Builder(
                                Icon.createWithResource(context, android.R.drawable.ic_lock_silent_mode),
                                ancLabel,
                                PendingIntent.getBroadcast(context, 1, ancCycleIntent, 201326592)
                            ).build()
                            action = createAction("key_anc_cycle", ancAction)
                            actionTitle = ancLabel
                        }
                        addActionInfo {
                            val disconnectLabel = moduleContext.getString(R.string.notification_btn_disconnect)
                            val disconnectIntent = Intent("com.android.bluetooth.headset.notification").apply {
                                putExtra("btData", bundle)
                                putExtra("disconnect", "1")
                                setIdentifier("BTHeadset$address")
                            }
                            val disconnectAction = Notification.Action.Builder(
                                Icon.createWithResource(context, android.R.drawable.ic_delete),
                                disconnectLabel,
                                PendingIntent.getBroadcast(context, 2, disconnectIntent, 201326592)
                            ).build()
                            action = createAction("key_disconnect", disconnectAction)
                            actionTitle = disconnectLabel
                        }
                    }
                }
                // AOD 息屏显示：左右耳电量拼合后注入 aodTitle
                if (focusExtras != null) {
                    val aodParts = mutableListOf<String>()
                    if (batteryParams.left?.isConnected == true)
                        aodParts.add("L ${batteryParams.left!!.battery}%")
                    if (batteryParams.right?.isConnected == true)
                        aodParts.add("R ${batteryParams.right!!.battery}%")
                    val aodTitle = aodParts.joinToString(" | ")
                    try {
                        val json = org.json.JSONObject(focusExtras.getString("miui.focus.param") ?: "{}")
                        val pv2 = json.optJSONObject("param_v2") ?: org.json.JSONObject()
                        pv2.put("aodTitle", aodTitle)
                        pv2.put("aodPic", "key_headset")
                        json.put("param_v2", pv2)
                        focusExtras.putString("miui.focus.param", json.toString())
                    } catch (_: Exception) {}
                }
                notificationManager.notifyAsUser(
                    "BTHeadset$address",
                    10003,
                    Notification.Builder(context, "BTHeadset$address")
                        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                        .setWhen(0L)
                        .setTicker(alias)
                        .setDefaults(-1)
                        .setContentTitle(alias)
                        .setContentText(contentText)
                        .setContentIntent(pendingIntent)
                        .setDeleteIntent(deleteIntent(context, bluetoothDevice))
                        .setColor(context.getColor(system_notification_accent_color))
                        .addAction(disconnectAction)
                        .apply { focusExtras?.let { addExtras(it) } }
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .build(),
                    SystemApisUtils.getUserAllUserHandle()
                )
            } catch (e: Exception) {
                Log.e("PuddingPods", "Failed to create Pod Notification", e)
            }
        }

        fun cancelNotification(bluetoothDevice: BluetoothDevice, context: Context) {
            try {
                val address = bluetoothDevice.address
                if (address.isNotEmpty()) {
                    val notificationManager = context.getSystemService("notification") as NotificationManager
                    notificationManager.cancelAsUser("BTHeadset$address", 10003, SystemApisUtils.getUserAllUserHandle())
                }
            } catch (e: Exception) {
                Log.e("PuddingPods", "Failed to cancel Pod Notification!", e)
            }
        }


        // HyperOriG/OppoPods rely on a receiver living in com.xiaomi.bluetooth.  Older
        // PuddingPods builds registered it only after one exact MiuiBluetoothNotification
        // constructor ran; if the constructor signature changed, the Focus-Island broadcast
        // was simply lost.  Register once at Xiaomi Bluetooth application start and keep
        // constructor hooks only as a fallback.
        val focusAction = "rongyi.puddingpods.action.SEND_FOCUS_ISLAND"
        var bridgeReceiverRegistered = false
        var bridgeContext: Context? = null

        val bridgeReceiver = object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) {
                val receiverCtx = p0 ?: bridgeContext ?: return
                android.util.Log.d("PuddingPods-Island", "bridge action=${p1?.action}")
                when (p1?.action) {
                    focusAction,
                    "chen.action.oppopods.sendstrongtoast" -> {
                        val batteryParams = p1.getParcelableExtra("batteryParams", BatteryParams::class.java)
                            ?: return
                        val address = p1.getStringExtra("address").orEmpty()
                        val shown = FocusIslandUtil.showBatteryIsland(receiverCtx, prefs, batteryParams, address)
                        android.util.Log.d("PuddingPods-Island", "Focus request handled shown=$shown address=$address")
                    }
                    "chen.action.oppopods.updatepodsnotification" -> {
                        val batteryParams = p1.getParcelableExtra("batteryParams", BatteryParams::class.java)
                            ?: return
                        val device = p1.getParcelableExtra("device", BluetoothDevice::class.java)
                        createPodsNotification(device, receiverCtx, batteryParams)
                    }
                    "chen.action.oppopods.cancelpodsnotification" -> {
                        val device = p1.getParcelableExtra("device", BluetoothDevice::class.java) ?: return
                        cancelNotification(device, receiverCtx)
                    }
                    OppoPodsAction.ACTION_PODS_ANC_CHANGED -> {
                        localAncMode = p1.getIntExtra("status", 1)
                    }
                    OppoPodsAction.ACTION_CYCLE_ANC -> {
                        val capabilities = detectDeviceCapabilities(
                            deviceName = p1.getStringExtra("device_name").orEmpty(),
                            adaptiveOverride = prefs.getInt(
                                ConfigManager.PREF_KEY_ADAPTIVE_CAPABILITY_OVERRIDE,
                                ConfigManager.CAPABILITY_OVERRIDE_AUTO
                            ),
                            spatialAudioOverride = ConfigManager.CAPABILITY_OVERRIDE_AUTO,
                            spatialSoundSwitchOverride = ConfigManager.CAPABILITY_OVERRIDE_AUTO,
                        )
                        val cycle = if (capabilities.adaptiveSupported) {
                            listOf(2, 4, 3, 1)
                        } else {
                            listOf(2, 3, 1)
                        }
                        val currentIndex = cycle.indexOf(if (localAncMode in 5..8) 2 else localAncMode)
                        localAncMode = cycle[(currentIndex + 1).floorMod(cycle.size)]
                        receiverCtx.sendBroadcast(Intent(OppoPodsAction.ACTION_ANC_SELECT).apply {
                            putExtra("status", localAncMode)
                            setPackage("com.android.bluetooth")
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        })
                    }
                }
            }
        }

        fun ensureBridgeReceiver(candidate: Context?) {
            if (candidate == null || bridgeReceiverRegistered) return
            val ctx = candidate.applicationContext ?: candidate
            val filter = IntentFilter(focusAction).apply {
                // Keep the old action accepted so an in-flight process from an older module
                // version cannot break the first reconnect after updating.
                addAction("chen.action.oppopods.sendstrongtoast")
                addAction("chen.action.oppopods.updatepodsnotification")
                addAction("chen.action.oppopods.cancelpodsnotification")
                addAction(OppoPodsAction.ACTION_CYCLE_ANC)
                addAction(OppoPodsAction.ACTION_PODS_ANC_CHANGED)
            }
            runCatching {
                ctx.registerReceiver(bridgeReceiver, filter, Context.RECEIVER_EXPORTED)
            }.onSuccess {
                bridgeReceiverRegistered = true
                bridgeContext = ctx
                android.util.Log.d("PuddingPods-Island", "bridge receiver registered package=${ctx.packageName}")
                Log.d("PuddingPods", "Xiaomi Bluetooth island receiver registered")
            }.onFailure {
                android.util.Log.e("PuddingPods-Island", "bridge receiver register failed", it)
                Log.e("PuddingPods", "Xiaomi Bluetooth island receiver register failed", it)
            }
        }

        // Guaranteed application lifecycle entry in com.xiaomi.bluetooth on normal launches.
        runCatching {
            hookAfter(findMethod("android.app.Instrumentation", "callApplicationOnCreate", Application::class.java)) {
                ensureBridgeReceiver(args[0] as? Context)
            }
        }.onFailure { Log.w("PuddingPods", "Instrumentation application hook unavailable", it) }

        // Fallback for already-running Xiaomi Bluetooth processes and for vendor variants.
        runCatching {
            findClass("com.android.bluetooth.ble.app.MiuiBluetoothNotification")
                .declaredConstructors
                .forEach { constructor ->
                    constructor.isAccessible = true
                    hookConstructorAfter(constructor) {
                        ensureBridgeReceiver(runCatching { getObjectField(instance, "mContext") as? Context }.getOrNull())
                    }
                }
        }.onFailure { Log.w("PuddingPods", "MiuiBluetoothNotification constructor hooks unavailable", it) }
    }

    private fun Int.floorMod(divisor: Int): Int = ((this % divisor) + divisor) % divisor
}
