package io.github.xntso.vendroid.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import io.github.xntso.vendroid.Intents
import io.github.xntso.vendroid.JobStatusInfo
import io.github.xntso.vendroid.R
import io.github.xntso.vendroid.getErrorIntent
import io.github.xntso.vendroid.getFinishedIntent
import io.github.xntso.vendroid.getProgressActivityPendingIntent
import io.github.xntso.vendroid.getProgressUpdateIntent
import io.github.xntso.vendroid.massstorage.BlockDeviceInputStream
import io.github.xntso.vendroid.massstorage.BlockDeviceOutputStream
import io.github.xntso.vendroid.massstorage.VendroidUsbMassStorageDevice
import io.github.xntso.vendroid.massstorage.UsbMassStorageDeviceDescriptor
import io.github.xntso.vendroid.massstorage.setUpLibUSB
import io.github.xntso.vendroid.plugins.telemetry.Telemetry
import io.github.xntso.vendroid.service.WorkerServiceFlowImpl.verifyImage
import io.github.xntso.vendroid.service.WorkerServiceFlowImpl.writeImage
import io.github.xntso.vendroid.ui.ProgressActivity
import io.github.xntso.vendroid.utils.TimeoutExpiredException
import io.github.xntso.vendroid.utils.broadcastReceiver
import io.github.xntso.vendroid.utils.exception.InitException
import io.github.xntso.vendroid.utils.exception.MissingDeviceException
import io.github.xntso.vendroid.utils.exception.NotEnoughSpaceException
import io.github.xntso.vendroid.utils.exception.OpenFileException
import io.github.xntso.vendroid.utils.exception.UnknownException
import io.github.xntso.vendroid.utils.exception.UsbCommunicationException
import io.github.xntso.vendroid.utils.exception.UsbCommunicationTimeoutException
import io.github.xntso.vendroid.utils.exception.UsbDriveTooLargeException
import io.github.xntso.vendroid.utils.exception.VerificationFailedException
import io.github.xntso.vendroid.utils.exception.base.VendroidException
import io.github.xntso.vendroid.utils.exception.base.FatalException
import io.github.xntso.vendroid.utils.ktexts.broadcastLocally
import io.github.xntso.vendroid.utils.ktexts.broadcastLocallySync
import io.github.xntso.vendroid.utils.ktexts.getDisplayName
import io.github.xntso.vendroid.utils.ktexts.getFileName
import io.github.xntso.vendroid.utils.ktexts.getFileSize
import io.github.xntso.vendroid.utils.ktexts.safeParcelableExtra
import io.github.xntso.vendroid.utils.ktexts.startForegroundSpecialUse
import io.github.xntso.vendroid.utils.ktexts.threadIdCompat
import io.github.xntso.vendroid.utils.ktexts.toHRSize
import io.github.xntso.vendroid.utils.lateInit
import io.github.xntso.vendroid.utils.timeoutWatchdog
import io.github.xntso.vendroid.ventoy.BlockDeviceRawBlockDevice
import io.github.xntso.vendroid.ventoy.VentoyInstallOptions
import io.github.xntso.vendroid.ventoy.VentoyInstallProgress
import io.github.xntso.vendroid.ventoy.VentoyInstallStage
import io.github.xntso.vendroid.ventoy.VentoyInstaller
import io.github.xntso.vendroid.ventoy.VentoyPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.Random
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "WorkerService"

private const val JOB_RESULT_CHANNEL = "io.github.xntso.vendroid.notifications.JOB_RESULT"
private const val JOB_PROGRESS_CHANNEL = "io.github.xntso.vendroid.notifications.JOB_PROGRESS"

private const val WAKELOCK_TIMEOUT = 10 * 60 * 1000L
private const val PROGRESS_UPDATE_INTERVAL = 1000L
const val BUFFER_BLOCKS = 4096L
const val QUEUE_SIZE = 2
const val IO_TIMEOUT = 10 * 1000L

class WorkerService : LifecycleService() {
    private var mLoggedNotificationWarning = false
    private var mNotificationsSetUp = false
    private val mProgressNotificationId = Random().nextInt()
    private lateinit var mSourceUri: Uri
    private lateinit var mDestDevice: UsbMassStorageDeviceDescriptor
    private var mJobId by lateInit<Int>(mutable = true)
    private var mOperation: String = Intents.OPERATION_WRITE_IMAGE
    private var mForceInstall: Boolean = false
    private var mWakelockAcquireTime = -1L
    private var mWakeLock: PowerManager.WakeLock? = null
    private var mVerificationCancelled = false

    private val mNotificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    private val notificationsAllowed: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mNotificationManager.areNotificationsEnabled()
        } else {
            true
        }

    init {
        setUpLibUSB()
    }

    override fun onCreate() {
        super.onCreate()
        println("WorkerService created")

        LocalBroadcastManager.getInstance(this)
            .registerReceiver(mBroadcastReceiver, IntentFilter().apply {
                addAction(Intents.SKIP_VERIFY)
                addAction(Intents.JOB_PROGRESS)
                addAction(Intents.ERROR)
                addAction(Intents.FINISHED)
            })

        trySetupNotifications()
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver)
    }

    private fun finish() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    /**
     * Broadcast receiver that forwards the received intents as notifications, if the user has
     * allowed notifications.
     */
    private val mBroadcastReceiver = broadcastReceiver { intent ->
        if (!mNotificationsSetUp) trySetupNotifications()

        when (intent.action) {
            Intents.SKIP_VERIFY -> {
                mVerificationCancelled = true
            }

            Intents.JOB_PROGRESS -> {
                if (!notificationsAllowed) return@broadcastReceiver
                val status =
                    intent.safeParcelableExtra<JobStatusInfo>("status") ?: return@broadcastReceiver

                mNotificationManager.notify(
                    mProgressNotificationId, NotificationCompat
                        .Builder(this@WorkerService, JOB_PROGRESS_CHANNEL)
                        .setSmallIcon(R.drawable.ic_write_to_usb_anim)
                        .setContentTitle(
                            getString(
                                if (status.isVerifying) R.string.notification_verify_progress_title
                                else R.string.notification_write_progress_title
                            )
                        )
                        .setContentText(
                            if (status.isVerifying) getString(
                                R.string.notification_verify_progress_content,
                                mDestDevice.name,
                                filenameStr
                            )
                            else getString(
                                R.string.notification_write_progress_content,
                                filenameStr,
                                mDestDevice.name
                            )
                        )
                        .setContentIntent(
                            getProgressUpdateIntent(
                                mSourceUri,
                                mDestDevice,
                                mJobId,
                                status.speed,
                                status.processedBytes,
                                status.totalBytes,
                                isVerifying = status.isVerifying,
                                packageContext = this@WorkerService,
                                cls = ProgressActivity::class.java,
                                operation = status.operation,
                                forceInstall = status.forceInstall,
                            ).getProgressActivityPendingIntent(this@WorkerService)
                        )
                        .setSubText(
                            if (status.operation == Intents.OPERATION_VENTOY_INSTALL) {
                                "${status.percent.coerceAtLeast(0)}%"
                            } else {
                                "${status.processedBytes.toHRSize()} • ${status.speed.toHRSize()}/s"
                            }
                        )
                        .setProgress(100, max(status.percent, 0), status.percent < 0)
                        .setOngoing(true)
                        .build()
                )
            }

            Intents.ERROR -> {
                if (!notificationsAllowed) return@broadcastReceiver
                val status =
                    intent.safeParcelableExtra<JobStatusInfo>("status") ?: return@broadcastReceiver

                status.exception!!

                mNotificationManager.notify(
                    mJobId, NotificationCompat
                        .Builder(this@WorkerService, JOB_RESULT_CHANNEL)
                        .setSmallIcon(R.drawable.ic_write_to_usb_failed)
                        .setContentTitle(
                            getString(
                                if (status.exception is FatalException) R.string.write_failed
                                else R.string.action_required
                            )
                        )
                        .setContentText(status.exception.getUiMessage(this@WorkerService))
                        .setContentIntent(
                            getErrorIntent(
                                mSourceUri,
                                mDestDevice,
                                mJobId,
                                status.processedBytes,
                                status.totalBytes,
                                status.exception,
                                packageContext = this@WorkerService,
                                cls = ProgressActivity::class.java,
                                operation = status.operation,
                                forceInstall = status.forceInstall,
                            ).getProgressActivityPendingIntent(this@WorkerService)
                        )
                        .setOngoing(false)
                        .build()
                )
            }

            Intents.FINISHED -> {
                if (!notificationsAllowed) return@broadcastReceiver
                val status =
                    intent.safeParcelableExtra<JobStatusInfo>("status") ?: return@broadcastReceiver

                mNotificationManager.notify(
                    mJobId, NotificationCompat
                        .Builder(this@WorkerService, JOB_RESULT_CHANNEL)
                        .setSmallIcon(R.drawable.ic_written_to_usb)
                        .setContentTitle(getString(R.string.write_finished))
                        .setContentText(
                            getString(
                                R.string.success_notif_content_text, filenameStr, mDestDevice.name
                            )
                        )
                        .setContentIntent(
                            getFinishedIntent(
                                mSourceUri,
                                mDestDevice,
                                status.totalBytes,
                                packageContext = this@WorkerService,
                                cls = ProgressActivity::class.java,
                                operation = status.operation,
                                forceInstall = status.forceInstall,
                            ).getProgressActivityPendingIntent(this@WorkerService)
                        )
                        .setOngoing(false)
                        .build()
                )
            }
        }
    }

    private fun ensureWakelock() {
        if (mWakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            mWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "Vendroid::WorkerService[$mProgressNotificationId]"
            )
        }
        if (!mWakeLock!!.isHeld || System.currentTimeMillis() - mWakelockAcquireTime > WAKELOCK_TIMEOUT * .9) {
            mWakeLock!!.acquire(WAKELOCK_TIMEOUT)
            mWakelockAcquireTime = System.currentTimeMillis()
        }
    }

    private fun releaseWakelock() {
        mWakeLock?.release()
        mWakeLock = null
        mWakelockAcquireTime = -1L
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val offset: Long
        val verifyOnly: Boolean

        try {
            if (intent?.action != Intents.START_JOB) {
                Telemetry.captureMessage("Received invalid intent action: ${intent?.action}")
                stopSelf()
                return START_NOT_STICKY
            }

            mSourceUri = intent.data!!
            mDestDevice = intent.safeParcelableExtra<UsbMassStorageDeviceDescriptor>("destDevice")
                ?: throw MissingDeviceException()
            mJobId = intent.getIntExtra("jobId", -1)
            offset = intent.getLongExtra("offset", 0L)
            verifyOnly = intent.getBooleanExtra("verifyOnly", false)
            mOperation = intent.getStringExtra(Intents.EXTRA_OPERATION) ?: Intents.OPERATION_WRITE_IMAGE
            mForceInstall = intent.getBooleanExtra(Intents.EXTRA_FORCE_INSTALL, false)

            val fileName = mSourceUri.getFileName(this@WorkerService) ?: "Unknown file"
            Telemetry.configureScope {
                setTag("intent", intent.toString())
                setTag("job.id", mJobId.toString())
                setTag("job.offset", offset.toString())
                setTag("job.verifyOnly", verifyOnly.toString())
                setTag("job.operation", mOperation)
                setTag("image.filename", fileName)
                setTag("usb.name", mDestDevice.name)
                setTag("usb.vidpid", mDestDevice.vidpid)
            }

            if (mNotificationsSetUp) {
                val notificationManager =
                    getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                try {
                    notificationManager.cancel(mJobId)
                } catch (e: Exception) {
                    Telemetry.captureException("Failed to cancel notification", e)
                }
            }

            require(mJobId != -1) { "Job ID not set" }

            Telemetry.addBreadcrumb {
                message = "Starting worker service for job ${mJobId}: " +
                        "'${fileName}' -> '${mDestDevice.name}' " +
                        "(offset: ${offset.toHRSize(false)})"
                category = "worker"
            }

            startForegroundSpecialUse(mProgressNotificationId, basicForegroundNotification)
        } catch (exception: Exception) {
            Telemetry.captureException("Failed to start worker service", exception)
            // mDestDevice (and mSourceUri/mJobId) may be unset if we failed before parsing
            // them; getErrorIntent needs a device, so only broadcast when we have one,
            // otherwise reading the uninitialized lateinit would crash the service itself.
            if (::mDestDevice.isInitialized) {
                val downstreamException =
                    exception as? VendroidException ?: UnknownException(exception)
                getErrorIntent(
                    mSourceUri,
                    mDestDevice,
                    mJobId,
                    0,
                    0,
                    exception = downstreamException,
                    operation = mOperation,
                    forceInstall = mForceInstall,
                ).broadcastLocallySync(this@WorkerService)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        lifecycleScope.launch(Dispatchers.IO) {
            Thread.currentThread().name = "WorkerService coroutine scope"

            Telemetry.debug(
                "Job coroutine scope started; thread ${Thread.currentThread().name} (${Thread.currentThread().threadIdCompat})",
                "worker"
            )

            val massStorageDevDelegate = lateInit<VendroidUsbMassStorageDevice>()
            var massStorageDev by massStorageDevDelegate
            var blockDev by lateInit<BlockDeviceDriver>()
            var rawSourceStream: InputStream? = null
            var currentOffset = offset
            var imageSize = 0L

            val coroScope = CoroutineScope(Dispatchers.IO)

            try {
                try {
                    Telemetry.debug("Building USB mass storage device", "worker")
                    massStorageDev = mDestDevice.buildDevice(this@WorkerService).apply {
                        init()
                    }
                    blockDev = massStorageDev.blockDevices[0]!!
                } catch (e: Exception) {
                    // Breadcrumb only: the wrapped exception below is captured (and
                    // classified/downgraded) at the outer handler — avoid duplicate events.
                    Telemetry.warning("Failed to initialize USB mass storage device: ${e.message}")
                    throw e as? VendroidException ?: InitException("Initialization failed", e)
                }
                currentOffset = offset - (offset % blockDev.blockSize)

                // Resume a few blocks earlier in case things went haywire earlier
                currentOffset = max(currentOffset - blockDev.blockSize * BUFFER_BLOCKS * 2, 0L)

                val devSize: Long = blockDev.blocks * blockDev.blockSize.toLong()
                Telemetry.debug(
                    "Device size: ${devSize.toHRSize(false)} " +
                            "(block size: ${blockDev.blockSize.toHRSize(false)}, " +
                            "num blocks: ${blockDev.blocks})",
                    "worker"
                )

                if (blockDev.blocks < 0) {
                    Telemetry.captureMessage(
                        "Device is too large: " +
                                "num blocks: ${blockDev.blocks}"
                    )
                    throw UsbDriveTooLargeException()
                }

                if (mOperation == Intents.OPERATION_VENTOY_INSTALL) {
                    val rawBlockDevice = BlockDeviceRawBlockDevice(blockDev)
                    imageSize = rawBlockDevice.sizeBytes

                    getProgressUpdateIntent(
                        mSourceUri,
                        mDestDevice,
                        mJobId,
                        0f,
                        0,
                        imageSize,
                        operation = mOperation,
                        forceInstall = mForceInstall,
                    ).broadcastLocallySync(this@WorkerService)

                    VentoyInstaller(VentoyPayload.fromAssets(assets)).install(
                        device = rawBlockDevice,
                        options = VentoyInstallOptions(forceInstall = mForceInstall),
                        onProgress = ::sendVentoyProgressUpdate,
                    )

                    getFinishedIntent(
                        mSourceUri,
                        mDestDevice,
                        imageSize,
                        operation = mOperation,
                        forceInstall = mForceInstall,
                    ).broadcastLocallySync(
                        this@WorkerService
                    )
                    return@launch
                }

                imageSize = mSourceUri.getFileSize(this@WorkerService)

                if (devSize < imageSize) {
                    Telemetry.captureMessage(
                        "Device size is smaller than image size: " +
                                "device: ${devSize.toHRSize(false)}, " +
                                "image: ${imageSize.toHRSize(false)}"
                    )
                    throw NotEnoughSpaceException(sourceSize = imageSize, destSize = devSize)
                }

                getProgressUpdateIntent(
                    mSourceUri,
                    mDestDevice,
                    mJobId,
                    0f,
                    currentOffset,
                    imageSize,
                    isVerifying = verifyOnly,
                    operation = mOperation,
                    forceInstall = mForceInstall,
                ).broadcastLocallySync(this@WorkerService)

                val bufferSize = BUFFER_BLOCKS * blockDev.blockSize

                // Write image
                if (!verifyOnly) {
                    try {
                        rawSourceStream = contentResolver.openInputStream(mSourceUri)!!
                    } catch (e: Exception) {
                        Telemetry.warning("Failed to open image file: ${e.message}")
                        throw OpenFileException("Failed to open image file", e)
                    }

                    Telemetry.info("Writing image to USB drive", "worker")

                    writeImage(
                        rawSourceStream,
                        blockDev,
                        imageSize,
                        bufferSize,
                        currentOffset,
                        { currentOffset = it },
                        coroScope,
                        ::ensureWakelock,
                        ::sendProgressUpdate
                    )
                } else {
                    Telemetry.info("Skipping write, only verifying image on USB drive", "worker")
                }

                // Verify written image
                try {
                    rawSourceStream = contentResolver.openInputStream(mSourceUri)!!
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open image file", e)
                    throw OpenFileException("Failed to open image file", e)
                }

                // Always verify the whole image, not just the part that was written
                currentOffset = 0
                mLast10Speeds.clear()

                Telemetry.info("Verifying image on USB drive", "worker")

                verifyImage(
                    rawSourceStream,
                    blockDev,
                    imageSize,
                    bufferSize,
                    { currentOffset = it },
                    coroScope,
                    ::sendProgressUpdate,
                    { mVerificationCancelled },
                    ::ensureWakelock
                )

                getFinishedIntent(
                    mSourceUri,
                    mDestDevice,
                    imageSize,
                    operation = mOperation,
                    forceInstall = mForceInstall,
                ).broadcastLocallySync(
                    this@WorkerService
                )

            } catch (exception: Exception) {
                val downstreamException = when (exception) {
                    is VendroidException -> exception
                    is TimeoutExpiredException -> UsbCommunicationTimeoutException(exception)
                    else -> UnknownException(exception)
                }
                Telemetry.captureException(downstreamException)
                getErrorIntent(
                    mSourceUri,
                    mDestDevice,
                    mJobId,
                    currentOffset,
                    imageSize,
                    exception = downstreamException,
                    operation = mOperation,
                    forceInstall = mForceInstall,
                ).broadcastLocallySync(this@WorkerService)
            } finally {
                finish()
                releaseWakelock()

                coroScope.cancel("Job finished or aborted")
                if (rawSourceStream != null) {
                    try {
                        rawSourceStream.close()
                    } catch (e: Exception) {
                        Telemetry.captureException("Failed to close image file", e)
                    }
                }
                // Use a timeout to prevent hanging if the USB device is stuck in a native
                // libusb transfer (e.g. after a Huawei/Samsung OTG reset mid-write).
                // close() calls into native code and cannot be interrupted by coroutine
                // cancellation, so without this timeout the service hangs indefinitely.
                withTimeoutOrNull(3000L.milliseconds) {
                    try {
                        if (massStorageDevDelegate.isInitialized)
                            massStorageDev.close()
                    } catch (e: Exception) {
                        Telemetry.captureException("Failed to close USB drive", e)
                    }
                } ?: Telemetry.captureMessage("Timed out closing USB drive (device may be stuck in native transfer)")
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private var mLastProgressUpdate = -1L
    private var mBytesSinceLastUpdate: Double = 0.0
    private var mLast10Speeds = mutableListOf<Float>()

    private fun sendProgressUpdate(
        lastWrittenBytes: Int,
        processedBytes: Long,
        imageSize: Long,
        isVerifying: Boolean = false,
    ) {
        mBytesSinceLastUpdate += lastWrittenBytes
        val newTime = System.currentTimeMillis()

        if (mLastProgressUpdate + PROGRESS_UPDATE_INTERVAL > newTime) return

        val interval = newTime - mLastProgressUpdate
        val speed =
            (mBytesSinceLastUpdate / (if (interval > 0) interval else PROGRESS_UPDATE_INTERVAL) * 1000).toFloat()
        mLast10Speeds.add(speed)
        if (mLast10Speeds.size > 10) mLast10Speeds.removeAt(0)

        // Calculate average with weights: 1, 2, 3, ..., mLast10Speeds.size
        val newSpeed = mLast10Speeds.mapIndexed { index, f -> f * (index + 1) }
            .sum() / (1..mLast10Speeds.size).sum().toFloat()

        getProgressUpdateIntent(
            mSourceUri,
            mDestDevice,
            mJobId,
            newSpeed,
            processedBytes,
            imageSize,
            isVerifying = isVerifying,
            operation = mOperation,
            forceInstall = mForceInstall,
        ).broadcastLocally(this@WorkerService)

        mLastProgressUpdate = newTime
        mBytesSinceLastUpdate = 0.0
    }

    private fun sendVentoyProgressUpdate(progress: VentoyInstallProgress) {
        ensureWakelock()
        val range = when (progress.stage) {
            VentoyInstallStage.ValidatingPayload -> 0L..5L
            VentoyInstallStage.Partitioning -> 5L..10L
            VentoyInstallStage.WritingBootloader -> 10L..20L
            VentoyInstallStage.WritingVentoyPayload -> 20L..70L
            VentoyInstallStage.FormattingExFat -> 70L..95L
            VentoyInstallStage.Verifying -> 95L..99L
            VentoyInstallStage.Complete -> 100L..100L
        }
        val fraction = if (progress.totalBytes > 0) {
            (progress.processedBytes.toDouble() / progress.totalBytes).coerceIn(0.0, 1.0)
        } else 0.0
        val processed = range.first + ((range.last - range.first) * fraction).toLong()
        getProgressUpdateIntent(
            mSourceUri,
            mDestDevice,
            mJobId,
            0f,
            processed,
            100,
            isVerifying = false,
            operation = mOperation,
            forceInstall = mForceInstall,
        ).broadcastLocally(this@WorkerService)
    }

    private val filenameStr: String by lazy {
        if (mOperation == Intents.OPERATION_VENTOY_INSTALL) {
            getString(R.string.ventoy_installer_name)
        } else {
            mSourceUri.getDisplayName(this) ?: getString(R.string.unknown_filename)
        }
    }

    private val basicForegroundNotification: Notification
        get() = NotificationCompat
            .Builder(this, JOB_PROGRESS_CHANNEL)
            .setSmallIcon(R.drawable.ic_write_to_usb_anim)
            .setContentTitle(getString(R.string.notification_write_progress_title))
            .setContentText(
                getString(
                    if (notificationsAllowed) R.string.notification_write_tap_for_progress_text
                    else R.string.notification_write_no_notifications_text,
                    filenameStr,
                    mDestDevice.name
                )
            )
            .setProgress(100, 0, true)
            .setOngoing(true)
            .build()

    private fun trySetupNotifications() {
        if (mNotificationsSetUp || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            mNotificationManager.createNotificationChannel(
                NotificationChannel(
                    JOB_PROGRESS_CHANNEL, getString(R.string.notif_channel_job_progress_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notif_channel_job_progress_desc)
                    setShowBadge(false)
                })
            mNotificationManager.createNotificationChannel(
                NotificationChannel(
                    JOB_RESULT_CHANNEL, getString(R.string.notif_channel_job_result_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = getString(R.string.notif_channel_job_result_desc)
                })

            mNotificationsSetUp = true
        } catch (e: SecurityException) {
            if (!mLoggedNotificationWarning) {
                Log.w(TAG, "Could not register notification channels", e)
                mLoggedNotificationWarning = true
            }
        }
    }
}

object WorkerServiceFlowImpl {
    suspend fun writeImage(
        rawSourceStream: InputStream,
        blockDev: BlockDeviceDriver,
        imageSize: Long,
        bufferSize: Long,
        initialOffset: Long,
        notifyCurrentOffset: (offset: Long) -> Unit,
        coroScope: CoroutineScope,
        grabWakeLock: () -> Unit,
        sendProgressUpdate: (
            lastWrittenBytes: Int,
            processedBytes: Long,
            imageSize: Long,
            isVerifying: Boolean,
        ) -> Unit,
    ) {
        val buffer = ByteArray(bufferSize.toInt())
        var currentOffset = initialOffset

        val src = BufferedInputStream(rawSourceStream, (bufferSize * 4).toInt())
        val dst =
            BlockDeviceOutputStream(blockDev, coroScope, BUFFER_BLOCKS / QUEUE_SIZE, QUEUE_SIZE)

        try {
            timeoutWatchdog(IO_TIMEOUT) { watchdog ->
                Thread.currentThread().name = "writeImage coroutine scope"

                src.skip(currentOffset)
                dst.seekAsync(currentOffset)

                while (currentOffset < imageSize) {
                    grabWakeLock()
                    watchdog.bump()

                    val read = src.read(buffer)
                    if (read == -1) break

                    watchdog.bump()
                    try {
                        dst.writeAsync(buffer, 0, read)
                        watchdog.bump()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Telemetry.warning("Failed to write to USB drive: ${e.message}")
                        throw UsbCommunicationException(e)
                    }
                    currentOffset += read
                    notifyCurrentOffset(currentOffset)

                    sendProgressUpdate(read, currentOffset, imageSize, false)
                }
                watchdog.bump()
                try {
                    dst.flushAsync()
                    watchdog.bump()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Telemetry.warning("Failed to flush USB drive: ${e.message}")
                    throw UsbCommunicationException(e)
                }
            }
        } finally {
            src.close()
            dst.closeAsync()
        }
    }

    @SuppressLint("WrongSdkInt")
    suspend fun verifyImage(
        rawSourceStream: InputStream,
        blockDev: BlockDeviceDriver,
        imageSize: Long,
        bufferSize: Long,
        notifyCurrentOffset: (offset: Long) -> Unit,
        lifecycleScope: CoroutineScope,
        sendProgressUpdate: (
            lastWrittenBytes: Int,
            processedBytes: Long,
            imageSize: Long,
            isVerifying: Boolean,
        ) -> Unit,
        isVerificationCanceled: () -> Boolean,
        grabWakeLock: () -> Unit,
    ) {

        val src = BufferedInputStream(rawSourceStream, (bufferSize * 4).toInt())
        val dst = BlockDeviceInputStream(
            blockDev, coroutineScope = lifecycleScope, BUFFER_BLOCKS / QUEUE_SIZE, QUEUE_SIZE
        )

        try {
            timeoutWatchdog(IO_TIMEOUT) { watchdog ->
                Thread.currentThread().name = "verifyImage coroutine scope"

                var currentOffset = 0L

                src.skip(currentOffset)
                dst.skipAsync(currentOffset)

                watchdog.bump()

                val fileBuffer = ByteArray(1024)
                val deviceBuffer = ByteArray(1024)

                while (!isVerificationCanceled()) {
                    grabWakeLock()
                    watchdog.bump()

                    val read = src.read(fileBuffer)
                    if (read == -1) break

                    watchdog.bump()
                    try {
                        val deviceRead = dst.readAsync(deviceBuffer, 0, read)
                        require(
                            deviceRead >= read
                        ) { "Device read $deviceRead < file read $read" }
                        watchdog.bump()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Telemetry.warning("Failed to read from USB drive: ${e.message}")
                        throw UsbCommunicationException(e)
                    }

                    val mismatch = if (read == fileBuffer.size) {
                        !fileBuffer.contentEquals(deviceBuffer)
                    } else {
                        !fileBuffer.copyOf(read).contentEquals(deviceBuffer.copyOf(read))
                    }
                    if (mismatch) {
                        Telemetry.captureMessage("Verification failed")
                        if (Build.VERSION.SDK_INT > 999999) {
                            // Prevent the compiler from optimizing out the buffers, for debugging
                            // purposes
                            print(deviceBuffer)
                            print(fileBuffer)
                        }
                        throw VerificationFailedException()
                    }
                    currentOffset += read
                    notifyCurrentOffset(currentOffset)

                    sendProgressUpdate(read, currentOffset, imageSize, true)
                }
            }
        } finally {
            src.close()
            try {
                dst.closeAsync()
            } catch (e: Exception) {
                Telemetry.warning("Failed to read from USB drive: ${e.message}")
                throw UsbCommunicationException(e)
            }
        }
    }
}
