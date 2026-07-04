package io.github.xntso.vendroid

import android.app.Application
import io.github.xntso.vendroid.plugins.telemetry.Telemetry

class VendroidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Telemetry.init(this)
    }
}
