package app.nouri.core.export

import app.nouri.core.progress.BodyMeasurement
import app.nouri.core.progress.WeightEntry
import app.nouri.core.progress.WeightUnit
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Strict RFC 4180 reader used only to check what the writer produces. */
private fun parseCsv(text: String): List<List<String>> {
    val records = mutableListOf<List<String>>()
    var fields = mutableListOf<String>()
    val field = StringBuilder()
    var i = 0
    var quoted = false
    while (i < text.length) {
        val c = text[i]
        if (quoted) {
            if (c == '"') {
                if (i + 1 < text.length && text[i + 1] == '"') {
                    field.append('"'); i++
                } else {
                    quoted = false
                }
            } else {
                field.append(c)
            }
        } else {
            when {
                c == '"' && field.isEmpty() -> quoted = true
                c == ',' -> { fields.add(field.toString()); field.clear() }
                c == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> {
                    fields.add(field.toString()); field.clear()
                    records.add(fields); fields = mutableListOf()
                    i++
                }
                else -> {
                    require(c != '"' && c != '\n' && c != '\r') { "Unescaped special character at $i" }
                    field.append(c)
                }
            }
        }
        i++
    }
    require(!quoted && field.isEmpty() && fields.isEmpty()) { "Last record must end with CRLF" }
    return records
}

class CsvTest {

    @Test
    fun plainFieldsAreUnchanged() {
        assertEquals("Greek yogurt", Csv.escape("Greek yogurt"))
        assertEquals("", Csv.escape(""))
        assertEquals("12.5", Csv.escape("12.5"))
    }

    @Test
    fun specialCharactersAreQuoted() {
        assertEquals("\"Rice, white\"", Csv.escape("Rice, white"))
        assertEquals("\"5\"\" pizza\"", Csv.escape("5\" pizza"))
        assertEquals("\"line one\nline two\"", Csv.escape("line one\nline two"))
        assertEquals("\"a\rb\"", Csv.escape("a\rb"))
    }

    @Test
    fun formulaInjectionIsNeutralised() {
        assertEquals("'=SUM(A1:A9)", Csv.escape("=SUM(A1:A9)"))
        assertEquals("'+cmd", Csv.escape("+cmd"))
        assertEquals("'-2+3", Csv.escape("-2+3"))
        assertEquals("'@SUM(1)", Csv.escape("@SUM(1)"))
        assertEquals("'\tx", Csv.escape("\tx"))
        assertEquals("\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\"", Csv.escape("=HYPERLINK(\"http://x\",\"y\")"))
        assertEquals("'-", Csv.escape("-"))
    }

    @Test
    fun numericFieldsAreNotPrefixed() {
        assertEquals("-1.2", Csv.escape("-1.2"))
        assertEquals("+3", Csv.escape("+3"))
        assertEquals("-0.5", Csv.escape("-0.5"))
        assertEquals("-.5", Csv.escape("-.5"))
        assertEquals("-1e3", Csv.escape("-1e3"))
    }

    @Test
    fun rowsUseCommasAndCrlf() {
        assertEquals("a,\"b,c\",-1.2\r\n", Csv.row(listOf("a", "b,c", "-1.2")))
        assertEquals("\r\n", Csv.row(listOf("")))
    }

    @Test
    fun quotesAndNewlinesRoundTrip() {
        val original = listOf(
            "He said \"hi\"",
            "multi\nline\r\nnote",
            "comma, inside",
            "\"",
            "",
            "plain",
            "trailing space ",
        )
        val parsed = parseCsv(Csv.row(original) + Csv.row(listOf("second", "row")))
        assertEquals(listOf(original, listOf("second", "row")), parsed)
    }

    @Test
    fun diaryExportHasHeaderAndFormattedNumbers() {
        val rows = listOf(
            DiaryExportRow(
                date = LocalDate(2026, 10, 20), time = "08:15", meal = "Breakfast", food = "Paratha, aloo",
                amount = 1.0, unit = "piece", grams = 120.04, energyKcal = 320.4, proteinG = 6.25, carbsG = 40.0,
                fatG = 14.96, fiberG = 3.0, sugarG = null, sodiumMg = 410.6, source = "Photo estimate", confidence = "Moderate confidence",
            ),
            DiaryExportRow(
                date = LocalDate(2026, 10, 20), time = null, meal = "Snack", food = "=1+1", amount = null, unit = null,
                grams = null, energyKcal = 95.0, proteinG = 0.5, carbsG = 25.0, fatG = 0.3, source = "Search",
            ),
        )
        val csv = DiaryCsvExporter.export(rows)
        val parsed = parseCsv(csv)
        assertEquals(DiaryCsvExporter.HEADER, parsed[0])
        assertEquals(
            listOf(
                "2026-10-20", "08:15", "Breakfast", "Paratha, aloo", "1", "piece", "120", "320", "6.3", "40", "15",
                "3", "", "411", "Photo estimate", "Moderate confidence",
            ),
            parsed[1],
        )
        assertEquals("'=1+1", parsed[2][3])
        assertEquals("", parsed[2][4])
        assertEquals(3, parsed.size)
        assertTrue(csv.endsWith("\r\n"))
        assertTrue(csv.lines().first().startsWith("date,time,meal,food,amount,unit,grams,energy_kcal"))
    }

    @Test
    fun weightExportSortsConvertsAndIncludesMeasurements() {
        val entries = listOf(
            WeightEntry("2", LocalDate(2026, 10, 2), 1290, 72.0, note = "after run, \"tired\""),
            WeightEntry("1", LocalDate(2026, 10, 2), 425, 71.55),
            WeightEntry("0", LocalDate(2026, 10, 1), null, 72.3),
        )
        val measurements = listOf(BodyMeasurement("m", LocalDate(2026, 10, 2), waistCm = 81.25, hipCm = null, chestCm = 98.0, note = null))
        val kg = parseCsv(WeightCsvExporter.export(entries, measurements, WeightUnit.KG))
        assertEquals(listOf("date", "time", "type", "weight_kg", "waist_cm", "hip_cm", "chest_cm", "note"), kg[0])
        assertEquals(listOf("2026-10-01", "", "weight", "72.3", "", "", "", ""), kg[1])
        assertEquals(listOf("2026-10-02", "07:05", "weight", "71.55", "", "", "", ""), kg[2])
        assertEquals(listOf("2026-10-02", "21:30", "weight", "72", "", "", "", "after run, \"tired\""), kg[3])
        assertEquals(listOf("2026-10-02", "", "measurement", "", "81.3", "", "98", ""), kg[4])

        val lb = parseCsv(WeightCsvExporter.export(entries, emptyList(), WeightUnit.LB))
        assertEquals("weight_lb", lb[0][3])
        assertEquals("159.39", lb[1][3]) // 72.3 kg
        assertEquals(4, lb.size)
    }
}
