package com.saann.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

object Voice {
    const val REQ = 4417
    @Volatile var autoSend = true
    @Volatile var speakReplies = false
    @Volatile var recognizer: ((Activity) -> Unit)? = null
    @Volatile var onHeard: ((String) -> Unit)? = null
    private val ui = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var queued: (() -> Unit)? = null
    private var doneCb: (() -> Unit)? = null
    private var rate = 0.93f
    private var pitch = 0.96f
    private var voiceName = ""
    private var triedDefault = false
    private var app: Context? = null
    private var focusReq: Any? = null
    private var listed: List<String> = emptyList()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("saann_voice", Context.MODE_PRIVATE)

    fun restore(ctx: Context) {
        val p = prefs(ctx)
        rate = p.getFloat("rate", 0.93f)
        pitch = p.getFloat("pitch", 0.96f)
        voiceName = p.getString("voice", "") ?: ""
        speakReplies = p.getBoolean("on", false)
        if (speakReplies) initTts(ctx)
    }

    fun listen(act: Activity) {
        val custom = recognizer
        if (custom != null) {
            custom(act)
            return
        }
        VoiceWindow.show(act) { false }
    }

    fun result(code: Int, res: Int, data: Intent?): String? {
        if (code != REQ || res != Activity.RESULT_OK) return null
        val t = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return null
        onHeard?.invoke(t)
        return t
    }

    private fun finish() {
        focus(false)
        val c = doneCb
        doneCb = null
        if (c != null) ui.post { c() }
    }

    private fun initTts(ctx: Context) {
        if (tts != null) return
        val ac = ctx.applicationContext
        app = ac
        val l = TextToSpeech.OnInitListener { st -> ui.post { onInit(ac, st) } }
        tts = try {
            if (!triedDefault) TextToSpeech(ac, l, "com.google.android.tts") else TextToSpeech(ac, l)
        } catch (e: Exception) {
            TextToSpeech(ac, l)
        }
    }

    private fun onInit(ac: Context, st: Int) {
        val t = tts ?: return
        if (st != TextToSpeech.SUCCESS) {
            try { t.shutdown() } catch (e: Exception) { }
            tts = null
            ttsReady = false
            if (!triedDefault) {
                triedDefault = true
                initTts(ac)
            } else {
                queued = null
                finish()
            }
            return
        }
        if (t.setLanguage(Locale.getDefault()) < TextToSpeech.LANG_AVAILABLE) t.setLanguage(Locale.US)
        t.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        pickVoice(t)
        t.setSpeechRate(rate)
        t.setPitch(pitch)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { if (id == "saann_end") ui.post { finish() } }
            override fun onError(id: String?) { ui.post { finish() } }
        })
        ttsReady = true
        val q = queued
        queued = null
        q?.invoke()
    }

    private fun pickVoice(t: TextToSpeech) {
        try {
            val all = t.voices ?: return
            val lang = Locale.getDefault().language
            val country = Locale.getDefault().country
            val mine = all.filter { it.locale.language == lang && it.features?.contains("notInstalled") != true }
            val named = mine.firstOrNull { it.name == voiceName }
            val best = named ?: mine.sortedByDescending {
                (if (it.isNetworkConnectionRequired) 0 else 1000) + it.quality + (if (it.locale.country == country) 50 else 0)
            }.firstOrNull()
            if (best != null) t.voice = best
        } catch (e: Exception) {
        }
    }

    private fun focus(on: Boolean) {
        try {
            if (Build.VERSION.SDK_INT < 26) return
            val am = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (on) {
                if (focusReq == null) {
                    focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(
                            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                        ).build()
                }
                am.requestAudioFocus(focusReq as AudioFocusRequest)
            } else {
                val r = focusReq as? AudioFocusRequest
                if (r != null) am.abandonAudioFocusRequest(r)
                focusReq = null
            }
        } catch (e: Exception) {
        }
    }

    private fun clean(raw: String): String {
        var s = raw
        s = s.replace(Regex("```[\\s\\S]*?```"), " ")
        s = s.replace(Regex("`([^`]*)`"), "$1")
        s = s.replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        s = s.replace(Regex("https?://\\S+"), "a link")
        s = s.replace(Regex("[*_#>|~]+"), " ")
        s = s.replace(Regex("[\\u2190-\\u21FF\\u2600-\\u27BF\\uFE0F\\u200D]|[\\uD83C-\\uD83E][\\uDC00-\\uDFFF]"), "")
        s = s.replace(Regex("(?m)^\\s*[-•·]\\s+"), "")
        s = s.replace(" & ", " and ").replace("%", " percent")
        s = s.replace(Regex("\\s*\\n+\\s*"), ". ")
        s = s.replace(Regex("([.!?:;])\\s*\\."), "$1")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    private fun chunks(s: String): List<String> {
        if (s.isBlank()) return emptyList()
        val raw = s.split(Regex("(?<=[.!?:;])\\s+"))
        val out = ArrayList<String>()
        var total = 0
        var buf = ""
        for ((i, x) in raw.withIndex()) {
            val piece = if (buf.isEmpty()) x else buf + " " + x
            if (piece.length < 28 && i < raw.size - 1) {
                buf = piece
                continue
            }
            buf = ""
            var rest = piece.trim()
            while (rest.length > 220) {
                val cut = rest.lastIndexOf(' ', 200)
                val k = if (cut < 40) 200 else cut
                out.add(rest.substring(0, k).trim())
                rest = rest.substring(k).trim()
            }
            if (rest.isNotEmpty()) {
                out.add(rest)
                total += rest.length
            }
            if (total > 800) break
        }
        return out
    }

    private fun say(raw: String) {
        val t = tts ?: return
        val parts = chunks(clean(raw))
        if (parts.isEmpty()) {
            finish()
            return
        }
        t.setSpeechRate(rate)
        t.setPitch(pitch)
        focus(true)
        for ((i, p) in parts.withIndex()) {
            val last = i == parts.size - 1
            t.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (last) "saann_end" else "s" + i)
            if (!last) {
                val gap = if (p.endsWith("?")) 320L else if (p.endsWith(":")) 360L else 260L
                t.playSilentUtterance(gap, TextToSpeech.QUEUE_ADD, "p" + i)
            }
        }
    }

    fun enableSpeech(act: Activity, on: Boolean) {
        speakReplies = on
        prefs(act).edit().putBoolean("on", on).apply()
        if (on) initTts(act)
    }

    fun speakThen(ctx: Context, text: String, done: () -> Unit) {
        initTts(ctx)
        doneCb = done
        if (ttsReady) say(text) else queued = { say(text) }
    }

    fun speak(text: String) {
        if (speakReplies && ttsReady && !VoiceWindow.isOpen) say(text)
    }

    fun stopSpeak() {
        doneCb = null
        queued = null
        focus(false)
        try { tts?.stop() } catch (e: Exception) { }
    }

    fun command(ctx: Context, arg: String): String {
        val p = prefs(ctx)
        val a = arg.trim().lowercase()
        if (a == "on" || a == "off") {
            speakReplies = a == "on"
            p.edit().putBoolean("on", speakReplies).apply()
            if (speakReplies) initTts(ctx) else stopSpeak()
            return if (speakReplies) "I'll speak my replies." else "Voice replies off."
        }
        if (a == "test") {
            speakThen(ctx, "Hi. I'm here. I'll keep my voice calm, and easy to listen to.") { }
            return "Playing a sample."
        }
        if (a.startsWith("rate ") || a.startsWith("pitch ")) {
            val v = a.substringAfter(' ').trim().toFloatOrNull() ?: return "Give me a number, for example /voice rate 0.9"
            if (a.startsWith("rate")) {
                rate = v.coerceIn(0.5f, 1.5f)
                p.edit().putFloat("rate", rate).apply()
            } else {
                pitch = v.coerceIn(0.6f, 1.4f)
                p.edit().putFloat("pitch", pitch).apply()
            }
            tts?.setSpeechRate(rate)
            tts?.setPitch(pitch)
            return "Rate " + rate + ", pitch " + pitch + ". Try /voice test."
        }
        initTts(ctx)
        if (a == "list" || a.startsWith("use ")) {
            val t = tts
            if (t == null || !ttsReady) return "The voice engine is still starting. Try again in a moment."
            val lang = Locale.getDefault().language
            val vs = (t.voices ?: emptySet()).filter { it.locale.language == lang && it.features?.contains("notInstalled") != true }
                .sortedByDescending { (if (it.isNetworkConnectionRequired) 0 else 1000) + it.quality }.take(10)
            listed = vs.map { it.name }
            if (a == "list") {
                if (vs.isEmpty()) return "No voices found for this language."
                return vs.withIndex().joinToString("\n") { (i, v) ->
                    (i + 1).toString() + ". " + v.name + (if (v.quality >= 400) " (high)" else if (v.quality >= 300) " (good)" else "") +
                        (if (v.isNetworkConnectionRequired) " online" else "")
                } + "\n\nPick one with /voice use 2"
            }
            val n = a.substringAfter(' ').trim().toIntOrNull() ?: return "Say which number, for example /voice use 2"
            val name = listed.getOrNull(n - 1) ?: return "No voice with that number. Try /voice list"
            voiceName = name
            p.edit().putString("voice", name).apply()
            pickVoice(t)
            speakThen(ctx, "Hi. This is my voice now.") { }
            return "Voice set to " + name + "."
        }
        return "Voice replies are " + (if (speakReplies) "on" else "off") + ", rate " + rate + ", pitch " + pitch +
            ".\n/voice on, off, list, use N, rate 0.9, pitch 0.95, test"
    }
}
