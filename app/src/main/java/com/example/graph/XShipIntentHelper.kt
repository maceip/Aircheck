package com.example.graph

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Zero-clipboard X/Twitter handoff and strict plain-ASCII sanitizer for Stage 4 Ship.
 *
 * Targets the native X app (`com.twitter.android`) via `twitter://post?message=...` or
 * `twitter://status?id=...` when installed, and falls back cleanly to the official
 * `https://x.com/intent/tweet?text=...` web intent otherwise.
 */
object XShipIntentHelper {

    const val X_PACKAGE_NAME: String = "com.twitter.android"

    private val MULTI_SPACE_AROUND_DASH_REGEX = Regex(" {2,}- {2,}| {2,}- | - {2,}")
    private val TRAILING_HORIZONTAL_WS_REGEX = Regex("[ \\t]+$", RegexOption.MULTILINE)

    /**
     * Normalizes curly quotes, em/en dashes, Unicode ellipsis, and non-breaking/zero-width
     * spaces into clean plain ASCII, stripping trailing whitespace per line.
     */
    fun sanitizeAscii(text: String): String {
        if (text.isEmpty()) return ""

        val normalizedLineEndings = text
            .replace("\r\n", "\n")
            .replace("\r", "\n")

        val sb = StringBuilder(normalizedLineEndings.length)
        for (i in normalizedLineEndings.indices) {
            when (val ch = normalizedLineEndings[i]) {
                '\u201C', '\u201D', '\u201E', '\u201F', '\u00AB', '\u00BB' -> sb.append('"')
                '\u2018', '\u2019', '\u201A', '\u201B', '\u2032' -> sb.append('\'')
                '\u2014', '\u2015' -> {
                    val prev = normalizedLineEndings.getOrNull(i - 1)
                    val next = normalizedLineEndings.getOrNull(i + 1)
                    if (prev != null && prev != ' ' && prev != '\n') sb.append(' ')
                    sb.append('-')
                    if (next != null && next != ' ' && next != '\n') sb.append(' ')
                }
                '\u2013', '\u2012', '\u2212' -> sb.append('-')
                '\u2026' -> sb.append("...")
                '\u00A0', '\u2000', '\u2001', '\u2002', '\u2003', '\u2004', '\u2005',
                '\u2006', '\u2007', '\u2008', '\u2009', '\u200A', '\u202F', '\u205F' -> sb.append(' ')
                '\u200B', '\u200C', '\u200D', '\uFEFF' -> {
                    // Strip zero-width characters
                }
                else -> sb.append(ch)
            }
        }

        val cleanedDashes = MULTI_SPACE_AROUND_DASH_REGEX.replace(sb.toString(), " - ")
        return TRAILING_HORIZONTAL_WS_REGEX
            .replace(cleanedDashes, "")
            .trimEnd('\n', ' ', '\t')
    }

    fun isXAppInstalled(context: Context): Boolean {
        val pm = context.packageManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(X_PACKAGE_NAME, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(X_PACKAGE_NAME, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Builds an Android [Intent] to open the sanitized post in the X app or web intent composer.
     *
     * - When `com.twitter.android` is installed:
     *   - If [replyTweetId] is non-blank: `twitter://status?id=<replyTweetId>` targeting `com.twitter.android`.
     *   - Otherwise: `twitter://post?message=<urlEncoded>` (appending [quoteUrl] when quoting) targeting `com.twitter.android`.
     * - When `com.twitter.android` is NOT installed:
     *   - Clean fallback `https://x.com/intent/tweet?text=<urlEncoded>` (with `&in_reply_to=<replyTweetId>` when replying).
     */
    fun buildOpenInXIntent(
        context: Context,
        plainText: String,
        quoteUrl: String? = null,
        replyTweetId: String? = null
    ): Intent {
        val sanitizedText = sanitizeAscii(plainText)
        val cleanReplyId = replyTweetId?.trim().orEmpty()
        val cleanQuoteUrl = quoteUrl?.trim().orEmpty()

        val composedMessage = if (cleanQuoteUrl.isNotEmpty() && !sanitizedText.contains(cleanQuoteUrl)) {
            if (sanitizedText.isBlank()) cleanQuoteUrl else "$sanitizedText\n$cleanQuoteUrl"
        } else {
            sanitizedText
        }

        val xInstalled = isXAppInstalled(context)
        val intent = if (xInstalled) {
            if (cleanReplyId.isNotEmpty()) {
                val uri = Uri.parse("twitter://status?id=${encodeQueryParam(cleanReplyId)}")
                Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(X_PACKAGE_NAME)
                    if (sanitizedText.isNotBlank()) {
                        putExtra(Intent.EXTRA_TEXT, sanitizedText)
                    }
                }
            } else {
                val uri = Uri.parse("twitter://post?message=${encodeQueryParam(composedMessage)}")
                Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(X_PACKAGE_NAME)
                }
            }
        } else {
            val baseUrl = buildString {
                append("https://x.com/intent/tweet?text=")
                append(encodeQueryParam(if (cleanReplyId.isNotEmpty()) sanitizedText else composedMessage))
                if (cleanReplyId.isNotEmpty()) {
                    append("&in_reply_to=")
                    append(encodeQueryParam(cleanReplyId))
                }
            }
            Intent(Intent.ACTION_VIEW, Uri.parse(baseUrl))
        }

        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return intent
    }

    /**
     * Copies sanitized plain-ASCII text into Android's [ClipboardManager] and returns
     * the exact string that was copied.
     */
    fun copySanitizedToClipboard(context: Context, text: String): String {
        val sanitized = sanitizeAscii(text)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Aircheck Draft", sanitized))
        return sanitized
    }

    private fun encodeQueryParam(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }
}
