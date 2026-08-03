package com.webcode.app.ui

import android.content.Context
import androidx.core.content.ContextCompat
import com.webcode.app.R
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.latex.JLatexMathPlugin
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
        get(textView.context).setMarkdown(textView, markdown)
    }
}
