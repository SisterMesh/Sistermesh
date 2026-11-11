package com.sistermesh.app.voice

/** Keeps final speech segments and removes repeated words when a recognizer restarts. */
class TranscriptAccumulator {
    private var committed = ""

    val text: String get() = committed

    fun clear() {
        committed = ""
    }

    fun appendFinal(segment: String): String {
        val next = clean(segment)
        if (next.isBlank()) return committed
        if (committed.isBlank()) {
            committed = next
            return committed
        }

        val currentTokens = committed.split(WHITESPACE)
        val nextTokens = next.split(WHITESPACE)
        val currentNormalized = currentTokens.map(::normalizeToken)
        val nextNormalized = nextTokens.map(::normalizeToken)

        when {
            nextNormalized == currentNormalized -> return committed
            nextNormalized.size > currentNormalized.size &&
                nextNormalized.take(currentNormalized.size) == currentNormalized -> {
                committed = next
                return committed
            }
            currentNormalized.size > nextNormalized.size &&
                currentNormalized.takeLast(nextNormalized.size) == nextNormalized -> return committed
        }

        val overlap = (minOf(currentTokens.size, nextTokens.size, MAX_OVERLAP) downTo 1)
            .firstOrNull { count ->
                currentNormalized.takeLast(count) == nextNormalized.take(count)
            } ?: 0
        val remainder = nextTokens.drop(overlap).joinToString(" ")
        if (remainder.isNotBlank()) committed = listOf(committed, remainder).joinToString(" ")
        return committed
    }

    fun withPartial(partial: String): String {
        val cleaned = clean(partial)
        if (cleaned.isBlank()) return committed
        if (committed.isBlank()) return cleaned
        val preview = TranscriptAccumulator().apply {
            appendFinal(committed)
            appendFinal(cleaned)
        }.text
        return preview
    }

    private fun clean(value: String): String =
        value.replace(WHITESPACE, " ").trim()

    private fun normalizeToken(value: String): String =
        value.lowercase().trim { !it.isLetterOrDigit() }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val MAX_OVERLAP = 12
    }
}
