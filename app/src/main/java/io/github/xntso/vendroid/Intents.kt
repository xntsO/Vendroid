package io.github.xntso.vendroid

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import io.github.xntso.vendroid.massstorage.UsbMassStorageDeviceDescriptor
import io.github.xntso.vendroid.utils.exception.base.VendroidException
import io.github.xntso.vendroid.utils.ktexts.safeParcelableExtra
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import java.util.Random

object Intents {
    const val START_JOB = "io.github.xntso.vendroid.action.START_JOB"
    const val USB_PERMISSION = "io.github.xntso.vendroid.action.USB_PERMISSION"
    const val SKIP_VERIFY = "io.github.xntso.vendroid.action.CANCEL_VERIFY"
    const val JOB_PROGRESS = "io.github.xntso.vendroid.broadcast.JOB_PROGRESS"
    const val ERROR = "io.github.xntso.vendroid.broadcast.ERROR"
    const val FINISHED = "io.github.xntso.vendroid.broadcast.FINISHED"
    const val EXTRA_OPERATION = "operation"
    const val EXTRA_FORCE_INSTALL = "forceInstall"
    const val OPERATION_WRITE_IMAGE = "write_image"
    const val OPERATION_VENTOY_INSTALL = "ventoy_install"
}

val VENTOY_INSTALL_URI: Uri = Uri.parse("vendroid://ventoy/install")

@Parcelize
data class JobStatusInfo(
    val sourceUri: Uri,
    val destDevice: UsbMassStorageDeviceDescriptor,
    val processedBytes: Long,
    val totalBytes: Long,
    val speed: Float = -1f,
    val jobId: Int,
    val isVerifying: Boolean = false,
    val exception: VendroidException? = null,
) : Parcelable {
    @IgnoredOnParcel
    val percent =
        if (totalBytes <= 0L) -1 else (processedBytes.toDouble() * 100 / totalBytes).toInt()
}

private fun mkIntent(
    packageContext: Context? = null, cls: Class<*>? = null,
) = if (packageContext != null && cls != null) Intent(packageContext, cls)
else Intent()

fun getConfirmOperationActivityIntent(
    sourceUri: Uri,
    destDevice: UsbMassStorageDeviceDescriptor,
    packageContext: Context? = null,
    cls: Class<*>? = null,
    operation: String = Intents.OPERATION_WRITE_IMAGE,
    forceInstall: Boolean = false,
): Intent {
    return mkIntent(packageContext, cls).apply {
        data = sourceUri
        putExtra("sourceUri", sourceUri)
        putExtra("destDevice", destDevice)
        putExtra(Intents.EXTRA_OPERATION, operation)
        putExtra(Intents.EXTRA_FORCE_INSTALL, forceInstall)
    }
}

fun getConfirmVentoyInstallActivityIntent(
    destDevice: UsbMassStorageDeviceDescriptor,
    forceInstall: Boolean,
    packageContext: Context? = null,
    cls: Class<*>? = null,
): Intent =
    getConfirmOperationActivityIntent(
        sourceUri = VENTOY_INSTALL_URI,
        destDevice = destDevice,
        packageContext = packageContext,
        cls = cls,
        operation = Intents.OPERATION_VENTOY_INSTALL,
        forceInstall = forceInstall,
    )

fun getStartJobIntent(
    sourceUri: Uri,
    destDevice: UsbMassStorageDeviceDescriptor,
    jobId: Int,
    offset: Long = 0L,
    verifyOnly: Boolean = false,
    packageContext: Context? = null,
    cls: Class<*>? = null,
): Intent {
    return mkIntent(packageContext, cls).apply {
        action = Intents.START_JOB
        data = sourceUri
        putExtra("sourceUri", sourceUri)
        putExtra("destDevice", destDevice)
        putExtra("jobId", jobId)
        putExtra("offset", offset)
        putExtra("verifyOnly", verifyOnly)
    }
}

fun getStartVentoyInstallJobIntent(
    destDevice: UsbMassStorageDeviceDescriptor,
    jobId: Int,
    forceInstall: Boolean = false,
    packageContext: Context? = null,
    cls: Class<*>? = null,
): Intent {
    return mkIntent(packageContext, cls).apply {
        action = Intents.START_JOB
        data = VENTOY_INSTALL_URI
        putExtra("sourceUri", VENTOY_INSTALL_URI)
        putExtra("destDevice", destDevice)
        putExtra("jobId", jobId)
        putExtra("offset", 0L)
        putExtra("verifyOnly", false)
        putExtra(Intents.EXTRA_OPERATION, Intents.OPERATION_VENTOY_INSTALL)
        putExtra(Intents.EXTRA_FORCE_INSTALL, forceInstall)
    }
}

fun getProgressUpdateIntent(
    sourceUri: Uri,
    destDevice: UsbMassStorageDeviceDescriptor,
    jobId: Int,
    speed: Float,
    processedBytes: Long,
    totalBytes: Long,
    isVerifying: Boolean = false,
    packageContext: Context? = null,
    cls: Class<*>? = null,
) = mkIntent(packageContext, cls).apply {
    action = Intents.JOB_PROGRESS
    putExtra("sourceUri", sourceUri)
    putExtra(
        "status", JobStatusInfo(
            sourceUri, destDevice, processedBytes, totalBytes, speed, jobId,
            isVerifying = isVerifying
        )
    )
}

fun getErrorIntent(
    sourceUri: Uri,
    destDevice: UsbMassStorageDeviceDescriptor,
    jobId: Int,
    processedBytes: Long,
    totalBytes: Long,
    exception: VendroidException,
    packageContext: Context? = null,
    cls: Class<*>? = null,
) = mkIntent(packageContext, cls).apply {
    action = Intents.ERROR
    putExtra("sourceUri", sourceUri)
    putExtra(
        "status",
        JobStatusInfo(
            sourceUri, destDevice, processedBytes, totalBytes,
            jobId = jobId, exception = exception
        )
    )
}

fun getFinishedIntent(
    sourceUri: Uri,
    destDevice: UsbMassStorageDeviceDescriptor,
    totalBytes: Long,
    packageContext: Context? = null,
    cls: Class<*>? = null,
) = mkIntent(packageContext, cls).apply {
    action = Intents.FINISHED
    putExtra("sourceUri", sourceUri)
    putExtra("status", JobStatusInfo(sourceUri, destDevice, totalBytes, totalBytes, jobId = -1))
}

fun Intent.getProgressActivityPendingIntent(
    context: Context, requestCode: Int = Random().nextInt(),
): PendingIntent = PendingIntent.getActivity(context, requestCode, this.apply {
    // Set data uri to propagate the permission
    if (data == null) data = safeParcelableExtra<Uri?>("sourceUri")

    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
}, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
