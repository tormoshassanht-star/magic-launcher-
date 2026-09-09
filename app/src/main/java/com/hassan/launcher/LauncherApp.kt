package com.hassan.launcher

import android.app.Application
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LauncherApp : Application() {

    companion object {
        fun crashFile(app: Application) = File(app.filesDir, "last_crash.txt")
    }

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                crashFile(this).writeText(
                    "Magic Launcher ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n" +
                        "$stamp on thread ${thread.name}\n\n$sw",
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }
}
