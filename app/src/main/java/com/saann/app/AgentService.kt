package com.saann.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.concurrent.CopyOnWriteArrayList

class AgentService : Service() {
    companion object {
        @Volatile var running = false
        @Volatile var listening = false
        @Volatile var appVisible = false
        @Volatile var onReply: ((String, String) -> Unit)? = null
        private val inbox = CopyOnWriteArrayList<Pair<String, String>>()
        private var seq = 200

        fun drain(): List<Pair<String, String>> {
            val l = inbox.toList()
            inbox.clear()
            return l
        }

        private fun launch(c: Context, i: Intent) {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun start(c: Context, listen: Boolean) {
            launch(c, Intent(c, AgentService::class.java).putExtra("keep", true).putExtra("listen", listen))
        }

        fun run(c: Context, task: String) {
            launch(c, Intent(c, AgentService::class.java).putExtra("task", task).putExtra("chat", true))
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, AgentService::class.java))
        }

        fun notifyReply(c: Context, title: String, text: String) {
            try {
                val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(NotificationChannel("saann_reply", "SAANN replies", NotificationManager.IMPORTANCE_HIGH))
                val open = PendingIntent.getActivity(
                    c, 0,
                    Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val n = Notification.Builder(c, "saann_reply")
                    .setContentTitle(title)
                    .setContentText(text.take(120))
                    .setStyle(Notification.BigTextStyle().bigText(text.take(1000)))
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(open)
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setAutoCancel(true)
                    .build()
                nm.notify(seq++, n)
            } catch (e: Exception) {
            }
        }

        fun notifyIfAway(c: Context, text: String) {
            if (!appVisible) notifyReply(c, "SAANN", text)
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val tick = Runnable { cycle() }
    private var keep = false
    private var listenOn = false
    private var active = 0
    private var speaking = false
    private var looping = false
    private var awakeUntil = 0L
    private var rec: SpeechRecognizer? = null
    private val wake = Regex("^(hey |ok |okay |hi )?(saann|saan|sann|sawn)\\b[ ,.:!]*(.*)$", RegexOption.IGNORE_CASE)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.getBooleanExtra("keep", false) == true) keep = true
        if (intent != null && intent.hasExtra("listen")) listenOn = intent.getBooleanExtra("listen", false)
        if (listenOn && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) listenOn = false
        listening = listenOn
        goForeground()
        val task = intent?.getStringExtra("task")
        if (task != null) work(task, intent?.getBooleanExtra("chat", false) ?: false)
        if (listenOn) startLoop() else stopRec()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("saann_agent", "SAANN agent", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, AgentService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "saann_agent")
            .setContentTitle(if (listenOn) "SAANN is listening" else "SAANN agent is on")
            .setContentText(if (listenOn) "Say \"SAANN\" then a command" else "Working in the background")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", stop).build())
            .setOngoing(true)
            .build()
        var type = 0
        if (Build.VERSION.SDK_INT >= 34) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (listenOn && Build.VERSION.SDK_INT >= 30) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (Build.VERSION.SDK_INT >= 29) startForeground(8, n, type) else startForeground(8, n)
    }

    private fun work(task: String, fromChat: Boolean) {
        active++
        Host.respond(task) { r ->
            ui.post {
                active--
                val shown = if (fromChat) "" else task
                val cb = onReply
                if (appVisible && cb != null) {
                    cb(shown, r)
                } else {
                    inbox.add(Pair(shown, r))
                    notifyReply(this, "SAANN", r)
                }
                if (listenOn && !fromChat) say(r)
                if (!keep && !listenOn && active == 0) stopSelf()
            }
        }
    }

    private fun say(t: String) {
        speaking = true
        ui.postDelayed({ speaking = false }, 40000)
        Voice.speakThen(this, t) { speaking = false }
    }

    private fun startLoop() {
        if (looping) return
        looping = true
        ui.post(tick)
    }

    private fun stopRec() {
        looping = false
        ui.removeCallbacks(tick)
        try { rec?.destroy() } catch (e: Exception) { }
        rec = null
    }

    private fun heard(t: String) {
        val m = wake.find(t.trim())
        val now = System.currentTimeMillis()
        val cmd: String
        if (m != null) {
            cmd = m.groupValues[3].trim()
            if (cmd.isEmpty()) {
                awakeUntil = now + 8000
                say("Yes?")
                return
            }
        } else if (now < awakeUntil) {
            cmd = t.trim()
        } else {
            return
        }
        awakeUntil = 0
        work(cmd, false)
    }

    private fun cycle() {
        if (!looping || !listenOn) return
        if (speaking || active > 0 || VoiceWindow.isOpen) {
            ui.postDelayed(tick, 1000)
            return
        }
        try { rec?.destroy() } catch (e: Exception) { }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) heard(t)
                ui.postDelayed(tick, 400)
            }
            override fun onError(e: Int) {
                if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    listenOn = false
                    listening = false
                    looping = false
                    goForeground()
                    return
                }
                val slow = e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_NETWORK ||
                    e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || e == SpeechRecognizer.ERROR_CLIENT
                ui.postDelayed(tick, if (slow) 3000L else 500L)
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        try { r.startListening(i) } catch (e: Exception) { ui.postDelayed(tick, 3000) }
    }

    override fun onDestroy() {
        running = false
        listening = false
        stopRec()
        Voice.stopSpeak()
        super.onDestroy()
    }
}
