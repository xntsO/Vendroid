package io.github.xntso.vendroid.ui

import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import io.github.xntso.vendroid.AppLanguage
import io.github.xntso.vendroid.AppLocales
import io.github.xntso.vendroid.AppSettings
import io.github.xntso.vendroid.Intents
import io.github.xntso.vendroid.VENTOY_INSTALL_URI
import io.github.xntso.vendroid.VendroidApplication
import io.github.xntso.vendroid.R
import io.github.xntso.vendroid.getFinishedIntent
import io.github.xntso.vendroid.getProgressUpdateIntent
import io.github.xntso.vendroid.massstorage.PreviewUsbDevice
import io.github.xntso.vendroid.massstorage.UsbMassStorageDeviceDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.util.Locale

abstract class ProgressLocaleLifecycleTestSuite {
    private val device = UsbMassStorageDeviceDescriptor(previewUsbDevice = PreviewUsbDevice("Test USB", "1234:5678"))

    @BeforeEach
    fun assertConfiguredApi() {
        assertEquals(requireNotNull(javaClass.getAnnotation(Config::class.java)).sdk.single(), Build.VERSION.SDK_INT)
    }

    @Test
    fun `locale configuration preserves resumed progress and still receives the terminal broadcast`() {
        val controller = Robolectric.buildActivity(ProgressActivity::class.java, progress(0)).setup()
        try {
            val activity = controller.get()
            val viewModel = ViewModelProvider(activity)[ProgressActivityViewModel::class.java]
            viewModel.updateFromIntent(progress(512))
            val configBits = activity.packageManager.getActivityInfo(activity.componentName, 0).configChanges
            assertTrue(configBits and ActivityInfo.CONFIG_LOCALE != 0)
            assertTrue(configBits and ActivityInfo.CONFIG_LAYOUT_DIRECTION != 0)
            AppSettings(activity).language = AppLanguage.ARABIC
            AppLocales.apply(activity)
            controller.configurationChange(Configuration(activity.resources.configuration).apply {
                setLocale(Locale("ar"))
                setLayoutDirection(Locale("ar"))
            })
            assertSame(activity, controller.get())
            assertEquals("تثبيت Ventoy", activity.getString(R.string.install_ventoy))
            assertEquals(512L, viewModel.state.value.processedBytes)
            LocalBroadcastManager.getInstance(activity).sendBroadcastSync(
                getFinishedIntent(VENTOY_INSTALL_URI, device, 1024, operation = Intents.OPERATION_VENTOY_INSTALL),
            )
            assertEquals(JobState.SUCCESS, viewModel.state.value.jobState)
        } finally {
            AppSettings(controller.get()).language = AppLanguage.AUTOMATIC
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `actual recreation does not replay initial progress over a completed retained job`() {
        val controller = Robolectric.buildActivity(ProgressActivity::class.java, progress(0)).setup()
        try {
            val viewModel = ViewModelProvider(controller.get())[ProgressActivityViewModel::class.java]
            viewModel.updateFromIntent(getFinishedIntent(VENTOY_INSTALL_URI, device, 1024, operation = Intents.OPERATION_VENTOY_INSTALL))
            controller.recreate()
            assertSame(viewModel, ViewModelProvider(controller.get())[ProgressActivityViewModel::class.java])
            assertEquals(JobState.SUCCESS, viewModel.state.value.jobState)
            assertEquals(100, viewModel.state.value.percent)
            assertEquals(1024L, viewModel.state.value.totalBytes)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun progress(processed: Long) = getProgressUpdateIntent(
        VENTOY_INSTALL_URI, device, 42, 1f, processed, 1024,
        operation = Intents.OPERATION_VENTOY_INSTALL,
    )
}

@ExtendWith(RobolectricExtension::class)
@Config(application = VendroidApplication::class, sdk = [32])
class ProgressLocaleApi32Test : ProgressLocaleLifecycleTestSuite()

@ExtendWith(RobolectricExtension::class)
@Config(application = VendroidApplication::class, sdk = [35])
class ProgressLocaleApi35Test : ProgressLocaleLifecycleTestSuite()
