package moe.chenxy.oppopods.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction

/**
 * HyperOriG 同路线：接管 HyperOS SystemUI 设备中心的 third_headset 卡片。
 * 卡片 MAC 与当前 PUDDING 一致时，不进入系统 OPPO 详情页，直接打开 PuddingPods 耳机详情页。
 */
object DeviceCardHook : HookContext() {
    @Volatile
    var mac = ""

    override fun onHook() {
        val handlerThread = HandlerThread("puddingpods_broadcast_handler").also { it.start() }
        val handler = Handler(handlerThread.looper)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == OppoPodsAction.ACTION_PODS_MAC_RECEIVED) {
                    mac = intent.getStringExtra("mac").orEmpty()
                    Log.i("PuddingPods", "received mac $mac")
                }
            }
        }
        var isRegistered = false
        var panelController: Any? = null

        hookAfter(findMethod("miui.systemui.controlcenter.panel.main.MainPanelController", "onCreate")) {
            panelController = instance
        }

        fun hidePanel() {
            panelController?.let { callMethod(it, "exitOrHide") }
        }

        hookBefore(
            findMethod(
                "miui.systemui.devicecenter.devices.DeviceInfoWrapper",
                "performClicked",
                Context::class.java
            )
        ) {
            val context = args[0] as Context
            val deviceInfo = callMethod(instance, "getDeviceInfo")
            val id = callMethod(deviceInfo, "getId") as String
            val deviceType = callMethod(deviceInfo, "getDeviceType") as String
            if (deviceType != "third_headset") return@hookBefore

            if (!isRegistered) {
                context.registerReceiver(
                    receiver,
                    IntentFilter(OppoPodsAction.ACTION_PODS_MAC_RECEIVED),
                    null,
                    handler,
                    Context.RECEIVER_EXPORTED
                )
                isRegistered = true
            }

            Intent(OppoPodsAction.ACTION_GET_PODS_MAC).apply {
                setPackage("com.android.bluetooth")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                context.sendBroadcast(this)
            }

            var waitCnt = 10
            while (mac.isEmpty() && waitCnt-- > 0) Thread.sleep(50)

            if (mac.equals(id, ignoreCase = true)) {
                // Keep the native HyperOS detail page as the first destination, same route
                // as the focus island. SettingsHeadsetHook supplies the virtual headset data.
                val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                val device = runCatching { adapter?.getRemoteDevice(id) }.getOrNull()
                Intent().apply {
                    setClassName(BuildConfig.APPLICATION_ID, "moe.chenxy.oppopods.PuddingDetailActivity")
                    device?.let { putExtra("android.bluetooth.device.extra.DEVICE", it) }
                    putExtra("bluetoothaddress", id)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    context.startActivity(this)
                }
                mac = ""
                hidePanel()
                result = null
            }

            Log.i("PuddingPods", "performClicked $id mac $mac")
        }
    }
}
