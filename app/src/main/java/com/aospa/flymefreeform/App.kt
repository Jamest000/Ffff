package com.aospa.flymefreeform

import android.app.Application
import com.aospa.flymefreeform.data.SettingsRepository

class App : Application() {
    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsRepository(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
