package moe.chenxy.oppopods.hook

/** HyperOriG 同路线：拿到 miui.systemui.plugin 的 ClassLoader 后安装 DeviceCardHook。 */
object SystemUIPluginHook : HookContext() {
    private var pluginLoaderClassLoader: ClassLoader? = null

    override fun onHook() {
        hookAfter(findMethod("com.android.systemui.shared.plugins.PluginInstance", "loadPlugin")) {
            val pkgName = callMethod(instance, "getPackage") as? String
            if (pkgName == "miui.systemui.plugin") {
                val factory = getObjectField(instance, "mPluginFactory")
                val clsLoader = callMethod(
                    getObjectField(factory, "mClassLoaderFactory"),
                    "get"
                ) as ClassLoader
                if (pluginLoaderClassLoader != clsLoader) {
                    Log.i("PuddingPods", "[loadPlugin] initDeviceCardHook")
                    pluginLoaderClassLoader = clsLoader
                    initDeviceCardHook()
                }
            }
        }
    }

    private fun initDeviceCardHook() {
        val classLoader = pluginLoaderClassLoader ?: return
        DeviceCardHook.apply {
            module = this@SystemUIPluginHook.module
            appClassLoader = classLoader
            packageName = this@SystemUIPluginHook.packageName
            prefs = this@SystemUIPluginHook.prefs
            onHook()
        }
    }
}
