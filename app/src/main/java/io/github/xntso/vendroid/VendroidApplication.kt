package io.github.xntso.vendroid

import android.app.Application
import android.content.res.Configuration
import io.github.xntso.vendroid.plugins.telemetry.Telemetry

class VendroidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLocales.apply(this)
        Telemetry.init(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLocales.apply(this)
    }
}
