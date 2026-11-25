package com.metin.gozlukgemini

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var answer: TextView
    private lateinit var keyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var intervalInput: EditText
    private lateinit var liveButton: Button

    private var session: DeviceSession? = null
    @Volatile private var latestFrame: VideoFrame? = null
    private var liveJob: Job? = null
    private var lastDescription = ""
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val prefs by lazy { getSharedPreferences("ayarlar", MODE_PRIVATE) }

    private val wearablesPermission =
        registerForActivityResult(Wearables.RequestPermissionContract()) { _ -> startCamera() }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) askQuestion() else setStatus("Mikrofon izni verilmedi")
        }

    private val btPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        tts = TextToSpeech(this) { s ->
            if (s == TextToSpeech.SUCCESS) {
                tts?.language = Locale("tr", "TR")
                ttsReady = true
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)

        lifecycleScope.launch {
            Wearables.registrationState.collect { state -> setStatus("Gözlük kaydı: $state") }
        }
    }

    // ---------------- Arayüz ----------------

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.WHITE)
        }
        fun label(t: String, size: Float = 14f) = TextView(this).apply {
            text = t; textSize = size; setTextColor(Color.DKGRAY); setPadding(0, pad / 2, 0, 0)
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setOnClickListener { onClick() }
        }

        root.addView(TextView(this).apply {
            text = "Gözlük Gemini"; textSize = 24f; setTextColor(Color.BLACK)
        })
        status = label("Hazır")
        root.addView(status)

        root.addView(label("Gemini API anahtarı"))
        keyInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "AIza... (Google AI Studio)"
            setText(prefs.getString("key", ""))
        }
        root.addView(keyInput)

        root.addView(label("Model"))
        modelInput = EditText(this).apply {
            setText(prefs.getString("model", "gemini-2.5-flash-lite"))
        }
        root.addView(modelInput)

        root.addView(label("Kaç saniyede bir anlatsın"))
        intervalInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("interval", 8).toString())
        }
        root.addView(intervalInput)

        root.addView(button("1) Gözlüğü bağla (Meta AI açılır)") {
            saveSettings()
            Wearables.startRegistration(this)
        })
        root.addView(button("2) Kamerayı başlat") { saveSettings(); checkPermissionAndStart() })
        root.addView(button("Gözlükteki uygulama bağlantısını güncelle") {
            lifecycleScope.launch { Wearables.openDATGlassesAppUpdate(this@MainActivity) }
        })
        root.addView(button("Gözlük yazılımını güncelle") {
            lifecycleScope.launch { Wearables.openFirmwareUpdate(this@MainActivity) }
        })
        liveButton = button("▶ Canlı anlatımı başlat") { toggleLive() }
        root.addView(liveButton)
        root.addView(button("🎤 Soru sor") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) askQuestion() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        })
        root.addView(button("Sustur") { tts?.stop() })

        preview = ImageView(this).apply {
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            visibility = View.GONE
        }
        root.addView(preview)

        answer = label("", 18f).apply { setTextColor(Color.BLACK) }
        root.addView(answer)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun setStatus(t: String) = runOnUiThread { status.text = t }

    private fun saveSettings() {
        prefs.edit()
            .putString("key", keyInput.text.toString().trim())
            .putString("model", modelInput.text.toString().trim().ifEmpty { "gemini-2.5-flash-lite" })
            .putInt("interval", intervalInput.text.toString().toIntOrNull()?.coerceIn(3, 120) ?: 8)
            .apply()
    }

    // ---------------- Gözlük kamerası ----------------

    private fun checkPermissionAndStart() {
        lifecycleScope.launch {
            val granted = Wearables.checkPermissionStatus(Permission.CAMERA).fold(
                onSuccess = { it == PermissionStatus.Granted },
                onFailure = { _, _ -> false },
            )
            if (granted) startCamera() else wearablesPermission.launch(Permission.CAMERA)
        }
    }

    private fun startCamera() {
        if (session != null) { setStatus("Kamera zaten açık"); return }
        setStatus("Gözlüğe bağlanılıyor…")
        Wearables.createSession(AutoDeviceSelector()).fold(
            onSuccess = { s ->
                session = s
                lifecycleScope.launch {
                    s.errors.collect { e ->
                        val needsUpdate = e.toString().contains("UPDATE", ignoreCase = true) ||
                            e.description.contains("update", ignoreCase = true)
                        if (needsUpdate) {
                            setStatus("Gözlükteki uygulama bağlantısının güncellenmesi gerekiyor. " +
                                "Meta AI'da güncelleme sayfası açılıyor; güncelleme bitince 2) Kamerayı başlat'a tekrar bas.")
                            session?.stop(); session = null
                            lifecycleScope.launch { Wearables.openDATGlassesAppUpdate(this@MainActivity) }
                        } else {
                            setStatus("Bağlantı hatası: ${e.description}")
                        }
                    }
                }
                lifecycleScope.launch {
                    s.state.collect { st ->
                        when (st) {
                            DeviceSessionState.STARTED -> attachCamera(s)
                            DeviceSessionState.STOPPED -> {
                                session = null; latestFrame = null
                                setStatus("Gözlük bağlantısı kapandı")
                            }
                            else -> setStatus("Gözlük: $st")
                        }
                    }
                }
                s.start()
            },
            onFailure = { e, _ -> setStatus("Oturum açılamadı: ${e.description}") },
        )
    }

    private fun attachCamera(s: DeviceSession) {
        s.addCamera(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 7)).fold(
            onSuccess = { camera ->
                camera.stream.start().onFailure { e, _ -> setStatus("Kamera başlamadı: ${e.description}") }
                setStatus("Kamera açık ✔ Canlı anlatımı başlatabilirsin")
                lifecycleScope.launch {
                    var lastPreview = 0L
                    camera.stream.videoStream.collect { frame ->
                        latestFrame = frame
                        val now = System.currentTimeMillis()
                        if (now - lastPreview > 1000) {
                            lastPreview = now
                            showPreview(frame)
                        }
                    }
                }
            },
            onFailure = { e, _ -> setStatus("Kamera eklenemedi: ${e.description}") },
        )
    }

    private fun frameToJpeg(f: VideoFrame): ByteArray? =
        if (f.isCompressed) null else FrameUtil.i420ToJpeg(f.buffer, f.width, f.height)

    private fun showPreview(f: VideoFrame) {
        val jpeg = frameToJpeg(f) ?: return
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        runOnUiThread { preview.visibility = View.VISIBLE; preview.setImageBitmap(bmp) }
    }

    // ---------------- Gemini ----------------

    private fun gemini(): GeminiClient? {
        val key = prefs.getString("key", "").orEmpty()
        if (key.isBlank()) { setStatus("Önce Gemini API anahtarını yaz"); return null }
        return GeminiClient(key, prefs.getString("model", "gemini-2.5-flash-lite").orEmpty())
    }

    private fun toggleLive() {
        if (liveJob != null) {
            liveJob?.cancel(); liveJob = null
            liveButton.text = "▶ Canlı anlatımı başlat"
            setStatus("Canlı anlatım durdu")
            return
        }
        saveSettings()
        val client = gemini() ?: return
        if (session == null) { setStatus("Önce kamerayı başlat"); return }
        liveButton.text = "⏸ Canlı anlatımı durdur"
        lastDescription = ""
        liveJob = lifecycleScope.launch {
            val interval = prefs.getInt("interval", 8) * 1000L
            while (isActive) {
                val frame = latestFrame
                val jpeg = frame?.let { frameToJpeg(it) }
                if (jpeg == null) {
                    setStatus("Görüntü bekleniyor…"); delay(1500); continue
                }
                val started = System.currentTimeMillis()
                try {
                    setStatus("Gemini bakıyor…")
                    val prompt = if (lastDescription.isEmpty()) {
                        "Şu an ne görüyorsun? Bana anlat."
                    } else {
                        "Az önce şunu söyledin: \"$lastDescription\". Şimdi ne görüyorsun? " +
                            "Kayda değer bir değişiklik yoksa sadece AYNI yaz."
                    }
                    val reply = client.describe(jpeg, prompt, LIVE_SYSTEM)
                    if (reply.isNotBlank() && !reply.trim().uppercase(Locale("tr")).startsWith("AYNI")) {
                        lastDescription = reply
                        runOnUiThread { answer.text = reply }
                        setStatus("Anlatıyor…")
                        speak(reply)
                    }
                    setStatus("Canlı anlatım açık")
                } catch (e: GeminiClient.GeminiException) {
                    if (e.code == 429) {
                        setStatus("Gemini ücretsiz sınırına takıldı, 30 sn bekliyorum")
                        delay(30_000)
                    } else {
                        setStatus("Gemini hatası (${e.code}): ${e.message}")
                        delay(5_000)
                    }
                } catch (e: Exception) {
                    setStatus("Hata: ${e.message}")
                    delay(5_000)
                }
                val wait = interval - (System.currentTimeMillis() - started)
                if (wait > 0) delay(wait)
            }
        }
    }

    private fun askQuestion() {
        saveSettings()
        val client = gemini() ?: return
        lifecycleScope.launch {
            setStatus("Dinliyorum…")
            val q = listen()
            if (q.isNullOrBlank()) { setStatus("Anlayamadım, tekrar dene"); return@launch }
            answer.text = "Sen: $q"
            val jpeg = latestFrame?.let { frameToJpeg(it) }
            try {
                setStatus(if (jpeg != null) "Gemini bakıyor…" else "Gemini düşünüyor (görüntü yok)…")
                val reply = client.describe(jpeg, q, ASK_SYSTEM)
                answer.text = "Sen: $q\n\n$reply"
                setStatus("Anlatıyor…")
                speak(reply)
                setStatus("Hazır")
            } catch (e: Exception) {
                setStatus("Hata: ${e.message}")
            }
        }
    }

    // ---------------- Ses ----------------

    private suspend fun speak(text: String) {
        val engine = tts ?: return
        if (!ttsReady || text.isBlank()) return
        suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
            })
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            cont.invokeOnCancellation { engine.stop() }
        }
    }

    private suspend fun listen(): String? = suspendCancellableCoroutine { cont ->
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { cont.resume(null); return@suspendCancellableCoroutine }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        fun finish(v: String?) { r.destroy(); if (cont.isActive) cont.resume(v) }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) =
                finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            override fun onError(error: Int) = finish(null)
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
        })
        cont.invokeOnCancellation { r.destroy() }
    }

    override fun onDestroy() {
        liveJob?.cancel()
        session?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val LIVE_SYSTEM =
            "Sen Metin'in Meta Ray-Ban gözlüğünden dünyayı görüyorsun. Görüntü onun o an baktığı şey. " +
                "Türkçe, doğal ve çok kısa konuş (en fazla 2 cümle); cevabın sesli okunacak, " +
                "madde işareti, emoji ve markdown kullanma. Görmediğin şeyi uydurma."
        private const val ASK_SYSTEM =
            "Sen Metin'in Meta Ray-Ban gözlüğünden dünyayı görüyorsun. Görüntü onun o an baktığı şey. " +
                "Sorusunu Türkçe, doğal ve kısa (en fazla 4 cümle) cevapla; cevap sesli okunacak, " +
                "madde işareti, emoji ve markdown kullanma. Emin değilsen söyle."
    }
}
