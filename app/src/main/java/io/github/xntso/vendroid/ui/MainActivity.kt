package io.github.xntso.vendroid.ui

import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import io.github.xntso.vendroid.AppSettings
import io.github.xntso.vendroid.BuildConfig
import io.github.xntso.vendroid.R
import io.github.xntso.vendroid.ThemeMode
import io.github.xntso.vendroid.VENTOY_INSTALL_URI
import io.github.xntso.vendroid.getConfirmOperationActivityIntent
import io.github.xntso.vendroid.getConfirmVentoyInstallActivityIntent
import io.github.xntso.vendroid.massstorage.IUsbMassStorageDeviceDescriptor
import io.github.xntso.vendroid.massstorage.UsbMassStorageDeviceDescriptor
import io.github.xntso.vendroid.plugins.telemetry.Telemetry
import io.github.xntso.vendroid.plugins.telemetry.TelemetryLevel
import io.github.xntso.vendroid.ui.composables.MainView
import io.github.xntso.vendroid.ui.composables.ScreenSizeLayoutSelector
import io.github.xntso.vendroid.ui.composables.appiumTag
import io.github.xntso.vendroid.utils.broadcastReceiver
import io.github.xntso.vendroid.utils.ktexts.getFileName
import io.github.xntso.vendroid.utils.ktexts.registerExportedReceiver
import io.github.xntso.vendroid.utils.ktexts.usbDevice

private const val TAG = "MainActivity"


class MainActivity : ActivityBase() {
    private val mViewModel: MainActivityViewModel by viewModels()
    private lateinit var mSettings: AppSettings

    private val mFilePickerActivity: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { result ->
            Telemetry.addBreadcrumb {
                message = "User selected file: $result"
                level = TelemetryLevel.DEBUG
                category = "flow"
                data["image.filename"] = result?.getFileName(this@MainActivity) ?: "null"
            }
            openUri(result ?: return@registerForActivityResult)
        }

    private fun ActivityResultLauncher<Array<String>>.launch() = launch(arrayOf("*/*"))

    private val mUsbDevicesReceiver = broadcastReceiver { intent ->

        if (intent.usbDevice == null) {
            Log.w(TAG, "Received USB broadcast without device, ignoring: $intent")
            return@broadcastReceiver
        }

        Log.d(
            TAG,
            "Received USB broadcast: $intent, action: ${intent.action}, device: ${intent.usbDevice}"
        )

        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                mViewModel.usbDeviceAttached(intent.usbDevice!!)
            }

            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                mViewModel.usbDeviceDetached(intent.usbDevice!!)
            }

            else -> {
                Log.w(TAG, "Received unknown broadcast: ${intent.action}")
            }
        }
    }

    private fun registerUsbReceiver() {
        val usbAttachedFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        val usbDetachedFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)

        registerExportedReceiver(mUsbDevicesReceiver, usbAttachedFilter)
        registerExportedReceiver(mUsbDevicesReceiver, usbDetachedFilter)

        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        mViewModel.replaceUsbDevices(usbManager.deviceList.values)
    }

    private fun unregisterUsbReceiver() {
        unregisterReceiver(mUsbDevicesReceiver)
    }

    private fun launchConfirmationActivity(
        selectedDevice: UsbMassStorageDeviceDescriptor,
        openedImage: Uri,
        forceInstall: Boolean = false,
    ) {
        startActivity(
            if (openedImage == VENTOY_INSTALL_URI) {
                getConfirmVentoyInstallActivityIntent(
                    selectedDevice,
                    forceInstall,
                    this,
                    ConfirmOperationActivity::class.java,
                )
            } else {
                getConfirmOperationActivityIntent(
                    openedImage,
                    selectedDevice,
                    this,
                    ConfirmOperationActivity::class.java
                )
            }
        )
    }

    private fun openUri(uri: Uri, userSaysYolo: Boolean = false) {
        val filename = uri.getFileName(this)
        Telemetry.addBreadcrumb {
            message = "Opening image: $filename"
            category = "flow"
            level = TelemetryLevel.DEBUG
            data["image.filename"] = filename ?: "null"
            data["bypass_windows_alert"] = userSaysYolo.toString()
        }

        if (!userSaysYolo) {
            try {
                // Check if it's a Windows image and alert the user; fail-open
                filename?.let {
                    val lowercase = it.lowercase()
                    if ("windows" in lowercase || lowercase.startsWith("win")) {
                        Telemetry.addBreadcrumb {
                            message = "Windows image detected"
                            category = "flow"
                            level = TelemetryLevel.DEBUG
                            data["image.filename"] = it
                        }

                        mViewModel.setShowWindowsAlertUri(uri)
                        return
                    }
                }
            } catch (e: Exception) {
                Log.w("Failed to get filename", e)
            }
        }
        mViewModel.setOpenedImage(uri)

        if (mViewModel.state.value.massStorageDevices.size == 1) {
            Telemetry.addBreadcrumb {
                message = "Only one USB device, opening confirmation activity"
                category = "flow"
            }

            launchConfirmationActivity(
                mViewModel.state.value.massStorageDevices.first(),
                uri
            )
        }
    }


    @OptIn(ExperimentalAnimationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mSettings = AppSettings(this).apply {
            addListener(mViewModel)
            mViewModel.refreshSettings(this)
        }

        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let { openUri(it) }
        }

        setContent {
            val snackbarHostState = remember { SnackbarHostState() }
            var forceInstallRequested by rememberSaveable { mutableStateOf(false) }


            MainView(mViewModel, snackbarHost = { SnackbarHost(snackbarHostState) }) {
                StartView(
                    mViewModel,
                    setThemeMode = { mSettings.themeMode = it },
                    setDynamicTheme = { mSettings.dynamicColors = it },
                    onCTAClick = {
                        Telemetry.addBreadcrumb {
                            message = "User clicked on Install Ventoy"
                            category = "flow"
                            level = TelemetryLevel.DEBUG
                        }
                        forceInstallRequested = false
                        mViewModel.setOpenedImage(VENTOY_INSTALL_URI)
                    },
                    onForceInstallClick = {
                        Telemetry.addBreadcrumb {
                            message = "User clicked on Force Install Ventoy"
                            category = "flow"
                            level = TelemetryLevel.WARNING
                        }
                        forceInstallRequested = true
                        mViewModel.setOpenedImage(VENTOY_INSTALL_URI)
                    },
                    openAboutView = {
                        startActivity(Intent(this, AboutActivity::class.java))
                    }
                )
                val uiState by mViewModel.state.collectAsState()
                if (uiState.showWindowsAlertForUri != null) {
                    WindowsImageAlertDialog(
                        onDismissRequest = { mViewModel.setShowWindowsAlertUri(null) },
                        onConfirm = { openUri(uiState.showWindowsAlertForUri!!, true) })
                }
                if (uiState.openedImage != null) {
                    UsbDevicePickerBottomSheet(
                        onDismissRequest = {
                            mViewModel.setOpenedImage(null)
                        },
                        selectDevice = {
                            Telemetry.addBreadcrumb {
                                message =
                                    "User selected USB device, opening confirmation activity: $it"
                                category = "flow"
                                level = TelemetryLevel.INFO
                                data["usb.device"] = it.toString()
                                data["usb.name"] = it.name
                                data["usb.vidpid"] = it.vidpid
                            }

                            launchConfirmationActivity(
                                it as UsbMassStorageDeviceDescriptor,
                                mViewModel.state.value.openedImage!!,
                                forceInstallRequested,
                            )
                        },
                        availableDevices = { uiState.massStorageDevices }
                    )
                }

            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerUsbReceiver()
    }

    override fun onStop() {
        super.onStop()
        unregisterUsbReceiver()
    }
}

@Composable
fun StartViewLayout(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    logo: @Composable () -> Unit,
    mainButton: @Composable () -> Unit,
    bottomButton: @Composable () -> Unit,
    menuButton: @Composable () -> Unit,
    dropdownMenu: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    ScreenSizeLayoutSelector(
        modifier = modifier,
        normal = {
            ConstraintLayout(
                modifier = Modifier
                    .wrapContentSize(Alignment.TopStart)
                    .widthIn(max = CONTENT_WIDTH)
                    .align(Alignment.Center)
                    .fillMaxSize()
            ) {
                val (titleRef, centerBoxRef, bottomButtonRef, menuButtonRef) = createRefs()
                Box(
                    modifier = Modifier.constrainAs(titleRef) {
                        top.linkTo(parent.top, 24.dp)
                        start.linkTo(parent.start, 16.dp)
                        end.linkTo(parent.end, 16.dp)
                    }
                ) {
                    title()
                }

                Column(
                    modifier = Modifier.constrainAs(centerBoxRef) {
                        top.linkTo(parent.top, 16.dp)
                        bottom.linkTo(parent.bottom, 16.dp)
                        start.linkTo(parent.start, 16.dp)
                        end.linkTo(parent.end, 16.dp)
                    }, horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(64.dp)
                ) {
                    logo()
                    mainButton()
                }

                Box(modifier = Modifier.constrainAs(bottomButtonRef) {
                    bottom.linkTo(parent.bottom, 16.dp)
                    start.linkTo(parent.start, 16.dp)
                    end.linkTo(parent.end, 16.dp)
                }) {
                    bottomButton()
                }

                Box(modifier = Modifier.constrainAs(menuButtonRef) {
                    bottom.linkTo(parent.bottom, 16.dp)
                    end.linkTo(parent.end, 16.dp)
                }) {
                    menuButton()
                    dropdownMenu()
                }
            }
        },
        compact = {
            ConstraintLayout(
                Modifier
                    .sizeIn(minWidth = 550.dp)
                    .fillMaxSize()
                    .horizontalScroll(rememberScrollState())
            ) {
                val (mainBox, bottomButtonRef) = createRefs()

                Row(
                    modifier = Modifier.constrainAs(mainBox) {
                        centerTo(parent)
                    },
                    horizontalArrangement = Arrangement.spacedBy(
                        80.dp,
                        Alignment.CenterHorizontally
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.padding(32.dp)) {
                        logo()
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        title()
                        mainButton()
                        bottomButton()
                    }
                }

                Box(
                    modifier = Modifier.constrainAs(bottomButtonRef) {
                        bottom.linkTo(parent.bottom, 16.dp)
                        start.linkTo(mainBox.end, 16.dp)
                    }
                ) {
                    menuButton()
                    dropdownMenu()
                }
            }
        }
    )

    content()
}

@Composable
fun StartView(
    viewModel: MainActivityViewModel,
    setThemeMode: (ThemeMode) -> Unit = {},
    setDynamicTheme: (Boolean) -> Unit = {},
    onCTAClick: () -> Unit = {},
    onForceInstallClick: () -> Unit = {},
    openAboutView: () -> Unit = {},
) {
    val uiState by viewModel.state.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }

    val menuScrollState = rememberScrollState()

    StartViewLayout(
        modifier = Modifier
            .safeDrawingPadding()
            .fillMaxSize(),
        title = {
            Text(
                text = "${stringResource(R.string.app_name)} v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 28.sp),
            )
        },
        logo = {
            VendroidLogo(modifier = Modifier.size(128.dp))
        },
        mainButton = {
            val label = stringResource(R.string.install_ventoy)
            ExtendedFloatingActionButton(
                onClick = onCTAClick,
                modifier = Modifier.appiumTag("installVentoyCTA"),
                text = { Text(label) },
                icon = {
                    Icon(
                        imageVector = ImageVector.vectorResource(
                            id = R.drawable.ic_write_to_usb
                        ),
                        // button isn't focusable without this for some reason
                        contentDescription = label
                    )
                },
            )
        },
        bottomButton = {
            OutlinedButton(onClick = onForceInstallClick) {
                Text(stringResource(R.string.force_install_ventoy))
            }
        },
        menuButton = {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    modifier = Modifier.size(20.dp),
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Menu"
                )
            }
        },
        dropdownMenu = {
            DropdownMenu(
                expanded = menuOpen,
                scrollState = menuScrollState,
                onDismissRequest = { menuOpen = false },
            ) {
                Text(
                    stringResource(R.string.style),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(12.dp, 0.dp)
                )

                // Radio buttons for theme modes SYSTEM, LIGHT, DARK
                DropdownMenuItem(
                    onClick = { setThemeMode(ThemeMode.SYSTEM) },
                    text = { Text(stringResource(R.string.device_setting)) },
                    leadingIcon = {
                        RadioButton(
                            modifier = Modifier.size(20.dp),
                            selected = uiState.themeMode == ThemeMode.SYSTEM,
                            onClick = { setThemeMode(ThemeMode.SYSTEM) })
                    })
                DropdownMenuItem(
                    onClick = { setThemeMode(ThemeMode.LIGHT) },
                    text = { Text(stringResource(R.string.light)) }, leadingIcon = {
                        RadioButton(
                            modifier = Modifier.size(20.dp),
                            selected = uiState.themeMode == ThemeMode.LIGHT,
                            onClick = { setThemeMode(ThemeMode.LIGHT) })
                    })
                DropdownMenuItem(
                    onClick = { setThemeMode(ThemeMode.DARK) },
                    text = { Text(stringResource(R.string.dark)) }, leadingIcon = {
                        RadioButton(
                            modifier = Modifier.size(20.dp),
                            selected = uiState.themeMode == ThemeMode.DARK,
                            onClick = { setThemeMode(ThemeMode.DARK) })
                    })

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    DropdownMenuItem(
                        onClick = { setDynamicTheme(!uiState.dynamicColors) },
                        text = { Text(stringResource(R.string.dynamic_colors)) },
                        leadingIcon = {
                            Checkbox(
                                modifier = Modifier.size(20.dp),
                                checked = uiState.dynamicColors,
                                onCheckedChange = { setDynamicTheme(!uiState.dynamicColors) })
                        })
                }

                HorizontalDivider()

                DropdownMenuItem(
                    onClick = { openAboutView() },
                    text = { Text(stringResource(R.string.about)) }, leadingIcon = {
                        Icon(
                            imageVector = Icons.TwoTone.Info,
                            contentDescription = stringResource(R.string.about)
                        )
                    })
            }
        },
    ) {}
}

@Composable
fun WindowsImageAlertDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit = {},
) = AlertDialog(
    modifier = Modifier.appiumTag("windows_image_alert"),
    onDismissRequest = onDismissRequest, title = {
        Text(
            modifier = Modifier.appiumTag("windows_image_alert_title"),
            text = stringResource(R.string.is_this_a_windows_iso),
            textAlign = TextAlign.Center
        )
    }, text = {
        Text(
            text = stringResource(R.string.a_regular_windows_iso_won_t_work)
        )
    }, icon = {
        Icon(
            imageVector = ImageVector.vectorResource(id = R.drawable.ic_windows),
            contentDescription = "Windows logo"
        )
    }, confirmButton = {
        TextButton(
            modifier = Modifier.appiumTag("confirmWindowsAlert"),
            onClick = {
                onConfirm()
                onDismissRequest()
            }
        ) {
            Text(stringResource(R.string.continue_anyway))
        }
    }, dismissButton = {
        TextButton(
            modifier = Modifier.appiumTag("cancelWindowsAlert"),
            onClick = {
                onCancel()
                onDismissRequest()
            }) {
            Text(stringResource(R.string.cancel))
        }
    })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbDevicePickerBottomSheet(
    onDismissRequest: () -> Unit,
    selectDevice: (IUsbMassStorageDeviceDescriptor) -> Unit,
    availableDevices: () -> Set<IUsbMassStorageDeviceDescriptor>,
    skipHalfExpanded: Boolean = true,
) {
    val bottomSheetState =
        rememberModalBottomSheetState(skipPartiallyExpanded = skipHalfExpanded)
    val anyDeviceAvailable by remember(availableDevices) {
        derivedStateOf { availableDevices().isNotEmpty() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = bottomSheetState,
    ) {
        Text(
            text = if (anyDeviceAvailable) stringResource(
                R.string.select_a_usb_drive
            ) else stringResource(
                R.string.connect_a_usb_drive
            ), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(32.dp, 0.dp, 32.dp, 16.dp)
                .fillMaxWidth()
        )
        if (anyDeviceAvailable) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth()
            ) {
                items(availableDevices().toList()) { device ->
                    ListItem(
                        modifier = Modifier
                            .appiumTag("usbDeviceListItem")
                            .clickable { selectDevice(device) },
                        headlineContent = { Text(device.name) },
                        supportingContent = { Text(device.vidpid, fontStyle = FontStyle.Italic) },
                        leadingContent = {
                            Icon(
                                imageVector = ImageVector.vectorResource(
                                    id = R.drawable.ic_usb_stick
                                ),
                                contentDescription = "USB drive"
                            )
                        },
                    )
                }
            }
        } else {
            Icon(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(48.dp)
                    .height(128.dp),
                imageVector = ImageVector.vectorResource(id = R.drawable.ic_usb_stick_search),
                contentDescription = stringResource(R.string.looking_for_usb_drives),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(0.dp, 24.dp, 0.dp, 0.dp),
        )
    }
}


@PreviewScreenSizes()
@Composable
fun StartViewPreview() {
    val viewModel = remember { MainActivityViewModel() }
    val uiState by viewModel.state.collectAsState()

    MainView(viewModel) {
        StartView(
            viewModel,
            setThemeMode = {
                viewModel.setState(
                    uiState.copy(
                        themeMode = it
                    )
                )
            },
            setDynamicTheme = {
                viewModel.setState(
                    uiState.copy(
                        dynamicColors = it
                    )
                )
            },
            onCTAClick = { },
        )
    }
}

@PreviewScreenSizes()
@Composable
fun WindowsAlertDialogPreview() {
    val viewModel = remember { MainActivityViewModel() }

    MainView(viewModel) {
        WindowsImageAlertDialog(
            onDismissRequest = { }, onConfirm = { },
            onCancel = { })
    }
}

@PreviewScreenSizes()
@Composable
fun UsbDevicePickerBottomSheetPreview() {
    val viewModel = remember { MainActivityViewModel() }
    var openBottomSheet by rememberSaveable { mutableStateOf(true) }
    val availableDevices = setOf(
        object : IUsbMassStorageDeviceDescriptor {
            override val name: String = "USB Drive"
            override val vidpid: String = "1234:5678"
        }
    )

    MainView(viewModel) {
        Row(
            Modifier.toggleable(
                value = openBottomSheet, role = Role.Checkbox,
                onValueChange = { checked -> openBottomSheet = checked })
        ) {
            Checkbox(checked = openBottomSheet, onCheckedChange = null)
            Spacer(Modifier.width(16.dp))
            Text("Open bottom sheet")
        }

        LaunchedEffect(Unit) {
            openBottomSheet = true
        }

        if (openBottomSheet) {
            UsbDevicePickerBottomSheet(
                onDismissRequest = { openBottomSheet = false },
                selectDevice = { },
                availableDevices = { availableDevices }
            )
        }
    }
}

@PreviewScreenSizes()
@Composable
fun EmptyUsbDevicePickerBottomSheetPreview() {
    val viewModel = remember { MainActivityViewModel() }
    var openBottomSheet by remember { mutableStateOf(true) }

    MainView(viewModel) {
        Row(
            Modifier.toggleable(
                value = openBottomSheet, role = Role.Checkbox,
                onValueChange = { checked -> openBottomSheet = checked })
        ) {
            Checkbox(checked = openBottomSheet, onCheckedChange = null)
            Spacer(Modifier.width(16.dp))
            Text("Open bottom sheet")
        }

        LaunchedEffect(Unit) {
            openBottomSheet = true
        }

        if (openBottomSheet) {
            UsbDevicePickerBottomSheet(
                onDismissRequest = { openBottomSheet = false },
                selectDevice = { },
                availableDevices = { emptySet() },
            )
        }
    }
}
