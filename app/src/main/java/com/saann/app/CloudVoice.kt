package com.saann.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object CloudVoice {
    private val ui = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var audioFile: File? = null
    private var callback: (() -> Unit)? = null
    private var generation = 0

    private val BG = 0xFF050505.toInt()
    private val PANEL = 0xFF101010.toInt()
    private val ORANGE = 0xFFE5484D.toInt()
    private val INK = 0xFFFAF7F2.toInt()

    private class ProviderError(val code: Int) : Exception()

    private fun http(path: String, key: String, body: JSONObject? = null): ByteArray {
        val c = URL("https://api.elevenlabs.io" + path)
            .openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 45000
            c.setRequestProperty("xi-api-key", key)
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            if (code !in 200..299) throw ProviderError(code)
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun synth(
        c: Context, key: String, voice: String,
        model: String, text: String
    ): File {
        val bytes = http(
            "/v1/text-to-speech/$voice?output_format=mp3_44100_128",
            key,
            JSONObject().put("text", text).put("model_id", model)
        )
        check(bytes.isNotEmpty())
        return File.createTempFile("saann-voice-", ".mp3", c.cacheDir)
            .apply { writeBytes(bytes) }
    }

    private fun error(e: Exception): String = when (e) {
        is ProviderError -> when (e.code) {
            401 -> "Voice authentication failed. Check the key and account."
            403 -> "Voice access denied. Check API permissions and voice access."
            404 -> "Voice or model unavailable."
            429 -> "Voice quota or rate limit reached."
            else -> "Voice provider HTTP ${e.code}."
        }
        else -> "Voice connection or playback failed. Check internet and retry."
    }

    fun stop() {
        generation++
        try { player?.release() } catch (_: Exception) {}
        player = null
        audioFile?.delete()
        audioFile = null
        callback = null
    }

    private fun play(file: File, done: () -> Unit) {
        audioFile = file
        callback = done
        val m = MediaPlayer()
        player = m

        fun finish() {
            if (player !== m) return
            player = null
            try { m.release() } catch (_: Exception) {}
            file.delete()
            audioFile = null
            val cb = callback
            callback = null
            cb?.invoke()
        }

        try {
            m.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            m.setDataSource(file.absolutePath)
            m.setOnPreparedListener { it.start() }
            m.setOnCompletionListener { finish() }
            m.setOnErrorListener { _, _, _ -> finish(); true }
            m.prepareAsync()
        } catch (_: Exception) {
            finish()
        }
    }

    fun speak(c: Context, text: String, done: () -> Unit): Boolean {
        if (!Keys.flag(c, "cloud_voice_on")) return false
        val key = Keys.get(c, "elevenlabs_key") ?: return false
        val voice = Keys.get(c, "elevenlabs_voice") ?: return false
        val model = Keys.get(c, "elevenlabs_model") ?: return false
        if (key.isBlank() || voice.isBlank() || model.isBlank()) return false

        val context = c.applicationContext
        ui.post {
            stop()
            val token = generation
            Thread {
                try {
                    val file = synth(context, key, voice, model, text.take(1500))
                    ui.post {
                        if (token != generation) {
                            file.delete()
                        } else {
                            play(file, done)
                        }
                    }
                } catch (e: Exception) {
                    ui.post {
                        if (token == generation) {
                            Toast.makeText(context, error(e), Toast.LENGTH_LONG).show()
                            done()
                        }
                    }
                }
            }.start()
        }
        return true
    }

    fun show(a: Activity) {
        val d = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val den = a.resources.displayMetrics.density
        fun dp(v: Int) = (v * den).toInt()

        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(24), dp(24), dp(24))
        box.setBackgroundColor(BG)
        val scroll = ScrollView(a)
        scroll.addView(box)
        d.setContentView(scroll)

        fun label(value: String, size: Float = 15f): TextView {
            val t = TextView(a)
            t.text = value
            t.textSize = size
            t.setTextColor(INK)
            t.setPadding(0, dp(10), 0, dp(10))
            box.addView(t)
            return t
        }

        var busy = false
        fun button(value: String, action: () -> Unit) {
            val b = Button(a)
            b.text = value
            b.isAllCaps = false
            b.textSize = 14f
            b.setTextColor(INK)
            b.background = GradientDrawable().apply {
                setColor(PANEL)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), ORANGE)
            }
            val lp = LinearLayout.LayoutParams(-1, dp(48))
            lp.topMargin = dp(12)
            box.addView(b, lp)
            b.setOnClickListener { if (!busy) action() }
        }

        label("Connect SAANN cloud voice", 24f)
        label(
            "ElevenLabs receives the text you choose to speak. " +
            "Testing and spoken replies consume provider credits. " +
            "This connects spoken output; microphone input still uses the phone."
        )

        val key = EditText(a)
        key.hint = "ElevenLabs API key"
        key.setHintTextColor(0xFFB8ADA5.toInt())
        key.setTextColor(INK)
        key.setSingleLine(true)
        key.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_PASSWORD
        key.setText(Keys.get(a, "elevenlabs_key") ?: "")
        box.addView(key)

        val status = label("Not tested in this window.")
        status.setTextColor(ORANGE)

        button("Open ElevenLabs") {
            Keys.set(a, "elevenlabs_key", key.text.toString().trim())
            try {
                a.startActivity(Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://elevenlabs.io/app")
                ))
            } catch (_: Exception) {
                status.text = "Open elevenlabs.io in your browser."
            }
        }

        button("Connect and play preview — uses credits") {
            val secret = key.text.toString().trim()
            if (secret.isBlank()) {
                status.text = "Enter the API key first."
            } else {
                Keys.set(a, "elevenlabs_key", secret)
                Keys.setFlag(a, "cloud_voice_on", false)
                busy = true
                status.text = "Discovering account voices and speech models…"
                val context = a.applicationContext
                Thread {
                    try {
                        val voices = JSONObject(String(http(
                            "/v2/voices?voice_type=default&page_size=100",
                            secret
                        ))).getJSONArray("voices")
                        check(voices.length() > 0)
                        val voice = voices.getJSONObject(0)

                        val models = JSONArray(String(http("/v1/models", secret)))
                        val supported = ArrayList<JSONObject>()
                        for (i in 0 until models.length()) {
                            val item = models.getJSONObject(i)
                            if (item.optBoolean("can_do_text_to_speech")) {
                                supported.add(item)
                            }
                        }
                        check(supported.isNotEmpty())
                        val model = supported.sortedBy {
                            val id = it.optString("model_id")
                            when {
                                id.contains("flash") -> 0
                                id.contains("turbo") -> 1
                                else -> 2
                            }
                        }.first().getString("model_id")
                        val voiceId = voice.getString("voice_id")
                        val file = synth(
                            context, secret, voiceId, model,
                            "Hi. I'm SAANN. Your cloud voice connection is ready."
                        )

                        ui.post {
                            busy = false
                            if (a.isFinishing || a.isDestroyed || !d.isShowing) {
                                file.delete()
                            } else {
                                Keys.set(a, "elevenlabs_voice", voiceId)
                                Keys.set(a, "elevenlabs_model", model)
                                Keys.setFlag(a, "cloud_voice_on", true)
                                Voice.enableSpeech(a, true)
                                stop()
                                play(file) {}
                                status.text =
                                    "Speech generated. Playing ${voice.optString("name")}.\n" +
                                    "Model: $model\nCloud voice enabled."
                            }
                        }
                    } catch (e: Exception) {
                        ui.post {
                            busy = false
                            if (!a.isFinishing && d.isShowing) status.text = error(e)
                        }
                    }
                }.start()
            }
        }

        button("Use phone voice instead") {
            Keys.setFlag(a, "cloud_voice_on", false)
            stop()
            Voice.enableSpeech(a, true)
            status.text = Voice.command(a, "test")
        }

        button("Done") { d.dismiss() }
        d.show()
        d.window?.setLayout(-1, -1)
        d.window?.statusBarColor = BG
        d.window?.navigationBarColor = BG
    }
}
