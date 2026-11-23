package com.saann.app

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.util.Linkify
import android.widget.TextView

object ReplyStyle {
    fun apply(view: TextView, raw: String) {
        val out = SpannableStringBuilder()
        var code = false
        val flag = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE

        fun appendInline(value: String) {
            val token = Regex("\\*\\*([^*\\n]+)\\*\\*|`([^`\\n]+)`")
            var cursor = 0

            for (match in token.findAll(value)) {
                out.append(value.substring(cursor, match.range.first))
                val start = out.length
                val bold = match.groups[1]?.value
                val inline = match.groups[2]?.value
                out.append(bold ?: inline ?: "")

                if (bold != null) {
                    out.setSpan(
                        StyleSpan(Typeface.BOLD), start, out.length, flag
                    )
                } else {
                    out.setSpan(
                        TypefaceSpan("monospace"), start, out.length, flag
                    )
                    out.setSpan(
                        BackgroundColorSpan(0xFF202020.toInt()),
                        start, out.length, flag
                    )
                }
                cursor = match.range.last + 1
            }

            out.append(value.substring(cursor))
        }

        for (line in raw.split("\n")) {
            if (line.trimStart().startsWith("```")) {
                code = !code
                continue
            }

            val start = out.length
            if (code) {
                out.append(line)
                out.append("\n")
                out.setSpan(
                    TypefaceSpan("monospace"), start, out.length, flag
                )
                out.setSpan(
                    RelativeSizeSpan(0.88f), start, out.length, flag
                )
                out.setSpan(
                    BackgroundColorSpan(0xFF151515.toInt()),
                    start, out.length, flag
                )
                continue
            }

            val heading = Regex("^(#{1,3})\\s+(.+)$").matchEntire(line)
            if (heading != null) {
                appendInline(heading.groupValues)[2]
                out.setSpan(
                    StyleSpan(Typeface.BOLD), start, out.length, flag
                )
                out.setSpan(
                    RelativeSizeSpan(
                        if (heading.groupValues.length == 1) 1.20f else 1.08f[1]
                    ),
                    start, out.length, flag
                )
            } else {
                appendInline(line)
            }
            out.append("\n")
        }

        Linkify.addLinks(out, Linkify.WEB_URLS)
        view.text = out
        view.setLinkTextColor(0xFF78AFFF.toInt())
        view.linksClickable = true
        view.movementMethod = LinkMovementMethod.getInstance()
    }
}
