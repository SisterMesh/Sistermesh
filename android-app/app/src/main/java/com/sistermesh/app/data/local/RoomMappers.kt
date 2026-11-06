package com.sistermesh.app.data.local

import com.sistermesh.app.data.BloodRequest
import com.sistermesh.app.data.Decision
import com.sistermesh.app.data.DonorResponse
import com.sistermesh.app.data.IncomingRequest
import com.sistermesh.app.data.Profile
import com.sistermesh.app.data.Receipt
import com.sistermesh.app.data.RequestStatus
import com.sistermesh.app.data.ResponseStatus
import com.sistermesh.app.data.Role
import com.sistermesh.app.data.Urgency
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun ProfileEntity.toDomain() = Profile(
    role = Role.valueOf(role),
    name = name,
    bloodGroup = bloodGroup,
    area = area,
    phone = phone,
    facility = facility,
    facilityLocation = facilityLocation,
    designation = designation
)

fun RequestWithResponses.toDomain() = BloodRequest(
    id = request.localId,
    bloodGroup = request.bloodGroup,
    urgency = Urgency.valueOf(request.urgency),
    units = request.units,
    note = request.note,
    facility = request.facility,
    createdAt = request.createdAt.toDisplayTimestamp(),
    status = RequestStatus.valueOf(request.status),
    devicesFound = request.devicesFound,
    delivered = request.delivered,
    responses = responses.map { it.toDomain() }
)

fun EmergencyRequestEntity.toDomain(responses: List<DonorResponseEntity>) =
    RequestWithResponses(this, responses).toDomain()

fun DonorResponseEntity.toDomain() = DonorResponse(
    ref = ref,
    area = area,
    distance = distance,
    status = ResponseStatus.valueOf(status)
)

fun EmergencyRequestEntity.toIncomingDomain() = IncomingRequest(
    id = localId,
    bloodGroup = bloodGroup,
    facility = facility,
    distance = distance,
    urgency = Urgency.valueOf(urgency),
    receivedAt = receivedAt,
    expiresIn = expiresIn,
    units = units,
    decision = Decision.valueOf(decision),
    confirmed = confirmed
)

fun DonationConfirmationEntity.toReceipt() = Receipt(
    id = id,
    requestId = requestLocalId,
    donorRef = donorRef,
    timestamp = confirmedAt.toDisplayTimestamp(),
    status = status,
    hash = receiptHash
)

private fun String.toDisplayTimestamp(): String = runCatching {
    Instant.parse(this)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault()))
}.getOrElse { this }
