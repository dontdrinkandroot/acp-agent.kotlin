package net.dontdrinkandroot.acpagent.tools

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.util.Locale

/**
 * Converts fetched HTTP content into the agent's line-oriented text model.
 *
 * Non-HTML payloads (plain text, JSON, markdown, XML, source code) pass
 * through as decoded text; HTML is parsed with jsoup and rendered as
 * newline-separated text: block elements (headings, p, li, tr, br, pre,
 * blockquote, ...) each terminate a line, inline tags are flattened. This
 * keeps the output paged line-by-line like read_file instead of collapsing
 * a page into one giant string.
 */
internal object WebContentConverter {

    fun toLines(body: String, contentType: String): List<String> {
        return if (isHtml(contentType)) renderLines(Jsoup.parse(body)) else body.split('\n')
    }

    private val HTML_MIME_TYPES = setOf(
        "text/html",
        "application/xhtml+xml",
        "text/xml",
        "application/xml",
    )

    internal fun isHtml(contentType: String): Boolean {
        val mime = contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
        return mime in HTML_MIME_TYPES || mime.endsWith("+xml")
    }

    /**
     * Renders the parsed DOM to lines: block elements flush the current line,
     * inline content accumulates. `pre` renders its raw text with newlines
     * preserved; `li` and table cells are prefixed ("• ", "cell | ") so the
     * structure survives the tag stripping; whitespace is collapsed outside
     * `pre` like a browser does.
     */
    internal fun renderLines(document: org.jsoup.nodes.Document): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var pre = false

        fun flush() {
            if (sb.isBlank()) {
                if (out.isNotEmpty() && out.last().isNotBlank()) out.add("")
                return
            }
            out += sb.toString().trimEnd()
            sb.clear()
        }

        fun isBlock(node: Node): Boolean = node is Element && node.tag().getName().lowercase() in BLOCK_TAGS

        fun walk(node: Node) {
            when (node) {
                is TextNode -> {
                    val text = node.text() // entity-decoded, whitespace-collapsed by jsoup
                    if (pre) {
                        sb.append(text)
                    } else {
                        val trimmed = text.trim()
                        if (trimmed.isNotEmpty()) {
                            if (sb.isNotEmpty()) sb.append(' ')
                            sb.append(trimmed)
                        }
                    }
                }
                is Element -> {
                    val name = node.tag().getName().lowercase()
                    if (name == "pre" || name == "textarea") {
                        flush()
                        pre = true
                        sb.append(node.text())
                        flush()
                        pre = false
                        return
                    }
                    if (name == "br") {
                        flush()
                        return
                    }
                    if (name == "li") {
                        flush()
                        sb.append("•")
                    }
                    if (name == "td" || name == "th") {
                        flush()
                        sb.append("|")
                    }
                    if (isBlock(node) && sb.isNotEmpty()) flush()
                    node.childNodes().forEach { walk(it) }
                    if (isBlock(node)) flush()
                }
                else -> Unit
            }
        }

        document.body().childNodes().forEach { walk(it) }
        flush()
        // A leading flush() before any content would have seeded an empty line
        // guard: drop leading blank lines so line 1 is real content.
        while (out.isNotEmpty() && out.first().isBlank()) out.removeAt(0)
        return out
    }

    private val BLOCK_TAGS = setOf(
        "address", "article", "aside", "blockquote", "canvas", "dd", "div", "dl", "dt",
        "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4",
        "h5", "h6", "header", "hr", "main", "nav", "noscript", "ol", "p", "section",
        "table", "ul", "video", "audio", "iframe", "script", "style", "template",
    )
}
