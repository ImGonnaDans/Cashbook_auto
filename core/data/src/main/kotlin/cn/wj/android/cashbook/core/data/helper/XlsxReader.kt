/*
 * Copyright 2021 The Cashbook Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.wj.android.cashbook.core.data.helper

import cn.wj.android.cashbook.core.common.ext.logger
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream
import kotlin.math.roundToLong

/**
 * xlsx（本质为 ZIP）通用读取工具
 *
 * 提供账单解析器共用的能力：ZIP 条目读取、共享字符串池解析、XML 解析器创建、Excel 时间解析。
 */
internal object XlsxReader {

    /** ZIP 内容数据 */
    data class ZipData(
        val sharedStrings: ByteArray?,
        val sheetData: ByteArray?,
    )

    /**
     * 账单中常见的时间文本格式（按优先级依次尝试）
     *
     * - `yyyy-MM-dd HH:mm:ss` / `yyyy-MM-dd HH:mm`：微信账单、标准导出
     * - `yyyy/M/d H:mm` / `yyyy/M/d HH:mm`：Excel 中文短日期
     * - `M/d/yyyy HH:mm`：Excel 英文短日期（部分支付宝导出/整理版使用，如 `6/1/2020 12:27`）
     */
    private val DATE_TIME_PATTERNS: List<String> = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy/M/d H:mm:ss",
        "yyyy/M/d H:mm",
        "yyyy/M/d HH:mm",
        "M/d/yyyy HH:mm:ss",
        "M/d/yyyy HH:mm",
    )

    /** 时间解析格式化器（懒初始化，解析时同步使用：SimpleDateFormat 非线程安全） */
    private val DATE_FORMATTERS: List<SimpleDateFormat> by lazy {
        DATE_TIME_PATTERNS.map { pattern ->
            SimpleDateFormat(pattern, Locale.getDefault()).apply { isLenient = false }
        }
    }

    /**
     * Excel 序列号与 Unix 时间戳的天数差（1899-12-30 到 1970-01-01）
     *
     * Excel 以 1899-12-30 为第 0 天（含 Lotus 1-2-3 闰年 Bug），
     * Unix 以 1970-01-01 为第 0 天，两者相差 25569 天。
     */
    private const val EXCEL_EPOCH_DIFF = 25569

    /** 一天的毫秒数 */
    private const val MS_PER_DAY = 24 * 60 * 60 * 1000L

    /** 从 ZIP 中读取账单所需的 XML 数据 */
    fun readZipEntries(inputStream: InputStream): ZipData {
        var sharedStrings: ByteArray? = null
        var sheetData: ByteArray? = null

        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when (entry.name) {
                    "xl/sharedStrings.xml" -> sharedStrings = zip.readBytes()
                    "xl/worksheets/sheet1.xml" -> sheetData = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return ZipData(sharedStrings = sharedStrings, sheetData = sheetData)
    }

    /**
     * 解析 sharedStrings.xml，提取字符串池
     *
     * 结构示例：
     * ```xml
     * <sst>
     *   <si><t>文本内容</t></si>
     *   ...
     * </sst>
     * ```
     */
    fun parseSharedStrings(data: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        val parser = createParser(data)
        var inT = false
        val textBuilder = StringBuilder()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "t") {
                        inT = true
                        textBuilder.clear()
                    }
                }

                XmlPullParser.TEXT -> {
                    if (inT) {
                        textBuilder.append(parser.text)
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "t") {
                        inT = false
                        strings.add(textBuilder.toString())
                    }
                }
            }
            eventType = parser.next()
        }
        return strings
    }

    /** 创建 XML 解析器 */
    fun createParser(data: ByteArray): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(data.inputStream(), "UTF-8")
        return parser
    }

    /**
     * 解析日期时间字符串
     *
     * 支持格式：
     * - ISO 格式：2026-03-26T11:50:04（xlsx 的 t="d" 类型）
     * - 常见文本格式：`2026-03-26 11:50:04`、`2026-03-26 11:50`、`2026/3/26 11:50`、`6/1/2020 12:27`
     * - Excel 序列号：46107.493101851855（xlsx 无 t 属性、通过 style 格式化的日期）
     */
    fun parseDateTime(dateStr: String): Long? {
        val trimmed = dateStr.trim()
        // 先尝试常见文本日期格式（ISO 格式的 T 归一化为空格）
        val normalized = trimmed.replace("T", " ")
        synchronized(DATE_FORMATTERS) {
            DATE_FORMATTERS.forEach { formatter ->
                try {
                    formatter.parse(normalized)?.let { parsed -> return parsed.time }
                } catch (_: Exception) {
                    // 当前格式不匹配，继续尝试下一个
                }
            }
        }
        // 再尝试 Excel 序列号格式
        return try {
            val serial = trimmed.toDouble()
            if (serial < 1) return null // 无效序列号
            // 序列号中的时间是本地时间（账单注明 UTC+8），
            // 直接换算得到的是 UTC 解释的毫秒值，减去时区偏移得到正确 UTC 时间戳
            // 注意：序列号小数部分存在精度误差（如 0.6131944444 → 52979.9999… 秒），
            // 必须四舍五入，否则会丢掉 1 秒导致分钟显示少 1 分钟
            val rawMs = ((serial - EXCEL_EPOCH_DIFF) * MS_PER_DAY).roundToLong()
            rawMs - TimeZone.getDefault().getOffset(rawMs)
        } catch (_: Exception) {
            logger().e("parse date failed: $dateStr")
            null
        }
    }
}
