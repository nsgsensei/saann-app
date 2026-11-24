package com.saann.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

object Onboarding {
    private val BG = 0xFF10131D.toInt()
    private val PANEL = 0xFF1B2130.toInt()
    private val ORANGE = 0xFFB9AAFF.toInt()
    private val RED = 0xFFEF8791.toInt()
    private val INK = 0xFFF3F0EB.toInt()
    private val DIM = 0xFFB0B9CB.toInt()
    private val ui = Handler(Looper.getMainLooper())

    private val names = listOf(
        "gemini", "groq", "cloudflare", "cloudflare_account"
    )

    private fun credentials(c: Context): JSONObject {
        val result = JSONObject()
        for (name in names) {
            val value = Keys.get(c, "${name}_key")
            if (!value.isNullOrBlank()) result.put(name, value)
        }
        return result
    }

    // Call on the engine initialization thread, after Host.py is assigned.
    fun restore(c: Context) {
        val p = Host.py ?: error("Engine is not ready")
        val result = JSONObject(
            p.callAttr("setup_api", "restore", credentials(c).toString()).toString()
        )
        check(result.optBoolean("ok")) { "Could not restore provider settings" }
    }

    fun needed(c: Context): Boolean =
        !Keys.flag(c, "connected_setup_v1")

    fun show(a: Activity) {
        if (a.isFinishing || a.isDestroyed) return

        val firstRun = needed(a)
        val d = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.setCancelable(!firstRun)
        d.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        d.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        val den = a.resources.displayMetrics.density
        fun dp(v: Int) = (v * den).toInt()

        val scroll = ScrollView(a)
        scroll.setBackgroundColor(BG)
        scroll.isFillViewport = true

        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(30), dp(24), dp(30))
        scroll.addView(box)
        d.setContentView(scroll)

        var page = 0
        var running = false

        fun text(value: String, size: Float = 15f, color: Int = INK): TextView {
            val t = TextView(a)
            t.text = value
            t.textSize = size
            t.setTextColor(color)
            t.setPadding(0, dp(8), 0, dp(8))
            box.addView(t)
            return t
        }

        fun button(label: String, primary: Boolean = false, click: () -> Unit): Button {
            val b = Button(a)
            b.text = label
            b.isAllCaps = false
            b.textSize = 14f
            b.setTextColor(
                if (label.contains("provider", ignoreCase = true))
                    0xFFAFC8EF.toInt()
                else if (primary) BG else INK
            )
            b.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (primary) ORANGE else PANEL)
                setStroke(dp(1), if (primary) ORANGE else 0x3372CDB6)
            }
            val lp = LinearLayout.LayoutParams(-1, dp(48))
            lp.topMargin = dp(12)
            box.addView(b, lp)
            b.setOnClickListener {
                if (!running) click()
            }
            return b
        }

        fun field(label: String, storage: String, secret: Boolean = true): EditText {
            text(label, 13f, DIM)
            val e = EditText(a)
            e.setSingleLine(true)
            e.setTextColor(INK)
            e.setHintTextColor(DIM)
            e.hint = label
            e.inputType = if (secret) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            e.setPadding(dp(14), dp(12), dp(14), dp(12))
            e.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(PANEL)
                setStroke(dp(1), 0x3372CDB6)
            }
            e.setText(Keys.get(a, storage) ?: "")
            box.addView(e, LinearLayout.LayoutParams(-1, -2))
            return e
        }

        fun preview(path: String, status: TextView) {
            if (path.isBlank()) return

            try {
                val options = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeFile(path, options)

                val target = dp(240)
                var sample = 1

                while (
                    options.outWidth / (sample * 2) >= target &&
                    options.outHeight / (sample * 2) >= target
                ) {
                    sample *= 2
                }

                val load = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sample
                }

                val bitmap =
                    android.graphics.BitmapFactory.decodeFile(path, load)

                if (bitmap == null) {
                    status.setTextColor(RED)
                    status.text = "Could not display the generated image."
                    return
                }

                val image = android.widget.ImageView(a)
                image.setImageBitmap(bitmap)
                image.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                image.background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(PANEL)
                    setStroke(dp(1), 0x3372CDB6)
                }

                val lp = LinearLayout.LayoutParams(-1, dp(240))
                lp.topMargin = dp(12)
                box.addView(image, lp)

                status.setTextColor(ORANGE)
                status.text = "Image generated. Preview shown below."
            } catch (e: Exception) {
                status.setTextColor(RED)
                status.text = "Could not display the generated image."
            }
        }

        fun info(question: String, message: String) {
            val b = TextView(a)
            b.text = "? " + question
            b.textSize = 12f
            b.setTextColor(0xFFB9AAFF.toInt())
            b.setPadding(0, dp(8), 0, dp(2))

            b.setOnClickListener {
                android.app.AlertDialog.Builder(a)
                    .setTitle(question)
                    .setMessage(message)
                    .setPositiveButton("Got it", null)
                    .show()
            }

            box.addView(
                b,
                LinearLayout.LayoutParams(-1, -2)
            )
        }

        fun request(
            operation: String,
            payload: JSONObject,
            status: TextView,
            complete: (JSONObject) -> Unit
        ) {
            running = true
            status.setTextColor(ORANGE)
            status.text = if (operation == "image_test")
                "Generating a test image… This uses image quota."
            else
                "Testing provider access and a usable model… Please wait."
            Thread {
                val result = try {
                    val p = Host.py
                    if (p == null) {
                        JSONObject()
                            .put("ok", false)
                            .put("message", "The engine is starting. Tap again in a moment.")
                    } else {
                        JSONObject(
                            p.callAttr("setup_api", operation, payload.toString()).toString()
                        )
                    }
                } catch (e: Exception) {
                    JSONObject()
                        .put("ok", false)
                        .put("message", "Setup request failed. Check connection and retry.")
                }
                ui.post {
                    running = false
                    if (!a.isFinishing && !a.isDestroyed && d.isShowing) {
                        val ok = result.optBoolean("ok")
                        status.setTextColor(if (ok) ORANGE else RED)
                        status.text = result.optString("message", "Request finished.")
                        complete(result)
                    }
                }
            }.start()
        }

        lateinit var render: () -> Unit

        render = {
            box.removeAllViews()

            text("SAANN", 30f).setTypeface(null, Typeface.BOLD)
            text("SETUP ${page + 1} / 5", 12f, ORANGE)

            when (page) {
                0, 1, 2 -> {
                    val provider = when (page) {
                        0 -> "gemini"
                        1 -> "groq"
                        else -> "cloudflare"
                    }
                    val title = when (page) {
                        0 -> "Connect reasoning and vision"
                        1 -> "Add a fast text backup"
                        else -> "Connect image generation"
                    }
                    val description = when (page) {
                        0 -> "Gemini handles text and attached photos. This connection test checks text access; photo understanding is tested when you attach an image."
                        1 -> "Groq is optional. Its test sends a short text request using your engine's configured model."
                        else -> "Cloudflare needs an Account ID and a Workers AI API token. Test connection first, then generate a test image."
                    }
                    text(title, 23f)
                    text(description, 15f, DIM)

                    info(
                        "What is an API key?",
                        "An API key is a private access code that lets SAANN use a provider such as Google Gemini or Groq. Keep it private: anyone with the key can use your provider account and quota."
                    )

                    info(
                        "How do I get one?",
                        "Tap Open provider key page, sign in, create a key, copy it, return here, and paste it into the key field. SAANN will test the connection before saving it."
                    )

                    info(
                        "What if the test fails?",
                        "Check that you copied the complete key, create a new key if needed, and make sure the provider account has access and available quota. SAANN will show the provider's specific error."
                    )
                    text(
                        "Provider accounts, access, and quotas are controlled by the provider. Setup does not enable billing.",
                        12f, DIM
                    )

                    if (provider == "cloudflare") {
                        info(
                            "What is a Cloudflare Account ID?",
                            "Your Account ID identifies which Cloudflare account SAANN should use. In Workers AI, choose Use REST API and copy the Account ID shown there."
                        )

                        info(
                            "What is a Workers AI token?",
                            "A Workers AI API token authorizes SAANN to run AI models on your Cloudflare account. Create it from the Workers AI page, copy it immediately, and paste it into the token field."
                        )

                        info(
                            "Why do I need both?",
                            "The Account ID says where to run the model. The API token proves that you have permission to use it. SAANN needs both to generate images."
                        )
                    }

                    val account = if (provider == "cloudflare") {
                        field("Cloudflare Account ID", "cloudflare_account_key", false)
                    } else null
                    val key = field(
                        if (provider == "cloudflare") "Workers AI API token" else "API key",
                        "${provider}_key"
                    )

                    // SAANN_VERIFIED_SETUP_V3
                    fun saveDraft(verified: Boolean = false) {
                        // Browser navigation and skipping must not replace a key.
                        if (!verified) return
                        Keys.set(a, "${provider}_key", key.text.toString().trim())
                        if (account != null) {
                            Keys.set(a, "cloudflare_account_key", account.text.toString().trim())
                        }
                    }

                    button("Paste key or token") {
                        val clipboard = a.getSystemService(
                            Context.CLIPBOARD_SERVICE
                        ) as android.content.ClipboardManager
                        val item = clipboard.primaryClip
                        if (item != null && item.itemCount > 0) {
                            val value = item.getItemAt(0)
                                .coerceToText(a)?.toString()?.trim().orEmpty()
                            if (value.isNotEmpty()) {
                                key.setText(value)
                                key.setSelection(key.text.length)
                            }
                        }
                    }

                    button("Show / hide key") {
                        val reveal = key.transformationMethod != null
                        key.transformationMethod = if (reveal) null else
                            android.text.method.PasswordTransformationMethod
                                .getInstance()
                        key.setSelection(key.text.length)
                    }

                    if (account != null) {
                        button("Paste Cloudflare Account ID") {
                            val clipboard = a.getSystemService(
                                Context.CLIPBOARD_SERVICE
                            ) as android.content.ClipboardManager
                            val item = clipboard.primaryClip
                            if (item != null && item.itemCount > 0) {
                                account.setText(
                                    item.getItemAt(0).coerceToText(a)
                                        ?.toString()?.trim().orEmpty()
                                )
                            }
                        }
                    }

                    val guidance = when (provider) {
                        "gemini" ->
                            "Open Google AI Studio, sign in, and create an API " +
                            "key for a project you can access. Return here, " +
                            "paste the complete key, then connect."
                        "groq" ->
                            "Open Groq API Keys, sign in, and create a key. " +
                            "Your account must have permission to create keys. " +
                            "Copy the secret, return here, and paste it."
                        else ->
                            "Open Workers AI and choose Use REST API. Copy " +
                            "your Account ID and create a Workers AI token. " +
                            "Return here and paste each into its own field."
                    }

                    info("Step-by-step connection help", guidance)
                    info(
                        "Will SAANN create the key automatically?",
                        "SAANN opens the provider's official setup page and " +
                        "handles connection testing here. Sign-in, verification, " +
                        "and key creation still require the provider's supported " +
                        "authorization or your confirmation. Never enter your " +
                        "provider password into SAANN."
                    )
                    info(
                        "What does Connected mean?",
                        "The provider returned a usable text response. This " +
                        "does not prove image or voice access. Connection tests " +
                        "can use a small amount of provider quota."
                    )
                    info(
                        "What happens if a replacement fails?",
                        "SAANN keeps the previous saved credential. Check the " +
                        "provider message before retrying. Changing models " +
                        "cannot fix an account permission or billing restriction."
                    )

                    val status = text(
                        "Not tested yet. Your saved connection is unchanged.",
                        14f, DIM
                    )
                    var passed = false
                    var checkedKey = ""
                    var checkedAccount = ""

                    // SAANN_CREDENTIAL_EDIT_GUARD_V1
                    val credentialWatcher = object : android.text.TextWatcher {
                        override fun beforeTextChanged(
                            value: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int
                        ) {}

                        override fun onTextChanged(
                            value: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int
                        ) {
                            passed = false
                            checkedKey = ""
                            checkedAccount = ""
                            status.setTextColor(DIM)
                            status.text = if (running) {
                                "Credentials changed. The pending test will " +
                                "not save these changes; test again afterward."
                            } else {
                                "Credentials changed. Test this connection " +
                                "before continuing."
                            }
                        }

                        override fun afterTextChanged(
                            value: android.text.Editable?
                        ) {}
                    }

                    key.addTextChangedListener(credentialWatcher)
                    account?.addTextChangedListener(credentialWatcher)

                    button("Open provider key page") {
                        saveDraft()
                        val url = when (provider) {
                            "gemini" -> "https://aistudio.google.com/apikey"
                            "groq" -> "https://console.groq.com/keys"
                            else -> "https://dash.cloudflare.com/?to=/:account/ai/workers-ai"
                        }
                        try {
                            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (e: Exception) {
                            status.setTextColor(RED)
                            status.text = "Could not open browser. Visit: $url"
                        }
                    }

                    button(if (provider == "cloudflare") "Test connection" else "Connect and continue", true) {
                        saveDraft()
                        passed = false
                        val testedKey = key.text.toString().trim()
                        val testedAccount = account?.text?.toString()?.trim() ?: ""
                        request(
                            "test",
                            JSONObject()
                                .put("provider", provider)
                                .put("key", testedKey)
                                .put("account", testedAccount),
                            status
                        ) { result ->
                            val unchanged =
                                testedKey == key.text.toString().trim() &&
                                testedAccount ==
                                    (account?.text?.toString()?.trim() ?: "")

                            passed = result.optBoolean("ok") && unchanged

                            if (!unchanged) {
                                checkedKey = ""
                                checkedAccount = ""
                                status.setTextColor(DIM)
                                status.text =
                                    "The test used earlier credentials. " +
                                    "Your edited credentials were not saved. " +
                                    "Test again to connect them."
                            } else if (passed) {
                                checkedKey = testedKey
                                checkedAccount = testedAccount

                                // Save exactly the values that passed.
                                Keys.set(a, "${provider}_key", testedKey)
                                if (provider == "cloudflare") {
                                    Keys.set(
                                        a,
                                        "cloudflare_account_key",
                                        testedAccount
                                    )
                                }

                                if (provider != "cloudflare") {
                                    page++
                                    render()
                                }
                            } else {
                                checkedKey = ""
                                checkedAccount = ""
                            }
                        }
                    }

                    if (provider == "cloudflare") {
                        button("Open API tokens page") {
                            saveDraft()

                            try {
                                a.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(
                                            "https://dash.cloudflare.com/profile/api-tokens"
                                        )
                                    )
                                )
                            } catch (e: Exception) {
                                status.setTextColor(RED)
                                status.text = (
                                    "Could not open browser. Visit: " +
                                    "https://dash.cloudflare.com/profile/api-tokens"
                                )
                            }
                        }
                    }

                    if (provider == "cloudflare") {
                        button("Generate test image — uses quota") {
                            val unchanged =
                                checkedKey == key.text.toString().trim() &&
                                checkedAccount == (account?.text?.toString()?.trim() ?: "")
                            if (!passed || !unchanged) {
                                status.text = "Test the current credentials first."
                                status.setTextColor(RED)
                            } else {
                                request("image_test", JSONObject(), status) { result ->
                                if (result.optBoolean("ok")) {
                                    preview(
                                        result.optString("path"),
                                        status
                                    )
                                }
                            }
                            }
                        }
                    }

                    button("Continue") {
                        val unchanged =
                            checkedKey == key.text.toString().trim() &&
                            checkedAccount == (account?.text?.toString()?.trim() ?: "")
                        if (!passed || !unchanged) {
                            status.text = "Test the current credentials before continuing, or skip this service."
                            status.setTextColor(RED)
                        } else {
                            saveDraft(true)
                            page++
                            render()
                        }
                    }

                    button("Skip this service") {
                        // Preserve drafts; skipping is not credential removal.
                        saveDraft()
                        page++
                        render()
                    }
                }

                3 -> {
                    text("Set up your voice", 23f)
                    text(
                        "Choose ElevenLabs cloud voice or the phone voice. Cloud voice sends spoken text to ElevenLabs and uses provider credits. Microphone input continues to use your phone's speech service.",
                        15f, DIM
                    )
                    val status = text("Voice has not been confirmed.", 14f, DIM)

                    // SAANN_VOICE_SETUP_V2
                    button("Connect ElevenLabs cloud voice") {
                        CloudVoice.show(a)
                    }

                    button("Play SAANN voice", true) {
                        status.text = Voice.command(a, "test")
                    }

                    button("I heard it — enable spoken replies") {
                        Voice.enableSpeech(a, true)
                        Keys.setFlag(a, "setup_voice_confirmed", true)
                        page++
                        render()
                    }

                    button("Continue without spoken replies") {
                        Voice.enableSpeech(a, false)
                        Keys.setFlag(a, "setup_voice_confirmed", false)
                        page++
                        render()
                    }

                    button("Open phone text-to-speech settings") {
                        try {
                            a.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                        } catch (e: Exception) {
                            status.text = "Open your phone Settings and search for text-to-speech."
                        }
                    }
                }

                4 -> {
                    text("Connect SAANN", 23f)
                    text(
                        "This checks your selected text services again, restores credentials into the live engine, and enables cloud mode. Local model servers stay off.",
                        15f, DIM
                    )

                    for (name in listOf("gemini", "groq", "cloudflare")) {
                        val saved = !Keys.get(a, "${name}_key").isNullOrBlank()
                        text(
                            "$name: " + if (saved) "credentials entered" else "skipped",
                            14f, DIM
                        )
                    }

                    val status = text("Ready to verify.", 14f, DIM)

                    button("Connect and open chat", true) {
                        running = true
                        status.setTextColor(ORANGE)
                        status.text = "Restoring credentials and checking providers…"

                        Thread {
                            var ok = false
                            var message = ""
                            try {
                                restore(a)
                                val p = Host.py ?: error("Engine not ready")
                                val report = ArrayList<String>()
                                var workingText = false

                                for (name in listOf("gemini", "groq")) {
                                    val value = Keys.get(a, "${name}_key") ?: ""
                                    if (value.isBlank()) continue

                                    val result = JSONObject(
                                        p.callAttr(
                                            "setup_api", "test",
                                            JSONObject()
                                                .put("provider", name)
                                                .put("key", value)
                                                .toString()
                                        ).toString()
                                    )
                                    if (result.optBoolean("ok")) workingText = true
                                    report.add("$name: ${result.optString("message")}")
                                }

                                if (workingText) {
                                    val finish = JSONObject(
                                        p.callAttr("setup_api", "finish", "{}").toString()
                                    )
                                    ok = finish.optBoolean("ok")
                                    report.add(finish.optString("message"))
                                } else {
                                    report.add("At least one working Gemini or Groq text connection is required.")
                                }
                                message = report.joinToString("\n\n")
                            } catch (e: Exception) {
                                message = "Could not complete setup. Wait for the engine, then retry."
                            }

                            val success = ok
                            val finalMessage = message
                            ui.post {
                                running = false
                                if (!a.isFinishing && !a.isDestroyed && d.isShowing) {
                                    status.text = finalMessage
                                    status.setTextColor(if (success) ORANGE else RED)
                                    if (success) {
                                        Keys.setFlag(a, "connected_setup_v1", true)
                                        Keys.setFlag(a, "diagnostic_mode", false)
                                        d.dismiss()
                                        SetupFinish.show(a)
                                    }
                                }
                            }
                        }.start()
                    }
                }
            }

            if (page > 0) {
                button("Back") {
                    page--
                    render()
                }
            }

            // SAANN_DIAGNOSTIC_ESCAPE_V1
            button("Open in offline / diagnostic mode") {
                Keys.setFlag(a, "connected_setup_v1", true)
                Keys.setFlag(a, "diagnostic_mode", true)
                d.dismiss()
            }

            if (!firstRun) {
                button("Close setup") { d.dismiss() }
            }
        }

        render()
        d.show()
        d.window?.setLayout(-1, -1)
        d.window?.statusBarColor = BG
        d.window?.navigationBarColor = BG
    }
}
