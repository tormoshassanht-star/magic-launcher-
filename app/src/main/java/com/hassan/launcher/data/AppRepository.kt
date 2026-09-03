package com.hassan.launcher.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import com.hassan.launcher.model.AppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object AppRepository {
    private lateinit var appContext: Context
    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps
    private val infos = HashMap<String, LauncherActivityInfo>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        val la = appContext.getSystemService(LauncherApps::class.java)
        la.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String?, user: UserHandle?) = reload()
            override fun onPackageAdded(packageName: String?, user: UserHandle?) = reload()
            override fun onPackageChanged(packageName: String?, user: UserHandle?) = reload()
            override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = reload()
            override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = reload()
        }, Handler(Looper.getMainLooper()))
        reload()
    }

    fun reload() {
        scope.launch { _apps.value = load() }
    }

    private fun load(): List<AppInfo> {
        val la = appContext.getSystemService(LauncherApps::class.java)
        val pm = appContext.packageManager
        val list = la.getActivityList(null, Process.myUserHandle())
        val result = ArrayList<AppInfo>(list.size)
        val fresh = HashMap<String, LauncherActivityInfo>()
        for (info in list) {
            val pkg = info.applicationInfo.packageName
            if (pkg == appContext.packageName) continue
            val updateTime = try {
                pm.getPackageInfo(pkg, 0).lastUpdateTime
            } catch (e: Exception) {
                info.firstInstallTime
            }
            val app = AppInfo(
                packageName = pkg,
                activityName = info.componentName.className,
                label = info.label?.toString() ?: pkg,
                installTime = info.firstInstallTime,
                updateTime = updateTime,
                isSystem = (info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            )
            fresh[app.key] = info
            result.add(app)
        }
        result.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        synchronized(infos) {
            infos.clear()
            infos.putAll(fresh)
        }
        IconCache.preload(appContext, result)
        return result
    }

    fun launcherInfo(key: String): LauncherActivityInfo? = synchronized(infos) { infos[key] }

    fun find(key: String): AppInfo? = _apps.value.firstOrNull { it.key == key }
}
