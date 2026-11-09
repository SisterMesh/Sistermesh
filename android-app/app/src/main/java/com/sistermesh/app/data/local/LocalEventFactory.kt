package com.sistermesh.app.data.local

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.Instant
import java.util.UUID

internal object LocalEventFactory {
    fun requestCreate(
        localId: String,
        bloodGroup: String,
        urgency: String,
        units: Int,
        facilityId: String? = null,
        isForSelf: Boolean = true,
        beneficiaryName: String? = null,
        beneficiaryRelationship: String? = null,
        additionalInfo: String? = null,
        originDeviceId: String,
        localCreatedAt: String,
        ownerAccountId: String? = null
    ) = event(
        eventType = "request.create",
        localEntityId = localId,
        entityId = null,
        originDeviceId = originDeviceId,
        localCreatedAt = localCreatedAt,
        ownerAccountId = ownerAccountId,
        payload = JSONObject()
            .put("requestedBloodGroup", apiBloodGroup(bloodGroup))
            .put("urgency", urgency)
            .put("units", units)
            .put("facilityId", facilityId)
            .put("isForSelf", isForSelf)
            .put("beneficiaryName", beneficiaryName)
            .put("beneficiaryRelationship", beneficiaryRelationship)
            .put("additionalInfo", additionalInfo)
            .toString()
    )

    fun event(
        eventType: String,
        localEntityId: String?,
        entityId: String?,
        originDeviceId: String,
        localCreatedAt: String,
        payload: String,
        correlationId: String? = null,
        dependsOnEventId: String? = null,
        blockedReason: Pair<String, String>? = null,
        ownerAccountId: String? = null
    ) = SyncEventEntity(
        eventId = UUID.randomUUID().toString(),
        eventType = eventType,
        localEntityId = localEntityId,
        entityId = entityId,
        originDeviceId = originDeviceId,
        localCreatedAt = localCreatedAt,
        correlationId = correlationId,
        payloadJson = payload,
        status = if (blockedReason == null) "PENDING" else "BLOCKED",
        dependsOnEventId = dependsOnEventId,
        ownerAccountId = ownerAccountId,
        serverErrorCode = blockedReason?.first,
        serverExplanation = blockedReason?.second
    )

    fun now() = Instant.now().toString()

    fun toIsoTimestamp(displayValue: String): String {
        val current = Instant.now()
        val relativeMinutes = Regex("^(\\d+) min ago$").matchEntire(displayValue)?.groupValues?.get(1)?.toLongOrNull()
        if (relativeMinutes != null) return current.minus(Duration.ofMinutes(relativeMinutes)).toString()
        if (displayValue == "Just now") return current.toString()
        if (displayValue == "Yesterday") return current.minus(Duration.ofDays(1)).toString()
        return runCatching {
            val fullDate = runCatching {
                LocalDateTime.parse(
                    displayValue,
                    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
                )
            }.getOrElse {
                LocalDate.parse(
                    "$displayValue ${LocalDate.now().year}",
                    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
                ).atTime(12, 0)
            }
            fullDate.atZone(ZoneId.systemDefault()).toInstant().toString()
        }.getOrElse { current.toString() }
    }

    fun apiBloodGroup(group: String): String = when (group.uppercase()) {
        "A+" -> "A_POSITIVE"
        "A-" -> "A_NEGATIVE"
        "B+" -> "B_POSITIVE"
        "B-" -> "B_NEGATIVE"
        "AB+" -> "AB_POSITIVE"
        "AB-" -> "AB_NEGATIVE"
        "O+" -> "O_POSITIVE"
        "O-" -> "O_NEGATIVE"
        else -> throw IllegalArgumentException("Unsupported blood group: $group")
    }
}
