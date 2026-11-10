package com.sistermesh.app.voice

import com.sistermesh.app.data.Facility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RequestVoiceParserTest {
    private val facilities = listOf(
        Facility("uuth", "University of Uyo Teaching Hospital", "Uyo", "Akwa Ibom"),
        Facility("city", "City Hospital", "Uyo", "Akwa Ibom"),
        Facility("city2", "City Hospital", "Eket", "Akwa Ibom")
    )

    @Test
    fun extractsOnlyExplicitBloodGroupUnitsAndExactFacility() {
        val result = RequestVoiceParser.parse(
            "I need two units of O positive blood at University of Uyo Teaching Hospital",
            facilities
        )

        assertEquals("O+", result.bloodGroup)
        assertEquals(2, result.units)
        assertEquals("uuth", result.facilityId)
    }

    @Test
    fun leavesMissingAndAmbiguousDetailsUnset() {
        val result = RequestVoiceParser.parse("Please help me at City Hospital", facilities)

        assertNull(result.bloodGroup)
        assertNull(result.units)
        assertNull(result.facilityId)
    }

    @Test
    fun rejectsUnitsOutsideTheRequestFormLimit() {
        val result = RequestVoiceParser.parse("I need 9 units of A negative blood", facilities)

        assertNull(result.units)
        assertEquals("A-", result.bloodGroup)
    }

    @Test
    fun recognizesWrittenBloodGroupNotation() {
        val result = RequestVoiceParser.parse("I need 2 units of O+.", facilities)

        assertEquals("O+", result.bloodGroup)
        assertEquals(2, result.units)
    }
}
