package com.google.antigravity.ui.components

import java.text.BreakIterator
import java.util.Locale

/** Visual lines only. The message and the inline placeholder's alternate text stay intact.
 * Greedy character wrapping avoids the native word break after a Windows drive prefix.
 */
internal fun inlineCodeLines(value: String, maxWidth: Int, measureWidth: (String) -> Int): List<String> =
    value.split('\n').flatMap { line ->
        if (line.isEmpty()) return@flatMap listOf(line)
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(line) }
        val boundaries = mutableListOf<Int>()
        var position = iterator.first()
        while (position != BreakIterator.DONE) {
            boundaries.add(position)
            position = iterator.next()
        }
        buildList {
            var start = 0
            while (start < boundaries.lastIndex) {
                // Find a nearby upper bound before binary search. Measuring the entire
                // remaining token repeatedly is quadratic and can overflow text constraints.
                var end = start + 1 // Keep one grapheme intact even if the viewport is too narrow.
                var probe = end
                var high = end
                while (true) {
                    if (measureWidth(line.substring(boundaries[start], boundaries[probe])) > maxWidth) {
                        high = probe - 1
                        break
                    }
                    end = probe
                    if (probe == boundaries.lastIndex) {
                        high = probe
                        break
                    }
                    probe = (start.toLong() + (probe - start).toLong() * 2)
                        .coerceAtMost(boundaries.lastIndex.toLong()).toInt()
                }
                var low = end + 1
                while (low <= high) {
                    val middle = (low + high) / 2
                    if (measureWidth(line.substring(boundaries[start], boundaries[middle])) <= maxWidth) {
                        end = middle
                        low = middle + 1
                    } else high = middle - 1
                }
                add(line.substring(boundaries[start], boundaries[end]))
                start = end
            }
        }
    }
