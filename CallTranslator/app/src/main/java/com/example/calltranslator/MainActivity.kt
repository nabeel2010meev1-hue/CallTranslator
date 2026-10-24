package com.example.calltranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

/**
 * نسخة أولى (MVP):
 *  - "اسمع الطرف الثاني": يسمع الإنكليزي من الميكروفون (المكالمة على السبيكر) ويعرض ترجمتو بالعربي.
 *  - "اضغط وتكلم": بتحكي عربي، يترجم لإنكليزي ويقراه بصوت عالي ليسمعو الطرف الثاني عبر المكالمة.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tts: TextToSpeech
    private var recognizer: SpeechRecognizer? = null

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var statusView: TextView
    private lateinit var themBtn: Button
    private lateinit var meBtn: Button
    private lateinit var speakArabic: CheckBox

    private var listeningThem = false
    private var talkingMe = false

    private val enToAr = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.ARABIC).build()
    )
    private val arToEn = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ARABIC)
            .setTargetLanguage(TranslateLanguage.ENGLISH).build()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        tts = TextToSpeech(this) { }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        downloadModels()
    }

    private fun downloadModels() {
        statusView.text = "جاري تحميل نماذج الترجمة (مرة وحدة)..."
        enToAr.downloadModelIfNeeded().addOnSuccessListener {
            arToEn.downloadModelIfNeeded().addOnSuccessListener {
                statusView.text = "جاهز ✅"
                themBtn.isEnabled = true
                meBtn.isEnabled = true
            }.addOnFailureListener { statusView.text = "فشل التحميل، تأكد من الإنترنت" }
        }.addOnFailureListener { statusView.text = "فشل التحميل، تأكد من الإنترنت" }
    }

    // ---------- الاستماع ----------

    private fun listen(lang: String, onText: (String) -> Unit, onDone: () -> Unit) {
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) onText(t)
                onDone()
            }
            override fun onError(error: Int) = onDone()
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        }
        r.startListening(intent)
    }

    private fun loopThem() {
        if (!listeningThem || talkingMe) return
        listen("en-US", { en ->
            enToAr.translate(en).addOnSuccessListener { ar ->
                add("👤 $en\n🔤 $ar")
                if (speakArabic.isChecked) speak(ar, Locale("ar"))
            }
        }, {
            handler.postDelayed({ loopThem() }, 300)
        })
    }

    private fun toggleThem() {
        listeningThem = !listeningThem
        themBtn.text = if (listeningThem) "⏹ وقف الاستماع" else "🎧 اسمع الطرف الثاني (إنكليزي ← عربي)"
        if (listeningThem) loopThem() else recognizer?.destroy()
    }

    private fun startMe() {
        talkingMe = true
        meBtn.text = "🎙 عم تحكي... فلّت لما تخلص"
        listen("ar-SA", { ar ->
            arToEn.translate(ar).addOnSuccessListener { en ->
                add("🗣 $ar\n🔤 $en")
                speak(en, Locale.US)
            }
        }, {
            talkingMe = false
            meBtn.text = "🗣 اضغط وتكلم (عربي ← إنكليزي)"
            loopThem()
        })
    }

    private fun stopMe() {
        recognizer?.stopListening()
    }

    private fun speak(text: String, locale: Locale) {
        tts.language = locale
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, System.nanoTime().toString())
    }

    private fun add(line: String) {
        logView.append(line + "\n\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------- الواجهة ----------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            setBackgroundColor(Color.WHITE)
        }
        statusView = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER }
        themBtn = Button(this).apply {
            text = "🎧 اسمع الطرف الثاني (إنكليزي ← عربي)"
            isEnabled = false
            setOnClickListener { toggleThem() }
        }
        speakArabic = CheckBox(this).apply { text = "اقرأ الترجمة العربية بصوت عالي (استخدم سماعة)" }
        logView = TextView(this).apply { textSize = 18f; setPadding(8, 8, 8, 8) }
        scroll = ScrollView(this).apply { addView(logView) }
        meBtn = Button(this).apply {
            text = "🗣 اضغط وتكلم (عربي ← إنكليزي)"
            isEnabled = false
            textSize = 18f
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { startMe(); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { stopMe(); true }
                    else -> false
                }
            }
        }
        root.addView(statusView)
        root.addView(themBtn)
        root.addView(speakArabic)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(meBtn, LinearLayout.LayoutParams(-1, 220))
        setContentView(root)
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts.shutdown()
        enToAr.close()
        arToEn.close()
        super.onDestroy()
    }
}
