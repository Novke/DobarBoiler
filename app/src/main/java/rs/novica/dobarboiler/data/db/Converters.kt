// Located in data/db/ per SPEC §4.1; declared in the parent package because every
// cross-module reference (AppContainer, repositories, SPEC snippets) uses
// `rs.novica.dobarboiler.data.*` for the Room types.
package rs.novica.dobarboiler.data

import androidx.room.TypeConverter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Room type converters (SPEC §4.2 column formats):
 * - [Instant]   ↔ INTEGER epoch millis
 * - [LocalDate] ↔ TEXT ISO "yyyy-MM-dd" (sorts lexicographically == chronologically)
 * - [LocalTime] ↔ TEXT "HH:mm"
 */
class Converters {

    @TypeConverter
    fun instantToLong(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun longToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun localDateToString(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun stringToLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter
    fun localTimeToString(value: LocalTime?): String? = value?.format(HH_MM)

    @TypeConverter
    fun stringToLocalTime(value: String?): LocalTime? = value?.let { LocalTime.parse(it, HH_MM) }

    private companion object {
        val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
