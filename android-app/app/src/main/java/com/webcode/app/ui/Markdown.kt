package com.webcode.app.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

object Markdown {

    fun render(text: String, codeBg: Int, codeFg: Int, codeSize: Float): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        val lines = text.split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.trimStart().startsWith("```")) {
                val buf = StringBuilder()
                while (i < lines.size) {
                    val l = lines[i]
                    if (l.trimStart().startsWith("```") && buf.isNotEmpty()) break
                    if (buf.isNotEmpty()) buf.append('\n')
                    buf.append(l)
                    i++
                }
                if (buf.isEmpty()) buf.append(lines.getOrElse(i) { "" })
                val codeText = buf.toString().removeSuffix("\n")
                val start = out.length
                out.append(codeText).append("\n")
                out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(codeSize), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(BackgroundColorSpan(codeBg), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(codeFg), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                renderInline(out, line, codeBg, codeFg, codeSize)
                if (i < lines.size - 1) out.append('\n')
            }
            i++
        }
        return out
    }

    private fun renderInline(
        out: SpannableStringBuilder,
        line: String,
        codeBg: Int,
        codeFg: Int,
        codeSize: Float
    ) {
        val trimmed = line.trimStart()
        val indent = line.length - trimmed.length
        when {
            trimmed.startsWith("### ") -> {
                appendStyled(out, trimmed.substring(4), Typeface.BOLD, 1.15f, indent)
            }
            trimmed.startsWith("## ") -> {
                appendStyled(out, trimmed.substring(3), Typeface.BOLD, 1.3f, indent)
            }
            trimmed.startsWith("# ") -> {
                appendStyled(out, trimmed.substring(2), Typeface.BOLD, 1.5f, indent)
            }
            trimmed.startsWith("> ") -> {
                val start = out.length
                out.append(trimmed.substring(2))
                out.setSpan(ForegroundColorSpan(codeFg), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.matches(Regex("\\d+\\.\\s.*")) -> {
                val bullet = if (trimmed.startsWith("-") || trimmed.startsWith("*")) "• " else ""
                if (indent > 0) out.append("  ".repeat(indent))
                out.append(bullet)
                appendInline(out, trimmed.substringAfter(" "), codeBg, codeFg, codeSize)
            }
            else -> {
                if (indent > 0) out.append("  ".repeat(indent))
                appendInline(out, line, codeBg, codeFg, codeSize)
            }
        }
    }

    private fun appendStyled(
        out: SpannableStringBuilder,
        text: String,
        style: Int,
        size: Float,
        indent: Int
    ) {
        if (indent > 0) out.append("  ".repeat(indent))
        val start = out.length
        out.append(text)
        out.setSpan(StyleSpan(style), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(RelativeSizeSpan(size), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun appendInline(
        out: SpannableStringBuilder,
        text: String,
        codeBg: Int,
        codeFg: Int,
        codeSize: Float
    ) {
        val regex = Regex("(`[^`]+`|\\*\\*[^*]+\\*\\*|\\*[^*]+\\*|\\[[^\\]]+\\]\\([^)]+\\))")
        var last = 0
        for (m in regex.findAll(text)) {
            if (m.range.first > last) out.append(text.substring(last, m.range.first))
            val tok = m.value
            when {
                tok.startsWith("`") -> {
                    val start = out.length
                    out.append(tok.substring(1, tok.length - 1))
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RelativeSizeSpan(codeSize), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(BackgroundColorSpan(codeBg), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(codeFg), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                tok.startsWith("**") -> {
                    val start = out.length
                    out.append(tok.substring(2, tok.length - 2))
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                tok.startsWith("*") && !tok.startsWith("**") -> {
                    val start = out.length
                    out.append(tok.substring(1, tok.length - 1))
                    out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                tok.startsWith("[") -> {
                    val label = tok.substringBefore("](").removePrefix("[")
                    out.append(label)
                }
            }
            last = m.range.last + 1
        }
        if (last < text.length) out.append(text.substring(last))
    }
}
