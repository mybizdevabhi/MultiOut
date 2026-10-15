package com.livemic.karaoke.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.livemic.karaoke.audio.AudioEndpoint
import com.livemic.karaoke.audio.RoutingMode
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E5FF),
                    secondary = Color(0xFF7C4DFF),
                    background = Color(0xFF0F1218),
                    surface = Color(0xFF1A1F29)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LiveMicScreen(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
fun LiveMicScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val inputs by viewModel.inputEndpoints.collectAsState()
    val outputs by viewModel.outputEndpoints.collectAsState()
    val btCallRisk by viewModel.hasBtCallProfileRisk.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val micGranted = grants[Manifest.permission.RECORD_AUDIO] == true
        if (micGranted) {
            viewModel.toggleEngine()
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "LiveMic BT Studio",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = if (state.isEngineRunning) {
                        "LIVE \u2022 Est. Buffer: ${state.estimatedLatencyMs.roundToInt()} ms"
                    } else {
                        "STANDBY \u2022 48kHz Float32 Oboe DSP"
                    },
                    fontSize = 13.sp,
                    color = if (state.isEngineRunning) Color(0xFF00E676) else Color.Gray
                )
            }

            Button(
                onClick = {
                    val ctx = viewModel.getApplication<android.app.Application>()
                    val hasMic = ContextCompat.checkSelfPermission(
                        ctx,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                    if (hasMic) {
                        viewModel.toggleEngine()
                    } else {
                        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            perms.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        permissionLauncher.launch(perms.toTypedArray())
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isEngineRunning) Color(0xFFFF1744) else Color(0xFF00E676)
                )
            ) {
                Text(
                    text = if (state.isEngineRunning) "STOP MIC" else "START MIC",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Real-Time Level Meters", fontWeight = FontWeight.SemiBold, color = Color.White)
                MeterBar(label = "Mic Input", rms = state.inputRms, barColor = Color(0xFF00E5FF))
                MeterBar(label = "Speaker Output", rms = state.outputRms, barColor = Color(0xFF00E676))
                Text(
                    text = "Compressor Gain Reduction: ${state.gainReductionDb.roundToInt()} dB",
                    fontSize = 12.sp,
                    color = Color.LightGray
                )
            }
        }

        if (btCallRisk) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3E2723)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Bluetooth Calls Profile Detected on Speaker",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFCA28)
                    )
                    Text(
                        text = "Your Bluetooth speaker has its HFP/SCO Mic profile enabled. To keep high-quality A2DP stereo & Samsung Dual Audio active during phone/WhatsApp calls, disable the Calls toggle for this speaker in Bluetooth settings.",
                        fontSize = 12.sp,
                        color = Color.White
                    )
                    OutlinedButton(onClick = { viewModel.routingManager.openBluetoothSettings() }) {
                        Text("Open Bluetooth Settings", color = Color(0xFFFFCA28))
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "1. Audio Routing & Multi-Speaker Output",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5FF)
                )

                RoutingMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { viewModel.setRoutingMode(mode) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = state.routingMode == mode,
                            onClick = { viewModel.setRoutingMode(mode) }
                        )
                        Column(modifier = Modifier.padding(start = 6.dp)) {
                            Text(mode.title, fontWeight = FontWeight.Medium, color = Color.White, fontSize = 14.sp)
                            Text(mode.subtitle, fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                }

                if (state.routingMode == RoutingMode.MANUAL_PICKER) {
                    HorizontalDivider(color = Color.DarkGray)
                    EndpointDropdown(
                        title = "Input Microphone Device",
                        endpoints = inputs,
                        selectedId = state.selectedInputDeviceId,
                        onSelect = { viewModel.setManualInputDevice(it) }
                    )
                    EndpointDropdown(
                        title = "Output Speaker Device",
                        endpoints = outputs,
                        selectedId = state.selectedOutputDeviceId,
                        onSelect = { viewModel.setManualOutputDevice(it) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.routingManager.openSystemMediaOutputSwitcher() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Dual Audio / Media Output", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { viewModel.routingManager.openSamsungSeparateAppSoundSettings() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Separate App Sound", fontSize = 11.sp)
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "2. Concurrency (Music, Notifications & Calls)",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5FF)
                )

                ToggleRow(
                    title = "Continue Playing Over Music & Notifications",
                    subtitle = "Keeps mic active in Shared Mode when Spotify, YouTube karaoke tracks, or notification tones play.",
                    checked = state.mixWithOtherApps,
                    onCheckedChange = { viewModel.setMixWithOtherApps(it) }
                )

                ToggleRow(
                    title = "Keep Mic Active During Phone / WhatsApp Calls",
                    subtitle = "Switches input to VoiceRecognition preset so your voice continues playing on speakers during calls.",
                    checked = state.playDuringCalls,
                    onCheckedChange = { viewModel.setPlayDuringCalls(it) }
                )

                if (state.playDuringCalls && !state.isAccessibilityServiceEnabled) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF263238)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Android 13 Call-Mic Permission Required",
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF00E5FF),
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Android 13 mutes third-party mics during active calls unless the LiveMic Call-Mode Keep-Alive service is enabled in Accessibility Settings.",
                                fontSize = 11.sp,
                                color = Color.LightGray
                            )
                            Button(onClick = { viewModel.openAccessibilitySettings() }) {
                                Text("Enable Call Keep-Alive Service", fontSize = 12.sp)
                            }
                        }
                    }
                }

                ToggleRow(
                    title = "Low-Latency Bluetooth Gaming Flag",
                    subtitle = "Signals compatible Bluetooth speakers to engage low-latency gaming buffer mode.",
                    checked = state.lowLatencyGameUsage,
                    onCheckedChange = { viewModel.setLowLatencyGameUsage(it) }
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "3. Input DSP (Noise Removal, Anti-Feedback & Normalizer)",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5FF)
                )

                SliderRow(
                    label = "Mic Preamp Gain",
                    valueText = "${state.inputGainDb.roundToInt()} dB",
                    value = state.inputGainDb,
                    valueRange = -12f..24f,
                    onValueChange = { viewModel.setInputGainDb(it) }
                )

                SliderRow(
                    label = "High-Pass Rumble Filter",
                    valueText = "${state.highPassCutoffHz.roundToInt()} Hz",
                    value = state.highPassCutoffHz,
                    valueRange = 40f..220f,
                    onValueChange = { viewModel.setHighPassCutoffHz(it) }
                )

                SliderRow(
                    label = "Noise Gate / Expander Threshold",
                    valueText = "${state.noiseGateThresholdDb.roundToInt()} dBFS",
                    value = state.noiseGateThresholdDb,
                    valueRange = -70f..-18f,
                    onValueChange = { viewModel.setNoiseGateThresholdDb(it) }
                )

                ToggleRow(
                    title = "Aggressive Speech Denoiser Mode",
                    subtitle = "OFF = Vocal/Karaoke Mode (preserves sustained notes). ON = Speech PA Mode (fast noise stripping).",
                    checked = state.speechDenoiseMode,
                    onCheckedChange = { viewModel.setSpeechDenoiseMode(it) }
                )

                ToggleRow(
                    title = "Anti-Feedback Howling Suppressor",
                    subtitle = "Applies 3 resonant notch filters + 3.5Hz micro-modulation to prevent room feedback loops.",
                    checked = state.antiFeedbackEnabled,
                    onCheckedChange = { viewModel.setAntiFeedbackEnabled(it) }
                )

                ToggleRow(
                    title = "Vocal Normalizer & Brickwall Limiter",
                    subtitle = "4:1 dynamic compressor (-18dBFS) + makeup gain + -0.5dBFS anti-clipping limiter.",
                    checked = state.normalizerEnabled,
                    onCheckedChange = { viewModel.setNormalizerEnabled(it) }
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "4. Output 5-Band EQ & Voice Focus",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E5FF)
                    )
                    TextButton(onClick = { viewModel.resetEq() }) {
                        Text("Reset EQ")
                    }
                }

                ToggleRow(
                    title = "Voice Focus Enhancement",
                    subtitle = "Cuts 300Hz speaker box mud (-3.5dB), boosts 2.8kHz vocal clarity (+4.5dB), rolls off >8.5kHz hiss.",
                    checked = state.voiceFocusEnabled,
                    onCheckedChange = { viewModel.setVoiceFocusEnabled(it) }
                )

                val bandLabels = listOf(
                    "100 Hz (Sub / Bass)",
                    "350 Hz (Low-Mid / Warmth)",
                    "1.2 kHz (Midrange / Body)",
                    "3.5 kHz (Vocal Presence)",
                    "8.0 kHz (Air & Crispness)"
                )

                bandLabels.forEachIndexed { idx, label ->
                    val gain = state.eqBandsDb[idx]
                    SliderRow(
                        label = label,
                        valueText = "${if (gain >= 0) "+" else ""}${gain.roundToInt()} dB",
                        value = gain,
                        valueRange = -12f..12f,
                        onValueChange = { viewModel.setEqBandGainDb(idx, it) }
                    )
                }
            }
        }
    }
}

@Composable
fun MeterBar(label: String, rms: Float, barColor: Color) {
    val clamped = (rms * 2.2f).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 12.sp, color = Color.LightGray)
            Text("${(clamped * 100).roundToInt()}%", fontSize = 12.sp, color = Color.LightGray)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color(0xFF0B0E14))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped)
                    .fillMaxHeight()
                    .background(barColor)
            )
        }
    }
}

@Composable
fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Color.Gray, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Color.White, fontSize = 13.sp)
            Text(valueText, color = Color(0xFF00E5FF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

@Composable
fun EndpointDropdown(
    title: String,
    endpoints: List<AudioEndpoint>,
    selectedId: Int,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val current = endpoints.firstOrNull { it.id == selectedId }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 12.sp, color = Color.LightGray)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = current?.let { "${it.name} \u2022 ${it.typeDescription}" } ?: "System Default (ID: 0)",
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("System Default (ID: 0)") },
                    onClick = {
                        onSelect(0)
                        expanded = false
                    }
                )
                endpoints.forEach { ep ->
                    DropdownMenuItem(
                        text = { Text("${ep.name} \u2022 ${ep.typeDescription} (ID: ${ep.id})") },
                        onClick = {
                            onSelect(ep.id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
