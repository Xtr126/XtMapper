package xtr.keymapper

import android.app.Activity
import android.graphics.drawable.Drawable
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyListScope
import xtr.keymapper.activity.MainActivity
import android.content.pm.PackageManager
import android.view.InputDevice
import androidx.annotation.DrawableRes
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*

// ============ THEME COLORS ============

object KeymapperTheme {
    val Background = Color(0xFF16110D)
    val Surface = Color(0xFF1F161E)
    val SurfaceElevated = Color(0xFF311F30)
    val Card = Color(0xFF3F2835)
    val Accent = Color(0xFFFF1442)
    val Success = Color(0xFF3FB900)
    val Warning = Color(0xFFF06159)
    val Error = Color(0xFFFF3B2D)
    val StopHighlight = Color(0xFF8B5CF6)
    val TextPrimary = Color(0xFFFFDDE5)
    val TextSecondary = Color(0xFFA2847E)
    val Border = Color(0xFF30363D)
}

// ============ DATA MODELS ============

internal data class GameProfile(
    val id: String,
    val appName: String,
    val packageName: String,
    val icon: Drawable? = null,
    val fallbackIcon: ImageVector? = Icons.Default.Gamepad,
    val isActive: Boolean = false,
    val keyCount: Int = 0,
    val lastUsed: String? = null
)

internal data class HardwareDevice(
    val name: String,
    val type: DeviceType,
    val isConnected: Boolean
)

internal enum class DeviceType {
    BLUETOOTH_CONTROLLER,
    USB_KEYBOARD,
    GAMEPAD,
    NONE
}

internal data class AppState(
    val isServiceActive: Boolean = false,
    val hasAccessibilityPermission: Boolean = true,
    val hasOverlayPermission: Boolean = false,
    val connectedDevices: List<HardwareDevice> = emptyList(),
    val profiles: List<GameProfile> = emptyList()
)

// ============ MAIN APP COMPOSABLE ============

@Composable
private fun KeymapperApp(
    state: MutableState<AppState>,
    onToggleService: () -> Unit = {},
    onFixPermission: () -> Unit = {},
    onTestInput: () -> Unit = {},
    onSelectProfile: (GameProfile) -> Unit = {},
    onLaunchProfile: (GameProfile) -> Unit = {},
    onEditProfile: (GameProfile) -> Unit = {},
    onRenameProfile: (GameProfile) -> Unit = {},
    onChangeProfileApp: (GameProfile) -> Unit = {},
    onDeleteProfile: (GameProfile) -> Unit = {},
    onStopService: () -> Unit = {},
    onHelp: () -> Unit = {},
    onAddProfile: () -> Unit = {},
    onExportProfiles: () -> Unit = {},
    onImportProfiles: () -> Unit = {},
    onToggleOverlay: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    val currentState = state.value

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = KeymapperTheme.Accent,
            secondary = KeymapperTheme.StopHighlight,
            surface = KeymapperTheme.Surface
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(KeymapperTheme.Background)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // Full-width top bar
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = KeymapperTheme.Background,
                    shadowElevation = 4.dp
                ) {
                    AppTopBarRow(
                        onSettings = onSettings,
                        onHelp = onHelp,
                    )
                }

                // Constrained main content
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .widthIn(max = 100.dp)
                        .weight(1f),
                    contentAlignment = Alignment.TopCenter
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        contentPadding = PaddingValues(vertical = 16.dp)
                    ) {
                        item {
                            Box(
                                modifier = Modifier.padding(horizontal = 16.dp)
                            ) {
                                GlobalStatusToggle(
                                    state = currentState,
                                    onToggleService = onToggleService,
                                    onFixPermission = onFixPermission
                                )
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            Box(
                                modifier = Modifier.padding(horizontal = 16.dp)
                            ) {
                                HardwareStatus(
                                    devices = currentState.connectedDevices,
                                    onTestInput = onTestInput
                                )
                            }

                            Spacer(modifier = Modifier.height(20.dp))
                        }

                        profileGrid(
                            profiles = currentState.profiles,
                            isOverlayChecked = currentState.hasOverlayPermission,
                            onSelectProfile = onSelectProfile,
                            onLaunchProfile = onLaunchProfile,
                            onEditProfile = onEditProfile,
                            onRenameProfile = onRenameProfile,
                            onChangeProfileApp = onChangeProfileApp,
                            onDeleteProfile = onDeleteProfile,
                            onStopService = onStopService,
                            onHelp = onHelp,
                            onToggleOverlay = onToggleOverlay,
                            onExport = onExportProfiles,
                            onImport = onImportProfiles
                        )
                    }
                }
            }

            AddProfileFAB(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                onAddProfile = onAddProfile
            )
        }
    }
}

// ============ REAL ACTIVITY HOST ============

object GameKeyMapperBridge {
    @JvmStatic
    fun installGameKeyMapperContent(activity: Activity) {
        val mainActivity = activity as? MainActivity
            ?: error("GameKeyMapperBridge requires MainActivity")
        activity.setContentView(
            ComposeView(activity).apply {
                setContent { GameKeyMapperActivityContent(mainActivity) }
            }
        )
    }
}

private fun createComposeState(activity: MainActivity): AppState {
    val keymapProfiles = xtr.keymapper.keymap.KeymapProfiles(activity)
    val profiles = mutableListOf<GameProfile>()

    keymapProfiles.getAllProfiles().forEach { (profileName, profile) ->
        if (profileName == null) {
            keymapProfiles.deleteProfile(null)
            return@forEach
        }

        val packageName = profile.packageName
        var appName = profileName
        var icon: Drawable? = null

        try {
            val appInfo = activity.packageManager.getApplicationInfo(packageName, 0)
            appName = activity.packageManager.getApplicationLabel(appInfo).toString()
            icon = activity.packageManager.getApplicationIcon(appInfo)
        } catch (_: PackageManager.NameNotFoundException) {
            // Keep the profile name and Compose fallback icon.
        }

        val keyCount = keymapProfiles.sharedPref
            .getStringSet(profileName, emptySet())
            ?.size ?: 0

        profiles += GameProfile(
            id = profileName,
            appName = appName,
            packageName = packageName,
            icon = icon,
            isActive = profileName == activity.selectedProfileNameForCompose(),
            keyCount = keyCount
        )
    }

    return AppState(
        isServiceActive = activity.isComposeServiceActive(),
        hasAccessibilityPermission = true,
        hasOverlayPermission = android.provider.Settings.canDrawOverlays(activity),
        connectedDevices = loadComposeDevices(),
        profiles = profiles
    )
}

private fun loadComposeDevices(): List<HardwareDevice> {
    val devices = mutableListOf<HardwareDevice>()
    for (deviceId in InputDevice.getDeviceIds()) {
        val device = InputDevice.getDevice(deviceId) ?: continue
        if (device.isVirtual) continue

        val sources = device.sources
        val type = when {
            sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD -> DeviceType.USB_KEYBOARD
            sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD -> DeviceType.GAMEPAD
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK -> DeviceType.BLUETOOTH_CONTROLLER
            else -> null
        } ?: continue

        devices += HardwareDevice(device.name, type, true)
    }
    return devices
}

@Composable
private fun GameKeyMapperActivityContent(activity: MainActivity) {
    val state = remember { mutableStateOf(createComposeState(activity)) }

    fun refresh() {
        state.value = createComposeState(activity)
    }

    KeymapperApp(
        state = state,
        onToggleService = {
            activity.toggleServiceFromCompose()
            refresh()
        },
        onFixPermission = {
            activity.fixPermissionFromCompose()
            refresh()
        },
        onTestInput = {
            activity.testInputFromCompose()
        },
        onSelectProfile = {
            activity.selectProfileFromCompose(it.profileNameForActivity())
            refresh()
        },
        onLaunchProfile = {
            activity.selectProfileFromCompose(it.profileNameForActivity())
            activity.launchAppFromCompose()
            refresh()
        },
        onEditProfile = {
            activity.selectProfileFromCompose(it.profileNameForActivity())
            activity.startEditorFromCompose()
            refresh()
        },
        onRenameProfile = {
            activity.renameProfileFromCompose(it.profileNameForActivity()) { refresh() }
        },
        onChangeProfileApp = {
            activity.changeProfileAppFromCompose(it.profileNameForActivity()) { refresh() }
        },
        onDeleteProfile = {
            activity.deleteProfileFromCompose(it.profileNameForActivity())
            refresh()
        },
        onStopService = {
            activity.stopPointerFromCompose()
            refresh()
        },
        onHelp = {
            activity.openHelpFromCompose()
        },
        onAddProfile = {
            activity.createNewProfileFromCompose { refresh() }
        },
        onExportProfiles = {
            activity.openExportImportFromCompose()
        },
        onImportProfiles = {
            activity.openExportImportFromCompose()
        },
        onToggleOverlay = {
            activity.toggleOverlayFromCompose()
            refresh()
        },
        onSettings = {
            activity.openSettingsFromCompose()
        }
    )
}

/*
 * MainActivity keeps the profile name separately for compatibility with the
 * existing Java service/editor code.  This adapter makes the Kotlin UI's id
 * unambiguously point at that same profile name.
 */
private fun GameProfile.profileNameForActivity(): String = id

// ============ SECTION 1: GLOBAL STATUS TOGGLE ============

@Composable
private fun GlobalStatusToggle(
    state: AppState,
    modifier: Modifier = Modifier,
    onToggleService: () -> Unit,
    onFixPermission: () -> Unit
) {
    val statusColor = if (state.isServiceActive) KeymapperTheme.Success else KeymapperTheme.Error
    val statusText = if (state.isServiceActive) "Service Active" else "Service Inactive"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = KeymapperTheme.SurfaceElevated),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = if (state.isServiceActive) KeymapperTheme.Success.copy(alpha = 0.2f)
                else KeymapperTheme.Error.copy(alpha = 0.2f),
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (state.isServiceActive) Icons.Default.CheckCircle
                        else Icons.Default.Error,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = statusText,
                color = statusColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Switch(
                checked = state.isServiceActive,
                onCheckedChange = { onToggleService() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = KeymapperTheme.Success,
                    checkedTrackColor = KeymapperTheme.Success.copy(alpha = 0.4f),
                    uncheckedThumbColor = KeymapperTheme.TextSecondary,
                    uncheckedTrackColor = KeymapperTheme.Border
                ),
                modifier = Modifier.padding(vertical = 12.dp)
            )

            if (!state.hasAccessibilityPermission) {
                PermissionRow(
                    icon = Icons.Default.Warning,
                    text = "Accessibility permission required",
                    onFixPermission = onFixPermission
                )
            }

            if (!state.hasOverlayPermission) {
                PermissionRow(
                    icon = Icons.Default.DragHandle,
                    text = "Overlay permission required",
                    onFixPermission = onFixPermission
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    text: String,
    onFixPermission: () -> Unit
) {
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                KeymapperTheme.Warning.copy(alpha = 0.15f),
                RoundedCornerShape(12.dp)
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = KeymapperTheme.Warning,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = text,
            color = KeymapperTheme.Warning,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = onFixPermission,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = KeymapperTheme.Warning),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text("Fix Now", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ============ SECTION 2: CONNECTED HARDWARE ============

@Composable
private fun HardwareStatus(
    devices: List<HardwareDevice>,
    modifier: Modifier = Modifier,
    onTestInput: () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = KeymapperTheme.Surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Connected Hardware",
                color = KeymapperTheme.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (devices.isNotEmpty()) {
                devices.filter { it.isConnected }.forEach { device ->
                    HardwareDeviceItem(device = device, modifier = Modifier.fillMaxWidth())
                }

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onTestInput,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = KeymapperTheme.Accent.copy(alpha = 0.2f)
                    ),
                    border = BorderStroke(1.dp, KeymapperTheme.Accent.copy(alpha = 0.5f))
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_touch_app_24),
                        contentDescription = null,
                        tint = KeymapperTheme.Accent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Test Input",
                        color = KeymapperTheme.Accent,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No devices connected",
                        color = KeymapperTheme.TextSecondary,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun HardwareDeviceItem(
    device: HardwareDevice,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                KeymapperTheme.Border.copy(alpha = 0.3f),
                RoundedCornerShape(10.dp)
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = KeymapperTheme.Success.copy(alpha = 0.15f),
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = when (device.type) {
                        DeviceType.BLUETOOTH_CONTROLLER -> Icons.Default.Gamepad
                        DeviceType.USB_KEYBOARD -> Icons.Default.Keyboard
                        DeviceType.GAMEPAD -> Icons.Default.Gamepad
                        DeviceType.NONE -> Icons.Default.DeviceUnknown
                    },
                    contentDescription = null,
                    tint = KeymapperTheme.Success,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name,
                color = KeymapperTheme.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = device.type.name.replace("_", " "),
                color = KeymapperTheme.TextSecondary,
                fontSize = 11.sp
            )
        }

        Surface(
            modifier = Modifier.size(10.dp),
            shape = CircleShape,
            color = KeymapperTheme.Success
        ) {}
    }

    Spacer(modifier = Modifier.height(8.dp))
}

// ============ SECTION 3: PROFILE GRID ============

private fun LazyListScope.profileGrid(
    profiles: List<GameProfile>,
    isOverlayChecked: Boolean,
    onSelectProfile: (GameProfile) -> Unit,
    onLaunchProfile: (GameProfile) -> Unit,
    onEditProfile: (GameProfile) -> Unit,
    onRenameProfile: (GameProfile) -> Unit,
    onChangeProfileApp: (GameProfile) -> Unit,
    onDeleteProfile: (GameProfile) -> Unit,
    onStopService: () -> Unit,
    onHelp: () -> Unit,
    onToggleOverlay: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    item {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Game Profiles",
                color = KeymapperTheme.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Text(
                    text = stringResource(R.string.overlay),
                    color = KeymapperTheme.TextSecondary,
                    fontSize = 12.sp
                )
                Switch(
                    checked = isOverlayChecked,
                    onCheckedChange = { onToggleOverlay() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = KeymapperTheme.Accent,
                        checkedTrackColor = KeymapperTheme.Accent.copy(alpha = 0.4f),
                        uncheckedThumbColor = KeymapperTheme.TextSecondary,
                        uncheckedTrackColor = KeymapperTheme.Border
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }

    if (profiles.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Gamepad,
                        contentDescription = null,
                        tint = KeymapperTheme.TextSecondary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No profiles yet",
                        color = KeymapperTheme.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Use Add Game to create a profile.",
                        color = KeymapperTheme.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }

    val chunkedProfiles = profiles.chunked(2)
    items(chunkedProfiles.size) { rowIndex ->
        val rowItems = chunkedProfiles[rowIndex]

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                ProfileCard(
                    profile = rowItems[0],
                    onClick = { onSelectProfile(rowItems[0]) },
                    onLaunch = { onLaunchProfile(rowItems[0]) },
                    onEdit = { onEditProfile(rowItems[0]) },
                    onRename = { onRenameProfile(rowItems[0]) },
                    onChangeApp = { onChangeProfileApp(rowItems[0]) },
                    onDelete = { onDeleteProfile(rowItems[0]) },
                    onStop = onStopService,
                    onHelp = onHelp
                )
            }

            if (rowItems.size > 1) {
                Box(modifier = Modifier.weight(1f)) {
                    ProfileCard(
                        profile = rowItems[1],
                        onClick = { onSelectProfile(rowItems[1]) },
                        onLaunch = { onLaunchProfile(rowItems[1]) },
                        onEdit = { onEditProfile(rowItems[1]) },
                        onRename = { onRenameProfile(rowItems[1]) },
                        onChangeApp = { onChangeProfileApp(rowItems[1]) },
                        onDelete = { onDeleteProfile(rowItems[1]) },
                        onStop = onStopService,
                        onHelp = onHelp
                    )
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }

    item {
        Button(
            onClick = onImport,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = KeymapperTheme.Accent.copy(alpha = 0.2f)
            ),
            border = BorderStroke(1.dp, KeymapperTheme.Accent.copy(alpha = 0.5f))
        ) {
            Icon(
                imageVector = Icons.Default.Sync,
                contentDescription = null,
                tint = KeymapperTheme.Accent,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.import_export),
                color = KeymapperTheme.Accent,
                fontWeight = FontWeight.Medium
            )
        }
    }

    item {
        Spacer(modifier = Modifier.height(60.dp))
    }
}

@Composable
private fun ProfileCard(
    profile: GameProfile,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLaunch: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onChangeApp: () -> Unit,
    onDelete: () -> Unit,
    onStop: () -> Unit,
    onHelp: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (profile.isActive) KeymapperTheme.StopHighlight.copy(alpha = 0.15f)
            else KeymapperTheme.Card
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (profile.isActive) 8.dp else 2.dp
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (profile.isActive) KeymapperTheme.StopHighlight.copy(alpha = 0.3f)
                else KeymapperTheme.Border.copy(alpha = 0.3f),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val drawable = profile.icon
                    if (drawable != null) {
                        val bitmap = remember(profile.id) {
                            drawable.toBitmap().asImageBitmap()
                        }
                        Image(
                            bitmap = bitmap,
                            contentDescription = profile.appName,
                            modifier = Modifier
                                .size(40.dp)
                                .padding(2.dp)
                        )
                    } else {
                        Icon(
                            imageVector = profile.fallbackIcon ?: Icons.Default.Gamepad,
                            contentDescription = profile.appName,
                            tint = if (profile.isActive) KeymapperTheme.StopHighlight
                            else KeymapperTheme.TextSecondary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = profile.appName,
                color = KeymapperTheme.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "${profile.keyCount} keys mapped",
                color = KeymapperTheme.TextSecondary,
                fontSize = 11.sp
            )

            profile.lastUsed?.let {
                Text(
                    text = "Last used: $it",
                    color = KeymapperTheme.TextSecondary.copy(alpha = 0.7f),
                    fontSize = 10.sp
                )
            }

            if (profile.isActive) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .background(
                            KeymapperTheme.StopHighlight,
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "SELECTED",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconButton(onClick = onLaunch) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Launch",
                        tint = KeymapperTheme.Success,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Menu",
                        tint = KeymapperTheme.TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    DropdownMenu(
        expanded = showMenu,
        onDismissRequest = { showMenu = false }
    ) {
        ProfileMenuItem("Edit Keymap", Icons.Default.Edit, onEdit) { showMenu = false }
        ProfileMenuItem("Rename", Icons.Default.DriveFileRenameOutline, onRename) { showMenu = false }
        ProfileMenuItem("Change App", Icons.Default.Apps, onChangeApp) { showMenu = false }
        ProfileMenuItem("Stop", Icons.Default.Stop, onStop) { showMenu = false }
        ProfileMenuItem("Launch App", Icons.Default.PlayArrow, onLaunch) { showMenu = false }
        ProfileMenuItem("Help", Icons.Default.HelpOutline, onHelp) { showMenu = false }
        ProfileMenuItem("Delete Profile", Icons.Default.Delete, onDelete) { showMenu = false }
    }
}

@Composable
private fun ProfileMenuItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    dismiss: () -> Unit
) {
    DropdownMenuItem(
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = KeymapperTheme.TextSecondary
            )
        },
        text = { Text(label, color = KeymapperTheme.TextPrimary) },
        onClick = {
            onClick()
            dismiss()
        }
    )
}

// ============ TOP BAR ============

@Composable
private fun AppTopBarRow(
    modifier: Modifier = Modifier,
    onSettings: () -> Unit,
    onHelp: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.app_name),
            color = KeymapperTheme.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(
                icon = R.drawable.ic_baseline_help_outline_24,
                label = stringResource(R.string.help),
                onClick = onHelp,
                tint = KeymapperTheme.TextSecondary
            )

            ActionButton(
                icon = R.drawable.ic_baseline_settings_24,
                label = stringResource(R.string.configure),
                onClick = onSettings,
                tint = KeymapperTheme.Accent
            )
        }
    }
}

@Composable
private fun ActionButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = tint.copy(alpha = 0.15f)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ============ FAB ============

@Composable
private fun AddProfileFAB(
    modifier: Modifier = Modifier,
    onAddProfile: () -> Unit
) {
    ExtendedFloatingActionButton(
        onClick = onAddProfile,
        modifier = modifier,
        containerColor = KeymapperTheme.Accent,
        contentColor = Color.White,
        icon = {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        },
        text = {
            Text(
                text = stringResource(R.string.add_games),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    )
}

// ============ SAMPLE DATA ============

private fun getSampleProfiles(): List<GameProfile> = listOf(
    GameProfile(
        id = "1",
        appName = "Call of Duty: Mobile",
        packageName = "com.activision.callofduty.shooter",
        fallbackIcon = Icons.Default.Shield,
        isActive = true,
        keyCount = 24,
        lastUsed = "2 hours ago"
    ),
    GameProfile(
        id = "2",
        appName = "PPSSPP Emulator",
        packageName = "org.ppsspp.ppsspp",
        fallbackIcon = Icons.Default.Gamepad,
        keyCount = 18,
        lastUsed = "1 day ago"
    ),
)

private fun getSampleDevices(): List<HardwareDevice> = listOf(
    HardwareDevice("Xbox Wireless Controller", DeviceType.BLUETOOTH_CONTROLLER, true),
    HardwareDevice("Logitech USB Keyboard", DeviceType.USB_KEYBOARD, true),
    HardwareDevice("DualShock 4", DeviceType.GAMEPAD, false)
)

private fun getSampleAppState(): AppState = AppState(
    isServiceActive = true,
    hasAccessibilityPermission = true,
    hasOverlayPermission = true,
    connectedDevices = getSampleDevices(),
    profiles = getSampleProfiles()
)


// ============ PREVIEW ============

@Composable
@Preview(name = "Empty")
fun KeymapperPreviewEmpty() {
    KeymapperApp(
        state = remember {
            mutableStateOf(
                AppState(
                    isServiceActive = false,
                    hasOverlayPermission = false,
                    profiles = emptyList()
                )
            )
        }
    )
}


@Composable
@Preview(name = "Default View")
fun KeymapperPreview() {
    KeymapperApp(
        state = remember { mutableStateOf(getSampleAppState()) },
    )
}

@Composable
@Preview(name = "Service Inactive")
fun KeymapperInactivePreview() {
    KeymapperApp(
        state = remember { mutableStateOf(getSampleAppState().copy(
            isServiceActive = false,
            hasAccessibilityPermission = false,
            hasOverlayPermission = false
        ))},
    )
}

@Composable
@Preview(name = "No Devices")
fun KeymapperNoDevicesPreview() {
    KeymapperApp(
        state = remember { mutableStateOf(getSampleAppState().copy(
            connectedDevices = emptyList()
        ))},
    )
}

@Composable
@Preview(name = "Empty Profiles")
fun KeymapperEmptyProfilesPreview() {
    KeymapperApp(
        state = remember { mutableStateOf(getSampleAppState().copy(
            profiles = emptyList()
        ))},
    )
}