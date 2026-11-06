package com.sistermesh.app.data

/** Local sample data so every screen is interactive without a backend. */
object MockData {

    fun incoming() = listOf(
        IncomingRequest(
            id = "REQ-4821", bloodGroup = "O-", facility = "St. Mary's Maternity Centre",
            distance = "About 1.2 km away", urgency = Urgency.CRITICAL,
            receivedAt = "2 min ago", expiresIn = "Expires in 28 min", units = 2
        ),
        IncomingRequest(
            id = "REQ-3307", bloodGroup = "O-", facility = "Rumuola Community Clinic",
            distance = "About 3.4 km away", urgency = Urgency.URGENT,
            receivedAt = "Yesterday", expiresIn = "Expired", units = 1,
            decision = Decision.DECLINED
        ),
        IncomingRequest(
            id = "REQ-2190", bloodGroup = "O-", facility = "Trans-Amadi General",
            distance = "About 2.0 km away", urgency = Urgency.CRITICAL,
            receivedAt = "12 Oct", expiresIn = "Completed", units = 1,
            decision = Decision.ACCEPTED, confirmed = true
        )
    )

    fun requests() = listOf(
        BloodRequest(
            "REQ-2190", "O-", Urgency.CRITICAL, 1, "", "Trans-Amadi General", "12 Oct 2026, 14:20",
            RequestStatus.COMPLETED, 5, 5,
            listOf(
                DonorResponse("91BE", "Trans-Amadi", "About 2.0 km", ResponseStatus.CONFIRMED),
                DonorResponse("0D4A", "Rumuola", "About 3.1 km", ResponseStatus.DECLINED)
            )
        ),
        BloodRequest(
            "REQ-1744", "B+", Urgency.URGENT, 2, "", "Trans-Amadi General", "29 Sep 2026, 09:05",
            RequestStatus.EXPIRED, 3, 3,
            listOf(DonorResponse("C52F", "Diobu", "About 4.2 km", ResponseStatus.EXPIRED))
        ),
        BloodRequest(
            "REQ-1102", "A-", Urgency.CRITICAL, 1, "", "Trans-Amadi General", "14 Sep 2026, 22:40",
            RequestStatus.CANCELLED, 2, 2
        )
    )

    fun receipts(): List<Receipt> {
        val ref = "91BE"
        val ts = "12 Oct 2026, 16:05"
        return listOf(Receipt("SM-R-2190", "REQ-2190", ref, ts, "Confirmed by healthcare worker", sha256("REQ-2190|$ref|$ts")))
    }

    val donorQuickReplies = listOf("I'm on my way", "I need 30 more minutes", "Which entrance should I use?")
    val chwQuickReplies = listOf("Please come to maternity reception", "Bring a valid ID", "Thank you, we are ready")
}
