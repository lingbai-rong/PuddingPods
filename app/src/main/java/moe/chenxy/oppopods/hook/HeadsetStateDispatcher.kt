package moe.chenxy.oppopods.hook

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.pods.RfcommController
import moe.chenxy.oppopods.utils.SystemApisUtils.setIconVisibility
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction

object HeadsetStateDispatcher : HookContext() {
    private var appRequestReceiverRegistered = false

    override fun onHook() {
        runCatching {
            hookAfter(findMethod("com.android.bluetooth.btservice.AdapterService", "onCreate")) {
                registerAppRequestReceiver(instance as? Context)
            }
        }.onFailure {
            Log.w("PuddingPods", "AdapterService.onCreate hook skipped", it)
        }

        hookAfter(findMethodByParamCount("com.android.bluetooth.a2dp.A2dpService", "handleConnectionStateChanged", 3)) {
            val currState = args[2] as Int
            val fromState = args[1] as Int
            val device = args[0] as BluetoothDevice?
            val handler = getObjectField(instance, "mHandler") as Handler
            if (device == null || currState == fromState) {
                return@hookAfter
            }
            handler.post {
                Log.d("PuddingPods", "A2DP Connection State: $currState, isOppoPod ${isOppoPod(device)}")
                val context = instance as ContextWrapper
                registerAppRequestReceiver(context)
                if (!isOppoPod(device)) return@post

                val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
                if (currState == BluetoothHeadset.STATE_CONNECTED) {
                    statusBarManager.setIconVisibility("wireless_headset", true)
                    RfcommController.connectPod(context, device, prefs)
                } else if (currState == BluetoothHeadset.STATE_DISCONNECTING || currState == BluetoothHeadset.STATE_DISCONNECTED) {
                    statusBarManager.setIconVisibility("wireless_headset", false)
                    RfcommController.disconnectedPod(context, device)
                }
            }
        }
    }

    private fun registerAppRequestReceiver(context: Context?) {
        if (context == null || appRequestReceiverRegistered) return
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (context == null) return
                when (intent?.action) {
                    OppoPodsAction.ACTION_PODS_UI_INIT,
                    OppoPodsAction.ACTION_REFRESH_STATUS -> {
                        context.sendBroadcast(Intent(OppoPodsAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE).apply {
                            setPackage(BuildConfig.APPLICATION_ID)
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        })
                    }
                    OppoPodsAction.ACTION_CONNECT_POD_REQUEST -> {
                        val device = intent.getParcelableExtra("device", BluetoothDevice::class.java) ?: return
                        Log.d("PuddingPods", "connect request from app device=${device.name}/${device.address}")
                        RfcommController.connectPod(context, device, prefs, appRequested = true)
                    }
                    OppoPodsAction.ACTION_DISCONNECT_POD_REQUEST -> {
                        val device = intent.getParcelableExtra("device", BluetoothDevice::class.java) ?: return
                        Log.d("PuddingPods", "disconnect request from app device=${device.name}/${device.address}")
                        RfcommController.disconnectedPod(context, device)
                    }
                }
            }
        }, IntentFilter().apply {
            addAction(OppoPodsAction.ACTION_PODS_UI_INIT)
            addAction(OppoPodsAction.ACTION_REFRESH_STATUS)
            addAction(OppoPodsAction.ACTION_CONNECT_POD_REQUEST)
            addAction(OppoPodsAction.ACTION_DISCONNECT_POD_REQUEST)
        }, Context.RECEIVER_EXPORTED)
        appRequestReceiverRegistered = true
    }

    /**
     * Detect OPPO earphones by checking if the device name contains "oppo" (case insensitive).
     */
    @SuppressLint("MissingPermission")
    fun isOppoPod(device: BluetoothDevice): Boolean {
        val name = device.name ?: return false
        return moe.chenxy.oppopods.pods.isPuddingDeviceName(name)
    }
}
