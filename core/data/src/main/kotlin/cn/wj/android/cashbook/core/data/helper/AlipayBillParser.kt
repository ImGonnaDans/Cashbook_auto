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

import androidx.annotation.VisibleForTesting
import cn.wj.android.cashbook.core.common.ext.logger
import cn.wj.android.cashbook.core.model.model.BillDirection
import cn.wj.android.cashbook.core.model.model.BillParseResult
import cn.wj.android.cashbook.core.model.model.BillSummary
import cn.wj.android.cashbook.core.model.model.ImportedBillItem
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * 支付宝 xlsx 账单解析器
 *
 * 与微信账单的差异：
 * 1. 前置信息行数不固定（微信为 17 行信息 + 第 18 行表头），这里**按内容动态定位表头行**
 *    （同时包含「交易时间」与「交易分类」的行），避免官方导出格式变化导致解析失效；
 * 2. **部分行会缺失单元格**（例如备注列单元格整个不存在），因此必须**按单元格引用（A/B/C…）定位列**，
 *    不能按出现顺序收集，否则列会整体错位；
 * 3. 文本单元格末尾可能带制表符（如交易订单号），所有取值统一 trim；
 * 4. 只保留交易状态为「交易成功」的交易。
 *
 * 注意：本方法依赖 [org.xmlpull.v1.XmlPullParserFactory]，在纯 JVM unit test 下
 * （android.jar stub）会抛 "not mocked"。解析子逻辑 [parseSheetCells] / [findHeaderRowIndex] /
 * [convertToItem] / [parseDateTime] 均为纯函数，可在单测中直接驱动。
 */
object AlipayBillParser {

    /** 需要导入的交易状态 */
    const val STATUS_SUCCESS = "交易成功"

    /** 退款方向（支付宝「收/支」列的取值之一） */
    @VisibleForTesting
    internal const val DIRECTION_REFUND = "退款"

    /** 表格列名（用于按表头文本定位列，兼容列顺序变化） */
    @VisibleForTesting
    internal const val HEADER_TIME = "交易时间"

    @VisibleForTesting
    internal const val HEADER_CATEGORY = "交易分类"
    private const val HEADER_COUNTERPARTY = "交易对方"
    private const val HEADER_DESCRIPTION = "商品说明"
    private const val HEADER_DIRECTION = "收/支"
    private const val HEADER_AMOUNT = "金额"
    private const val HEADER_PAYMENT = "收/付款方式"

    @VisibleForTesting
    internal const val HEADER_STATUS = "交易状态"
    private const val HEADER_TRANSACTION_ID = "交易订单号"
    private const val HEADER_MERCHANT_ID = "商家订单号"
    private const val HEADER_REMARK = "备注"

    /** 共享字符串类型标记 */
    private const val TYPE_SHARED_STRING = "s"

    /** 内联字符串类型标记 */
    private const val TYPE_INLINE_STR = "inlineStr"

    /**
     * 解析支付宝 xlsx 账单文件
     *
     * @param inputStream xlsx 文件输入流
     * @return 解析结果（仅含交易成功的记录）；解析失败返回 null
     */
    fun parse(inputStream: InputStream): BillParseResult? {
        return try {
            val zipData = XlsxReader.readZipEntries(inputStream)
            val sharedStrings = zipData.sharedStrings ?: return null
            val sheetData = zipData.sheetData ?: return null

            val stringPool = XlsxReader.parseSharedStrings(sharedStrings)
            val rows = parseSheetCells(sheetData, stringPool)
            val headerIndex = findHeaderRowIndex(rows) ?: return null
            val columnMap = rows[headerIndex].cells

            val items = rows.drop(headerIndex + 1)
                .mapNotNull { row -> convertToItem(row, columnMap) }

            if (items.isEmpty()) return null

            val summary = BillSummary(
                totalCount = items.size,
                incomeCount = items.count { it.direction == BillDirection.INCOME },
                incomeAmount = items.filter { it.direction == BillDirection.INCOME }.sumOf { it.amount },
                expenditureCount = items.count { it.direction == BillDirection.EXPENDITURE },
                expenditureAmount = items.filter { it.direction == BillDirection.EXPENDITURE }.sumOf { it.amount },
            )

            BillParseResult(items = items, summary = summary)
        } catch (e: Exception) {
            logger().e(e, "parse alipay bill failed")
            null
        }
    }

    /**
     * 单元格行数据
     *
     * @param rowIndex 行号（1 起）
     * @param cells 列字母（A/B/C…）→ 单元格文本
     */
    @VisibleForTesting
    internal data class SheetRow(
        val rowIndex: Int,
        val cells: Map<String, String>,
    )

    /**
     * 解析 sheet1.xml，按**单元格引用**收集每行数据
     *
     * 结构示例：
     * ```xml
     * <row r="26" spans="1:12">
     *   <c r="A26" s="1"><v>45998.6131944444</v></c>   <!-- 日期序列号 -->
     *   <c r="B26" t="s"><v>34</v></c>                 <!-- 共享字符串索引 -->
     * </row>
     * ```
     */
    @VisibleForTesting
    internal fun parseSheetCells(data: ByteArray, stringPool: List<String>): List<SheetRow> {
        val rows = mutableListOf<SheetRow>()
        val parser = XlsxReader.createParser(data)

        var rowIndex = 0
        var cellRef = ""
        var cellType = ""
        var valueBuilder = StringBuilder()
        var inValue = false
        var cells = mutableMapOf<String, String>()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> {
                        rowIndex = parser.getAttributeValue(null, "r")?.toIntOrNull() ?: 0
                        cells = mutableMapOf()
                    }

                    "c" -> {
                        cellRef = parser.getAttributeValue(null, "r").orEmpty().filter { it.isLetter() }
                        cellType = parser.getAttributeValue(null, "t").orEmpty()
                        valueBuilder = StringBuilder()
                    }

                    "v" -> inValue = true

                    // inlineStr：<c t="inlineStr"><is><t>文本</t></is></c>
                    "t" -> if (cellType == TYPE_INLINE_STR) {
                        inValue = true
                    }
                }

                XmlPullParser.TEXT -> if (inValue) {
                    valueBuilder.append(parser.text)
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "v", "t" -> inValue = false

                    "c" -> if (cellRef.isNotEmpty()) {
                        cells[cellRef] = resolveCellValue(
                            cellType = cellType,
                            raw = valueBuilder.toString(),
                            stringPool = stringPool,
                        )
                    }

                    "row" -> if (cells.isNotEmpty()) {
                        rows.add(SheetRow(rowIndex = rowIndex, cells = cells.toMap()))
                    }
                }
            }
            eventType = parser.next()
        }
        return rows
    }

    /** 解析单元格文本：t="s" 取字符串池，其余取原始值 */
    private fun resolveCellValue(cellType: String, raw: String, stringPool: List<String>): String {
        return when (cellType) {
            TYPE_SHARED_STRING -> {
                val index = raw.trim().toIntOrNull() ?: 0
                stringPool.getOrElse(index) { "" }
            }

            else -> raw
        }
    }

    /**
     * 定位表头行：同时包含「交易时间」与「交易分类」的行
     *
     * @return 表头行在 [rows] 中的下标，未找到返回 null
     */
    @VisibleForTesting
    internal fun findHeaderRowIndex(rows: List<SheetRow>): Int? {
        return rows.indexOfFirst { row ->
            val values = row.cells.values.map { it.trim() }
            values.contains(HEADER_TIME) && values.contains(HEADER_CATEGORY)
        }.takeIf { it >= 0 }
    }

    /**
     * 将一行数据转换为 [ImportedBillItem]
     *
     * 列顺序（按表头文本定位，示例）：
     * 交易时间 | 交易分类 | 交易对方 | 对方账号 | 商品说明 | 收/支 | 金额 |
     * 收/付款方式 | 交易状态 | 交易订单号 | 商家订单号 | 备注
     *
     * 只保留「[STATUS_SUCCESS]」的记录；「收/支」为其他值（如不计收支）的记录跳过。
     */
    @VisibleForTesting
    internal fun convertToItem(row: SheetRow, columnMap: Map<String, String>): ImportedBillItem? {
        val status = value(row, columnMap, HEADER_STATUS)
        if (status != STATUS_SUCCESS) return null

        // 收/支：收入 / 支出 / 退款（退款按收入方向导入，类型固定为内置「退款」分类）
        val directionValue = value(row, columnMap, HEADER_DIRECTION)
        val isRefund = directionValue == DIRECTION_REFUND
        val direction = when (directionValue) {
            "收入", DIRECTION_REFUND -> BillDirection.INCOME
            "支出" -> BillDirection.EXPENDITURE
            else -> return null
        }

        val transactionTime = parseDateTime(value(row, columnMap, HEADER_TIME)) ?: return null

        val amountStr = value(row, columnMap, HEADER_AMOUNT)
            .replace("¥", "")
            .replace(",", "")
            .trim()
        val amount = amountStr.toDoubleOrNull() ?: return null

        return ImportedBillItem(
            transactionTime = transactionTime,
            // 支付宝账单：交易分类作为原始交易类型，用于后续分类映射
            transactionType = value(row, columnMap, HEADER_CATEGORY),
            counterparty = value(row, columnMap, HEADER_COUNTERPARTY),
            description = value(row, columnMap, HEADER_DESCRIPTION),
            direction = direction,
            amount = amount,
            paymentMethod = value(row, columnMap, HEADER_PAYMENT).let { if (it == "/") "" else it },
            status = status,
            transactionId = value(row, columnMap, HEADER_TRANSACTION_ID).let { if (it == "/") "" else it },
            merchantId = value(row, columnMap, HEADER_MERCHANT_ID).let { if (it == "/") "" else it },
            remark = value(row, columnMap, HEADER_REMARK).let { if (it == "/") "" else it },
            isRefund = isRefund,
        )
    }

    /** 按表头列名取值并去除首尾空白（缺失列返回空字符串） */
    private fun value(row: SheetRow, columnMap: Map<String, String>, header: String): String {
        val column = columnMap.entries.firstOrNull { it.value.trim() == header }?.key ?: return ""
        return row.cells[column].orEmpty().trim()
    }

    /** 解析日期时间（复用 xlsx 通用逻辑，同时支持 Excel 序列号与标准字符串） */
    @VisibleForTesting
    internal fun parseDateTime(dateStr: String): Long? = XlsxReader.parseDateTime(dateStr)
}
