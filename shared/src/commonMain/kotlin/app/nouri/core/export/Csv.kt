package app.nouri.core.export

/**
 * Minimal RFC 4180 CSV writer.
 *
 * - Fields containing a comma, double quote, CR or LF are wrapped in double
 *   quotes, with embedded quotes doubled.
 * - Records end with CRLF, including the last one.
 * - Formula-injection protection (OWASP): a text field starting with `=`, `+`,
 *   `-`, `@`, tab or CR is prefixed with a single quote so spreadsheet apps show
 *   it as text instead of evaluating it. Plain numbers such as `-1.2` or `+3`
 *   are left alone so numeric columns stay numeric. The trade-off is that the
 *   quote is part of the data: some apps display it, and a program re-importing
 *   the file sees `'=…`. Exported food names and notes are user text, so the
 *   protection is on by default.
 *
 * The output is a String; when saving a file for spreadsheet apps, write it as
 * UTF-8 and prepend [UTF8_BOM] so Excel detects the encoding of non-ASCII food
 * names.
 */
object Csv {
    const val SEPARATOR = ','
    const val LINE_END = "\r\n"
    const val UTF8_BOM = "﻿"

    private val formulaTriggers = charArrayOf('=', '+', '-', '@', '\t', '\r')
    private val plainNumber = Regex("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?")

    /** One field, neutralised against formula injection and quoted if needed. */
    fun escape(field: String): String {
        val safe = if (field.isNotEmpty() && field[0] in formulaTriggers && !plainNumber.matches(field)) {
            "'$field"
        } else {
            field
        }
        val needsQuotes = safe.any { it == SEPARATOR || it == '"' || it == '\r' || it == '\n' }
        return if (needsQuotes) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    /** One record, terminated with CRLF. */
    fun row(fields: List<String>): String = fields.joinToString(SEPARATOR.toString(), postfix = LINE_END) { escape(it) }
}
