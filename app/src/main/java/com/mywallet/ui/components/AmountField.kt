package com.mywallet.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.mywallet.core.money.DigitGrouping
import com.mywallet.core.money.group
import com.mywallet.ui.LocalMoneyFormatter

/**
 * Groups the digits in a box the user is typing an amount into — 1,00,000 —
 * the way every figure the app prints is grouped.
 *
 * A **visual** transformation and deliberately not a rewrite of the field's own
 * value. The value stays exactly the run of digits that was typed, which is what
 * `MoneyFormatter.parse` reads and what `toPlainInput` writes back, so nothing
 * about saving, parsing or rounding changes: only what is drawn. Grouping the
 * stored string instead would put separators into the text the caret walks
 * through, and every backspace across one would need special handling.
 *
 * That is what replaced the rule that a box being filled in stays ungrouped. It
 * was right when the alternative was rewriting the value under the thumb, and it
 * cost the reader the one thing the grouping is for: रू 2700000 typed into a
 * loan is unreadable at a glance, and the figure it becomes an inch below it is
 * not.
 *
 * Only the whole part is grouped. Everything from the decimal point onwards is
 * passed through as typed — including a lone trailing "." — because a box mid-
 * figure must not have its decimals tidied while the user is still writing them.
 *
 * The separator comes from the locale by way of the formatter, so a field and
 * the amount printed under it cannot punctuate a number two different ways.
 */
@Composable
fun rememberAmountGrouping(): VisualTransformation {
    val formatter = LocalMoneyFormatter.current
    val separator = formatter.groupingSeparator
    // And in the same style, for the reason it takes the same separator: a box
    // and the figure printed under it must not punctuate one number two ways.
    val grouping = formatter.grouping
    return remember(separator, grouping) { AmountGrouping(separator, grouping) }
}

private class AmountGrouping(
    private val separator: Char,
    private val grouping: DigitGrouping,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        // Separators the user typed themselves are dropped and re-supplied by the
        // grouping. `parse` tolerates them, so they reach here, and left in they
        // would be grouped a second time — "1,0,00,0".
        val kept = ArrayList<Int>(raw.length)
        val sig = StringBuilder(raw.length)
        for (i in raw.indices) {
            if (raw[i] == separator || raw[i] == ',') continue
            kept.add(i)
            sig.append(raw[i])
        }
        val significant = sig.toString()
        val point = significant.indexOf('.')
        val whole = if (point < 0) significant else significant.substring(0, point)
        val tail = if (point < 0) "" else significant.substring(point)
        val out = group(whole, separator, grouping) + tail

        // Walked in step rather than computed from group sizes: the output is the
        // significant characters with separators inserted between them, and a
        // digit is never the separator, so which is which is unambiguous.
        val rawToOut = IntArray(raw.length + 1)
        val outToRaw = IntArray(out.length + 1)
        var j = 0
        for (k in out.indices) {
            if (j < significant.length && out[k] == significant[j]) {
                rawToOut[kept[j]] = k
                outToRaw[k] = kept[j]
                j++
            } else {
                // An inserted separator belongs to the character after it, so a
                // caret sitting on one resolves to the digit it precedes.
                outToRaw[k] = if (j < kept.size) kept[j] else raw.length
            }
        }
        rawToOut[raw.length] = out.length
        outToRaw[out.length] = raw.length
        // A separator the user typed maps to wherever the next kept character
        // went, so the caret does not jump to the start when they backspace it.
        var next = out.length
        for (i in raw.indices.reversed()) {
            if (raw[i] == separator || raw[i] == ',') rawToOut[i] = next else next = rawToOut[i]
        }

        return TransformedText(
            AnnotatedString(out),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int =
                    rawToOut[offset.coerceIn(0, raw.length)]

                override fun transformedToOriginal(offset: Int): Int =
                    outToRaw[offset.coerceIn(0, out.length)]
            },
        )
    }
}
