package com.sistermesh.app.data

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Role { DONOR, CHW }

enum class Urgency(val label: String) { CRITICAL("Critical"), URGENT("Urgent") }

enum class ResponseStatus(val label: String) {
    PENDING("Pending"), DELIVERED("Delivered"), ACCEPTED("Accepted"),
    DECLINED("Declined"), EXPIRED("Expired"), CONFIRMED("Donation confirmed"),
    WITHDRAWN("Withdrawn")
}

enum class RequestStatus(val label: String) {
    SEARCHING("Searching"), WAITING("Waiting for response"), ACCEPTED("Donor accepted"),
    COMPLETED("Completed"), CANCELLED("Cancelled"), EXPIRED("Expired");

    val isActive get() = this == SEARCHING || this == WAITING || this == ACCEPTED
}

enum class Decision { NONE, ACCEPTED, DECLINED, WITHDRAWN }

val BloodGroups = listOf("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-")
val Languages = listOf("English", "Hausa", "Yoruba", "Igbo", "Pidgin")

data class Profile(
    val role: Role,
    val name: String,
    val bloodGroup: String = "",
    val area: String = "",
    val phone: String = "",
    val facility: String = "",
    val facilityLocation: String = "",
    val designation: String = ""
) {
    /** Pseudonymous reference shown instead of a real identity. */
    val donorRef: String get() = name.hashCode().toUInt().toString(16).padStart(4, '0').take(4).uppercase()
}

data class DonorResponse(val ref: String, val area: String, val distance: String, val status: ResponseStatus)

data class BloodRequest(
    val id: String,
    val bloodGroup: String,
    val urgency: Urgency,
    val units: Int,
    val note: String,
    val facility: String,
    val createdAt: String,
    val status: RequestStatus,
    val devicesFound: Int = 0,
    val delivered: Int = 0,
    val responses: List<DonorResponse> = emptyList()
) {
    val accepted get() = responses.count { it.status == ResponseStatus.ACCEPTED || it.status == ResponseStatus.CONFIRMED }
    val declined get() = responses.count { it.status == ResponseStatus.DECLINED }
}

data class IncomingRequest(
    val id: String,
    val bloodGroup: String,
    val facility: String,
    val distance: String,
    val urgency: Urgency,
    val receivedAt: String,
    val expiresIn: String,
    val units: Int,
    val decision: Decision = Decision.NONE,
    val confirmed: Boolean = false
)

data class Receipt(
    val id: String,
    val requestId: String,
    val donorRef: String,
    val timestamp: String,
    val status: String,
    val hash: String
)

data class ChatMessage(val text: String, val mine: Boolean)

fun sha256(input: String): String =
    MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }

fun nowStamp(): String = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date())
