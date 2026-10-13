package io.github.xntso.vendroid.ui

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import io.github.xntso.vendroid.AppLocales

open class ActivityBase : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        AppLocales.apply(newBase)
        super.attachBaseContext(newBase)
    }

    override fun onStart() {
        super.onStart()
        // Automatic also follows device changes made while this activity was stopped.
        AppLocales.apply(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

    }
}
