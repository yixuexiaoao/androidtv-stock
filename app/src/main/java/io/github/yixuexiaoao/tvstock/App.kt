package io.github.yixuexiaoao.tvstock

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCore.init(this)
    }
}
