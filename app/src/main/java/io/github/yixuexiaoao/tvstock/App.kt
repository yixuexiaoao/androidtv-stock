package io.github.yixuexiaoao.tvstock

import android.app.Application
import android.os.Build
import io.github.yixuexiaoao.tvstock.log.AppLog
import io.github.yixuexiaoao.tvstock.update.UpdateManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.installCrashHandler()
        AppCore.init(this)
        UpdateManager.init(this)
        AppLog.i(
            "App",
            "启动 v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}），设备 ${Build.MANUFACTURER} ${Build.MODEL}，" +
                "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}），架构 ${Build.SUPPORTED_ABIS.joinToString()}",
        )
    }
}
