package com.foldledger.util

import com.foldledger.data.db.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 账单导入解析（v9.1）。
 *
 * 重点验三件事：**表头能认出来**（支付宝前面还压着几行标题）、
 * **不该导的行被剔掉**（不计收支 / 已退款 / 已关闭）、**金额时间不串行**。
 */
class BillParserTest {

    // ---------- 造一个最小 xlsx（sharedStrings + sheet1） ----------

    private fun buildXlsx(rows: List<List<String>>): ByteArray {
        val shared = ArrayList<String>()
        val index = HashMap<String, Int>()
        fun sid(s: String): Int {
            index[s]?.let { return it }
            val i = shared.size
            shared += s
            index[s] = i
            return i
        }
        val sheet = StringBuilder()
        sheet.append("""<?xml version="1.0" encoding="UTF-8"?><worksheet><sheetData>""")
        rows.forEachIndexed { r, row ->
            sheet.append("""<row r="${r + 1}">""")
            row.forEachIndexed { c, cell ->
                // 列名 = A + 列号（注意：Int.toChar() 是按码点转字符，不能用 1.toChar() 当 'A'）
                val ref = "${('A'.code + c).toChar()}${r + 1}"
                if (cell.isEmpty()) return@forEachIndexed
                sheet.append("""<c r="$ref" t="s"><v>${sid(cell)}</v></c>""")
            }
            sheet.append("</row>")
        }
        sheet.append("</sheetData></worksheet>")

        val sharedXml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?><sst>""")
            shared.forEach { append("<si><t>${xmlEscape(it)}</t></si>") }
            append("</sst>")
        }

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zip.write(sharedXml.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheet.toString().toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun xmlEscape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ---------- 微信支付账单（xlsx） ----------

    private val wechatHeader = listOf(
        "交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)", "支付方式", "当前状态", "交易单号", "商户单号", "备注"
    )

    private val wechatRows = listOf(
        wechatHeader,
        listOf("2024-01-15 12:33:21", "商户消费", "星巴克", "拿铁", "支出", "32.50", "零钱", "支付成功", "4200001", "M001", ""),
        listOf("2024-01-16 09:02:11", "转账", "张三", "还款", "收入", "200.00", "零钱", "已存入零钱", "4200002", "M002", ""),
        listOf("2024-01-17 20:00:00", "商户消费", "美团", "外卖", "支出", "45.80", "招商银行", "支付成功", "4200003", "M003", ""),
        // 不计收支（转账）——不该导
        listOf("2024-01-18 10:00:00", "转账", "李四", "还款", "不计收支", "1000.00", "零钱", "已全额收钱", "4200004", "M004", ""),
        // 已退款——不该导
        listOf("2024-01-19 11:11:11", "商户消费", "优衣库", "衣服", "支出", "299.00", "微信支付", "已全额退款", "4200005", "M005", "")
    )

    @Test
    fun xlsxIsReadAsTable() {
        val rows = BillParser.parseXlsx(buildXlsx(wechatRows))
        assertEquals(6, rows.size)
        assertEquals("交易时间", rows[0][0])
        assertEquals("星巴克", rows[1][2])
        assertEquals("32.50", rows[1][5])
    }

    @Test
    fun wechatBillMapsToTransactions() {
        val rows = BillParser.parseXlsx(buildXlsx(wechatRows))
        val headerIdx = BillMapper.findHeaderRow(rows)
        assertEquals(0, headerIdx)
        val mapped = BillMapper.mapRows(rows[headerIdx], rows.drop(headerIdx + 1))

        // 5 行里只有 3 行该导（不计收支 + 已退款被剔掉）
        assertEquals(3, mapped.size)
        assertEquals(3250L, mapped[0].amountCents)
        assertEquals("星巴克", mapped[0].counterparty)
        assertEquals(Direction.EXPENSE, mapped[0].direction)
        assertEquals("4200001", mapped[0].orderId)
        assertEquals(Direction.INCOME, mapped[1].direction) // 收入
        assertEquals(20000L, mapped[1].amountCents)
    }

    @Test
    fun timeAndAmountAreParsed() {
        val t = BillMapper.parseTime("2024-01-15 12:33:21")
        assertNotNull(t)
        assertTrue(t!! > 1_700_000_000_000L)
        assertEquals(3250L, BillMapper.parseAmount("32.50"))
        assertEquals(3250L, BillMapper.parseAmount("¥32.50"))
        assertEquals(123456L, BillMapper.parseAmount("1,234.56"))
        assertEquals(3250L, BillMapper.parseAmount("32.5元"))
        assertNull(BillMapper.parseAmount("abc"))
        assertNull(BillMapper.parseAmount(""))
    }

    @Test
    fun excelSerialDateIsSupported() {
        // 45000.5 ≈ 2023-03-15 12:00
        val t = BillMapper.parseTime("45000.5")
        assertNotNull(t)
        assertTrue(t!! > 1_600_000_000_000L)
    }

    // ---------- 支付宝账单（CSV，带标题行 + GBK 编码） ----------

    @Test
    fun alipayCsvWithTitleRowsIsFound() {
        val csv = listOf(
            "支付宝交易记录明细查询",
            "账号:[xxx@xx.com]",
            "开始日期:[2024-01-01 00:00:00]    结束日期:[2024-01-31 23:59:59]",
            "",
            "交易号,商家订单号,交易创建时间,付款时间,交易来源地,类型,交易对方,商品名称,金额（元）,收/支,交易状态,服务费（元）,成功退款（元）,备注,资金状态",
            "2024011500001,M001,2024-01-15 12:33:21,2024-01-15 12:33:21,支付宝网站,即时交易,盒马鲜生,生鲜,88.60,支出,交易成功,0.00,0.00,,已支出",
            "2024011600002,M002,2024-01-16 09:00:00,2024-01-16 09:00:00,支付宝网站,即时交易,滴滴出行,快车,23.00,支出,交易成功,0.00,0.00,,已支出"
        ).joinToString("\n")
        val rows = BillParser.parseCsv(csv.toByteArray())
        val headerIdx = BillMapper.findHeaderRow(rows)
        assertEquals(4, headerIdx) // 表头在第 5 行

        val mapped = BillMapper.mapRows(rows[headerIdx], rows.drop(headerIdx + 1))
        assertEquals(2, mapped.size)
        assertEquals(8860L, mapped[0].amountCents)
        assertEquals("盒马鲜生", mapped[0].counterparty)
        assertEquals(2300L, mapped[1].amountCents)
    }

    @Test
    fun gbkEncodedCsvIsDecoded() {
        val csv = "交易时间,交易对方,商品,收/支,金额(元)\n2024-01-15 12:33:21,美团外卖,外卖,支出,45.80\n"
        val gbk = csv.toByteArray(Charset.forName("GBK"))
        val rows = BillParser.parseCsv(gbk)
        assertTrue(rows[0][0].contains("交易时间")) // 不是乱码
        assertEquals("美团外卖", rows[1][1])
    }

    @Test
    fun csvQuotedFieldsWithCommaAreKept() {
        val csv = "交易时间,交易对方,商品,收/支,金额(元)\n2024-01-15 12:33:21,星巴克,\"拿铁, 大杯\",支出,32.50\n"
        val rows = BillParser.parseCsv(csv.toByteArray())
        assertEquals("拿铁, 大杯", rows[1][2])
    }

    @Test
    fun nonBillFileIsRejected() {
        val rows = BillParser.parseCsv("a,b,c\n1,2,3\n".toByteArray())
        assertFalse(BillMapper.looksLikeBill(rows))
        assertEquals(-1, BillMapper.findHeaderRow(rows))
    }
}
