package xtr.keymapper

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable

// ============ THEME COLORS ============
object KeymapperTheme {
    val Background = Color(0xFF0D1117)
    val Surface = Color(0xFF161B22)
    val SurfaceElevated = Color(0xFF1C2333)
    val Card = Color(0xFF1E2835)
    val Accent = Color(0xFF64D2FF)
    val Success = Color(0xFF3FB950)
    val Warning = Color(0xFFF0883E)
    val Error = Color(0xFFF85149)
    val StopHighlight = Color(0xFF8B5CF6)
    val TextPrimary = Color(0xFFE6EDF3)
    val TextSecondary = Color(0xFF8B949E)
    val Border = Color(0xFF30363D)
}

// ============ DATA MODELS ============

data class GameProfile(
    val id: String,
    val appName: String,
    val packageName: String,
    val iconRes: ImageVector,
    val isActive: Boolean = false,
    val keyCount: Int,
    val lastUsed: String? = null
)

data class HardwareDevice(
    val name: String,
    val type: DeviceType,
    val isConnected: Boolean
)

enum class DeviceType {
    BLUETOOTH_CONTROLLER,
    USB_KEYBOARD,
    GAMEPAD,
    NONE
}

data class AppState(
    val isServiceActive: Boolean = false,
    val hasAccessibilityPermission: Boolean = false,
    val hasOverlayPermission: Boolean = false,
    val connectedDevices: List<HardwareDevice> = emptyList(),
    val profiles: List<GameProfile> = emptyList()
)

// ============ SAMPLE DATA ============

fun getSampleProfiles(): List<GameProfile> = listOf(
    GameProfile(
        id = "1",
        appName = "Call of Duty: Mobile",
        packageName = "com.activision.callofduty.shooter",
        iconRes = Icons.Default.Shield,
        isActive = true,
        keyCount = 24,
        lastUsed = "2 hours ago"
    ),
    GameProfile(
        id = "2",
        appName = "PPSSPP Emulator",
        packageName = "org.ppsspp.ppsspp",
        iconRes = Icons.Default.Gamepad,
        keyCount = 18,
        lastUsed = "1 day ago"
    ),
    GameProfile(
        id = "3",
        appName = "Netflix",
        packageName = "com.netflix.ninja",
        iconRes = Icons.Default.Movie,
        keyCount = 6,
        lastUsed = "3 days ago"
    ),
    GameProfile(
        id = "4",
        appName = "Genshin Impact",
        packageName = "com.miHoYo.GenshinImpact",
        iconRes = Icons.Default.RocketLaunch,
        keyCount = 32,
        lastUsed = "5 hours ago"
    )
)

fun getSampleDevices(): List<HardwareDevice> = listOf(
    HardwareDevice("Xbox Wireless Controller", DeviceType.BLUETOOTH_CONTROLLER, true),
    HardwareDevice("Logitech USB Keyboard", DeviceType.USB_KEYBOARD, true),
    HardwareDevice("DualShock 4", DeviceType.GAMEPAD, false)
)

// ============ MAIN APP COMPOSABLE ============

@Composable
fun KeymapperApp(
    state: MutableState<AppState>,
    onToggleService: () -> Unit = {},
    onFixPermission: () -> Unit = {},
    onTestInput: () -> Unit = {},
    onSelectProfile: (GameProfile) -> Unit = {},
    onAddProfile: () -> Unit = {},
    onExportProfiles: () -> Unit = {},
    onImportProfiles: () -> Unit = {},
    onToggleOverlay: () -> Unit = {},
    onSettings: () -> Unit = {}
) {
    val currentState = state.value
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = KeymapperTheme.Accent,
            secondary = KeymapperTheme.StopHighlight,
            surface = KeymapperTheme.Surface
        )
    ) {
        // Use Scaffold-like structure with LazyColumn for scrolling
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(KeymapperTheme.Background)
        ) {
            // Fixed top bar (sticky)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = KeymapperTheme.Background,
                shadowElevation = 4.dp
            ) {
                AppTopBarRow(
                    state = currentState,
                    onSettings = onSettings,
                    onExport = onExportProfiles,
                    onImport = onImportProfiles
                )
            }

            // Scrollable content using LazyColumn
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(vertical = 16.dp)
                // Note: Extracted horizontal padding here because grid rows have their own internal edge padding.
            ) {
                item {
                    // 1. Global Status Toggle
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        GlobalStatusToggle(
                            state = currentState,
                            onToggleService = onToggleService,
                            onFixPermission = onFixPermission
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // 2. Connected Hardware
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        HardwareStatus(
                            devices = currentState.connectedDevices,
                            onTestInput = onTestInput
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                }

                // 3. Profile Grid (Called directly inside the LazyColumn block)
                profileGrid(
                    profiles = currentState.profiles,
                    isOverlayChecked = currentState.hasOverlayPermission, // Assuming state hook name
                    onSelectProfile = onSelectProfile,
                    onToggleOverlay = onToggleOverlay
                )
            }
        }

        // FAB positioned at bottom right (overlay, not in scroll)
        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.BottomEnd
        ) {
            AddProfileFAB(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                onAddProfile = onAddProfile
            )
        }
    }
}

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
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status indicator icon
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

            // Status text
            Text(
                text = statusText,
                color = statusColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Toggle Switch
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

            // Permission status
            if (!state.hasAccessibilityPermission) {
                Spacer(modifier = Modifier.height(12.dp))
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
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = KeymapperTheme.Warning,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Accessibility permission required",
                        color = KeymapperTheme.Warning,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
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

            if (!state.hasOverlayPermission) {
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
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = null,
                        tint = KeymapperTheme.Warning,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Overlay permission required",
                        color = KeymapperTheme.Warning,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
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
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Connected Hardware",
                color = KeymapperTheme.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (devices.isNotEmpty()) {
                // Connected devices
                devices.forEach { device ->
                    if (device.isConnected) {
                        HardwareDeviceItem(device = device, modifier = Modifier.fillMaxWidth())
                    }
                }

                // Input tester button
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
                        imageVector = Icons.Default.TouchApp,
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
                    modifier = Modifier.height(80.dp),
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

        Column(
            modifier = Modifier.weight(1f)
        ) {
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

        Badge(
            modifier = Modifier.size(10.dp),
            containerColor = KeymapperTheme.Success
        )
    }

    Spacer(modifier = Modifier.height(8.dp))
}

// ============ SECTION 3: PROFILE GRID ============

fun LazyListScope.profileGrid(
    profiles: List<GameProfile>,
    isOverlayChecked: Boolean, // Pass the state down explicitly
    onSelectProfile: (GameProfile) -> Unit,
    onToggleOverlay: () -> Unit
) {
    // 1. Render the Grid Header as a single list item
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
                    text = "Overlay",
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

    // 2. Chunk the profiles into lists of 2 elements each to emulate a 2-column grid
    val chunkedProfiles = profiles.chunked(2)

    items(chunkedProfiles.size) { rowIndex ->
        val rowItems = chunkedProfiles[rowIndex]

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp), // Replaces grid vertical Arrangement
            horizontalArrangement = Arrangement.spacedBy(12.dp) // Replaces grid horizontal Arrangement
        ) {
            // First item in the row
            Box(modifier = Modifier.weight(1f)) {
                ProfileCard(
                    profile = rowItems[0],
                    onClick = { onSelectProfile(rowItems[0]) }
                )
            }

            // Second item in the row (if it exists)
            if (rowItems.size > 1) {
                Box(modifier = Modifier.weight(1f)) {
                    ProfileCard(
                        profile = rowItems[1],
                        onClick = { onSelectProfile(rowItems[1]) }
                    )
                }
            } else {
                // Empty space placeholder to ensure the first item stays exactly half width
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: GameProfile,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick)
            .then(modifier),
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

            // Icon
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (profile.isActive) KeymapperTheme.StopHighlight.copy(alpha = 0.3f)
                else KeymapperTheme.Border.copy(alpha = 0.3f),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = profile.iconRes,
                        contentDescription = null,
                        tint = if (profile.isActive) KeymapperTheme.StopHighlight
                        else KeymapperTheme.TextSecondary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // App name
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

            // Key count
            Text(
                text = "${profile.keyCount} keys mapped",
                color = KeymapperTheme.TextSecondary,
                fontSize = 11.sp
            )

            if (profile.lastUsed != null) {
                Text(
                    text = "Last used: ${profile.lastUsed}",
                    color = KeymapperTheme.TextSecondary.copy(alpha = 0.7f),
                    fontSize = 10.sp
                )
            }

            // Active indicator
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
                        text = "ACTIVE",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Action buttons row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconButton(onClick = onClick) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Launch",
                        tint = KeymapperTheme.Success,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = { showMenu = !showMenu }) {
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

    // Context menu
    DropdownMenu(
        expanded = showMenu,
        onDismissRequest = { showMenu = false }
    ) {
        menuItems.forEach { menuItem ->
            DropdownMenuItem(
                text = { Text(menuItem.label, color = KeymapperTheme.TextPrimary) },
                onClick = { showMenu = false }
            )
        }
    }
}

// ============ TOP BAR ============

@Composable
private fun AppTopBarRow(
    state: AppState,
    modifier: Modifier = Modifier,
    onSettings: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Title
        Text(
            text = "GameKeyMapper",
            color = KeymapperTheme.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        // Action buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ActionButton(
                icon = Icons.Default.Download,
                label = "Import",
                onClick = onImport,
                tint = KeymapperTheme.Accent
            )
            ActionButton(
                icon = Icons.Default.Upload,
                label = "Export",
                onClick = onExport,
                tint = KeymapperTheme.Accent
            )
            ActionButton(
                icon = Icons.Default.Settings,
                label = "Settings",
                onClick = onSettings,
                tint = KeymapperTheme.TextSecondary
            )
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
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
            imageVector = icon,
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
    FloatingActionButton(
        onClick = onAddProfile,
        modifier = modifier,
        containerColor = KeymapperTheme.Accent,
        contentColor = Color.White
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = "Add Game",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ============ MENU ITEMS ============

private val menuItems = listOf(
    MenuItem(label = "Edit Keymap"),
    MenuItem(label = "Stop"),
    MenuItem(label = "Launch App"),
    MenuItem(label = "Help"),
    MenuItem(label = "Delete Profile")
)

data class MenuItem(
    val label: String
)

// ============ HELPER FUNCTIONS ============

private fun getSampleAppState(): AppState = AppState(
    isServiceActive = true,
    hasAccessibilityPermission = true,
    hasOverlayPermission = true,
    connectedDevices = getSampleDevices(),
    profiles = getSampleProfiles()
)

// ============ PREVIEW FUNCTIONS ============

@Composable
@Preview(name = "Default View")
fun KeymapperPreview() {
    KeymapperApp(
        state = remember { mutableStateOf(getSampleAppState()) },
        onToggleService = {},
        onFixPermission = {},
        onTestInput = {},
        onSelectProfile = {},
        onAddProfile = {},
        onExportProfiles = {},
        onImportProfiles = {},
        onToggleOverlay = {},
        onSettings = {}
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
        onToggleService = {},
        onFixPermission = {},
        onTestInput = {},
        onSelectProfile = {},
        onAddProfile = {},
        onExportProfiles = {},
        onImportProfiles = {},
        onToggleOverlay = {},
        onSettings = {}
    )
}

@Composable
@Preview(name = "No Devices")
fun KeymapperNoDevicesPreview() {
    KeymapperApp(
        state = remember { mutableStateOf(getSampleAppState().copy(
            connectedDevices = emptyList()
        ))},
        onToggleService = {},
        onFixPermission = {},
        onTestInput = {},
        onSelectProfile = {},
        onAddProfile = {},
        onExportProfiles = {},
        onImportProfiles = {},
        onToggleOverlay = {},
        onSettings = {}
    )
}

@Composable
@Preview(name = "Empty Profiles")
fun KeymapperEmptyProfilesPreview() {
    KeymapperApp(
        state = remember { mutableStateOf(getSampleAppState().copy(
            profiles = emptyList()
        ))},
        onToggleService = {},
        onFixPermission = {},
        onTestInput = {},
        onSelectProfile = {},
        onAddProfile = {},
        onExportProfiles = {},
        onImportProfiles = {},
        onToggleOverlay = {},
        onSettings = {}
    )
}