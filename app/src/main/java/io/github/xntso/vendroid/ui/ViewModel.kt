package io.github.xntso.vendroid.ui

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.xntso.vendroid.AppSettings
import io.github.xntso.vendroid.Intents
import io.github.xntso.vendroid.JobStatusInfo
import io.github.xntso.vendroid.SettingChangeListener
import io.github.xntso.vendroid.ThemeMode
import io.github.xntso.vendroid.VentoyJobOptions
import io.github.xntso.vendroid.massstorage.VendroidUsbMassStorageDevice.Companion.massStorageDevices
import io.github.xntso.vendroid.massstorage.UsbMassStorageDeviceDescriptor
import io.github.xntso.vendroid.plugins.telemetry.Telemetry
import io.github.xntso.vendroid.plugins.telemetry.TelemetryLevel
import io.github.xntso.vendroid.utils.exception.ServiceTimeoutException
import io.github.xntso.vendroid.utils.exception.base.VendroidException
import io.github.xntso.vendroid.utils.exception.base.RecoverableException
import io.github.xntso.vendroid.utils.ktexts.safeParcelableExtra
import io.github.xntso.vendroid.ventoy.VentoyDiskInfo
import io.github.xntso.vendroid.ventoy.VentoyDiskLayout
import io.github.xntso.vendroid.ventoy.VentoyOnlineUpdater
import io.github.xntso.vendroid.ventoy.VentoyPartitionStyle
import io.github.xntso.vendroid.ventoy.VentoyPayloadCache
import io.github.xntso.vendroid.ventoy.VentoyVersion
import io.github.xntso.vendroid.ventoy.VentoyVersionRelation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update


interface IThemeState {
    val dynamicColors: Boolean
    val themeMode: ThemeMode
}

interface IViewModel<T> {
    val state: StateFlow<T>
}

interface IThemeViewModel<T> : IViewModel<T> where T : IThemeState {
    val darkMode: State<Boolean>
        @Composable get() {
            val stateValue by state.collectAsState()
            val systemInDarkMode = isSystemInDarkTheme()
            return remember(stateValue.themeMode) {
                derivedStateOf {
                    when (stateValue.themeMode) {
                        ThemeMode.SYSTEM -> systemInDarkMode
                        ThemeMode.DARK -> true
                        ThemeMode.LIGHT -> false
                    }
                }
            }
        }
}

data class ThemeState(
    override val dynamicColors: Boolean = false,
    override val themeMode: ThemeMode = ThemeMode.SYSTEM,
) : IThemeState {
    companion object {
        val Empty: ThemeState
            get() = ThemeState()
    }
}

class ThemeViewModel : ViewModel(), SettingChangeListener, IThemeViewModel<ThemeState> {
    private val _state = MutableStateFlow(ThemeState.Empty)
    override val state: StateFlow<ThemeState> = _state.asStateFlow()

    override fun refreshSettings(settings: AppSettings) {
        _state.update {
            it.copy(
                dynamicColors = settings.dynamicColors, themeMode = settings.themeMode
            )
        }
    }
}

data class MainActivityState(
    override val dynamicColors: Boolean = false,
    override val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val showWindowsAlertForUri: Uri? = null,
    val openedImage: Uri? = null,
    val massStorageDevices: Set<UsbMassStorageDeviceDescriptor> = emptySet(),
) : IThemeState {
    companion object {
        val Empty: MainActivityState
            get() = MainActivityState()
    }
}

class MainActivityViewModel : ViewModel(), SettingChangeListener, IThemeViewModel<MainActivityState> {
    private val _state = MutableStateFlow(MainActivityState.Empty)
    override val state: StateFlow<MainActivityState> = _state.asStateFlow()

    override fun refreshSettings(settings: AppSettings) {
        _state.update {
            it.copy(
                    dynamicColors = settings.dynamicColors,
                    themeMode = settings.themeMode,
            )
        }
    }

    fun setState(state: MainActivityState) {
        _state.update { state }
    }

    fun setShowWindowsAlertUri(uri: Uri?) {
        _state.update {
            it.copy(showWindowsAlertForUri = uri)
        }
    }

    fun setOpenedImage(uri: Uri?) {
        _state.update {
            it.copy(openedImage = uri)
        }
    }


    fun usbDeviceAttached(device: UsbDevice) {
        _state.update {
            val new = it.copy(
                massStorageDevices = it.massStorageDevices + device.massStorageDevices
            )
            // println("usbDeviceAttached: $new")
            new
        }
    }

    fun usbDeviceDetached(device: UsbDevice) {
        _state.update { state ->
            state.copy(massStorageDevices = state.massStorageDevices.filter { it.usbDevice != device }.toSet())
        }
    }


    fun replaceUsbDevices(devices: Collection<UsbDevice>) {
        _state.update { state ->
            state.copy(
                massStorageDevices = devices.flatMap { it.massStorageDevices }.toSet()
            )
        }
    }

    override fun toString(): String {
        return "MainActivityViewModel(state=${_state.value})"
    }
}

enum class VentoyDriveState {
    NotApplicable,
    AwaitingPermission,
    Scanning,
    ReadyToInstall,
    UpdateAvailable,
    ReadyToRepair,
    NewerVersion,
    RequiresPreview,
    ExistingPartitions,
    ScanFailed,
}

enum class VentoyOnlineState {
    Idle,
    Checking,
    Downloading,
    Ready,
    UpToDate,
    Error,
}

data class ConfirmOperationActivityState(
    override val dynamicColors: Boolean = false,
    override val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val openedImage: Uri? = null,
    val selectedDevice: UsbMassStorageDeviceDescriptor? = null,
    val hasUsbPermission: Boolean = false,
    val operation: String = Intents.OPERATION_WRITE_IMAGE,
    val forceInstall: Boolean = false,
    val ventoyOptions: VentoyJobOptions = VentoyJobOptions(),
    val ventoyDriveState: VentoyDriveState = VentoyDriveState.NotApplicable,
    val installedVentoyVersion: String? = null,
    val bundledVentoyVersion: String? = null,
    val onlineVentoyVersion: String? = null,
    val onlineState: VentoyOnlineState = VentoyOnlineState.Idle,
    val onlineDownloadPercent: Int = -1,
    val onlineError: String? = null,
    val scannedDiskSizeBytes: Long = 0,
    val reservedSpaceBytes: Long = 0,
    val scanError: String? = null,
    val hasRecognizedVentoy: Boolean = false,
    val hasAnyPartition: Boolean = false,
    val ventoyNeedsRepair: Boolean = false,
) : IThemeState {
    val targetVentoyVersion: String?
        get() = ventoyOptions.onlinePayloadVersion ?: bundledVentoyVersion

    companion object {
        val Empty: ConfirmOperationActivityState
            get() = ConfirmOperationActivityState()
    }
}


class ConfirmOperationActivityViewModel : ViewModel(), SettingChangeListener,
    IThemeViewModel<ConfirmOperationActivityState> {
    private val _state = MutableStateFlow(ConfirmOperationActivityState.Empty)
    override val state: StateFlow<ConfirmOperationActivityState> = _state.asStateFlow()

    override fun refreshSettings(settings: AppSettings) {
        _state.update {
            it.copy(
                dynamicColors = settings.dynamicColors, themeMode = settings.themeMode
            )
        }
    }

    fun setState(state: ConfirmOperationActivityState) {
        _state.update { state }
    }

    fun init(
        openedImage: Uri?,
        selectedDevice: UsbMassStorageDeviceDescriptor?,
        operation: String = Intents.OPERATION_WRITE_IMAGE,
        forceInstall: Boolean = false,
        bundledVentoyVersion: String? = null,
    ) = _state.update {
        it.copy(
            openedImage = openedImage,
            selectedDevice = selectedDevice,
            hasUsbPermission = false,
            operation = operation,
            forceInstall = forceInstall,
            ventoyOptions = VentoyJobOptions(forceInstall = forceInstall),
            bundledVentoyVersion = bundledVentoyVersion,
            ventoyDriveState = if (Intents.isVentoyOperation(operation)) {
                VentoyDriveState.AwaitingPermission
            } else {
                VentoyDriveState.NotApplicable
            },
        )
    }

    fun setPermission(permission: Boolean) {
        _state.update {
            it.copy(hasUsbPermission = permission)
        }
    }

    fun setVentoyScanning() {
        _state.update { it.copy(ventoyDriveState = VentoyDriveState.Scanning, scanError = null) }
    }

    fun setVentoyScanResult(
        diskInfo: VentoyDiskInfo?,
        hasAnyPartition: Boolean,
        diskSizeBytes: Long,
        bundledVersion: String,
        supportsGpt: Boolean = false,
    ) {
        _state.update { state ->
            val targetVersion = state.ventoyOptions.onlinePayloadVersion ?: bundledVersion
            val requiresPreview = !supportsGpt && (
                diskSizeBytes > VentoyDiskLayout.MAX_MBR_DISK_BYTES ||
                    diskInfo?.partitionStyle == VentoyPartitionStyle.Gpt
                )
            if (requiresPreview) {
                return@update state.copy(
                    ventoyDriveState = VentoyDriveState.RequiresPreview,
                    installedVentoyVersion = diskInfo?.installedVersion,
                    bundledVentoyVersion = bundledVersion,
                    scannedDiskSizeBytes = diskSizeBytes,
                    reservedSpaceBytes = diskInfo?.reservedSpaceBytes ?: 0,
                    scanError = null,
                    hasRecognizedVentoy = diskInfo != null,
                    hasAnyPartition = hasAnyPartition,
                    ventoyNeedsRepair = diskInfo?.needsRepair == true,
                )
            }
            if (state.forceInstall) {
                return@update state.copy(
                    operation = Intents.OPERATION_VENTOY_INSTALL,
                    ventoyDriveState = VentoyDriveState.ReadyToInstall,
                    installedVentoyVersion = diskInfo?.installedVersion,
                    bundledVentoyVersion = bundledVersion,
                    scannedDiskSizeBytes = diskSizeBytes,
                    reservedSpaceBytes = diskInfo?.reservedSpaceBytes ?: 0,
                    scanError = null,
                    hasRecognizedVentoy = diskInfo != null,
                    hasAnyPartition = hasAnyPartition,
                    ventoyNeedsRepair = diskInfo?.needsRepair == true,
                )
            }

            if (diskInfo == null) {
                return@update state.copy(
                    operation = Intents.OPERATION_VENTOY_INSTALL,
                    ventoyDriveState = if (hasAnyPartition) {
                        VentoyDriveState.ExistingPartitions
                    } else {
                        VentoyDriveState.ReadyToInstall
                    },
                    installedVentoyVersion = null,
                    bundledVentoyVersion = bundledVersion,
                    scannedDiskSizeBytes = diskSizeBytes,
                    reservedSpaceBytes = 0,
                    scanError = null,
                    hasRecognizedVentoy = false,
                    hasAnyPartition = hasAnyPartition,
                    ventoyNeedsRepair = false,
                )
            }

            val relation = VentoyVersion.compare(diskInfo.installedVersion, targetVersion)
            state.copy(
                operation = Intents.OPERATION_VENTOY_UPDATE,
                ventoyDriveState = when (relation) {
                    VentoyVersionRelation.Older -> VentoyDriveState.UpdateAvailable
                    VentoyVersionRelation.Same, VentoyVersionRelation.Unknown ->
                        VentoyDriveState.ReadyToRepair
                    VentoyVersionRelation.Newer -> if (diskInfo.needsRepair) {
                        VentoyDriveState.ReadyToRepair
                    } else {
                        VentoyDriveState.NewerVersion
                    }
                },
                installedVentoyVersion = diskInfo.installedVersion,
                bundledVentoyVersion = bundledVersion,
                scannedDiskSizeBytes = diskSizeBytes,
                reservedSpaceBytes = diskInfo.reservedSpaceBytes,
                scanError = null,
                hasRecognizedVentoy = true,
                hasAnyPartition = hasAnyPartition,
                ventoyNeedsRepair = diskInfo.needsRepair,
            )
        }
    }

    fun setVentoyScanError(message: String?) {
        _state.update {
            it.copy(
                ventoyDriveState = VentoyDriveState.ScanFailed,
                scanError = message,
            )
        }
    }

    fun retryVentoyScan() {
        _state.update {
            it.copy(
                ventoyDriveState = VentoyDriveState.AwaitingPermission,
                scanError = null,
            )
        }
    }

    fun setVentoyOptions(options: VentoyJobOptions) {
        _state.update {
            it.copy(
                ventoyOptions = options.copy(
                    forceInstall = it.forceInstall,
                    onlinePayloadVersion = it.ventoyOptions.onlinePayloadVersion,
                ),
            )
        }
    }

    fun loadCachedOnlinePayload(cache: VentoyPayloadCache, bundledVersion: String) {
        if (_state.value.onlineState != VentoyOnlineState.Idle) return
        viewModelScope.launch(Dispatchers.IO) {
            cache.newestCompatible(bundledVersion)
                ?.let { selectOnlinePayload(it.version) }
        }
    }

    fun checkForOnlinePayload(updater: VentoyOnlineUpdater, bundledVersion: String) {
        if (_state.value.onlineState in setOf(
                VentoyOnlineState.Checking,
                VentoyOnlineState.Downloading,
            )
        ) return

        _state.update {
            it.copy(
                onlineState = VentoyOnlineState.Checking,
                onlineDownloadPercent = -1,
                onlineError = null,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val release = updater.fetchLatestRelease()
                val currentTarget = _state.value.targetVentoyVersion ?: bundledVersion
                when (VentoyVersion.compare(release.version, currentTarget)) {
                    VentoyVersionRelation.Older,
                    VentoyVersionRelation.Same -> {
                        _state.update {
                            it.copy(
                                onlineVentoyVersion = release.version,
                                onlineState = VentoyOnlineState.UpToDate,
                                onlineDownloadPercent = 100,
                            )
                        }
                    }
                    VentoyVersionRelation.Newer -> {
                        _state.update {
                            it.copy(
                                onlineVentoyVersion = release.version,
                                onlineState = VentoyOnlineState.Downloading,
                                onlineDownloadPercent = 0,
                            )
                        }
                        val payload = updater.downloadRelease(
                            release = release,
                            bundledVersion = bundledVersion,
                        ) { progress ->
                            coroutineContext.ensureActive()
                            val percent = if (progress.totalBytes > 0) {
                                (progress.downloadedBytes * 100 / progress.totalBytes)
                                    .toInt()
                                    .coerceIn(0, 100)
                            } else {
                                -1
                            }
                            _state.update {
                                it.copy(
                                    onlineState = VentoyOnlineState.Downloading,
                                    onlineDownloadPercent = percent,
                                )
                            }
                        }
                        selectOnlinePayload(payload.version)
                    }
                    VentoyVersionRelation.Unknown ->
                        error("Could not compare Ventoy release versions")
                }
            }.onFailure { exception ->
                if (exception is CancellationException) throw exception
                _state.update {
                    it.copy(
                        onlineState = VentoyOnlineState.Error,
                        onlineDownloadPercent = -1,
                        onlineError = exception.message,
                    )
                }
            }
        }
    }

    private fun selectOnlinePayload(version: String) {
        _state.update { state ->
            val updated = state.copy(
                onlineVentoyVersion = version,
                onlineState = VentoyOnlineState.Ready,
                onlineDownloadPercent = 100,
                onlineError = null,
                ventoyOptions = state.ventoyOptions.copy(onlinePayloadVersion = version),
            )
            when (state.ventoyDriveState) {
                VentoyDriveState.AwaitingPermission,
                VentoyDriveState.Scanning,
                VentoyDriveState.ScanFailed,
                VentoyDriveState.NotApplicable -> updated
                else -> updated.withTargetVersion(version)
            }
        }
    }

    private fun ConfirmOperationActivityState.withTargetVersion(
        targetVersion: String,
    ): ConfirmOperationActivityState {
        if (ventoyDriveState == VentoyDriveState.RequiresPreview) {
            return copy(ventoyDriveState = VentoyDriveState.RequiresPreview)
        }
        if (forceInstall) {
            return copy(
                operation = Intents.OPERATION_VENTOY_INSTALL,
                ventoyDriveState = VentoyDriveState.ReadyToInstall,
            )
        }
        if (!hasRecognizedVentoy) {
            return copy(
                operation = Intents.OPERATION_VENTOY_INSTALL,
                ventoyDriveState = if (hasAnyPartition) {
                    VentoyDriveState.ExistingPartitions
                } else {
                    VentoyDriveState.ReadyToInstall
                },
            )
        }
        return copy(
            operation = Intents.OPERATION_VENTOY_UPDATE,
            ventoyDriveState = when (
                VentoyVersion.compare(installedVentoyVersion, targetVersion)
            ) {
                VentoyVersionRelation.Older -> VentoyDriveState.UpdateAvailable
                VentoyVersionRelation.Same,
                VentoyVersionRelation.Unknown -> VentoyDriveState.ReadyToRepair
                VentoyVersionRelation.Newer -> if (ventoyNeedsRepair) {
                    VentoyDriveState.ReadyToRepair
                } else {
                    VentoyDriveState.NewerVersion
                }
            },
        )
    }
}

enum class JobState {
    IN_PROGRESS, SUCCESS, FATAL_ERROR, RECOVERABLE_ERROR
}

data class ProgressActivityState(
    override val dynamicColors: Boolean = false,
    override val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val jobState: JobState = JobState.IN_PROGRESS,
    val percent: Int = -1,
    val speed: Float = 0f,
    val processedBytes: Long = 0,
    val totalBytes: Long = 0,
    val jobId: Int = -1,
    val isVerifying: Boolean = false,
    val operation: String = Intents.OPERATION_WRITE_IMAGE,
    val forceInstall: Boolean = false,
    val ventoyOptions: VentoyJobOptions = VentoyJobOptions(),
    val sourceUri: Uri? = null,
    val destDevice: UsbMassStorageDeviceDescriptor? = null,
    val exception: VendroidException? = null,
    val recoverableError: Boolean = false,
    val notificationsPermission: Boolean = false,
    val showNotificationsBanner: Boolean = true,
    val lastNotificationTime: Long = System.currentTimeMillis(),
) : IThemeState {
    companion object {
        val Empty: ProgressActivityState
            get() = ProgressActivityState()
    }
}

class ProgressActivityViewModel : ViewModel(), SettingChangeListener, IThemeViewModel<ProgressActivityState> {
    private val _state = MutableStateFlow(ProgressActivityState.Empty)
    override val state: StateFlow<ProgressActivityState> = _state.asStateFlow()

    override fun refreshSettings(settings: AppSettings) {
        _state.update {
            it.copy(
                dynamicColors = settings.dynamicColors, themeMode = settings.themeMode,
                showNotificationsBanner = settings.showNotificationsBanner
            )
        }
    }

    fun setState(state: ProgressActivityState) {
        _state.update { state }
    }

    fun updateFromIntent(intent: Intent) {
        val sourceUri = intent.safeParcelableExtra<Uri>("sourceUri")!!
        val status = intent.safeParcelableExtra<JobStatusInfo>("status")!!

        Telemetry.addBreadcrumb {
            message =
                "[${status.jobId}] Job progress notification: ${intent.action}, ${status.percent}%, verifying: ${status.isVerifying}"
            category = "job_broadcast"
            level = when (intent.action) {
                Intents.JOB_PROGRESS -> TelemetryLevel.DEBUG
                Intents.FINISHED -> TelemetryLevel.INFO
                Intents.ERROR -> TelemetryLevel.ERROR
                else -> TelemetryLevel.INFO
            }
            data["job.id"] = status.jobId.toString()
            data["job.percent"] = status.percent.toString()
            data["job.isVerifying"] = status.isVerifying.toString()
            data["job.processedBytes"] = status.processedBytes.toString()
            data["job.totalBytes"] = status.totalBytes.toString()
        }

        when (intent.action) {
            Intents.JOB_PROGRESS -> {
                _state.update {
                    it.copy(
                        jobState = JobState.IN_PROGRESS,
                        jobId = status.jobId,
                        isVerifying = status.isVerifying,
                        operation = status.operation,
                        forceInstall = status.forceInstall,
                        ventoyOptions = status.ventoyOptions,
                        percent = status.percent,
                        speed = status.speed,
                        processedBytes = status.processedBytes,
                        totalBytes = status.totalBytes,
                        sourceUri = sourceUri,
                        destDevice = status.destDevice,
                        exception = null,
                        lastNotificationTime = System.currentTimeMillis(),
                    )
                }
            }

            Intents.FINISHED -> {
                _state.update {
                    it.copy(
                        jobState = JobState.SUCCESS,
                        exception = null,
                        operation = status.operation,
                        forceInstall = status.forceInstall,
                        ventoyOptions = status.ventoyOptions,
                        sourceUri = sourceUri,
                        destDevice = status.destDevice,
                        totalBytes = status.totalBytes,
                        percent = 100,
                        lastNotificationTime = System.currentTimeMillis(),
                    )
                }
            }

            Intents.ERROR -> {
                _state.update {
                    it.copy(
                        jobState = if (status.exception is RecoverableException) JobState.RECOVERABLE_ERROR else JobState.FATAL_ERROR,
                        jobId = status.jobId,
                        exception = status.exception,
                        operation = status.operation,
                        forceInstall = status.forceInstall,
                        ventoyOptions = status.ventoyOptions,
                        sourceUri = sourceUri,
                        destDevice = status.destDevice,
                        processedBytes = status.processedBytes,
                        totalBytes = status.totalBytes,
                        percent = status.percent,
                        lastNotificationTime = System.currentTimeMillis(),
                    )
                }
                if (status.exception != null) {
                    Telemetry.captureException(status.exception)
                }
            }
        }
    }

    fun setTimeoutError() {
        _state.update {
            it.copy(
                jobState = JobState.FATAL_ERROR,
                exception = ServiceTimeoutException(),
            )
        }
    }

    fun setNotificationsPermission(permission: Boolean) {
        _state.update {
            it.copy(notificationsPermission = permission)
        }
    }
}
