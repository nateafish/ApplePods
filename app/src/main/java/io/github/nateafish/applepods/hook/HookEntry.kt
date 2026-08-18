package io.github.nateafish.applepods.hook

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class HookEntry : XposedModule() {
    private val installedPackages = mutableSetOf<String>()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Log.module = this
        log(android.util.Log.INFO, "ApplePods", "module loaded in ${param.processName}; API $apiVersion")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val hook = when (param.packageName) {
            "com.xiaomi.bluetooth" -> ApplePodsBluetoothHook
            "com.android.settings" -> ApplePodsSettingsHook
            "com.milink.service" -> ApplePodsMiLinkHook
            else -> return
        }
        synchronized(installedPackages) {
            if (!installedPackages.add(param.packageName)) return
        }
        hook.module = this
        hook.appClassLoader = param.classLoader
        log(android.util.Log.INFO, "ApplePods", "installing hook for ${param.packageName}")
        hook.onHook()
    }
}
