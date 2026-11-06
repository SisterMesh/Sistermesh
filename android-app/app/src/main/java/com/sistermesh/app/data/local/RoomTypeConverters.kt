package com.sistermesh.app.data.local

import androidx.room.TypeConverter
import java.util.UUID

class RoomTypeConverters {
    @TypeConverter
    fun uuidToString(value: UUID?): String? = value?.toString()

    @TypeConverter
    fun stringToUuid(value: String?): UUID? = value?.let(UUID::fromString)
}
