package com.foldledger.util

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 极简的 .xlsx (Office Open XML SpreadsheetML) 生成器。
 *
 * 为什么不用 Apache POI：POI 在 Android 上体积 10MB+、方法数巨大，还需要
 * 额外的 XML 解析依赖。而 xlsx 本质上就是一个装了若干 XML 的 zip 包，
 * 自己拼字符串反而更可控、更小、无依赖。
 *
 * 支持：多工作表、列宽、首行冻结、5 种内置样式（文本 / 金额 / 日期）。
 */
object XlsxWriter {

    // ---------------- 样式 id（与 styles.xml 里的 cellXfs 顺序一致） ----------------
    const val STYLE_DEFAULT = 0
    const val STYLE_HEADER = 1
    const val STYLE_TEXT = 2
    const val STYLE_MONEY = 3
    const val STYLE_DATETIME = 4

    data class Cell(val value: Any?, val style: Int = STYLE_TEXT)

    class Sheet(
        val name: String,
        val freezeFirstRow: Boolean = true
    ) {
        internal val rows = mutableListOf<List<Cell>>()
        internal var widths: List<Double>? = null

        fun widths(vararg w: Double) = apply { widths = w.toList() }

        fun row(vararg cells: Cell) = apply { rows += cells.toList() }

        fun row(cells: List<Cell>) = apply { rows += cells }

        val columnCount: Int get() = maxOf(widths?.size ?: 0, rows.maxOfOrNull { it.size } ?: 0)
    }

    // ---------------- 便捷构造 ----------------
    fun text(v: String?): Cell = Cell(v ?: "", STYLE_TEXT)

    fun money(yuan: Double): Cell = Cell(yuan, STYLE_MONEY)

    /** 传毫秒时间戳，内部转成 Excel 日期序列号 */
    fun dateTime(epochMillis: Long): Cell = Cell(toExcelSerial(epochMillis), STYLE_DATETIME)

    fun header(v: String): Cell = Cell(v, STYLE_HEADER)

    fun headerRow(vararg v: String): List<Cell> = v.map { Cell(it, STYLE_HEADER) }

    // ---------------- 生成 ----------------

    fun build(sheets: List<Sheet>): ByteArray {
        require(sheets.isNotEmpty()) { "至少需要一个工作表" }

        val used = mutableSetOf<String>()
        val safeNames = sheets.mapIndexed { i, s ->
            sanitizeSheetName(s.name.ifBlank { "Sheet${i + 1}" }, used)
        }

        val out = ByteArrayOutputStream(64 * 1024)
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(contentTypes(sheets.size))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(rootRels())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("docProps/core.xml"))
            zip.write(coreProps())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("docProps/app.xml"))
            zip.write(appProps())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/workbook.xml"))
            zip.write(workbook(safeNames))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zip.write(workbookRels(sheets.size))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/styles.xml"))
            zip.write(styles())
            zip.closeEntry()

            sheets.forEachIndexed { index, sheet ->
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet${index + 1}.xml"))
                zip.write(worksheet(sheet))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // ---------------- 各部件 ----------------

    private fun contentTypes(sheetCount: Int): ByteArray {
        val sb = StringBuilder(1024)
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        sb.append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        sb.append("""<Default Extension="xml" ContentType="application/xml"/>""")
        sb.append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        sb.append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        sb.append("""<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""")
        sb.append("""<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>""")
        for (i in 1..sheetCount) {
            sb.append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        sb.append("</Types>")
        return sb.toUtf8()
    }

    private fun rootRels(): ByteArray = ("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
        """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
        """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>""" +
        """<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>""" +
        """</Relationships>""").toUtf8()

    private fun coreProps(): ByteArray {
        val now = isoNow()
        return ("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" """ +
            """xmlns:dcmitype="http://purl.org/dc/dcmitype/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">""" +
            """<dc:title>滴水记账 数据导出</dc:title>""" +
            """<dc:creator>滴水记账</dc:creator>""" +
            """<cp:lastModifiedBy>滴水记账</cp:lastModifiedBy>""" +
            """<dcterms:created xsi:type="dcterms:W3CDTF">$now</dcterms:created>""" +
            """<dcterms:modified xsi:type="dcterms:W3CDTF">$now</dcterms:modified>""" +
            """</cp:coreProperties>""").toUtf8()
    }

    private fun appProps(): ByteArray = ("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
        """<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" """ +
        """xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">""" +
        """<Application>滴水记账</Application>""" +
        """<AppVersion>1.0</AppVersion>""" +
        """</Properties>""").toUtf8()

    private fun workbook(sheetNames: List<String>): ByteArray {
        val sb = StringBuilder(512)
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """)
        sb.append("""xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        sb.append("<sheets>")
        sheetNames.forEachIndexed { i, name ->
            sb.append("""<sheet name="${esc(name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
        }
        sb.append("</sheets></workbook>")
        return sb.toUtf8()
    }

    private fun workbookRels(sheetCount: Int): ByteArray {
        val sb = StringBuilder(512)
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..sheetCount) {
            sb.append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        }
        sb.append("""<Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        sb.append("</Relationships>")
        return sb.toUtf8()
    }

    private fun styles(): ByteArray {
        // 0 默认 | 1 表头 | 2 正文 | 3 金额(#,##0.00) | 4 日期
        return ("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<numFmts count="1"><numFmt numFmtId="164" formatCode="yyyy\-mm\-dd\ hh:mm"/></numFmts>""" +
            """<fonts count="2">""" +
            """<font><sz val="11"/><color theme="1"/><name val="等线"/><charset val="134"/></font>""" +
            """<font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="等线"/><charset val="134"/></font>""" +
            """</fonts>""" +
            """<fills count="3">""" +
            """<fill><patternFill patternType="none"/></fill>""" +
            """<fill><patternFill patternType="gray125"/></fill>""" +
            """<fill><patternFill patternType="solid"><fgColor rgb="FF1F6FEB"/><bgColor indexed="64"/></patternFill></fill>""" +
            """</fills>""" +
            """<borders count="2">""" +
            """<border><left/><right/><top/><bottom/><diagonal/></border>""" +
            """<border><left style="thin"><color rgb="FFD9DEE7"/></left><right style="thin"><color rgb="FFD9DEE7"/></right>""" +
            """<top style="thin"><color rgb="FFD9DEE7"/></top><bottom style="thin"><color rgb="FFD9DEE7"/></bottom><diagonal/></border>""" +
            """</borders>""" +
            """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
            """<cellXfs count="5">""" +
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
            """<xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1">""" +
            """<alignment horizontal="center" vertical="center" wrapText="1"/></xf>""" +
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1">""" +
            """<alignment vertical="center" wrapText="1"/></xf>""" +
            """<xf numFmtId="4" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1">""" +
            """<alignment horizontal="right" vertical="center"/></xf>""" +
            """<xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1">""" +
            """<alignment horizontal="center" vertical="center"/></xf>""" +
            """</cellXfs>""" +
            """<cellStyles count="1"><cellStyle name="常规" xfId="0" builtinId="0"/></cellStyles>""" +
            """</styleSheet>""").toUtf8()
    }

    private fun worksheet(sheet: Sheet): ByteArray {
        val sb = StringBuilder(8 * 1024)
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")

        sb.append("""<sheetViews><sheetView workbookViewId="0">""")
        if (sheet.freezeFirstRow && sheet.rows.isNotEmpty()) {
            sb.append("""<pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/>""")
            sb.append("""<selection pane="bottomLeft" activeCell="A2" sqref="A2"/>""")
        } else {
            sb.append("""<selection activeCell="A1" sqref="A1"/>""")
        }
        sb.append("</sheetView></sheetViews>")

        sb.append("""<sheetFormatPr defaultRowHeight="16.5"/>""")

        sheet.widths?.let { ws ->
            if (ws.isNotEmpty()) {
                sb.append("<cols>")
                ws.forEachIndexed { i, w ->
                    sb.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""")
                }
                sb.append("</cols>")
            }
        }

        sb.append("<sheetData>")
        sheet.rows.forEachIndexed { rIndex, row ->
            if (row.isEmpty()) {
                sb.append("""<row r="${rIndex + 1}"/>""")
                return@forEachIndexed
            }
            val isHeader = row.firstOrNull()?.style == STYLE_HEADER
            sb.append("""<row r="${rIndex + 1}"""")
            if (isHeader) sb.append(""" ht="24" customHeight="1"""")
            sb.append(">")
            row.forEachIndexed { cIndex, cell ->
                writeCell(sb, rIndex, cIndex, cell)
            }
            sb.append("</row>")
        }
        sb.append("</sheetData>")

        sb.append("""<pageMargins left="0.4" right="0.4" top="0.5" bottom="0.5" header="0.3" footer="0.3"/>""")
        sb.append("</worksheet>")
        return sb.toUtf8()
    }

    private fun writeCell(sb: StringBuilder, rowIndex: Int, colIndex: Int, cell: Cell) {
        val ref = "${colLetter(colIndex)}${rowIndex + 1}"
        val v = cell.value
        when (v) {
            null -> sb.append("""<c r="$ref" s="${cell.style}"/>""")

            is Number -> {
                val d = v.toDouble()
                if (d.isNaN() || d.isInfinite()) {
                    sb.append("""<c r="$ref" s="${cell.style}" t="inlineStr"><is><t xml:space="preserve"></t></is></c>""")
                } else {
                    sb.append("""<c r="$ref" s="${cell.style}"><v>${num(d)}</v></c>""")
                }
            }

            is Boolean -> sb.append("""<c r="$ref" s="${cell.style}" t="b"><v>${if (v) 1 else 0}</v></c>""")

            else -> {
                val s = esc(v.toString())
                sb.append("""<c r="$ref" s="${cell.style}" t="inlineStr"><is><t xml:space="preserve">$s</t></is></c>""")
            }
        }
    }

    // ---------------- 工具 ----------------

    /** 1 -> A, 27 -> AA */
    fun colLetter(index0: Int): String {
        var n = index0 + 1
        val sb = StringBuilder(2)
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.append(('A'.code + rem).toChar())
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    /** 毫秒时间戳 -> Excel 日期序列号（1899-12-30 为原点，含本地时区偏移） */
    fun toExcelSerial(epochMillis: Long): Double {
        val tzOffsetMinutes = java.util.TimeZone.getDefault().getOffset(epochMillis) / 60_000.0
        return epochMillis / 86_400_000.0 + 25_569.0 + tzOffsetMinutes / 1_440.0
    }

    private fun num(d: Double): String {
        // 避免科学计数法，最多保留 6 位小数
        val r = Math.round(d * 1_000_000.0) / 1_000_000.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
    }

    private fun sanitizeSheetName(raw: String, used: MutableSet<String>): String {
        var n = raw.replace(Regex("[\\[\\]:*?/\\\\]"), "_").trim().trim('\'').take(31)
        if (n.isBlank()) n = "Sheet"
        var candidate = n
        var i = 2
        while (!used.add(candidate)) {
            val suffix = "_$i"
            candidate = n.take(31 - suffix.length) + suffix
            i++
        }
        return candidate
    }

    private fun isoNow(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date())
    }

    /** XML 转义 + 剔除非法控制字符 */
    private fun esc(input: String): String {
        val sb = StringBuilder(input.length + 16)
        for (ch in input) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                '\n' -> sb.append("&#10;")
                '\r' -> sb.append("&#13;")
                '\t' -> sb.append("&#9;")
                else -> if (ch.code >= 0x20 || ch.code == 0x09) sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun String.toUtf8(): ByteArray = toByteArray(Charsets.UTF_8)

    private fun StringBuilder.toUtf8(): ByteArray = toString().toByteArray(Charsets.UTF_8)
}
