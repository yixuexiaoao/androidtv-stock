package io.github.yixuexiaoao.tvstock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.yixuexiaoao.tvstock.log.AppLog
import io.github.yixuexiaoao.tvstock.ui.MainActivity

/** 开机自启（默认关闭）。Web 服务随界面启动；部分系统会限制后台拉起界面。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AppCore.config.settings.autostart) return
        AppLog.i("Boot", "开机自启")
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.w("Boot", "系统禁止开机打开界面", e)
        }
    }
}
