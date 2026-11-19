package com.saann.app

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

object Vision {
    fun look(b: Bitmap): String {
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val t = Tasks.await(rec.process(InputImage.fromBitmap(b, 0)), 20, TimeUnit.SECONDS)
            val sb = StringBuilder()
            var n = 0
            loop@ for (blk in t.textBlocks) {
                for (ln in blk.lines) {
                    if (n >= 70) break@loop
                    val r = ln.boundingBox ?: continue
                    sb.append("- ").append(ln.text.take(80).replace("\n", " "))
                        .append(" @(").append(r.centerX()).append(",").append(r.centerY()).append(")\n")
                    n++
                }
            }
            return if (n == 0) "no readable text found" else sb.toString()
        } finally {
            rec.close()
        }
    }
}
