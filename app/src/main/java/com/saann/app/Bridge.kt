package com.saann.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.speech.tts.TextToSpeech
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Bridge(private val act: Activity) {
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    private val streams = linkedMapOf(
        "music" to AudioManager.STREAM_MUSIC,
        "ring" to AudioManager.STREAM_RING,
        "alarm" to AudioManager.STREAM_ALARM,
        "notification" to AudioManager.STREAM_NOTIFICATION,
        "system" to AudioManager.STREAM_SYSTEM,
        "call" to AudioManager.STREAM_VOICE_CALL
    )

    // Called from Python: shows an Allow / Deny dialog and waits for the answer.
    fun confirm(msg: String): String {
        val latch = CountDownLatch(1)
        var answer = "n"
        act.runOnUiThread {
            AlertDialog.Builder(act)
                .setTitle("SAANN needs your approval")
                .setMessage(msg.take(400))
                .setCancelable(false)
                .setPositiveButton("Allow") { _, _ -> answer = "y"; latch.countDown() }
                .setNegativeButton("Deny") { _, _ -> latch.countDown() }
                .show()
        }
        latch.await()
        return answer
    }

    fun call(name: String, a: String, b: String): String {
        return try {
            when (name) {
                "battery" -> battery()
                "torch" -> torch(a == "on")
                "volume_get" -> volumeGet()
                "volume_set" -> volumeSet(a, b.toInt())
                "notify" -> notify(a, b)
                "clipboard_get" -> clipGet()
                "clipboard_set" -> clipSet(a)
                "open_url" -> openUrl(a)
                "speak" -> speak(a)
                "wifi" -> wifi()
                else -> "ERR:not available in the app yet: $name"
            }
        } catch (e: Exception) {
            "ERR:" + (e.message ?: e.toString())
        }
    }

    private fun battery(): String {
        val i = act.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val status = when (st) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
            BatteryManager.BATTERY_STATUS_FULL -> "FULL"
            BatteryManager.BATTERY_STATUS_DISCHARGING,
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "DISCHARGING"
            else -> "UNKNOWN"
        }
        return JSONObject().put("percentage", level * 100 / scale).put("status", status).toString()
    }

    private fun torch(on: Boolean): String {
        val cm = act.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "ERR:no flashlight found"
        cm.setTorchMode(id, on)
        return "ok"
    }

    private fun volumeGet(): String {
        val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val arr = JSONArray()
        for ((n, s) in streams) {
            arr.put(
                JSONObject().put("stream", n)
                    .put("volume", am.getStreamVolume(s))
                    .put("max_volume", am.getStreamMaxVolume(s))
            )
        }
        return arr.toString()
    }

    private fun volumeSet(stream: String, level: Int): String {
        val s = streams[stream] ?: return "ERR:unknown stream"
        val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(s, level.coerceIn(0, am.getStreamMaxVolume(s)), 0)
        return "ok"
    }

    private fun notify(title: String, text: String): String {
        val nm = act.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("saann", "SAANN", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val n = Notification.Builder(act, "saann")
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
        return "ok"
    }

    private fun clipGet(): String {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val c = cm.primaryClip ?: return ""
        return if (c.itemCount > 0) c.getItemAt(0).coerceToText(act).toString() else ""
    }

    private fun clipSet(t: String): String {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("saann", t))
        return "ok"
    }

    private fun openUrl(url: String): String {
        act.runOnUiThread {
            try {
                act.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
            }
        }
        return "ok"
    }

    private fun speak(t: String): String {
        if (tts == null) {
            val latch = CountDownLatch(1)
            tts = TextToSpeech(act) { s ->
                ttsReady = (s == TextToSpeech.SUCCESS)
                latch.countDown()
            }
            latch.await(5, TimeUnit.SECONDS)
            if (ttsReady) tts?.language = Locale.getDefault()
        }
        if (!ttsReady) return "ERR:text to speech is not ready"
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "saann")
        return "ok"
    }

    @Suppress("DEPRECATION")
    private fun wifi(): String {
        val wm = act.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val i = wm.connectionInfo
        return JSONObject().put("ssid", i.ssid).put("link_speed_mbps", i.linkSpeed)
            .put("rssi", i.rssi).toString()
    }
}
