package com.neilluo.scrolllock

import android.app.Application

class ScrollLockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
