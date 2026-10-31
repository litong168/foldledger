package com.foldledger.util

import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/**
 * 账单文件解析（v9.1）——把微信/支付宝导出的账单读成表格。
 *
 * 纯本机实现，**不引任何第三方库**：
 *  - `.xlsx` 本质是个 zip，里面是 XML —— 用 `java.util.zip` + SAX 流式解析（几千行也不卡）；
 *  - `.csv` 自己写个带引号转义的解析器（字段里常有逗号和引号）。
 *
 * 只做"读成格子"，不做业务判断 —— 字段怎么对应、哪行算收入，交给 [BillMapper]。
 */
object BillParser {

    /** 表格：行 × 列（缺失的列补空串，保证每行列数一致） */
    fun parse(bytes: ByteArray, fileName: String): List<List<String>> {
        val isXlsx = fileName.lowercase().endsWith(".xlsx") ||
            fileName.lowercase().endsWith(".xlsm") ||
            (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())
        return if (isXlsx) parseXlsx(bytes) else parseCsv(bytes)
    }

    // ================= XLSX =================

    fun parseXlsx(bytes: ByteArray): List<List<String>> {
        val shared = ArrayList<String>()
        var sheetXml: ByteArray? = null

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    name == "xl/sharedStrings.xml" -> shared.addAll(parseSharedStrings(zip.readBytes()))
                    name.startsWith("xl/worksheets/") && name.endsWith(".xml") &&
                        (sheetXml == null || name == "xl/worksheets/sheet1.xml") -> {
                        if (name == "xl/worksheets/sheet1.xml" || sheetXml == null) {
                            sheetXml = zip.readBytes()
                        }
                    }
                }
                entry = zip.nextEntry
            }
        }
        val sheet = sheetXml ?: return emptyList()
        return parseSheet(sheet, shared)
    }

    /** sharedStrings.xml → 字符串表（处理 <si><t> 与 <si><r><t> 富文本两种写法） */
    private fun parseSharedStrings(xml: ByteArray): List<String> {
        val out = ArrayList<String>()
        val handler = object : DefaultHandler() {
            private val sb = StringBuilder()
            private var inSi = false
            private var inT = false

            override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes?) {
                when (qName) {
                    "si" -> { inSi = true; sb.setLength(0) }
                    "t" -> if (inSi) inT = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inT) sb.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (qName) {
                    "t" -> inT = false
                    "si" -> {
                        inSi = false
                        out.add(sb.toString())
                        sb.setLength(0)
                    }
                }
            }
        }
        runSax(xml, handler)
        return out
    }

    /** worksheet XML → 行列表 */
    private fun parseSheet(xml: ByteArray, shared: List<String>): List<List<String>> {
        val rows = ArrayList<ArrayList<String>>()
        var current: ArrayList<String>? = null
        var cellRef = ""
        var cellType = ""
        var value = StringBuilder()
        var inV = false
        var inIsT = false
        var isText = StringBuilder()

        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes?) {
                when (qName) {
                    "row" -> current = ArrayList()
                    "c" -> {
                        cellRef = a?.getValue("r") ?: ""
                        cellType = a?.getValue("t") ?: ""
                        value.setLength(0)
                        isText.setLength(0)
                    }
                    "v" -> inV = true
                    "t" -> if (cellType == "inlineStr") inIsT = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inV) value.append(ch, start, length)
                if (inIsT) isText.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (qName) {
                    "v" -> inV = false
                    "t" -> if (cellType == "inlineStr") inIsT = false
                    "c" -> {
                        val row = current ?: return
                        val idx = columnIndex(cellRef)
                        val text = when (cellType) {
                            "s" -> shared.getOrElse(value.toString().trim().toIntOrNull() ?: 0) { "" }
                            "inlineStr" -> isText.toString()
                            "b" -> if (value.toString() == "1") "TRUE" else "FALSE"
                            else -> value.toString()
                        }
                        // 按列号补齐缺失的空格
                        while (row.size < idx) row.add("")
                        if (row.size == idx) row.add(text) else row[idx] = text
                    }
                    "row" -> {
                        current?.let { rows.add(it) }
                        current = null
                    }
                }
            }
        }
        runSax(xml, handler)

        val width = rows.maxOfOrNull { it.size } ?: 0
        return rows.map { r ->
            if (r.size == width) r else r + List(width - r.size) { "" }
        }
    }

    private fun runSax(xml: ByteArray, handler: DefaultHandler) {
        runCatching {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = false
            factory.newSAXParser().parse(InputSource(ByteArrayInputStream(xml)), handler)
        }.onFailure { e ->
            android.util.Log.w("BillParser", "SAX 解析失败", e)
        }
    }

    /** "B7" → 1（0 基列号） */
    private fun columnIndex(cellRef: String): Int {
        var idx = 0
        for (ch in cellRef) {
            if (ch in 'A'..'Z') {
                idx = idx * 26 + (ch - 'A' + 1)
            } else {
                break
            }
        }
        return (idx - 1).coerceAtLeast(0)
    }

    // ================= CSV =================

    fun parseCsv(bytes: ByteArray): List<List<String>> {
        val text = decodeText(bytes)
        val rows = ArrayList<List<String>>()
        var field = StringBuilder()
        var row = ArrayList<String>()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                inQuotes -> {
                    if (ch == '"') {
                        if (i + 1 < text.length && text[i + 1] == '"') {
                            field.append('"'); i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        field.append(ch)
                    }
                }
                ch == '"' -> inQuotes = true
                ch == ',' -> { row.add(field.toString()); field.setLength(0) }
                ch == '\r' -> { /* 忽略，交给 \n 收尾 */ }
                ch == '\n' -> {
                    row.add(field.toString()); field.setLength(0)
                    rows.add(row); row = ArrayList()
                }
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        val width = rows.maxOfOrNull { it.size } ?: 0
        return rows.map { r -> if (r.size == width) r else r + List(width - r.size) { "" } }
    }

    /**
     * 文本解码：先按 UTF-8，若出现替换字符说明不是 UTF-8（支付宝/微信的 CSV 常是 GBK），改用 GBK。
     */
    fun decodeText(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('�')) return utf8.removePrefix("﻿")
        val gbk = runCatching { String(bytes, Charset.forName("GBK")) }.getOrElse { utf8 }
        return gbk.removePrefix("﻿")
    }
}
