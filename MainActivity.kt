
package com.example.aiscriber

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var recognizer: SpeechRecognizer
    private lateinit var recIntent: Intent
    private val engine = ScribeEngine()

    private val askAudioPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startListening() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { startListening() } // auto-restart
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    engine.addLine(text)
                }
                startListening()
            }
        })

        setContent { AppUI(engine, ::startOrAskPerm, ::stopListening) }
    }

    private fun startOrAskPerm() {
        when (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)) {
            PackageManager.PERMISSION_GRANTED -> startListening()
            else -> askAudioPerm.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    private fun startListening() {
        recognizer.stopListening()
        recognizer.startListening(recIntent)
    }
    private fun stopListening() { recognizer.stopListening() }
    override fun onDestroy() {
        recognizer.destroy()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUI(engine: ScribeEngine, onStart: () -> Unit, onStop: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var ui by remember { mutableStateOf(engine.uiState()) }

    // naive refresh tick
    LaunchedEffect(Unit) {
        while (true) {
            ui = engine.uiState()
            kotlinx.coroutines.delay(500)
        }
    }

    Scaffold(topBar = {
        SmallTopAppBar(title = { Text("AI Scriber") },
            actions = {
                TextButton(onClick = {
                    listOf(
                        "I've had fever and body aches since yesterday.",
                        "Any cough or sore throat?",
                        "Yeah I have a cough and some sore throat.",
                        "Any chest pain or shortness of breath?",
                        "No chest pain, just tired."
                    ).forEach { engine.addLine(it) }
                    ui = engine.uiState()
                }) { Text("Demo") }
            })
    }) { padding ->
        Column(Modifier.padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                listOf("Transcript + Follow-ups", "Differentials", "Orders", "SOAP").forEachIndexed { i, t ->
                    Tab(selected = tab==i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> TranscriptFollowups(ui, onStart, onStop)
                1 -> Diffs(ui)
                2 -> Orders(ui)
                3 -> SOAP(ui)
            }
        }
    }
}

@Composable
fun TranscriptFollowups(ui: UiState, onStart: () -> Unit, onStop: () -> Unit) {
    Row(Modifier.fillMaxSize().padding(12.dp)) {
        Column(Modifier.weight(2f).padding(end = 8.dp)) {
            Text("Transcript", style = MaterialTheme.typography.titleMedium)
            Divider(Modifier.padding(vertical = 6.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(ui.transcript.size) { i ->
                    val (speaker, text) = ui.transcript[i]
                    Text("• $speaker: $text", Modifier.padding(vertical = 4.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart) { Text("Start mic") }
                OutlinedButton(onClick = onStop) { Text("Stop mic") }
            }
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text("Follow-ups", style = MaterialTheme.typography.titleMedium)
            Divider(Modifier.padding(vertical = 6.dp))
            if (ui.followups.isEmpty()) {
                Text("No follow-ups yet. Keep talking.")
            } else {
                ui.followups.forEach { f -> Text("• ${f.text}", Modifier.padding(vertical = 4.dp)) }
            }
        }
    }
}

@Composable
fun Diffs(ui: UiState) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Differential (top)", style = MaterialTheme.typography.titleMedium)
        Divider(Modifier.padding(vertical = 6.dp))
        ui.diffs.take(5).forEach { d ->
            Text("${d.label} — ${(d.score*100).toInt()}%")
        }
    }
}

@Composable
fun Orders(ui: UiState) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Suggested labs & imaging", style = MaterialTheme.typography.titleMedium)
        Divider(Modifier.padding(vertical = 6.dp))
        ui.orders.forEach { o -> Text("• ${o.name}") }
    }
}

@Composable
fun SOAP(ui: UiState) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Subjective"); Text(if (ui.soap.S.isBlank()) "—" else ui.soap.S); Spacer(Modifier.height(8.dp))
        Text("Objective"); Text(if (ui.soap.O.isBlank()) "—" else ui.soap.O); Spacer(Modifier.height(8.dp))
        Text("Assessment"); Text(if (ui.soap.A.isBlank()) "—" else ui.soap.A); Spacer(Modifier.height(8.dp))
        Text("Plan"); Text(if (ui.soap.P.isBlank()) "—" else ui.soap.P)
    }
}
