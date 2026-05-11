package cz.julek.rails.util

import android.text.Spanned
import androidx.core.text.HtmlCompat
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Lightweight Markdown parser for the RAILS Android app.
 *
 * Supports three formatting types:
 *   - **bold**   → <b>bold</b>   (notifications/overlay) / SpanStyle(fontWeight = Bold) (Compose)
 *   - *italic*   → <i>italic</i> (notifications/overlay) / SpanStyle(fontStyle = Italic) (Compose)
 *   - `code`     → <tt>code</tt> (notifications/overlay) / SpanStyle(fontFamily = Monospace) (Compose)
 *
 * Edge case handling:
 *   - Unmatched markers are left as-is (no partial rendering)
 *   - Bold is processed before italic to avoid interference
 *   - Code backticks are processed first to protect their content
 *   - Nested formatting is NOT supported (e.g., **bold *italic* bold** renders bold with literal asterisks)
 */

// ═══════════════════════════════════════════════════════════════════════
//  Part 1: HTML output — for Notifications & Overlay (TextView)
// ═══════════════════════════════════════════════════════════════════════

/**
 * Converts basic Markdown to HTML tags suitable for [HtmlCompat.fromHtml].
 *
 * Processing order: code → bold → italic (code first to protect content,
 * bold before italic to prevent `**` from being consumed by `*` regex).
 *
 * @return HTML string with <b>, <i>, <tt> tags replacing Markdown markers.
 */
fun String.parseMarkdownToHtml(): String {
    var result = this

    // 1) `code` → <tt>code</tt>  (process first to protect content from asterisk regex)
    result = result.replace(Regex("""`([^`\n]+)`""")) { match ->
        "<tt>${escapeHtml(match.groupValues[1])}</tt>"
    }

    // 2) **bold** → <b>bold</b>  (non-greedy, requires closing **)
    result = result.replace(Regex("""\*\*(.+?)\*\*""")) { match ->
        "<b>${escapeHtml(match.groupValues[1])}</b>"
    }

    // 3) *italic* → <i>italic</i>  (single *, not preceded/followed by another *)
    result = result.replace(Regex("""(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)""")) { match ->
        "<i>${escapeHtml(match.groupValues[1])}</i>"
    }

    return result
}

/**
 * Parses Markdown and returns a [Spanned] suitable for Android TextView / Notifications.
 * Uses [HtmlCompat.fromHtml] internally.
 */
fun String.parseMarkdownToSpanned(): Spanned {
    return HtmlCompat.fromHtml(this.parseMarkdownToHtml(), HtmlCompat.FROM_HTML_MODE_COMPACT)
}

/**
 * Minimal HTML escaping for text inside Markdown-converted tags.
 * Only escapes characters that would break HTML parsing.
 */
private fun escapeHtml(text: String): String {
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

// ═══════════════════════════════════════════════════════════════════════
//  Part 2: AnnotatedString output — for Jetpack Compose Chat UI
// ═══════════════════════════════════════════════════════════════════════

/**
 * Data class representing a single parsed Markdown span.
 */
private data class MarkdownSpan(
    val start: Int,        // Start index in the *clean* (marker-stripped) text
    val end: Int,          // End index in the *clean* text
    val style: SpanStyle,  // Compose SpanStyle to apply
)

/**
 * Converts basic Markdown to a Compose [AnnotatedString].
 *
 * Single-pass algorithm that scans left-to-right, finds matching
 * closing markers, records span positions, and strips Markdown
 * syntax characters from the output.
 *
 * Supports: **bold**, *italic*, `code`
 * Unmatched markers are left as literal text.
 */
fun String.toAnnotatedMarkdownString(): AnnotatedString {
    val spans = mutableListOf<MarkdownSpan>()
    val sb = StringBuilder(this)
    var i = 0

    while (i < sb.length) {
        // ── Code: `text` ──
        if (sb[i] == '`') {
            val closeIdx = sb.indexOf('`', i + 1)
            if (closeIdx != -1 && closeIdx > i + 1) {
                // Found matching backticks
                val contentLen = closeIdx - i - 1  // length of content between backticks
                sb.deleteCharAt(closeIdx)  // Remove closing `
                sb.deleteCharAt(i)         // Remove opening `
                spans.add(MarkdownSpan(i, i + contentLen, SpanStyle(fontFamily = FontFamily.Monospace)))
                // i now points to first char of code content, continue from here
                continue
            }
        }

        // ── Bold: **text** ──
        if (i + 1 < sb.length && sb[i] == '*' && sb[i + 1] == '*') {
            // Look for closing **
            val closeIdx = sb.indexOf("**", i + 2)
            if (closeIdx != -1 && closeIdx > i + 2) {
                // Found matching **
                val contentLen = closeIdx - i - 2  // length of content between ** markers
                sb.delete(closeIdx, closeIdx + 2)   // Remove closing **
                sb.delete(i, i + 2)                  // Remove opening **
                spans.add(MarkdownSpan(i, i + contentLen, SpanStyle(fontWeight = FontWeight.Bold)))
                continue
            }
        }

        // ── Italic: *text* (single *, not part of **) ──
        if (sb[i] == '*') {
            // Make sure this isn't part of a ** pair
            val prevIsStar = i > 0 && sb[i - 1] == '*'
            val nextIsStar = i + 1 < sb.length && sb[i + 1] == '*'
            if (!prevIsStar && !nextIsStar) {
                // Look for closing * (not part of **)
                var closeIdx = -1
                for (j in i + 1 until sb.length) {
                    if (sb[j] == '*') {
                        val cPrevIsStar = j > 0 && sb[j - 1] == '*'
                        val cNextIsStar = j + 1 < sb.length && sb[j + 1] == '*'
                        if (!cPrevIsStar && !cNextIsStar) {
                            closeIdx = j
                            break
                        }
                    }
                }
                if (closeIdx != -1 && closeIdx > i + 1) {
                    // Found matching *
                    val contentLen = closeIdx - i - 1  // length of content between * markers
                    sb.deleteCharAt(closeIdx)  // Remove closing *
                    sb.deleteCharAt(i)         // Remove opening *
                    spans.add(MarkdownSpan(i, i + contentLen, SpanStyle(fontStyle = FontStyle.Italic)))
                    continue
                }
            }
        }

        i++
    }

    // Build AnnotatedString from clean text + collected spans
    return buildAnnotatedString {
        append(sb.toString())
        for (span in spans) {
            // Clamp span bounds to valid range
            val start = span.start.coerceIn(0, length)
            val end = span.end.coerceIn(0, length)
            if (start < end) {
                addStyle(span.style, start, end)
            }
        }
    }
}
