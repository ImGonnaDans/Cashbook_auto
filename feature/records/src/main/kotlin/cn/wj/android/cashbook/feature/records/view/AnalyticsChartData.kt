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

package cn.wj.android.cashbook.feature.records.view

import cn.wj.android.cashbook.core.design.component.LineEntry
import cn.wj.android.cashbook.core.model.entity.AnalyticsRecordBarEntity
import cn.wj.android.cashbook.core.model.enums.AnalyticsBarGranularity

/**
 * 分析报表折线图数据集
 *
 * 每条序列与传入的 [AnalyticsRecordBarEntity] 列表一一对应（含无数据日期，值为 0），
 * 保证按天统计时每一天都落在横轴上。
 *
 * @param expenditureEntries 支出坐标点（支出取正数，原值为负表示实际产生了收益）
 * @param incomeEntries 收入坐标点
 * @param balanceEntries 结余坐标点（可为负）
 * @param axisEntries 横轴标签坐标点，仅使用其中的 x 与 label
 */
internal data class AnalyticsChartSeries(
    val expenditureEntries: List<LineEntry>,
    val incomeEntries: List<LineEntry>,
    val balanceEntries: List<LineEntry>,
    val axisEntries: List<LineEntry>,
)

/**
 * 将分析报表数据转换为折线图坐标点。
 *
 * - 不做 0 值过滤：没有数据的日期填充为 0，曲线落到 0 轴；
 * - 支出不做取反：支出本身已表达「花钱」语义，为负数时表示实际产生了收益。
 */
internal fun buildAnalyticsChartSeries(dataList: List<AnalyticsRecordBarEntity>): AnalyticsChartSeries {
    val expenditureEntries = mutableListOf<LineEntry>()
    val incomeEntries = mutableListOf<LineEntry>()
    val balanceEntries = mutableListOf<LineEntry>()
    val axisEntries = mutableListOf<LineEntry>()
    dataList.forEachIndexed { index, entity ->
        val x = (index + 1).toFloat()
        val label = analyticsAxisLabel(entity.granularity, entity.date)
        expenditureEntries.add(
            LineEntry(x = x, y = entity.expenditure / MONEY_TO_YUAN, label = label),
        )
        incomeEntries.add(
            LineEntry(x = x, y = entity.income / MONEY_TO_YUAN, label = label),
        )
        balanceEntries.add(
            LineEntry(x = x, y = entity.balance / MONEY_TO_YUAN, label = label),
        )
        axisEntries.add(LineEntry(x = x, y = 0f, label = label))
    }
    return AnalyticsChartSeries(
        expenditureEntries = expenditureEntries,
        incomeEntries = incomeEntries,
        balanceEntries = balanceEntries,
        axisEntries = axisEntries,
    )
}

/**
 * 获取横轴标签。
 *
 * - 按年统计：显示年份，如 `2024`；
 * - 按月统计：仅显示月份，如 `1`、`12`；
 * - 按天统计：仅显示日期，如 `1`、`31`，不再拼接月份前缀，避免标签过长导致重叠。
 */
internal fun analyticsAxisLabel(granularity: AnalyticsBarGranularity, date: String): String =
    when (granularity) {
        AnalyticsBarGranularity.YEAR -> date
        else -> date.substringAfterLast('-').trimStart('0').ifEmpty { "0" }
    }

/** 金额分转元 */
private const val MONEY_TO_YUAN = 100f
