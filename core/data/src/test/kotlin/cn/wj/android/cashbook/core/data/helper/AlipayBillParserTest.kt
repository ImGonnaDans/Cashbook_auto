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

import cn.wj.android.cashbook.core.model.model.BillDirection
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [AlipayBillParser] 纯函数测试（findHeaderRowIndex / convertToItem / parseDateTime）
 *
 * 全部使用脱敏数据；列按**单元格引用**（A/B/C…）构造，覆盖支付宝账单「部分单元格缺失」的特例。
 */
class AlipayBillParserTest {

    /** 表头列引用 → 列名（与支付宝导出示例一致） */
    private val headerCells: Map<String, String> = mapOf(
        "A" to "交易时间",
        "B" to "交易分类",
        "C" to "交易对方",
        "D" to "对方账号",
        "E" to "商品说明",
        "F" to "收/支",
        "G" to "金额",
        "H" to "收/付款方式",
        "I" to "交易状态",
        "J" to "交易订单号",
        "K" to "商家订单号",
        "L" to "备注",
    )

    private fun row(cells: Map<String, String>) = AlipayBillParser.SheetRow(rowIndex = 24, cells = cells)

    @Test
    fun findHeaderRowIndex_locates_header_after_metadata_rows() {
        val rows = listOf(
            AlipayBillParser.SheetRow(1, mapOf("A" to "导出信息：")),
            AlipayBillParser.SheetRow(2, mapOf("A" to "姓名：测试用户")),
            AlipayBillParser.SheetRow(23, headerCells),
            AlipayBillParser.SheetRow(24, mapOf("A" to "45998.6131944444")),
        )
        assertThat(AlipayBillParser.findHeaderRowIndex(rows)).isEqualTo(2)
    }

    @Test
    fun findHeaderRowIndex_returns_null_without_header() {
        val rows = listOf(AlipayBillParser.SheetRow(1, mapOf("A" to "导出信息：")))
        assertThat(AlipayBillParser.findHeaderRowIndex(rows)).isNull()
    }

    @Test
    fun convertToItem_success_row_maps_fields_with_trim() {
        val item = AlipayBillParser.convertToItem(
            row = row(
                mapOf(
                    "A" to "45998.6131944444",
                    "B" to "餐饮",
                    "C" to "淘宝闪购",
                    "E" to "汉堡外卖订单",
                    "F" to "支出",
                    "G" to "30.3",
                    "H" to "平安银行信用购&红包",
                    "I" to "交易成功",
                    "J" to "2025120722001192861424014358\t",
                ),
            ),
            columnMap = headerCells,
        )
        assertThat(item).isNotNull()
        assertThat(item!!.direction).isEqualTo(BillDirection.EXPENDITURE)
        assertThat(item.amount).isEqualTo(30.3)
        assertThat(item.transactionType).isEqualTo("餐饮")
        assertThat(item.counterparty).isEqualTo("淘宝闪购")
        assertThat(item.description).isEqualTo("汉堡外卖订单")
        assertThat(item.paymentMethod).isEqualTo("平安银行信用购&红包")
        // 订单号尾部制表符必须被去除，否则去重标记会带控制字符
        assertThat(item.transactionId).isEqualTo("2025120722001192861424014358")
        assertThat(item.status).isEqualTo("交易成功")
        assertThat(item.transactionTime).isEqualTo(AlipayBillParser.parseDateTime("45998.6131944444"))
    }

    @Test
    fun convertToItem_missing_cells_do_not_shift_columns() {
        // H（收/付款方式）与 L（备注）单元格整个缺失：金额、状态、订单号仍应取到正确列
        val item = AlipayBillParser.convertToItem(
            row = row(
                mapOf(
                    "A" to "46000.5",
                    "B" to "交通",
                    "C" to "滴滴出行",
                    "E" to "快车",
                    "F" to "支出",
                    "G" to "12",
                    "I" to "交易成功",
                    "J" to "2025120922001492861427777777",
                ),
            ),
            columnMap = headerCells,
        )
        assertThat(item).isNotNull()
        assertThat(item!!.amount).isEqualTo(12.0)
        assertThat(item.status).isEqualTo("交易成功")
        assertThat(item.paymentMethod).isEmpty()
        assertThat(item.remark).isEmpty()
    }

    @Test
    fun convertToItem_only_success_status_imported() {
        val base = mapOf(
            "A" to "45998.6",
            "B" to "餐饮",
            "C" to "商户",
            "F" to "支出",
            "G" to "10",
            "J" to "1",
        )
        assertThat(AlipayBillParser.convertToItem(row(base + ("I" to "交易成功")), headerCells)).isNotNull()
        assertThat(AlipayBillParser.convertToItem(row(base + ("I" to "支付成功")), headerCells)).isNull()
        assertThat(AlipayBillParser.convertToItem(row(base + ("I" to "交易关闭")), headerCells)).isNull()
    }

    @Test
    fun convertToItem_refund_direction_then_income_with_refund_flag() {
        val data = mapOf(
            "A" to "45998.6",
            "B" to "餐饮",
            "C" to "商户",
            "F" to AlipayBillParser.DIRECTION_REFUND,
            "G" to "10",
            "I" to "交易成功",
            "J" to "1",
        )
        val item = AlipayBillParser.convertToItem(row(data), headerCells)
        assertThat(item).isNotNull()
        // 退款按收入方向纳入导入，并标记 isRefund（导入时固定挂内置「退款」分类）
        assertThat(item!!.direction).isEqualTo(BillDirection.INCOME)
        assertThat(item.isRefund).isTrue()
    }

    @Test
    fun convertToItem_ignores_non_income_expenditure_rows() {
        val data = mapOf(
            "A" to "45998.6",
            "B" to "其他",
            "C" to "商户",
            "F" to "不计收支",
            "G" to "10",
            "I" to "交易成功",
            "J" to "1",
        )
        assertThat(AlipayBillParser.convertToItem(row(data), headerCells)).isNull()
    }

    @Test
    fun convertToItem_invalid_amount_or_time_returns_null() {
        val badAmount = mapOf(
            "A" to "45998.6",
            "B" to "其他",
            "C" to "商户",
            "F" to "支出",
            "G" to "—",
            "I" to "交易成功",
            "J" to "1",
        )
        assertThat(AlipayBillParser.convertToItem(row(badAmount), headerCells)).isNull()

        val badTime = mapOf(
            "A" to "不是日期",
            "B" to "其他",
            "C" to "商户",
            "F" to "支出",
            "G" to "10",
            "I" to "交易成功",
            "J" to "1",
        )
        assertThat(AlipayBillParser.convertToItem(row(badTime), headerCells)).isNull()
    }

    @Test
    fun parseDateTime_supports_serial_and_standard_format() {
        val serial = AlipayBillParser.parseDateTime("45998.6131944444")
        assertThat(serial).isNotNull()
        // 序列号换算存在亚毫秒截断（.999…ms），按「分钟」粒度比较
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val serialText = formatter.format(Date(serial!!))
        val standardText = formatter.format(Date(AlipayBillParser.parseDateTime("2025-12-07 14:43:00")!!))
        assertThat(serialText).isEqualTo(standardText)
        assertThat(serialText).isEqualTo("2025-12-07 14:43")
    }

    @Test
    fun parseDateTime_supports_multiple_text_formats() {
        // 标准格式（含无秒）
        assertThat(AlipayBillParser.parseDateTime("2020-06-01 12:27")).isNotNull()
        // 中文短日期
        assertThat(AlipayBillParser.parseDateTime("2020/6/1 12:27")).isEqualTo(
            AlipayBillParser.parseDateTime("2020-06-01 12:27"),
        )
        // 英文短日期（部分支付宝导出/整理版：6/1/2020 12:27）
        assertThat(AlipayBillParser.parseDateTime("6/1/2020 12:27")).isEqualTo(
            AlipayBillParser.parseDateTime("2020-06-01 12:27"),
        )
    }
}
