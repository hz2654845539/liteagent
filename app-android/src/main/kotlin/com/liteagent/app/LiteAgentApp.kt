package com.liteagent.app

import android.app.Application

class LiteAgentApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: LiteAgentApp
            private set
    }
}
