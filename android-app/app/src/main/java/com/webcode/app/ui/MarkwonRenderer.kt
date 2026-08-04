package com.webcode.app.ui

import android.content.Context
import android.text.style.ClickableSpan
import androidx.core.content.ContextCompat
import com.webcode.app.R
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.latex.JLatexMathPlugin
import android.text.method.ArrowKeyMovementMethod
import io.noties.markwon.core.spans.CodeBlockSpan
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin

/**
 * Markdown + LaTeX 渲染（Markwon + ext-latex/JLaTeXMath）
 * - 完整 CommonMark：代码块/行内代码/标题/表格/列表/引用/链接
 * - LaTeX：行内 $...$ 与块级 $$...$$（JLaTeXMath 渲染）
 */
object MarkwonRenderer {

    @Volatile
    private var instance: Markwon? = null

    private fun get(context: Context): Markwon {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val text = ContextCompat.getColor(context, R.color.text)
            val muted = ContextCompat.getColor(context, R.color.muted)
            val codeFg = ContextCompat.getColor(context, R.color.success)
            val codeBg = ContextCompat.getColor(context, R.color.panel3)
            val accent = ContextCompat.getColor(context, R.color.accent)
            val density = context.resources.displayMetrics.density

            val latexBuilder = JLatexMathPlugin.builder(13f * density, 15f * density)
            latexBuilder.inlinesEnabled(true)
            latexBuilder.theme()
                .textColor(text)
                .inlineTextColor(codeFg)
                .blockTextColor(text)
            val latexPlugin = JLatexMathPlugin.create(latexBuilder.build())

            instance = Markwon.builder(context)
                .usePlugin(MarkwonInlineParserPlugin.create())
                .usePlugin(TablePlugin.create(context)) // 表格
                .usePlugin(object : AbstractMarkwonPlugin() {
                    override fun configureTheme(builder: MarkwonTheme.Builder) {
                        builder.codeTextColor(codeFg)
                        builder.codeBackgroundColor(codeBg)
                        builder.codeBlockTextColor(codeFg)
                        builder.codeBlockBackgroundColor(codeBg)
                        builder.linkColor(accent)
                        builder.blockQuoteColor(muted)
                        builder.listItemColor(muted)
                    }
                })
                .usePlugin(latexPlugin)
                .build()
            return instance!!
        }
    }

    fun setMarkdown(textView: android.widget.TextView, markdown: String) {
        val spanned = get(textView.context).toMarkdown(markdown)
        val builder = spanned as? android.text.SpannableStringBuilder
            ?: android.text.SpannableStringBuilder(spanned)
        // 代码块支持点击复制
        try {
            val spans = builder.getSpans(0, builder.length, CodeBlockSpan::class.java)
            if (spans.isNotEmpty()) {
                val ctx = textView.context
                for (cs in spans) {
                    val start = builder.getSpanStart(cs)
                    val end = builder.getSpanEnd(cs)
                    if (start >= 0 && end > start) {
                        val code = builder.substring(start, end).trim()
                        builder.setSpan(
                            object : android.text.style.ClickableSpan() {
                                override fun onClick(widget: android.view.View) {
                                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                                            as android.content.ClipboardManager
                                    cm.setPrimaryClip(
                                        android.content.ClipData.newPlainText("code", code)
                                    )
                                    android.widget.Toast.makeText(
                                        ctx, "代码已复制", android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }

                                override fun updateDrawState(ds: android.text.TextPaint) {
                                    ds.isUnderlineText = false
                                }
                            },
                            start, end,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
            }
        } catch (e: Exception) {
        }
        textView.text = builder
        // 代码块的 ClickableSpan 会让 TextView 切换成 LinkMovementMethod 导致长按无法选取，
        // 强制恢复 ArrowKeyMovementMethod（同时保留点击代码块复制与长按选择）
        try {
            textView.movementMethod = ArrowKeyMovementMethod.getInstance()
        } catch (e: Exception) {
        }
    }
}
