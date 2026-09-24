package com.example.winzeraiassistant

/*
 * ---------------------------------------------------------------
 *  AndroidManifest.xml  -- add these BEFORE <application>:
 * ---------------------------------------------------------------
 *  <uses-permission android:name="android.permission.RECORD_AUDIO" />
 *  <uses-permission android:name="com.android.alarm.permission.SET_ALARM" />
 *  <uses-permission android:name="android.permission.INTERNET" />
 *
 *  <queries>
 *      <!-- lets Winzer see launchable apps (for "open YouTube") -->
 *      <intent>
 *          <action android:name="android.intent.action.MAIN" />
 *          <category android:name="android.intent.category.LAUNCHER" />
 *      </intent>
 *      <!-- required for speech recognition on Android 11+ -->
 *      <intent>
 *          <action android:name="android.speech.RecognitionService" />
 *      </intent>
 *      <!-- required for text-to-speech on Android 11+ -->
 *      <intent>
 *          <action android:name="android.intent.action.TTS_SERVICE" />
 *      </intent>
 *  </queries>
 * ---------------------------------------------------------------
 */

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null

    private var statusText by mutableStateOf("Ready")
    private var heard by mutableStateOf("")
    private var reply by mutableStateOf("")
    private var isListening by mutableStateOf(false)
    private var banglaMode by mutableStateOf(false)
    private var apiKey by mutableStateOf("")

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else statusText = "Microphone permission is needed"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        apiKey = getSharedPreferences("winzer_prefs", MODE_PRIVATE)
            .getString("api_key", "") ?: ""

        setContent {
            WinzerAssistant(
                status = statusText,
                heard = heard,
                reply = reply,
                isListening = isListening,
                banglaMode = banglaMode,
                apiKey = apiKey,
                onTalk = { onTalkClicked() },
                onToggleLanguage = { banglaMode = !banglaMode },
                onKeyChange = { key ->
                    apiKey = key
                    getSharedPreferences("winzer_prefs", MODE_PRIVATE)
                        .edit().putString("api_key", key).apply()
                },
                onOpenAccess = {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            )
        }
    }

    // ------------------------------------------------------------
    //  Voice output
    // ------------------------------------------------------------
    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        val locale = if (banglaMode) Locale("bn", "BD") else Locale.US
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.setLanguage(Locale.US)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "winzer_reply")
    }

    // ------------------------------------------------------------
    //  Voice input
    // ------------------------------------------------------------
    private fun onTalkClicked() {
        if (isListening) {
            recognizer?.stopListening()
            isListening = false
            statusText = "Ready"
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startListening() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusText = "Speech recognition is not available on this device"
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { statusText = "Listening…" }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { statusText = "Thinking…" }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    isListening = false
                    statusText = "Didn't catch that. Tap and try again."
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (text.isNullOrBlank()) {
                        statusText = "Didn't catch that. Tap and try again."
                        return
                    }
                    heard = text
                    if (apiKey.isBlank()) {
                        finishWith(
                            handleCommand(text)
                                ?: "I heard \"$text\", but I don't know that command yet. " +
                                "Add your API key below to turn on the AI brain."
                        )
                    } else {
                        statusText = "Thinking…"
                        askAi(text)
                    }
                }
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (banglaMode) "bn-BD" else "en-US")
        }
        isListening = true
        statusText = "Starting…"
        recognizer?.startListening(intent)
    }

    // ------------------------------------------------------------
    //  Command executor  (Phase 4 will replace the keyword matching
    //  with an AI model that picks one of these actions)
    // ------------------------------------------------------------
    private fun has(t: String, vararg keys: String) = keys.any { t.contains(it) }

    private fun findNumber(s: String): MatchResult? =
        Regex("\\+?\\d[\\d\\s-]{5,}\\d").find(s)

    private fun handleCommand(raw: String): String? {
        val t = raw.lowercase(Locale.ROOT).trim()
        return try {
            when {
                has(t, "flashlight", "torch", "টর্চ", "ফ্ল্যাশ") ->
                    setTorch(!has(t, "off", "বন্ধ"))
                has(t, "volume", "ভলিউম") ->
                    adjustVolume(has(t, "up", "increase", "raise", "বাড়া", "বেশি"))
                has(t, "timer", "টাইমার") -> setTimer(t)
                has(t, "alarm", "অ্যালার্ম", "এলার্ম") -> setAlarm(t)
                has(t, "telegram", "টেলিগ্রাম", "who messaged", "inbox", "ইনবক্স", "কে মেসেজ") ||
                    t.startsWith("reply ") || t.startsWith("রিপ্লাই ") -> telegramCommand(raw, t)
                has(t, "notification access") ->
                    start(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                        "Opening notification access. Turn on Winzer."
                    )
                has(t, "call", "কল") -> dial(raw)
                has(t, "sms", "message", "মেসেজ") && findNumber(raw) != null -> composeSms(raw)
                has(t, "camera", "ক্যামেরা") ->
                    start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Opening the camera")
                has(t, "wifi", "wi-fi", "ওয়াইফাই") ->
                    start(Intent(Settings.ACTION_WIFI_SETTINGS), "Opening Wi-Fi settings")
                has(t, "bluetooth", "ব্লুটুথ") ->
                    start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Opening Bluetooth settings")
                has(t, "navigate", "directions", "map", "নেভিগেট", "ম্যাপ") -> openMap(t)
                has(t, "what time", "the time", "সময়") ->
                    "It's " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                else -> openOrSearch(raw, t)
            }
        } catch (e: Exception) {
            "Sorry, I couldn't do that. ${e.message ?: ""}".trim()
        }
    }

    // ------------------------------------------------------------
    //  Telegram inbox: read who messaged, reply, share
    // ------------------------------------------------------------
    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun shareToTelegram(raw: String): String {
        val text = Regex("(?i)(?:share|শেয়ার)\\s+(.+?)(?:\\s+(?:on|to|via|in)\\s+telegram)?\\s*$")
            .find(raw)?.groupValues?.get(1)?.trim()
        if (text.isNullOrBlank()) return "What should I share?"
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        val installed = WinzerBridge.TELEGRAM_PACKAGES.firstOrNull { isInstalled(it) }
        if (installed != null) {
            send.setPackage(installed)
            startActivity(send)
        } else {
            startActivity(Intent.createChooser(send, "Share"))
        }
        return "Pick who to send it to in Telegram."
    }

    private fun telegramCommand(raw: String, t: String): String {
        // "reply to Rahim ok I'm coming"  or  "telegram reply to Rahim ok I'm coming"
        val replyText = Regex("(?i)^\\s*(?:telegram\\s+|টেলিগ্রাম\\s+)?(?:reply|রিপ্লাই)\\s+(.+)$")
            .find(raw)?.groupValues?.get(1)?.trim()
        if (!replyText.isNullOrBlank()) {
            return WinzerBridge.replyTo(applicationContext, replyText)
        }

        return when {
            has(t, "share", "শেয়ার") -> shareToTelegram(raw)
            has(t, "clear", "মুছে") -> WinzerBridge.clear()
            has(t, "read", "latest", "who messaged", "inbox", "পড়ো", "ইনবক্স", "কে মেসেজ") ->
                WinzerBridge.readInbox()
            has(t, "access", "permission", "setup") ->
                start(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                    "Opening notification access. Turn on Winzer."
                )
            else ->
                "Say: telegram read, to hear who messaged you. " +
                    "Then say: reply to, the name, and your message."
        }
    }

    private fun start(intent: Intent, message: String): String {
        startActivity(intent)
        return message
    }

    private fun setTorch(on: Boolean): String {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "This phone has no flashlight."
        cm.setTorchMode(id, on)
        return if (on) "Flashlight on" else "Flashlight off"
    }

    private fun adjustVolume(up: Boolean): String {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
        return if (up) "Volume up" else "Volume down"
    }

    private fun setTimer(t: String): String {
        val n = Regex("\\d+").find(t)?.value?.toIntOrNull() ?: return "For how long?"
        val seconds = when {
            has(t, "second") -> n
            has(t, "hour") -> n * 3600
            else -> n * 60
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return start(intent, "Timer started")
    }

    private fun setAlarm(t: String): String {
        val m = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?").find(t)
            ?: return "What time should I set the alarm for?"
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        val meridiem = m.groupValues[3]
        if (meridiem.startsWith("p") && hour < 12) hour += 12
        if (meridiem.startsWith("a") && hour == 12) hour = 0
        if (hour > 23 || minute > 59) return "That time doesn't look right. Try again."
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return start(intent, "Alarm set for %02d:%02d".format(hour, minute))
    }

    // Opens the dialer with the number filled in; you tap the call button.
    private fun dial(raw: String): String {
        val m = findNumber(raw) ?: return "Tell me the number too, for example: call 0171 234 5678."
        val number = m.value.filter { it.isDigit() || it == '+' }
        return start(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")),
            "Dialing $number. Tap the call button to confirm."
        )
    }

    // Opens the SMS composer with the text filled in; you tap send.
    private fun composeSms(raw: String): String {
        val m = findNumber(raw) ?: return "Tell me the number too."
        val number = m.value.filter { it.isDigit() || it == '+' }
        val body = raw.substring(m.range.last + 1).trim()
            .removePrefix("saying").removePrefix("that").trim()
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number"))
            .putExtra("sms_body", body)
        return start(intent, "Message ready for $number. Tap send to confirm.")
    }

    private fun openMap(t: String): String {
        val place = t.replace(Regex("^(navigate to|directions to|map of|show me|map)\\s*"), "").trim()
        if (place.isEmpty()) return "Where do you want to go?"
        return start(
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))),
            "Showing $place on the map"
        )
    }

    private fun openOrSearch(raw: String, t: String): String? {
        Regex("^(?:open|launch|start)\\s+(.+)$").find(t)?.let {
            return openApp(it.groupValues[1].trim())
        }
        Regex("^(.+?)\\s*(?:খোলো|খুলো|ওপেন করো|ওপেন কর)$").find(t)?.let {
            return openApp(it.groupValues[1].trim())
        }
        Regex("^(?:search for|search|google|look up|সার্চ করো)\\s+(.+)$").find(t)?.let {
            val q = it.groupValues[1].trim()
            return start(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))),
                "Searching for $q"
            )
        }
        return null
    }

    private fun openApp(name: String): String {
        val query = APP_ALIASES[name] ?: name
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(launcher, 0).firstOrNull {
            it.loadLabel(pm).toString().lowercase(Locale.ROOT).contains(query)
        } ?: return "I couldn't find an app called $name."
        val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "I couldn't open ${match.loadLabel(pm)}."
        startActivity(launch)
        return "Opening ${match.loadLabel(pm)}"
    }

    // ------------------------------------------------------------
    //  Phase 4: AI brain
    //  Claude turns anything you say into either one of the phone
    //  commands above, or a spoken answer.
    // ------------------------------------------------------------
    private fun finishWith(answer: String) {
        reply = answer
        statusText = "Ready"
        speak(answer)
    }

    private fun askAi(userText: String) {
        val key = apiKey
        val systemPrompt = """
            You are Winzer, a voice assistant running on the user's Android phone.
            Reply with ONLY a JSON object: {"command": <string or null>, "say": <string>}.
            If the user wants a phone action, set "command" to exactly ONE of these forms:
            open <app name> | open camera | flashlight on | flashlight off | volume up | volume down |
            set timer for <n> minutes (or seconds/hours) | set alarm for <H:MM> am (or pm) |
            call <digits> | message <digits> <text> | wifi settings | bluetooth settings |
            navigate to <place> | search for <query> | what time is it |
            telegram read | reply to <name> <text> | telegram clear | telegram share <text> |
            notification access
            Otherwise set "command" to null and put your answer in "say".
            "say" is spoken aloud: keep it under 25 words, plain text, in the user's language.
        """.trimIndent()

        Thread {
            try {
                val body = JSONObject()
                    .put("model", "claude-sonnet-5")
                    .put("max_tokens", 300)
                    .put("system", systemPrompt)
                    .put(
                        "messages",
                        JSONArray().put(
                            JSONObject().put("role", "user").put("content", userText)
                        )
                    )

                val conn = (URL("https://api.anthropic.com/v1/messages").openConnection()
                    as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 30000
                    doOutput = true
                    setRequestProperty("content-type", "application/json")
                    setRequestProperty("x-api-key", key)
                    setRequestProperty("anthropic-version", "2023-06-01")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val responseText = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code == 401) throw Exception("Your API key was rejected.")
                if (code !in 200..299) throw Exception("AI error $code")

                val modelText = JSONObject(responseText)
                    .getJSONArray("content").getJSONObject(0).getString("text")

                var say = modelText.trim()
                var command = ""
                val start = modelText.indexOf('{')
                val end = modelText.lastIndexOf('}')
                if (start >= 0 && end > start) {
                    val json = JSONObject(modelText.substring(start, end + 1))
                    say = json.optString("say", "")
                    command = if (json.isNull("command")) "" else json.optString("command", "")
                }

                val finalSay = say
                val finalCommand = command
                runOnUiThread {
                    val done = if (finalCommand.isNotBlank()) handleCommand(finalCommand) else null
                    val spoken = if (finalCommand.startsWith("telegram") || finalCommand.startsWith("reply")) {
                        done ?: finalSay
                    } else {
                        finalSay.ifBlank { done ?: "Done" }
                    }
                    finishWith(spoken)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    finishWith(
                        handleCommand(userText)
                            ?: "Sorry, the AI couldn't answer. ${e.message ?: ""}".trim()
                    )
                }
            }
        }.start()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        // Bangla app names -> the English label Android shows
        private val APP_ALIASES = mapOf(
            "ইউটিউব" to "youtube",
            "ফেসবুক" to "facebook",
            "হোয়াটসঅ্যাপ" to "whatsapp",
            "টেলিগ্রাম" to "telegram",
            "মেসেঞ্জার" to "messenger",
            "ইনস্টাগ্রাম" to "instagram",
            "ক্রোম" to "chrome",
            "ক্যালকুলেটর" to "calculator",
            "গ্যালারি" to "gallery",
            "সেটিংস" to "settings"
        )
    }
}

@Composable
fun WinzerAssistant(
    status: String,
    heard: String,
    reply: String,
    isListening: Boolean,
    banglaMode: Boolean,
    apiKey: String,
    onTalk: () -> Unit,
    onToggleLanguage: () -> Unit,
    onKeyChange: (String) -> Unit,
    onOpenAccess: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        Text(
            text = "WINZER",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(text = "Your AI Voice Assistant")

        Spacer(modifier = Modifier.height(32.dp))

        Text(text = status, style = MaterialTheme.typography.bodyMedium)

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onTalk) {
            Text(if (isListening) "⏹ Listening… (tap to stop)" else "🎙 Talk to Winzer")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(onClick = onToggleLanguage) {
            Text(if (banglaMode) "ভাষা: বাংলা" else "Language: English")
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = apiKey,
            onValueChange = onKeyChange,
            label = { Text("Anthropic API key (turns on the AI brain)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(onClick = onOpenAccess) {
            Text("Open notification access")
        }

        if (heard.isNotBlank()) {
            Spacer(modifier = Modifier.height(24.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("You: $heard")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Winzer: $reply")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Try: \"open YouTube\", \"flashlight on\", \"set a timer for 5 minutes\", " +
                "\"call 0171 234 5678\", \"search for weather\"",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
    }
}
