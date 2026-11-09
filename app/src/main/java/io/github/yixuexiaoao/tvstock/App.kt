package io.github.yixuexiaoao.tvstock

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import io.github.yixuexiaoao.tvstock.log.AppLog
import io.github.yixuexiaoao.tvstock.update.UpdateManager
import io.github.yixuexiaoao.tvstock.web.WebHost

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
        registerActivityLifecycleCallbacks(WebLifecycle())
    }

    /** 有界面可见时运行 Web 服务。切换界面时新界面先 start、旧界面后 stop，计数不会归零。 */
    private class WebLifecycle : ActivityLifecycleCallbacks {
        private var visible = 0

        override fun onActivityStarted(activity: Activity) {
            visible++
            WebHost.start(activity)
        }

        override fun onActivityStopped(activity: Activity) {
            visible--
            if (visible == 0 && !activity.isChangingConfigurations) WebHost.stop()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
