package xtr.keymapper.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import xtr.keymapper.activity.ui.theme.XtMapperTheme

// Constants for the result
private const val EXTRA_SELECTED_DISPLAY_ID = "selected_display_id"

// 1. Activity to select display
class DisplaySelectorActivity : ComponentActivity() {
    private var selectedDisplayId by mutableIntStateOf(-1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            XtMapperTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DisplaySelectorScreen(
                        onOkClicked = {
                            val resultIntent = Intent().apply {
                                putExtra(EXTRA_SELECTED_DISPLAY_ID, selectedDisplayId)
                            }
                            setResult(RESULT_OK, resultIntent)
                            finish()
                        },
                        onCancelClicked = {
                            setResult(RESULT_CANCELED)
                            finish()
                        },
                        onDisplaySelected = { displayId ->
                            selectedDisplayId = displayId
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DisplaySelectorScreen(
    onOkClicked: () -> Unit,
    onCancelClicked: () -> Unit,
    onDisplaySelected: (Int) -> Unit
) {
    val context = LocalContext.current
    val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    val displays = remember { displayManager.displays }
    var selectedId by remember { mutableIntStateOf(-1) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Select a Display",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (displays.isEmpty()) {
            Text("No displays found", modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn (
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(displays) { display ->
                    DisplayCard(
                        display = display,
                        isSelected = selectedId == display.displayId,
                        onSelected = {
                            selectedId = display.displayId
                            onDisplaySelected(display.displayId)
                        }
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onCancelClicked,
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Text("Cancel")
            }
            Button(
                onClick = onOkClicked,
                enabled = selectedId != -1
            ) {
                Text("OK")
            }
        }
    }
}

@Composable
fun DisplayCard(
    display: Display,
    isSelected: Boolean,
    onSelected: () -> Unit
) {
    val mode = display.mode
    val width = mode.physicalWidth
    val height = mode.physicalHeight

    Card(
        modifier = Modifier
            .width(200.dp)
            .height(150.dp)
            .selectable(
                selected = isSelected,
                onClick = onSelected
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RadioButton(
                selected = isSelected,
                onClick = null, // null because we handle selection with the card click
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "Display #${display.displayId}",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${width}x${height}",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

// 2. Helper to launch the activity from another activity
class DisplaySelector(var activity: AppCompatActivity) {
    private lateinit var launcher: ActivityResultLauncher<Intent>
    private lateinit var intent: Intent

    fun register(callback: (displayId: Int?) -> Unit?): DisplaySelector  {
        intent = Intent(activity, DisplaySelectorActivity::class.java)
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val displayId = result.data?.getIntExtra(EXTRA_SELECTED_DISPLAY_ID, -1)
                callback(displayId?.takeIf { it >= 0 })
            } else {
                callback(null)
            }
        }
        return this
    }

    fun launch() {
        launcher.launch(intent)
    }
}