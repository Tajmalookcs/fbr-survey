package com.tajmalook.fbrsurvey

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes the survey report in the same layout as the office form:
 * merged title row, "Survey Conducted by" row, bold header row, an empty row, then data rows,
 * Times New Roman 12 with thin borders, plus a clickable Google Maps link column.
 */
object Xlsx {

    class Cell(val value: Any?, val style: Int, val link: String? = null)

    // style ids (cellXfs index)
    const val TITLE = 1
    const val HEADER = 2
    const val CENTER = 3
    const val LEFT = 4
    const val TEXT_CENTER = 5
    const val LINK = 6
    const val BLANK = 7
    const val SUBTITLE = 8
    /** text, centered, one value per line */
    const val TEXT_LINES = 9

    fun writeReport(
        out: OutputStream,
        sheetName: String,
        title: String,
        subtitle: String,
        header: List<String>,
        rows: List<List<Cell>>,
        widths: List<Double>,
    ) {
        val lastCol = col(header.size - 1)
        val links = mutableListOf<Pair<String, String>>() // cellRef -> url

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheetViews><sheetView workbookViewId="0"><pane ySplit="3" topLeftCell="A4" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
<sheetFormatPr defaultRowHeight="15.75"/>
<cols>""")
        widths.forEachIndexed { i, wd -> sb.append("""<col min="${i + 1}" max="${i + 1}" width="$wd" customWidth="1"/>""") }
        sb.append("</cols><sheetData>")

        // Row 1: title (merged), Row 2: header, Row 3: empty bordered row
        sb.append("""<row r="1" ht="34.5" customHeight="1">""")
        sb.append(cellXml("A1", title, TITLE))
        for (i in 1 until header.size) sb.append("""<c r="${col(i)}1" s="$TITLE"/>""")
        sb.append("</row>")
        sb.append("""<row r="2" ht="24" customHeight="1">""")
        sb.append(cellXml("A2", subtitle, SUBTITLE))
        for (i in 1 until header.size) sb.append("""<c r="${col(i)}2" s="$SUBTITLE"/>""")
        sb.append("</row>")
        sb.append("""<row r="3" ht="47.25" customHeight="1">""")
        header.forEachIndexed { i, h -> sb.append(cellXml("${col(i)}3", h, HEADER)) }
        sb.append("</row>")
        sb.append("""<row r="4">""")
        for (i in header.indices) sb.append("""<c r="${col(i)}4" s="$BLANK"/>""")
        sb.append("</row>")

        rows.forEachIndexed { ri, r ->
            val rowNum = ri + 5
            sb.append("""<row r="$rowNum">""")
            r.forEachIndexed { i, c ->
                val ref = "${col(i)}$rowNum"
                sb.append(cellXml(ref, c.value, c.style))
                if (c.link != null) links += ref to c.link
            }
            sb.append("</row>")
        }
        sb.append("</sheetData>")
        sb.append("""<mergeCells count="2"><mergeCell ref="A1:${lastCol}1"/><mergeCell ref="A2:${lastCol}2"/></mergeCells>""")
        if (links.isNotEmpty()) {
            sb.append("<hyperlinks>")
            links.forEachIndexed { i, (ref, _) -> sb.append("""<hyperlink ref="$ref" r:id="rId${i + 1}"/>""") }
            sb.append("</hyperlinks>")
        }
        sb.append("""<pageMargins left="0.4" right="0.4" top="0.5" bottom="0.5" header="0.3" footer="0.3"/>""")
        sb.append("""<pageSetup paperSize="9" orientation="landscape" fitToHeight="0"/>""")
        sb.append("</worksheet>")

        ZipOutputStream(out).use { zip ->
            fun put(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>""")
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""")
            put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="${esc(sheetName.take(31))}" sheetId="1" r:id="rId1"/></sheets>
<definedNames><definedName name="_xlnm.Print_Titles" localSheetId="0">'${esc(sheetName.take(31))}'!${'$'}1:${'$'}3</definedName></definedNames>
</workbook>""")
            put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>""")
            if (links.isNotEmpty()) {
                val rels = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                links.forEachIndexed { i, (_, url) ->
                    rels.append("""<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="${esc(url)}" TargetMode="External"/>""")
                }
                rels.append("</Relationships>")
                put("xl/worksheets/_rels/sheet1.xml.rels", rels.toString())
            }
            put("xl/styles.xml", STYLES)
            put("xl/worksheets/sheet1.xml", sb.toString())
        }
    }

    private fun cellXml(ref: String, v: Any?, style: Int): String = when (v) {
        null -> """<c r="$ref" s="$style"/>"""
        is Number -> """<c r="$ref" s="$style"><v>$v</v></c>"""
        else -> """<c r="$ref" s="$style" t="inlineStr"><is><t xml:space="preserve">${esc(v.toString())}</t></is></c>"""
    }

    private fun col(i: Int): String {
        var n = i + 1
        val s = StringBuilder()
        while (n > 0) {
            val m = (n - 1) % 26
            s.insert(0, ('A' + m))
            n = (n - 1) / 26
        }
        return s.toString()
    }

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\n' || it == '\t' || it >= ' ' }

    private const val TNR = """<name val="Times New Roman"/><family val="1"/>"""
    private const val B = """<border><left style="thin"><color auto="1"/></left><right style="thin"><color auto="1"/></right><top style="thin"><color auto="1"/></top><bottom style="thin"><color auto="1"/></bottom><diagonal/></border>"""

    private val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="4">
<font><sz val="11"/><name val="Calibri"/><family val="2"/></font>
<font><b/><sz val="12"/>$TNR</font>
<font><sz val="12"/>$TNR</font>
<font><u/><sz val="12"/><color rgb="FF0563C1"/>$TNR</font>
</fonts>
<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
<borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border>$B</borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="10">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
<xf numFmtId="0" fontId="1" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
<xf numFmtId="0" fontId="2" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="top" wrapText="1"/></xf>
<xf numFmtId="0" fontId="2" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1" applyAlignment="1"><alignment horizontal="left" vertical="top" wrapText="1"/></xf>
<xf numFmtId="49" fontId="2" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="top"/></xf>
<xf numFmtId="0" fontId="3" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="top"/></xf>
<xf numFmtId="0" fontId="2" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1"/>
<xf numFmtId="0" fontId="1" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1" applyAlignment="1"><alignment horizontal="left" vertical="center" wrapText="1"/></xf>
<xf numFmtId="49" fontId="2" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="top" wrapText="1"/></xf>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""
}
