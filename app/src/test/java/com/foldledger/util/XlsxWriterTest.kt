package com.foldledger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * XlsxWriter 的结构测试。
 *
 * 产物同时会落盘到 `app/build/test-export/sample.xlsx`，
 * 便于用 Excel / WPS / openpyxl 再做一次「真实打开」验证。
 */
class XlsxWriterTest {

    private val sampleEpochMillis = 1_760_000_000_000L

    private fun sample(): ByteArray {
        val summary = XlsxWriter.Sheet("账本汇总")
        summary.widths(16.0, 10.0, 16.0, 22.0)
        summary.row(XlsxWriter.headerRow("账本名称", "场景", "累计支出(元)", "备注"))
        summary.row(
            XlsxWriter.text("家装账本"),
            XlsxWriter.text("家装"),
            XlsxWriter.money(123456.78),
            XlsxWriter.text("keep <&>\"' literal")
        )
        summary.row(
            XlsxWriter.text("报销账本"),
            XlsxWriter.text("报销"),
            XlsxWriter.money(0.0),
            XlsxWriter.text("")
        )

        val detail = XlsxWriter.Sheet("全部流水")
        detail.widths(18.0, 14.0, 14.0, 30.0)
        detail.row(XlsxWriter.headerRow("发生时间", "分类", "金额(元)", "场景专属字段"))
        detail.row(
            XlsxWriter.dateTime(sampleEpochMillis),
            XlsxWriter.text("🧱 主材"),
            XlsxWriter.money(-8650.5),
            XlsxWriter.text("空间=客厅；商家=XX装饰公司")
        )

        // 工作表名：超长 + 非法字符 + 重名 + 空名
        val longName = "这是一个非常非常长的工作表名称/含非法字符[测试]:*?"
        val s3 = XlsxWriter.Sheet(longName).apply {
            row(XlsxWriter.headerRow("说明"))
            row(XlsxWriter.text("名称会被清洗并截断"))
        }
        val s4 = XlsxWriter.Sheet(longName).apply {
            row(XlsxWriter.headerRow("重名测试"))
        }
        val s5 = XlsxWriter.Sheet("").apply {
            row(XlsxWriter.headerRow("空名测试"))
        }

        return XlsxWriter.build(listOf(summary, detail, s3, s4, s5))
    }

    private fun unzip(bytes: ByteArray): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(bytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val buf = ByteArrayOutputStream()
                zis.copyTo(buf)
                out[entry.name] = buf.toString("UTF-8")
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return out
    }

    @Test
    fun containsAllRequiredParts() {
        val parts = unzip(sample())
        val required = listOf(
            "[Content_Types].xml",
            "_rels/.rels",
            "docProps/core.xml",
            "docProps/app.xml",
            "xl/workbook.xml",
            "xl/_rels/workbook.xml.rels",
            "xl/styles.xml",
            "xl/worksheets/sheet1.xml",
            "xl/worksheets/sheet2.xml",
            "xl/worksheets/sheet3.xml",
            "xl/worksheets/sheet4.xml",
            "xl/worksheets/sheet5.xml"
        )
        required.forEach { assertTrue("missing part: $it", parts.containsKey(it)) }
        // [Content_Types].xml 必须是第一个条目
        assertEquals("[Content_Types].xml", parts.keys.first())
    }

    @Test
    fun contentTypeOverridesCoverEverySheet() {
        val ct = unzip(sample())["[Content_Types].xml"]!!
        for (i in 1..5) {
            assertTrue("missing override for sheet$i", ct.contains("/xl/worksheets/sheet$i.xml"))
        }
        assertTrue(ct.contains("spreadsheetml.sheet.main+xml"))
        assertTrue(ct.contains("spreadsheetml.styles+xml"))
        assertTrue(ct.contains("extended-properties+xml"))
        assertTrue(ct.contains("core-properties+xml"))
    }

    @Test
    fun workbookRelsMatchSheets() {
        val rels = unzip(sample())["xl/_rels/workbook.xml.rels"]!!
        for (i in 1..5) {
            assertTrue("missing target sheet$i", rels.contains("Target=\"worksheets/sheet$i.xml\""))
            assertTrue("missing rId$i", rels.contains("Id=\"rId$i\""))
        }
        assertTrue(rels.contains("Target=\"styles.xml\""))
    }

    @Test
    fun sheetNamesAreSanitizedTruncatedAndUnique() {
        val workbook = unzip(sample())["xl/workbook.xml"]!!
        val names = Regex("<sheet name=\"([^\"]*)\"")
            .findAll(workbook).map { it.groupValues[1] }.toList()

        assertEquals(5, names.size)
        assertEquals("sheet names must stay unique", 5, names.toSet().size)
        names.forEach { n ->
            assertTrue("name longer than 31 chars: $n", n.length <= 31)
            assertFalse("illegal chars in sheet name: $n", n.any { it in "[]:*?/\\" })
            assertTrue("blank sheet name", n.isNotBlank())
        }
        assertEquals("账本汇总", names[0])
        assertEquals("全部流水", names[1])
    }

    @Test
    fun headersAndTextUseInlineStrWithEscaping() {
        val parts = unzip(sample())
        val sheet1 = parts["xl/worksheets/sheet1.xml"]!!

        assertTrue(sheet1.contains("t=\"inlineStr\""))
        assertTrue("header style must be 1", sheet1.contains("s=\"1\""))
        // < > " ' 都需转义
        assertTrue(sheet1.contains("&lt;&amp;&gt;&quot;&apos;"))
        assertFalse("raw special chars leaked", sheet1.contains("keep <&>"))
        assertTrue("chinese text lost", sheet1.contains("家装账本"))
        assertTrue(
            "emoji lost",
            parts["xl/worksheets/sheet2.xml"]!!.contains("🧱 主材")
        )
    }

    @Test
    fun moneyWrittenAsNumberWithTwoDecimalFormat() {
        val parts = unzip(sample())
        val sheet1 = parts["xl/worksheets/sheet1.xml"]!!
        assertTrue("money must be a numeric cell", sheet1.contains("<v>123456.78</v>"))
        assertTrue("money style must be 3", sheet1.contains("s=\"3\""))
        assertTrue("zero must still be written", sheet1.contains("<v>0</v>"))
        assertTrue(
            "negative money lost",
            parts["xl/worksheets/sheet2.xml"]!!.contains("<v>-8650.5</v>")
        )
    }

    @Test
    fun dateTimeWrittenAsExcelSerialWithDateFormat() {
        val parts = unzip(sample())
        val sheet2 = parts["xl/worksheets/sheet2.xml"]!!
        assertTrue("datetime style must be 4", sheet2.contains("s=\"4\""))

        val serial = XlsxWriter.toExcelSerial(sampleEpochMillis)
        assertTrue("bad excel serial: $serial", serial in 45_000.0..47_000.0)

        val styles = parts["xl/styles.xml"]!!
        assertTrue(styles.contains("numFmtId=\"164\""))
        assertTrue(styles.contains("<cellXfs count=\"5\">"))
        assertTrue("money numFmt missing", styles.contains("numFmtId=\"4\""))
    }

    @Test
    fun freezePaneAndColumnWidthsApplied() {
        val sheet1 = unzip(sample())["xl/worksheets/sheet1.xml"]!!
        assertTrue(sheet1.contains("state=\"frozen\""))
        assertTrue(sheet1.contains("ySplit=\"1\""))
        assertTrue(sheet1.contains("<col min=\"1\" max=\"1\" width=\"16.0\" customWidth=\"1\"/>"))
    }

    @Test
    fun columnLetterConversion() {
        assertEquals("A", XlsxWriter.colLetter(0))
        assertEquals("Z", XlsxWriter.colLetter(25))
        assertEquals("AA", XlsxWriter.colLetter(26))
        assertEquals("AZ", XlsxWriter.colLetter(51))
        assertEquals("BA", XlsxWriter.colLetter(52))
        assertEquals("ZZ", XlsxWriter.colLetter(701))
        assertEquals("AAA", XlsxWriter.colLetter(702))
    }

    @Test
    fun dumpSampleForManualExcelCheck() {
        val dir = File("build/test-export")
        dir.mkdirs()
        val f = File(dir, "sample.xlsx")
        f.writeBytes(sample())
        assertTrue(f.exists())
        assertTrue("artifact should not be empty", f.length() > 2000)
        println("[XlsxWriterTest] sample written: ${f.absolutePath} (${f.length()} bytes)")
    }
}
