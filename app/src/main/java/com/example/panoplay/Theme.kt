package com.example.panoplay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup

/**
 * Design tokens for PanoPlay's "Dusk" look.
 *
 * An indigo night for the page, one sunrise-amber accent for anything you can act on or
 * that is currently active, and a lilac that is reserved for the sphere motif.
 */
internal object Pano {
    val NIGHT: Int = Color.rgb(13, 14, 27)
    val RAISED: Int = Color.rgb(21, 23, 43)
    val HIGH: Int = Color.rgb(32, 35, 66)
    val HAIRLINE: Int = Color.argb(40, 188, 194, 255)

    val TEXT: Int = Color.rgb(243, 241, 251)
    val MUTED: Int = Color.rgb(146, 150, 186)
    val FAINT: Int = Color.rgb(98, 102, 148)

    val SUN: Int = Color.rgb(255, 180, 94)
    val ON_SUN: Int = Color.rgb(28, 19, 6)
    val IRIS: Int = Color.rgb(158, 163, 255)
    val LAGOON: Int = Color.rgb(111, 227, 208)
    val DANGER: Int = Color.rgb(255, 107, 122)

    /** Inter Display Medium: wordmark, titles, empty-state headline. */
    var display: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** Inter Medium: labels, buttons, values. */
    var medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** Inter Regular: secondary and body text. */
    var regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    /** Loads the bundled fonts; silently keeps the system fonts if a file cannot be read. */
    fun init(context: Context) {
        display = load(context, "fonts/InterDisplay-Medium.otf", display)
        medium = load(context, "fonts/Inter-Medium.otf", medium)
        regular = load(context, "fonts/Inter-Regular.otf", regular)
    }

    private fun load(context: Context, path: String, fallback: Typeface): Typeface =
        try {
            Typeface.createFromAsset(context.assets, path)
        } catch (_: Exception) {
            fallback
        }

    /** Returns [color] with its alpha replaced by [alpha] (0..1). */
    fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    /** Returns [color] with its existing alpha multiplied by [factor]. */
    fun scaleAlpha(color: Int, factor: Float): Int {
        val a = (Color.alpha(color) * factor + 0.5f).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    fun lerp(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun mix(a: Int, b: Int): Int = (a + (b - a) * f + 0.5f).toInt().coerceIn(0, 255)
        return Color.argb(
            mix(Color.alpha(from), Color.alpha(to)),
            mix(Color.red(from), Color.red(to)),
            mix(Color.green(from), Color.green(to)),
            mix(Color.blue(from), Color.blue(to))
        )
    }
}

internal const val MATCH: Int = ViewGroup.LayoutParams.MATCH_PARENT
internal const val WRAP: Int = ViewGroup.LayoutParams.WRAP_CONTENT
