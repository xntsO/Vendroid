package io.github.xntso.vendroid.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.twotone.ArrowDownward
import androidx.compose.material.icons.twotone.Check
import androidx.compose.material.icons.twotone.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ChainStyle
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import androidx.constraintlayout.compose.atLeastWrapContent
import androidx.core.net.toUri
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import io.github.xntso.vendroid.AppSettings
import io.github.xntso.vendroid.Intents
import io.github.xntso.vendroid.R
import io.github.xntso.vendroid.VENTOY_INSTALL_URI
import io.github.xntso.vendroid.VentoyJobOptions
import io.github.xntso.vendroid.getProgressUpdateIntent
import io.github.xntso.vendroid.getStartJobIntent
import io.github.xntso.vendroid.getStartVentoyInstallJobIntent
import io.github.xntso.vendroid.getStartVentoyUpdateJobIntent
import io.github.xntso.vendroid.massstorage.PreviewUsbDevice
import io.github.xntso.vendroid.massstorage.UsbMassStorageDeviceDescriptor
import io.github.xntso.vendroid.plugins.telemetry.Telemetry
import io.github.xntso.vendroid.service.WorkerService
import io.github.xntso.vendroid.ui.composables.MainView
import io.github.xntso.vendroid.ui.composables.ScreenSizeLayoutSelector
import io.github.xntso.vendroid.ui.composables.appiumTag
import io.github.xntso.vendroid.utils.broadcastReceiver
import io.github.xntso.vendroid.utils.ktexts.formatID
import io.github.xntso.vendroid.utils.ktexts.getFileName
import io.github.xntso.vendroid.utils.ktexts.getFileSize
import io.github.xntso.vendroid.utils.ktexts.registerExportedReceiver
import io.github.xntso.vendroid.utils.ktexts.safeParcelableExtra
import io.github.xntso.vendroid.utils.ktexts.startForegroundServiceCompat
import io.github.xntso.vendroid.utils.ktexts.toHRSize
import io.github.xntso.vendroid.utils.ktexts.toast
import io.github.xntso.vendroid.utils.ktexts.usbDevice
import io.github.xntso.vendroid.ventoy.BlockDeviceRawBlockDevice
import io.github.xntso.vendroid.ventoy.VentoyDiskScanner
import io.github.xntso.vendroid.ventoy.VentoyClusterSize
import io.github.xntso.vendroid.ventoy.VentoyPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

private const val TAG = "ConfirmOperationActivit"

class ConfirmOperationActivity : ActivityBase() {
    private val mViewModel: ConfirmOperationActivityViewModel by viewModels()
    private lateinit var mSettings: AppSettings
    private lateinit var mUsbPermissionIntent: PendingIntent
    private var mOperation: String = Intents.OPERATION_WRITE_IMAGE
    private var mForceInstall: Boolean = false


    private val mUsbDevicesReceiver = broadcastReceiver { intent ->
        val usbDevice: UsbDevice? = if (intent.usbDevice == null) {
            Log.w(TAG, "Received USB broadcast without device, using selected device: $intent")
            mViewModel.state.value.selectedDevice?.usbDevice
        } else {
            intent.usbDevice
        }
        if (usbDevice == null) {
            Log.w(
                TAG,
                "Received USB broadcast without device and no selected device, ignoring: $intent"
            )
            Telemetry.captureMessage("Received USB broadcast without device and no selected device")
            return@broadcastReceiver
        }

        Telemetry.addBreadcrumb {
            category = "usb"
            message = "Received USB broadcast: ${intent.action}, device: $usbDevice"
            data["intent.action"] = intent.action.toString()
            data["usb.device"] = usbDevice.toString()
        }

        when (intent.action) {
            Intents.USB_PERMISSION -> {
                // Since we're using an immutable PendingIntent as recommended by the latest API
                // we won't receive a USB device or grant status; we need to check back with the
                // USB manager
                val usbManager = getSystemService(USB_SERVICE) as UsbManager
                val granted = usbManager.hasPermission(usbDevice)

                Telemetry.addBreadcrumb {
                    category = "usb"
                    message = "USB permission granted: $granted"
                    data["usb.device"] = usbDevice.toString()
                    data["usb.permission"] = granted.toString()
                }
                mViewModel.setPermission(granted)

                if (!granted) {
                    toast(
                        getString(
                            R.string.permission_denied_for_usb_device, usbDevice.deviceName
                        )
                    )
                }
            }

            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                if (usbDevice == mViewModel.state.value.selectedDevice?.usbDevice) {
                    Telemetry.addBreadcrumb {
                        category = "usb"
                        message = "Selected USB device was unplugged"
                        data["usb.device"] = usbDevice.toString()
                    }
                    toast(getString(R.string.usb_device_was_unplugged))
                    finish()
                } else {
                    Telemetry.addBreadcrumb("Unplugged USB device was not selected", "usb")
                }
            }

            else -> {
                Telemetry.captureMessage("Received unknown USB broadcast: ${intent.action}")
                Log.w(TAG, "Received unknown broadcast: ${intent.action}")
            }
        }
    }

    private fun registerUsbReceiver() {
        val usbPermissionFilter = IntentFilter(Intents.USB_PERMISSION)
        val usbDetachedFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)

        registerExportedReceiver(mUsbDevicesReceiver, usbPermissionFilter)
        registerExportedReceiver(mUsbDevicesReceiver, usbDetachedFilter)
    }

    private fun unregisterUsbReceiver() {
        unregisterReceiver(mUsbDevicesReceiver)
    }

    override fun onStart() {
        super.onStart()
        registerUsbReceiver()
    }

    override fun onStop() {
        super.onStop()
        unregisterUsbReceiver()
    }

    private fun writeImage(uri: Uri, device: UsbMassStorageDeviceDescriptor) {
        println("writeImage $uri, $device")
        val jobId = Random.nextInt()
        val state = mViewModel.state.value
        val operation = state.operation
        val ventoyOptions = state.ventoyOptions
        val intent = when (operation) {
            Intents.OPERATION_VENTOY_INSTALL -> getStartVentoyInstallJobIntent(
                destDevice = device,
                jobId = jobId,
                forceInstall = mForceInstall,
                ventoyOptions = ventoyOptions,
                packageContext = this,
                cls = WorkerService::class.java,
            )
            Intents.OPERATION_VENTOY_UPDATE -> getStartVentoyUpdateJobIntent(
                destDevice = device,
                jobId = jobId,
                ventoyOptions = ventoyOptions,
                packageContext = this,
                cls = WorkerService::class.java,
            )
            else -> getStartJobIntent(uri, device, jobId, 0, false, this, WorkerService::class.java)
        }
        Telemetry.addBreadcrumb {
            category = "flow"
            message = "Starting worker service; job ID: $jobId"
            data["intent"] = intent.toString()
            data["job.id"] = jobId.toString()
        }
        startForegroundServiceCompat(intent)
        startActivity(
            getProgressUpdateIntent(
                uri,
                device,
                jobId,
                0f,
                0,
                0,
                isVerifying = false,
                packageContext = this,
                cls = ProgressActivity::class.java,
                operation = operation,
                forceInstall = mForceInstall,
                ventoyOptions = ventoyOptions,
            )
        )
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mOperation = intent.getStringExtra(Intents.EXTRA_OPERATION) ?: Intents.OPERATION_WRITE_IMAGE
        mForceInstall = intent.getBooleanExtra(Intents.EXTRA_FORCE_INSTALL, false)

        val openedImage = intent.safeParcelableExtra<Uri>("sourceUri") ?: if (mOperation == Intents.OPERATION_VENTOY_INSTALL) {
            VENTOY_INSTALL_URI
        } else run {
            Log.e(TAG, "No source image URI provided")
            toast(getString(R.string.no_image_uri_provided))
            finish()
            return
        }
        val selectedDevice =
            intent.safeParcelableExtra<UsbMassStorageDeviceDescriptor>("destDevice") ?: run {
                Log.e(TAG, "No destination device selected")
                toast(getString(R.string.no_destination_device_selected))
                finish()
                return
            }
        if (mViewModel.state.value.selectedDevice == null) {
            mViewModel.init(openedImage, selectedDevice, mOperation, mForceInstall)
        }

        val imageFileName = openedImage.getFileName(this) ?: "unknown"
        Telemetry.addBreadcrumb {
            category = "flow"
            message = "Image opened"
            data["image.filename"] = imageFileName
        }

        Telemetry.configureScope {
            setTag("usb.vid", formatID(selectedDevice.usbDevice.vendorId))
            setTag("usb.pid", formatID(selectedDevice.usbDevice.productId))
            setTag("usb.vidpid", selectedDevice.vidpid)
            setTag("usb.name", selectedDevice.name)
            setTag("image.filename", imageFileName)
            try {
                setTag(
                    "image.size",
                    openedImage.getFileSize(this@ConfirmOperationActivity).toString()
                )
            } catch (_: Exception) {
                setTag("image.size", "unknown")
            }
        }

        // Use an immutable PendingIntent as recommended by the latest API
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        mUsbPermissionIntent = PendingIntent.getBroadcast(
            this, 0, Intent().apply {
                action = Intents.USB_PERMISSION
            }, pendingIntentFlags
        )

        mSettings = AppSettings(this).apply {
            addListener(mViewModel)
            mViewModel.refreshSettings(this)
        }

        setContent {
            MainView(mViewModel) {
                val uiState by mViewModel.state.collectAsState()
                var showLayFlatDialog by rememberSaveable { mutableStateOf(false) }
                var showAdvancedOptions by rememberSaveable { mutableStateOf(false) }

                LaunchedEffect(uiState.hasUsbPermission, uiState.ventoyDriveState) {
                    if (
                        uiState.hasUsbPermission &&
                        uiState.ventoyDriveState == VentoyDriveState.AwaitingPermission
                    ) {
                        scanVentoyDrive(uiState.selectedDevice!!)
                    }
                }

                LaunchedEffect(uiState.selectedDevice) {
                    if (uiState.selectedDevice == null) {
                        toast(getString(R.string.usb_device_was_unplugged))
                        finish()
                    }
                }

                ConfirmationView(mViewModel, onConfirm = {
                    showLayFlatDialog = true
                }, onCancel = {
                    finish()
                }, onEditVentoyOptions = {
                    showAdvancedOptions = true
                }, onRetryVentoyScan = {
                    mViewModel.retryVentoyScan()
                }, askUsbPermission = {
                    val usbManager = getSystemService(USB_SERVICE) as UsbManager
//                    Monitoring.addBreadcrumb("Requesting USB permission", "usb")
                    Telemetry.addBreadcrumb {
                        category = "usb"
                        message = "Requesting USB permission: ${uiState.selectedDevice}"
                        data["usb.name"] = uiState.selectedDevice?.name ?: "unknown"
                        data["usb.vidpid"] = uiState.selectedDevice?.vidpid ?: "unknown"
                    }
                    usbManager.requestPermission(
                        uiState.selectedDevice!!.usbDevice, mUsbPermissionIntent
                    )
                })

                LaunchedEffect(uiState.hasUsbPermission, showLayFlatDialog) {
                    println("hasUsbPermission: ${uiState.hasUsbPermission}")
                }

                if (uiState.hasUsbPermission && showLayFlatDialog) {
                    LayFlatOnTableBottomSheet(
                        onReady = {
                            println("onReady")
                            writeImage(
                                uiState.openedImage!!, uiState.selectedDevice!!
                            )
                        },
                        onDismissRequest = { showLayFlatDialog = false },
                    )
                }

                if (showAdvancedOptions) {
                    VentoyAdvancedOptionsBottomSheet(
                        options = uiState.ventoyOptions,
                        diskSizeBytes = uiState.scannedDiskSizeBytes,
                        onApply = {
                            mViewModel.setVentoyOptions(it)
                            showAdvancedOptions = false
                        },
                        onDismissRequest = { showAdvancedOptions = false },
                    )
                }
            }
        }

    }

    private suspend fun scanVentoyDrive(device: UsbMassStorageDeviceDescriptor) {
        mViewModel.setVentoyScanning()
        withContext(Dispatchers.IO) {
            var massStorageDevice: io.github.xntso.vendroid.massstorage.VendroidUsbMassStorageDevice? = null
            try {
                val openedDevice = device.buildDevice(this@ConfirmOperationActivity)
                massStorageDevice = openedDevice
                openedDevice.init()
                val blockDevice = openedDevice.blockDevices[0]
                    ?: error("The USB drive does not expose a usable block device.")
                val rawDevice = BlockDeviceRawBlockDevice(blockDevice)
                val scanner = VentoyDiskScanner()
                val diskInfo = scanner.scan(rawDevice)
                val hasAnyPartition = scanner.hasAnyMbrPartition(rawDevice)
                val bundledVersion = VentoyPayload.fromAssets(assets).version
                mViewModel.setVentoyScanResult(
                    diskInfo = diskInfo,
                    hasAnyPartition = hasAnyPartition,
                    diskSizeBytes = rawDevice.sizeBytes,
                    bundledVersion = bundledVersion,
                )
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to inspect Ventoy drive", exception)
                mViewModel.setVentoyScanError(exception.message)
            } finally {
                runCatching { massStorageDevice?.close() }
            }
        }
    }
}

@Composable
fun ConfirmationViewLayout(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    sourceCard: @Composable (modifier: Modifier, fillMaxSize: Boolean) -> Unit,
    destinationCard: @Composable (modifier: Modifier) -> Unit,
    warningCard: @Composable () -> Unit,
    cancelButton: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    content: @Composable () -> Unit = {},
) {
    ScreenSizeLayoutSelector(
        modifier = modifier,
        normal = {
            Column(
                modifier = Modifier
                    .wrapContentSize(Alignment.TopStart)
                    .widthIn(max = CONTENT_WIDTH)
                    .align(Alignment.Center)
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.SpaceBetween
            ) {

                Box(
                    Modifier
                        .padding(24.dp)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    title()
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    sourceCard(Modifier.fillMaxWidth(), false)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            modifier = Modifier.size(48.dp),
                            imageVector = Icons.TwoTone.ArrowDownward,
                            contentDescription = null
                        )
                    }

                    destinationCard(Modifier.fillMaxWidth())
                }

                Column {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        warningCard()
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)
                    ) {
                        cancelButton()
                        confirmButton()
                    }
                }
            }
        },
        compact = {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .safeDrawingPadding()
            ) {
                Box(
                    Modifier
                        .padding(8.dp)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    title()
                }

                ConstraintLayout(Modifier.fillMaxWidth()) {
                    val (sourceCardRef, arrowRef, destinationCardRef) = createRefs()
                    val chain =
                        if (LocalLayoutDirection.current == LayoutDirection.Ltr) createHorizontalChain(
                            sourceCardRef, arrowRef, destinationCardRef,
                            chainStyle = ChainStyle.Packed
                        ) else createHorizontalChain(
                            destinationCardRef, arrowRef, sourceCardRef,
                            chainStyle = ChainStyle.Packed
                        )
                    constrain(chain) {
                        start.linkTo(parent.start, 16.dp)
                        end.linkTo(parent.end, 16.dp)
                    }

                    sourceCard(
                        Modifier
                            .constrainAs(sourceCardRef) {
                                top.linkTo(parent.top, 16.dp)
                                bottom.linkTo(parent.bottom, 16.dp)
                                height = Dimension.fillToConstraints.atLeastWrapContent
                                width = Dimension.fillToConstraints
                                horizontalChainWeight = 1f
                            },
                        true
                    )

                    Icon(
                        modifier = Modifier
                            .size(48.dp)
                            .constrainAs(arrowRef) {
                                centerVerticallyTo(parent)
                                width = Dimension.value(48.dp)
                                horizontalChainWeight = 0f
                            },
                        imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = null
                    )

                    destinationCard(
                        Modifier
                            .constrainAs(destinationCardRef) {
                                top.linkTo(parent.top, 16.dp)
                                bottom.linkTo(parent.bottom, 16.dp)
                                height = Dimension.wrapContent
                                width = Dimension.fillToConstraints
                                horizontalChainWeight = 1f
                            }
                    )
                }

                warningCard()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)
                ) {
                    cancelButton()
                    confirmButton()
                }
            }
        }
    )

    content()
}

@Composable
fun ConfirmationView(
    viewModel: ConfirmOperationActivityViewModel,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    askUsbPermission: () -> Unit,
    onEditVentoyOptions: () -> Unit = {},
    onRetryVentoyScan: () -> Unit = {},
) {
    val uiState by viewModel.state.collectAsState()

    ConfirmationViewLayout(
        modifier = Modifier.fillMaxSize(),
        title = {
            Text(
                text = stringResource(R.string.confirm_operation),
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 28.sp),
            )
        },
        sourceCard = { modifier, fillMaxSize ->
            Card(modifier) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .then(if (fillMaxSize) Modifier.fillMaxSize() else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    Icon(
                        modifier = Modifier
                            .size(128.dp)
                            .padding(16.dp, 16.dp, 0.dp, 16.dp),
                        imageVector = ImageVector.vectorResource(
                            id = R.drawable.ic_disk_image_large
                        ), contentDescription = stringResource(R.string.disk_image)
                    )
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(
                            8.dp,
                            Alignment.CenterVertically
                        )
                    ) {
                        val sourceUri = uiState.openedImage
                        val context = LocalContext.current
                        val unknownFileName = stringResource(R.string.unknown_filename)
                        val sourceFileName by remember(sourceUri, context, unknownFileName) {
                            derivedStateOf {
                                sourceUri?.getFileName(context)
                                    ?: unknownFileName
                            }
                        }
                        val sourceFileSize by remember {
                            derivedStateOf {
                                try {
                                    sourceUri!!.getFileSize(context).toHRSize()
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to get file size", e)
                                    "Unknown file size"
                                }
                            }
                        }

                        val isVentoyOperation = Intents.isVentoyOperation(uiState.operation)
                        Text(
                            text = stringResource(
                                if (isVentoyOperation) R.string.ventoy_installer_name
                                else R.string.image_to_write,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(0.dp, 0.dp, 0.dp, 8.dp)
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (isVentoyOperation) {
                                VentoyDriveStatus(uiState, onRetryVentoyScan)
                                if (uiState.operation == Intents.OPERATION_VENTOY_INSTALL &&
                                    uiState.ventoyDriveState == VentoyDriveState.ReadyToInstall
                                ) {
                                    Text(
                                        text = ventoyOptionsSummary(uiState.ventoyOptions),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    TextButton(
                                        modifier = Modifier.appiumTag("ventoyAdvancedOptionsButton"),
                                        onClick = onEditVentoyOptions,
                                    ) {
                                        Text(stringResource(R.string.advanced_options))
                                    }
                                }
                            } else {
                                Text(
                                    text = sourceFileName,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 2,
                                    softWrap = true,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = sourceFileSize,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontStyle = FontStyle.Italic,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        destinationCard = { modifier ->
            Card(modifier) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    Icon(
                        modifier = Modifier
                            .size(128.dp)
                            .padding(16.dp, 16.dp, 0.dp, 16.dp),
                        imageVector = ImageVector.vectorResource(
                            id = R.drawable.ic_usb_stick_large
                        ), contentDescription = stringResource(R.string.usb_drive)
                    )
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(
                            8.dp,
                            Alignment.CenterVertically
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.destination_usb_device),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Column {
                            Text(
                                text = uiState.selectedDevice?.name ?: stringResource(
                                    R.string.unknown_device
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                softWrap = true,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = uiState.selectedDevice?.vidpid ?: "Unknown VID:PID",
                                style = MaterialTheme.typography.labelMedium,
                                softWrap = true,
                                fontStyle = FontStyle.Italic, maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Button(
                            modifier = Modifier.appiumTag("grantUsbPermissionButton"),
                            onClick = askUsbPermission,
                            enabled = !uiState.hasUsbPermission,
                            contentPadding = if (!uiState.hasUsbPermission) PaddingValues(
                                24.dp, 8.dp
                            )
                            else PaddingValues(24.dp, 8.dp, 16.dp, 8.dp),
                        ) {
                            Text(text = stringResource(R.string.grant_access))
                            if (uiState.hasUsbPermission) {
                                Icon(
                                    modifier = Modifier.padding(8.dp, 0.dp, 0.dp, 0.dp),
                                    imageVector = Icons.TwoTone.Check,
                                    contentDescription = stringResource(R.string.permission_granted)
                                )
                            }
                        }
                    }
                }
            }
        },
        warningCard = {
            val isUpdate = uiState.operation == Intents.OPERATION_VENTOY_UPDATE
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isUpdate) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    contentColor = if (isUpdate) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                )
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp, 16.dp, 16.dp, 16.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.Start),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        modifier = Modifier.size(48.dp),
                        imageVector = Icons.TwoTone.Warning,
                        contentDescription = null
                    )
                    Column {
                        Text(
                            text = stringResource(
                                if (isUpdate) R.string.files_will_be_preserved
                                else R.string.be_careful,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isUpdate) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                        )
                        Text(
                            text = stringResource(
                                if (isUpdate) R.string.updating_ventoy_preserves_files
                                else R.string.writing_the_image_will_erase
                            ), style = MaterialTheme.typography.labelMedium,
                            color = if (isUpdate) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                        )
                    }
                }
            }
        },
        cancelButton = {
            OutlinedButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            val ventoyReady = when (uiState.ventoyDriveState) {
                VentoyDriveState.ReadyToInstall,
                VentoyDriveState.UpdateAvailable,
                VentoyDriveState.ReadyToRepair,
                VentoyDriveState.NotApplicable -> true
                else -> false
            }
            Button(
                modifier = Modifier.appiumTag("writeImageButton"),
                onClick = onConfirm,
                enabled = uiState.selectedDevice != null && uiState.hasUsbPermission && ventoyReady,
            ) {
                Text(
                    text = stringResource(
                        when (uiState.ventoyDriveState) {
                            VentoyDriveState.UpdateAvailable -> R.string.update_ventoy
                            VentoyDriveState.ReadyToRepair -> R.string.repair_ventoy
                            else -> R.string.write_image
                        },
                    ),
                )
            }
        }
    )
}

@Composable
private fun VentoyDriveStatus(
    state: ConfirmOperationActivityState,
    onRetry: () -> Unit,
) {
    val installed = state.installedVentoyVersion ?: stringResource(R.string.unknown_version)
    val bundled = state.bundledVentoyVersion ?: stringResource(R.string.unknown_version)
    when (state.ventoyDriveState) {
        VentoyDriveState.AwaitingPermission -> Text(
            stringResource(R.string.grant_access_to_check_ventoy),
            style = MaterialTheme.typography.labelMedium,
        )
        VentoyDriveState.Scanning -> Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.checking_ventoy_installation))
        }
        VentoyDriveState.ReadyToInstall -> Text(
            stringResource(R.string.ready_to_install_ventoy_version, bundled),
            style = MaterialTheme.typography.labelLarge,
        )
        VentoyDriveState.UpdateAvailable -> {
            Text(
                stringResource(R.string.ventoy_update_available, installed, bundled),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                stringResource(R.string.files_will_be_preserved),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        VentoyDriveState.ReadyToRepair -> Text(
            stringResource(R.string.ventoy_ready_to_repair, installed),
            style = MaterialTheme.typography.labelLarge,
        )
        VentoyDriveState.NewerVersion -> Text(
            stringResource(R.string.ventoy_newer_than_bundled, installed, bundled),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
        VentoyDriveState.ExistingPartitions -> Text(
            stringResource(R.string.existing_partitions_use_force_install),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
        VentoyDriveState.ScanFailed -> Column {
            Text(
                state.scanError ?: stringResource(R.string.could_not_check_ventoy),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.try_again))
            }
        }
        VentoyDriveState.NotApplicable -> Unit
    }
}

@Composable
private fun ventoyOptionsSummary(options: VentoyJobOptions): String {
    val cluster = when (options.clusterSize) {
        VentoyClusterSize.Automatic -> stringResource(R.string.automatic)
        VentoyClusterSize.KiB32 -> "32 KiB"
        VentoyClusterSize.KiB64 -> "64 KiB"
        VentoyClusterSize.KiB128 -> "128 KiB"
        VentoyClusterSize.KiB256 -> "256 KiB"
    }
    val gibibyte = 1024L * 1024L * 1024L
    val mebibyte = 1024L * 1024L
    val reserved = if (
        options.reservedSpaceBytes >= gibibyte && options.reservedSpaceBytes % gibibyte == 0L
    ) {
        "${options.reservedSpaceBytes / gibibyte} GiB"
    } else {
        "${options.reservedSpaceBytes / mebibyte} MiB"
    }
    return stringResource(
        R.string.ventoy_options_summary,
        options.label,
        cluster,
        reserved,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VentoyAdvancedOptionsBottomSheet(
    options: VentoyJobOptions,
    diskSizeBytes: Long,
    onApply: (VentoyJobOptions) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val mebibyte = 1024L * 1024L
    val gibibyte = 1024L * mebibyte
    val initialUnit = if (
        options.reservedSpaceBytes >= gibibyte && options.reservedSpaceBytes % gibibyte == 0L
    ) {
        ReservedSpaceUnit.GiB
    } else {
        ReservedSpaceUnit.MiB
    }
    var label by rememberSaveable(options) { mutableStateOf(options.label) }
    var reserveEnabled by rememberSaveable(options) {
        mutableStateOf(options.reservedSpaceBytes > 0)
    }
    var reservedUnit by rememberSaveable(options) { mutableStateOf(initialUnit) }
    var reservedAmount by rememberSaveable(options) {
        mutableStateOf((options.reservedSpaceBytes / initialUnit.bytes).toString())
    }
    var clusterSize by rememberSaveable(options) { mutableStateOf(options.clusterSize) }
    var clusterMenuOpen by remember { mutableStateOf(false) }
    var unitMenuOpen by remember { mutableStateOf(false) }
    val parsedReservedAmount = reservedAmount.toLongOrNull()
    val maximumReservedAmount = if (diskSizeBytes > 40L * mebibyte) {
        (diskSizeBytes - 40L * mebibyte) / reservedUnit.bytes
    } else {
        0
    }
    val labelError = label.length !in 1..11
    val reservedError = reserveEnabled && (
        parsedReservedAmount == null || parsedReservedAmount <= 0 ||
            parsedReservedAmount > Long.MAX_VALUE / reservedUnit.bytes ||
            (diskSizeBytes > 0 && parsedReservedAmount > maximumReservedAmount)
        )

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.advanced_options),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth().appiumTag("ventoyVolumeLabelField"),
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.volume_label)) },
                supportingText = { Text(stringResource(R.string.exfat_label_limit)) },
                isError = labelError,
                singleLine = true,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.cluster_size), fontWeight = FontWeight.Bold)
                Box {
                    OutlinedButton(
                        modifier = Modifier.appiumTag("ventoyClusterSizeButton"),
                        onClick = { clusterMenuOpen = true },
                    ) {
                        Text(
                            when (clusterSize) {
                                VentoyClusterSize.Automatic -> stringResource(R.string.automatic)
                                VentoyClusterSize.KiB32 -> "32 KiB"
                                VentoyClusterSize.KiB64 -> "64 KiB"
                                VentoyClusterSize.KiB128 -> "128 KiB"
                                VentoyClusterSize.KiB256 -> "256 KiB"
                            },
                        )
                    }
                    DropdownMenu(
                        expanded = clusterMenuOpen,
                        onDismissRequest = { clusterMenuOpen = false },
                    ) {
                        VentoyClusterSize.entries.forEach { size ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (size) {
                                            VentoyClusterSize.Automatic -> stringResource(R.string.automatic)
                                            VentoyClusterSize.KiB32 -> "32 KiB"
                                            VentoyClusterSize.KiB64 -> "64 KiB"
                                            VentoyClusterSize.KiB128 -> "128 KiB"
                                            VentoyClusterSize.KiB256 -> "256 KiB"
                                        },
                                    )
                                },
                                onClick = {
                                    clusterSize = size
                                    clusterMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.reserve_space), fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.reserve_space_description),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Switch(
                    modifier = Modifier.appiumTag("ventoyReserveSpaceSwitch"),
                    checked = reserveEnabled,
                    onCheckedChange = { reserveEnabled = it },
                )
            }
            if (reserveEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    OutlinedTextField(
                        modifier = Modifier.weight(1f).appiumTag("ventoyReservedSpaceField"),
                        value = reservedAmount,
                        onValueChange = { value -> reservedAmount = value.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.reserved_space)) },
                        supportingText = {
                            if (diskSizeBytes > 0) {
                                Text(
                                    stringResource(
                                        R.string.maximum_reserved_space,
                                        maximumReservedAmount,
                                        reservedUnit.label,
                                    ),
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = reservedError,
                        singleLine = true,
                    )
                    Box {
                        OutlinedButton(
                            modifier = Modifier.appiumTag("ventoyReservedSpaceUnitButton"),
                            onClick = { unitMenuOpen = true },
                        ) {
                            Text(reservedUnit.label)
                        }
                        DropdownMenu(
                            expanded = unitMenuOpen,
                            onDismissRequest = { unitMenuOpen = false },
                        ) {
                            ReservedSpaceUnit.entries.forEach { unit ->
                                DropdownMenuItem(
                                    text = { Text(unit.label) },
                                    onClick = {
                                        val safeAmount = parsedReservedAmount?.takeIf {
                                            it <= Long.MAX_VALUE / reservedUnit.bytes
                                        } ?: 0L
                                        val bytes = safeAmount * reservedUnit.bytes
                                        reservedUnit = unit
                                        reservedAmount = (bytes / unit.bytes).coerceAtLeast(1).toString()
                                        unitMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.partitions_aligned_4k),
                style = MaterialTheme.typography.labelMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onDismissRequest) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    modifier = Modifier.appiumTag("ventoyApplyOptionsButton"),
                    enabled = !labelError && !reservedError,
                    onClick = {
                        onApply(
                            options.copy(
                                label = label,
                                reservedSpaceBytes = if (reserveEnabled) {
                                    parsedReservedAmount!! * reservedUnit.bytes
                                } else {
                                    0
                                },
                                clusterSize = clusterSize,
                            ),
                        )
                    },
                ) {
                    Text(stringResource(R.string.apply))
                }
            }
        }
    }
}

private enum class ReservedSpaceUnit(
    val bytes: Long,
    val label: String,
) {
    MiB(1024L * 1024L, "MiB"),
    GiB(1024L * 1024L * 1024L, "GiB"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayFlatOnTableBottomSheet(
    onReady: () -> Unit,
    onDismissRequest: () -> Unit,
    useGravitySensor: Boolean = true,
) {
    val context = LocalContext.current
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.animated_check))
    val sensorManager =
        remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val sensor = remember(sensorManager) { sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
        if (sensor == null || !useGravitySensor) {
            Text(
                text = stringResource(R.string.lay_your_device_flat_no_accel),
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp, 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                var hideSheet by remember { mutableStateOf(false) }
                LaunchedEffect(hideSheet) {
                    if (hideSheet) {
                        sheetState.hide()
                        onReady()
                    }
                }
                Button(onClick = { hideSheet = true }) {
                    Text(
                        modifier = Modifier.appiumTag("layFlatSkipButton"),
                        text = stringResource(R.string.continue_)
                    )
                }
            }
        } else {
            val readings = 10
            var hasBeenFlatOnTable by rememberSaveable { mutableStateOf(false) }
            var gravityCircularBuffer by remember { mutableStateOf(emptyList<Float>()) }

            val sensorListener = remember {
                object : SensorEventListener {
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

                    override fun onSensorChanged(event: SensorEvent) {
                        gravityCircularBuffer = gravityCircularBuffer + event.values[2]
                        if (gravityCircularBuffer.size > readings) {
                            gravityCircularBuffer = gravityCircularBuffer.drop(1)
                        }
                    }
                }
            }
            val movingAverage = remember(gravityCircularBuffer) {
                if (gravityCircularBuffer.size >= readings) gravityCircularBuffer.average()
                    .toFloat()
                else 0f
            }

            val isFlatOnTable by remember(movingAverage) {
                derivedStateOf {
                    movingAverage > 9.7f
                }
            }
            LaunchedEffect(isFlatOnTable) {
                if (isFlatOnTable) {
                    hasBeenFlatOnTable = true
                }
            }

            DisposableEffect(sensor, sensorListener, sensorManager) {
                println("Registering sensor listener")
                sensorManager.registerListener(
                    sensorListener, sensor, SensorManager.SENSOR_DELAY_NORMAL
                )
                onDispose {
                    println("Unregistering sensor listener")
                    sensorManager.unregisterListener(sensorListener)
                }
            }

            Text(
                text = stringResource(R.string.lay_your_device_flat),
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                if (!hasBeenFlatOnTable) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(128.dp)
                            .padding(15.dp),
                        strokeWidth = 5.dp,
                    )
                } else {
                    val progress by animateLottieCompositionAsState(
                        composition, isPlaying = hasBeenFlatOnTable
                    )
                    val lottieDynamicProperties = rememberLottieDynamicProperties(
                        rememberLottieDynamicProperty(
                            property = LottieProperty.COLOR_FILTER, value = PorterDuffColorFilter(
                                MaterialTheme.colorScheme.primary.toArgb(), PorterDuff.Mode.SRC_ATOP
                            ), keyPath = arrayOf("**")
                        )
                    )
                    LottieAnimation(
                        composition, progress = { progress }, modifier = Modifier.size(128.dp),
                        dynamicProperties = lottieDynamicProperties
                    )
                    LaunchedEffect(progress) {
                        if (progress == 1f) {
                            sheetState.hide()
                            onReady()
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp, 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                var hideSheet by remember { mutableStateOf(false) }
                LaunchedEffect(hideSheet) {
                    if (hideSheet) {
                        sheetState.hide()
                        onReady()
                    }
                }
                OutlinedButton(onClick = { hideSheet = true }) {
                    Text(
                        modifier = Modifier.appiumTag("layFlatSkipButton"),
                        text = stringResource(R.string.skip)
                    )
                }
            }
        }
    }
}

@PreviewScreenSizes
@Composable
fun ConfirmationViewPreview() {
    val viewModel = remember {
        ConfirmOperationActivityViewModel().apply {
            setState(
                state.value.copy(
                    selectedDevice = UsbMassStorageDeviceDescriptor(
                        previewUsbDevice = PreviewUsbDevice(
                            name = "Kingston DataTraveler 3.0",
                            vidpid = "dead:beef",
                        )
                    ),
                    openedImage = "file:///storage/emulated/0/Download/vendroid-test-image-very-long-name-lol-rip-ive-never-seen-a-filename-this-long-its-absolutely-crazy.img".toUri(),
                )
            )
        }
    }
    var showLayFlatSheet by rememberSaveable { mutableStateOf(false) }

    MainView(viewModel) {
        ConfirmationView(viewModel, onCancel = {
            viewModel.setState(
                viewModel.state.value.copy(
                    hasUsbPermission = false
                )
            )
        }, onConfirm = {
            showLayFlatSheet = true
        }, askUsbPermission = {
            viewModel.setState(
                viewModel.state.value.copy(
                    hasUsbPermission = true
                )
            )
        })

        if (showLayFlatSheet) {
            LayFlatOnTableBottomSheet(
                onReady = { showLayFlatSheet = false },
                onDismissRequest = { showLayFlatSheet = false }, useGravitySensor = true
            )
        }
    }
}
