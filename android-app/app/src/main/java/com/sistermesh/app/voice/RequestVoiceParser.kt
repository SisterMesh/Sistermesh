package com.sistermesh.app.voice

import com.sistermesh.app.data.Facility

data class RequestVoiceDraft(
    val bloodGroup: String? = null,
    val units: Int? = null,
    val facilityId: String? = null
)

/**
 * Conservative extraction only: this never fills values that are not explicit in the transcript.
 * Any extracted values remain editable in the request form and are not submitted automatically.
 */
object RequestVoiceParser {
    private val bloodGroupPatterns = listOf(
        Regex("""\b(AB|A|B|O)\s*(?:positive\b|\+)""", RegexOption.IGNORE_CASE),
        Regex("""\b(AB|A|B|O)\s*(?:negative\b|-)""", RegexOption.IGNORE_CASE)
    )
    private val numberWords = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6
    )

    fun parse(transcript: String, facilities: List<Facility>): RequestVoiceDraft {
        val bloodGroup = bloodGroupPatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(transcript)?.let { match ->
                val type = match.groupValues[1].uppercase()
                val suffix = match.value.trim().last()
                val positive = suffix == '+' || match.value.contains("positive", ignoreCase = true)
                "$type${if (positive) "+" else "-"}"
            }
        }
        val units = Regex("""\b(\d+|one|two|three|four|five|six)\s+units?\b""", RegexOption.IGNORE_CASE)
            .find(transcript)
            ?.groupValues
            ?.get(1)
            ?.let { value ->
                value.toIntOrNull() ?: numberWords[value.lowercase()]
            }
            ?.takeIf { it in 1..6 }

        val normalizedTranscript = normalize(transcript)
        val facilityMatches = facilities
            .mapNotNull { facility ->
                val candidates = listOf(facility.label, facility.name)
                    .map(::normalize)
                    .filter(String::isNotBlank)
                    .distinct()
                    .filter(normalizedTranscript::contains)
                candidates.maxByOrNull(String::length)?.let { facility to it.length }
            }
            .sortedByDescending { it.second }
        val facilityId = facilityMatches.firstOrNull()?.let { best ->
            val equallySpecific = facilityMatches.takeWhile { it.second == best.second }
            best.first.id.takeIf { equallySpecific.size == 1 }
        }

        return RequestVoiceDraft(bloodGroup, units, facilityId)
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim().replace(Regex("""\s+"""), " ")
}
