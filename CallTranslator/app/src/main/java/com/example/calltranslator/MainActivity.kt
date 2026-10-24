package com.example.calltranslator

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

class MainActivity : Activity() {

    // ---------- الألوان ----------
    private val BG = Color.parseColor("#0B0F1A")
    private val CARD = Color.parseColor("#151B2B")
    private val CARD2 = Color.parseColor("#1E2640")
    private val ACCENT1 = Color.parseColor("#6C5CE7")
    private val ACCENT2 = Color.parseColor("#00D2FF")
    private val TEXT2 = Color.parseColor("#9AA4BF")
    private val GREEN = Color.parseColor("#2ED573")
    private val AMBER = Color.parseColor("#FFA502")

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var tts: TextToSpeech
    private var recognizer: SpeechRecognizer? = null
    private val translators = HashMap<String, Translator>()

    private var myLang = "ar"
    private var theirLang = "en"
    private var ready = false
    private var listeningThem = false
    private var talkingMe = false

    // عناصر الواجهة
    private lateinit var pageHost: FrameLayout
    private lateinit var pages: List<View>
    private lateinit var navItems: List<TextView>
    private lateinit var statusChip: TextView
    private lateinit var myChip: TextView
    private lateinit var theirChip: TextView
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var emptyView: TextView
    private lateinit var themBtn: TextView
    private lateinit var talkBtn: TextView

    private val talkIdle = "🎙\nاضغط وتكلم"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        myLang = prefs.getString("my", "ar") ?: "ar"
        theirLang = prefs.getString("their", "en") ?: "en"
        if (myLang == theirLang) theirLang = if (myLang == "en") "ar" else "en"

        window.statusBarColor = BG
        window.navigationBarColor = CARD

        tts = TextToSpeech(this) { }
        buildUi()
        refreshChips()
        refreshThemBtn()

        if (!hasMic()) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        prepareModels()
    }

    // ---------- الترجمة والنماذج ----------

    private fun translator(src: String, tgt: String): Translator =
        translators.getOrPut("$src-$tgt") {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(tgt).build()
            )
        }

    private fun prepareModels() {
        ready = false
        updateButtons()
        setStatus("جاري تحميل نماذج الترجمة...", false)
        val m = myLang
        val t = theirLang
        translator(m, t).downloadModelIfNeeded().addOnSuccessListener {
            translator(t, m).downloadModelIfNeeded().addOnSuccessListener {
                if (m == myLang && t == theirLang) {
                    ready = true
                    setStatus("جاهز", true)
                    updateButtons()
                }
            }.addOnFailureListener { setStatus("فشل التحميل، تأكد من الإنترنت", false) }
        }.addOnFailureListener { setStatus("فشل التحميل، تأكد من الإنترنت", false) }
    }

    // ---------- الاستماع ----------

    private fun hasMic() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun listen(lang: String, onText: (String) -> Unit, onDone: (Int) -> Unit) {
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) onText(t)
                onDone(0)
            }
            override fun onError(error: Int) = onDone(error)
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.forLanguageTag(lang).toLanguageTag())
        }
        r.startListening(intent)
    }

    private fun loopThem() {
        if (!listeningThem || talkingMe || !ready) return
        setStatus("عم يسمع الطرف الثاني...", true)
        listen(theirLang, { heard ->
            translator(theirLang, myLang).translate(heard).addOnSuccessListener { out ->
                addBubble(heard, out, false)
                if (prefs.getBoolean("speakThem", false)) speak(out, myLang)
            }
        }, { err ->
            if (err != 0 && err != SpeechRecognizer.ERROR_NO_MATCH && err != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                setStatus("مشكلة بالميكروفون (رمز $err)", false)
                handler.postDelayed({ loopThem() }, 1500)
            } else {
                handler.postDelayed({ loopThem() }, 200)
            }
        })
    }

    private fun toggleThem() {
        if (!ready) return
        if (!hasMic()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        listeningThem = !listeningThem
        refreshThemBtn()
        if (listeningThem) loopThem() else {
            recognizer?.destroy()
            setStatus("جاهز", true)
        }
    }

    private fun startMe() {
        if (!ready) {
            Toast.makeText(this, "استنى لحد ما تخلص النماذج", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasMic()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        talkingMe = true
        talkBtn.text = "🎙\nعم يسمعك..."
        talkBtn.animate().scaleX(1.12f).scaleY(1.12f).setDuration(150).start()
        setStatus("احكي هلأ...", true)
        listen(myLang, { heard ->
            translator(myLang, theirLang).translate(heard).addOnSuccessListener { out ->
                addBubble(heard, out, true)
                if (prefs.getBoolean("speakMe", true)) speak(out, theirLang)
            }
        }, { err ->
            talkingMe = false
            talkBtn.text = talkIdle
            talkBtn.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            if (err != 0 && err != SpeechRecognizer.ERROR_NO_MATCH && err != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                setStatus("مشكلة بالميكروفون (رمز $err)", false)
            } else {
                setStatus("جاهز", true)
            }
            loopThem()
        })
    }

    private fun stopMe() {
        recognizer?.stopListening()
    }

    private fun speak(text: String, lang: String) {
        val res = tts.setLanguage(Locale.forLanguageTag(lang))
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            setStatus("صوت هاي اللغة غير متوفر على جهازك", false)
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, System.nanoTime().toString())
    }

    // ---------- اللغات ----------

    private fun langShort(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.forLanguageTag("ar")).ifBlank { code }

    private fun langFull(code: String): String {
        val loc = Locale.forLanguageTag(code)
        val ar = langShort(code)
        val native = loc.getDisplayLanguage(loc)
        return if (native.isNotBlank() && native != ar) "$ar  ·  $native" else ar
    }

    private fun pickLanguage(forMe: Boolean) {
        val codes = TranslateLanguage.getAllLanguages().sortedBy { langShort(it) }
        val names = codes.map { langFull(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (forMe) "لغتي" else "لغة الطرف الثاني")
            .setItems(names) { _, i -> setLang(forMe, codes[i]) }
            .show()
    }

    private fun setLang(forMe: Boolean, code: String) {
        if (forMe) {
            if (code == theirLang) theirLang = myLang
            myLang = code
        } else {
            if (code == myLang) myLang = theirLang
            theirLang = code
        }
        afterLangChange()
    }

    private fun swapLangs() {
        val tmp = myLang
        myLang = theirLang
        theirLang = tmp
        afterLangChange()
    }

    private fun afterLangChange() {
        prefs.edit().putString("my", myLang).putString("their", theirLang).apply()
        refreshChips()
        prepareModels()
    }

    private fun refreshChips() {
        myChip.text = chipText("لغتي", langShort(myLang))
        theirChip.text = chipText("الطرف الثاني", langShort(theirLang))
    }

    private fun chipText(title: String, name: String): CharSequence {
        val s = SpannableString("$title\n$name")
        s.setSpan(RelativeSizeSpan(0.7f), 0, title.length, 0)
        s.setSpan(ForegroundColorSpan(TEXT2), 0, title.length, 0)
        return s
    }

    // ---------- تحديث الواجهة ----------

    private fun setStatus(text: String, ok: Boolean) {
        statusChip.text = "● $text"
        statusChip.setTextColor(if (ok) GREEN else AMBER)
    }

    private fun updateButtons() {
        val a = if (ready) 1f else 0.4f
        themBtn.alpha = a
        talkBtn.alpha = a
    }

    private fun refreshThemBtn() {
        themBtn.text = if (listeningThem) "⏹  إيقاف الاستماع" else "🎧  اسمع الطرف الثاني"
        themBtn.background = if (listeningThem) gradient(ACCENT1, ACCENT2, 20) else rounded(CARD2, 20)
    }

    private fun addBubble(original: String, translated: String, fromMe: Boolean) {
        if (emptyView.parent != null) chat.removeView(emptyView)
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = if (fromMe) gradient(ACCENT1, ACCENT2, 18) else rounded(CARD, 18)
        }
        bubble.addView(label(translated, 18f, Color.WHITE, true))
        bubble.addView(label(original, 13f, Color.argb(170, 255, 255, 255)))
        val lp = LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(8)
            gravity = if (fromMe) Gravity.START else Gravity.END
        }
        chat.addView(bubble, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------- بناء الواجهة ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    private fun gradient(c1: Int, c2: Int, r: Int, oval: Boolean = false) =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(c1, c2)).apply {
            if (oval) shape = GradientDrawable.OVAL else cornerRadius = dp(r).toFloat()
        }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun LinearLayout.add(v: View, top: Int = 0, w: Int = -1, h: Int = -2) {
        addView(v, LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) })
    }

    private fun chip(onClick: () -> Unit) =
        label("", 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(CARD2, 16)
            setOnClickListener { onClick() }
        }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        pageHost = FrameLayout(this)
        pages = listOf(buildTranslatorPage(), buildSettingsPage(), buildGuidePage())
        pages.forEach { pageHost.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        root.addView(pageHost, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(buildNav(), LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        selectPage(0)
    }

    private fun buildNav(): View {
        val nav = LinearLayout(this).apply {
            setBackgroundColor(CARD)
            setPadding(0, dp(8), 0, dp(8))
        }
        val items = listOf("🌐\nالترجمة", "⚙️\nالإعدادات", "📖\nالدليل")
        navItems = items.mapIndexed { i, t ->
            label(t, 12f, TEXT2, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener { selectPage(i) }
            }
        }
        navItems.forEach { nav.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        return nav
    }

    private fun selectPage(i: Int) {
        pages.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        navItems.forEachIndexed { idx, t -> t.setTextColor(if (idx == i) ACCENT2 else TEXT2) }
    }

    private fun buildTranslatorPage(): View {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(12))
        }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("مترجم المكالمات", 24f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        statusChip = label("", 12f, TEXT2, true).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(CARD, 20)
        }
        header.addView(statusChip)
        p.add(header)

        val langBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        myChip = chip { pickLanguage(true) }
        theirChip = chip { pickLanguage(false) }
        val swap = label("⇄", 20f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = gradient(ACCENT1, ACCENT2, 0, true)
            setOnClickListener { swapLangs() }
        }
        langBar.addView(myChip, LinearLayout.LayoutParams(0, dp(60), 1f))
        langBar.addView(swap, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            marginStart = dp(10); marginEnd = dp(10)
        })
        langBar.addView(theirChip, LinearLayout.LayoutParams(0, dp(60), 1f))
        p.add(langBar, top = 16)

        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        emptyView = label("اضغط 🎙 وتكلم،\nأو شغّل الاستماع للطرف الثاني", 15f, TEXT2).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(60), dp(20), dp(20))
        }
        chat.addView(emptyView, LinearLayout.LayoutParams(-1, -2))
        scroll = ScrollView(this).apply {
            addView(chat)
            isVerticalScrollBarEnabled = false
        }
        p.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })

        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        talkBtn = label(talkIdle, 12f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = gradient(ACCENT1, ACCENT2, 0, true)
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { startMe(); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { stopMe(); true }
                    else -> false
                }
            }
        }
        themBtn = label("", 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { toggleThem() }
        }
        controls.addView(talkBtn, LinearLayout.LayoutParams(dp(100), dp(100)).apply { marginEnd = dp(14) })
        controls.addView(themBtn, LinearLayout.LayoutParams(0, dp(72), 1f))
        p.add(controls, top = 12)
        return p
    }

    private fun switchRow(title: String, sub: String, key: String, def: Boolean): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(CARD, 16)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(title, 16f, Color.WHITE, true))
        col.addView(label(sub, 13f, TEXT2))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(this).apply {
            isChecked = prefs.getBoolean(key, def)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(key, c).apply() }
        }
        row.addView(sw)
        return row
    }

    private fun buildSettingsPage(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(20))
        }
        col.add(label("الإعدادات", 24f, Color.WHITE, true))
        col.add(
            switchRow(
                "قراءة ترجمة الطرف الثاني بصوت عالي",
                "فعّلها إذا بتستخدم سماعة. بدونها بتشوف الترجمة مكتوبة بس.",
                "speakThem", false
            ), top = 16
        )
        col.add(
            switchRow(
                "قراءة ترجمتي للطرف الثاني",
                "بتنقرأ ترجمة كلامك بصوت عالي ليسمعها الطرف الثاني.",
                "speakMe", true
            ), top = 12
        )
        val clear = label("🗑  مسح المحادثة", 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
            background = rounded(CARD2, 16)
            setOnClickListener {
                chat.removeAllViews()
                chat.addView(emptyView, LinearLayout.LayoutParams(-1, -2))
                selectPage(0)
            }
        }
        col.add(clear, top = 12)
        col.add(label("الإصدار 0.2", 12f, TEXT2).apply { gravity = Gravity.CENTER }, top = 24)
        return ScrollView(this).apply { addView(col) }
    }

    private fun card(title: String, body: String): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(CARD, 16)
        }
        c.addView(label(title, 17f, Color.WHITE, true))
        c.add(label(body, 14f, TEXT2).apply { setLineSpacing(0f, 1.2f) }, top = 8)
        return c
    }

    private fun buildGuidePage(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(20))
        }
        col.add(label("دليل الاستخدام", 24f, Color.WHITE, true))
        col.add(
            card(
                "🎯 مع واتساب أو ديسكورد",
                "١. ابدأ المكالمة وشغّل السبيكر.\n" +
                        "٢. افتح التطبيق واختار لغتك ولغة الطرف الثاني.\n" +
                        "٣. اضغط «اسمع الطرف الثاني»، وبتطلع ترجمة كلامو مكتوبة.\n" +
                        "٤. لما بدك تحكي، اضغط مطولاً على 🎙 وتكلم بلغتك، وفلّت لما تخلص."
            ), top = 16
        )
        col.add(
            card(
                "💡 نصيحة لنتيجة أفضل",
                "خلّي المكالمة على جهاز تاني (كمبيوتر أو موبايل ثاني) والتطبيق على موبايلك. " +
                        "أندرويد غالباً بيمنع أكتر من تطبيق يستخدم الميكروفون بنفس الوقت، " +
                        "فإذا كانت المكالمة والتطبيق على نفس الجهاز ممكن التطبيق ما يسمع."
            ), top = 12
        )
        col.add(
            card(
                "🌍 اللغات",
                "بيدعم أكتر من ٥٠ لغة. الترجمة بتشتغل بدون إنترنت بعد تحميل نموذج اللغة مرة وحدة، " +
                        "بس الاستماع والنطق بيعتمدوا على خدمات جوجل بجهازك، فبعض اللغات ممكن ما تتوفر."
            ), top = 12
        )
        col.add(
            card(
                "⚠️ بصراحة",
                "ما في ربط مباشر بصوت المكالمة، لأنو واتساب وديسكورد وأندرويد بيمنعوا التطبيقات من التقاط أو إدخال صوت المكالمات. " +
                        "التطبيق بيشتغل عن طريق السبيكر والميكروفون."
            ), top = 12
        )
        return ScrollView(this).apply { addView(col) }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts.shutdown()
        translators.values.forEach { it.close() }
        super.onDestroy()
    }
}
