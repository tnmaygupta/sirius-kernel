/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.kernel.nls
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import sirius.kernel.SiriusExtension
import sirius.kernel.async.CallContext
import sirius.kernel.commons.Amount
import java.time.*
import kotlin.test.assertEquals
import java.math.BigDecimal

/**
 * Tests the [NLS] class.
 */
@ExtendWith(SiriusExtension::class)

class NLSTest {

    companion object {
        @JvmStatic
        private fun `generator for parseUserString fails when expected`(): Stream<Arguments?>? {
            return Stream.of(
                Arguments.of(
                    "42,1",
                    Int::class.java,
                    IllegalArgumentException::class.java,
                    "Bitte geben Sie eine gültige Zahl ein. '42,1' ist ungültig."
                ),
                Arguments.of(
                    "2999999999",
                    Int::class.java, IllegalArgumentException::class.java,
                    "Bitte geben Sie eine gültige Zahl ein. '2999999999' ist ungültig."
                ),
                Arguments.of(
                    "blub",
                    Double::class.java, IllegalArgumentException::class.java,
                    "Bitte geben Sie eine gültige Dezimalzahl ein. 'blub' ist ungültig."
                )
            )
        }

        @JvmStatic
        private fun `generator for parseUserString for a LocalTime works`(): Stream<Arguments?>? {
            return Stream.of(
                Arguments.of(
                    "14:30:12", LocalTime.of(14, 30, 12, 0)
                ),
                Arguments.of(
                    "14:30", LocalTime.of(14, 30, 0, 0)
                ),
                Arguments.of(
                    "14", LocalTime.of(14, 0, 0, 0)
                )
            )
        }
    }

    @Test
    fun `toMachineString() formats a LocalDate as date without time`() {
        val date = LocalDate.of(2014, 8, 9)
        val result = NLS.toMachineString(date)
        assertEquals("2014-08-09", result)
    }

    @Test
    fun `toMachineString() formats a LocalDateTime as date with time`() {
        val date = LocalDateTime.of(2014, 8, 9, 12, 0, 59)
        val result = NLS.toMachineString(date)
        assertEquals("2014-08-09 12:00:59", result)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|', textBlock = """
        123456.789 | 123456.79
        123456.81  | 123456.81
        0.113      | 0.11
        -11111.1   | -11111.10
        1          | 1.00
        -1         | -1.00
        0          | 0.00"""
    )
    fun `toMachineString() of Amount is properly formatted`(
        input: Double,
        output: String
    ) {
        assertEquals(NLS.toMachineString(Amount.of(input)), output)
    }

    @Test
    fun `toUserString() formats a LocalDateTime as date with time`() {
        val date = LocalDateTime.of(2014, 8, 9, 12, 0, 59)
        CallContext.getCurrent().language = "de"
        val result = NLS.toUserString(date)
        assertEquals("09.08.2014 12:00:59", result)
    }

    @Test
    fun `toUserString() formats a LocalTime as simple time`() {
        val date = LocalTime.of(17, 23, 15)
        CallContext.getCurrent().language = "de"
        val result = NLS.toUserString(date)
        assertEquals("17:23:15", result)
    }


    @Test
    fun `toUserString() formats a LocalDate as date without time`() {
        val date = LocalDate.of(2014, 8, 9)
        CallContext.getCurrent().language = "de"
        assertEquals("09.08.2014", NLS.toUserString(date))
    }

    @Test
    fun `toUserString() formats an Instant successfully`() {
        val instant = Instant.now()
        val date = instant.atZone(ZoneId.systemDefault()).toLocalDateTime()
        CallContext.getCurrent().language = "de"
        val dateFormatted = NLS.toUserString(date)
        val instantFormatted = NLS.toUserString(instant)
        assertEquals(dateFormatted, instantFormatted)
    }

    @Test
    fun `toUserString() formats null as empty string`() {
        val input = null
        assertEquals("", NLS.toUserString(input))
    }

    @Test
    fun `toSpokenDate() formats dates and dateTimes correctly`() {
        assertEquals("heute", NLS.toSpokenDate(LocalDate.now()))
        assertEquals("vor wenigen Minuten", NLS.toSpokenDate(LocalDateTime.now()))
        assertEquals("gestern", NLS.toSpokenDate(LocalDate.now().minusDays(1)))
        assertEquals("gestern", NLS.toSpokenDate(LocalDateTime.now().minusDays(1)))
        assertEquals("morgen", NLS.toSpokenDate(LocalDate.now().plusDays(1)))
        assertEquals("morgen", NLS.toSpokenDate(LocalDateTime.now().plusDays(1)))
        assertEquals("01.01.2114", NLS.toSpokenDate(LocalDate.of(2114, 1, 1)))
        assertEquals("01.01.2114", NLS.toSpokenDate(LocalDateTime.of(2114, 1, 1, 0, 0)))
        assertEquals("01.01.2014", NLS.toSpokenDate(LocalDate.of(2014, 1, 1)))
        assertEquals("01.01.2014", NLS.toSpokenDate(LocalDateTime.of(2014, 1, 1, 0, 0)))
        assertEquals("vor wenigen Minuten", NLS.toSpokenDate(LocalDateTime.now().minusMinutes(5)))
        assertEquals("vor 35 Minuten", NLS.toSpokenDate(LocalDateTime.now().minusMinutes(35)))
        assertEquals("vor einer Stunde", NLS.toSpokenDate(LocalDateTime.now().minusHours(1)))
        assertEquals("vor 4 Stunden", NLS.toSpokenDate(LocalDateTime.now().minusHours(4)))
        assertEquals("in der nächsten Stunde", NLS.toSpokenDate(LocalDateTime.now().plusMinutes(40)))
        assertEquals("in 3 Stunden", NLS.toSpokenDate(LocalDateTime.now().plusHours(4)))
    }

    @Test
    fun `parseUserString with LocalTime parses 900 and 90023`() {
        val input = "9:00"
        val inputWithSeconds = "9:00:23"
        assertEquals(9, NLS.parseUserString(LocalTime::class.java, input).getHour())
        assertEquals(9, NLS.parseUserString(LocalTime::class.java, inputWithSeconds).getHour())
    }

    @Test
    fun `parseUserString for an Amount works`() {
        val input = "34,54"
        assertEquals("34,54", NLS.parseUserString(Amount::class.java, input).toString())
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|', textBlock = """
        42      | 42
        77,0000 | 77"""
    )
    fun `parseUserString works for integers`(input: String, output: Int) {
        assertEquals(output, NLS.parseUserString(Int::class.java, input))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|', textBlock = """
        12      | 12
        31,0000 | 31"""
    )
    fun `parseUserString works for longs`(input: String, output: Long) {
        assertEquals(output, NLS.parseUserString(Long::class.java, input))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|', textBlock = """
         55.000,00 | de     | 55000
        56,000.00 | en     | 56000"""
    )
    fun `parseUserString works for integers considering locale`(input: String, language: String, output: Int) {
        assertEquals(output, NLS.parseUserString(Int::class.java, input, language))
    }

    @ParameterizedTest
    @MethodSource("generator for parseUserString fails when expected")
    fun `parseUserString fails when expected`(
        input: String,
        type: Class<out Number>,
        exception: Class<Exception>,
        message: String
    ) {
        val thrown: Exception = Assertions.assertThrows(exception) {
            NLS.parseUserString(type, input)
        }
        Assertions.assertEquals(message, thrown.message)
    }

    @ParameterizedTest
    @MethodSource("generator for parseUserString for a LocalTime works")
    fun `parseUserString for a LocalTime works`(input: String, output: LocalTime) {
        assertEquals(output, NLS.parseUserString(LocalTime::class.java, input))
    }

    @Test
    fun `parseMachineString works for decimals`() {
        assertEquals(BigDecimal.ONE.divide(BigDecimal.TEN), NLS.parseMachineString(BigDecimal::class.java, "0.1"))
        assertEquals(BigDecimal("0.1"), NLS.parseMachineString(BigDecimal::class.java, "0.1"))
    }

    @Test
    fun `parseMachineString works for integers`() {
        assertEquals(23, NLS.parseMachineString(Int::class.java, "23"))
        assertEquals(90, NLS.parseMachineString(Int::class.java, "90.0000"))
    }

    @Test
    fun `parseMachineString works for longs`() {
        assertEquals(5L, NLS.parseMachineString(Long::class.java, "5"))
        assertEquals(43L, NLS.parseMachineString(Long::class.java, "43.0000"))
    }

    @Test
    fun `getMonthNameShort correctly appends the given symbol`() {
        Assertions.assertEquals("Jan.", NLS.getMonthNameShort(1, "."))
        Assertions.assertEquals("Mai", NLS.getMonthNameShort(5, "."))
        Assertions.assertEquals("März", NLS.getMonthNameShort(3, "."))
        Assertions.assertEquals("Juni", NLS.getMonthNameShort(6, "."))
        Assertions.assertEquals("Nov.", NLS.getMonthNameShort(11, "."))
        Assertions.assertEquals("Dez.", NLS.getMonthNameShort(12, "."))
        Assertions.assertEquals("", NLS.getMonthNameShort(0, "."))
        Assertions.assertEquals("", NLS.getMonthNameShort(13, "."))
    }

}