package com.webcode.app.ui

import android.content.Context
import androidx.core.content.ContextCompat
import com.webcode.app.R
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.latex.JLatexMathPlugin
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
            // 行内 $...$ 渲染会造成字体基线偏移，禁用；保留 $$...$$ 块级公式
            latexBuilder.inlinesEnabled(false)
            latexBuilder.theme()
                // 默认 blockFitCanvas=true 会把宽度小于行宽的公式横向拉满（宽拉伸高不变）
                // 导致公式变形、与上下行文字错位；关闭后保持原始宽高比
                .blockFitCanvas(false)
                // 块级公式与上下正文之间留出空隙，避免贴死
                .blockPadding(
                    io.noties.markwon.ext.latex.JLatexMathTheme.Padding.symmetric(
                        (4 * density).toInt(), (12 * density).toInt()
                    )
                )
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
        textView.text = spanned
        // 系统选区：长按可选取复制（不设 LinkMovementMethod，避免其吞掉长按事件）
        try {
            textView.setTextIsSelectable(true)
            textView.movementMethod = android.text.method.ArrowKeyMovementMethod.getInstance()
        } catch (e: Exception) {
        }
    }

    /** 提取 markdown 中的所有 ``` 代码块内容（用于选区菜单"复制代码块"） */
    fun extractCodeBlocks(markdown: String): List<String> {
        val out = mutableListOf<String>()
        try {
            val re = Regex("```[\\w+-]*\\s*\\n?([\\s\\S]*?)```")
            for (m in re.findAll(markdown)) {
                val code = m.groupValues[1].trim('\n')
                if (code.isNotBlank()) out.add(code)
            }
        } catch (e: Exception) {
        }
        return out
    }
}
