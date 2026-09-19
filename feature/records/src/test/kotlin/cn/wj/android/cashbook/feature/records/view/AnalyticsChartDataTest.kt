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

import cn.wj.android.cashbook.core.model.entity.AnalyticsRecordBarEntity
import cn.wj.android.cashbook.core.model.enums.AnalyticsBarGranularity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 分析报表折线图数据转换单元测试
 *
 * 覆盖：无数据日期补 0、支出取正数（负值为收益）、负结余不被丢弃、横轴标签文本。
 */
class AnalyticsChartDataTest {

    private fun entity(
        date: String,
        expenditure: Long = 0L,
        income: Long = 0L,
        balance: Long = 0L,
        granularity: AnalyticsBarGranularity = AnalyticsBarGranularity.DAY,
    ) = AnalyticsRecordBarEntity(
        date = date,
        expenditure = expenditure,
        income = income,
        balance = balance,
        granularity = granularity,
    )

    @Test
    fun when_day_has_no_data_then_series_keeps_zero_point() {
        val series = buildAnalyticsChartSeries(
            listOf(
                entity(date = "2024-01-01", expenditure = 100_00L, income = 200_00L, balance = 100_00L),
                entity(date = "2024-01-02"),
            ),
        )

        // 每一天都有坐标点，无数据日期填充为 0
        assertThat(series.expenditureEntries).hasSize(2)
        assertThat(series.incomeEntries).hasSize(2)
        assertThat(series.balanceEntries).hasSize(2)
        assertThat(series.expenditureEntries[1].y).isEqualTo(0f)
        assertThat(series.incomeEntries[1].y).isEqualTo(0f)
        assertThat(series.balanceEntries[1].y).isEqualTo(0f)
    }

    @Test
    fun when_series_built_then_x_position_matches_index() {
        val series = buildAnalyticsChartSeries(
            listOf(
                entity(date = "2024-01-01"),
                entity(date = "2024-01-02"),
                entity(date = "2024-01-03"),
            ),
        )

        assertThat(series.expenditureEntries.map { it.x }).containsExactly(1f, 2f, 3f).inOrder()
        assertThat(series.axisEntries.map { it.x }).containsExactly(1f, 2f, 3f).inOrder()
    }

    @Test
    fun when_expenditure_positive_then_point_is_positive() {
        val series = buildAnalyticsChartSeries(
            listOf(entity(date = "2024-01-01", expenditure = 300_50L, balance = -300_50L)),
        )

        // 支出本身已表明是花钱，取正数显示
        assertThat(series.expenditureEntries.first().y).isEqualTo(300.5f)
        assertThat(series.balanceEntries.first().y).isEqualTo(-300.5f)
    }

    @Test
    fun when_expenditure_negative_then_point_stays_negative() {
        val series = buildAnalyticsChartSeries(
            listOf(entity(date = "2024-01-01", expenditure = -80_00L, balance = 80_00L)),
        )

        // 支出为负表示实际产生了收益，保留负值（落在 0 轴下方）
        assertThat(series.expenditureEntries.first().y).isEqualTo(-80f)
    }

    @Test
    fun when_balance_negative_then_point_is_kept() {
        val series = buildAnalyticsChartSeries(
            listOf(entity(date = "2024-01-01", expenditure = 500_00L, income = 300_00L, balance = -200_00L)),
        )

        assertThat(series.balanceEntries).hasSize(1)
        assertThat(series.balanceEntries.first().y).isEqualTo(-200f)
    }

    @Test
    fun when_day_granularity_then_label_is_day_without_month_prefix() {
        val series = buildAnalyticsChartSeries(
            listOf(
                entity(date = "2024-09-01"),
                entity(date = "2024-09-15"),
                entity(date = "2024-09-30"),
            ),
        )

        assertThat(series.axisEntries.map { it.label })
            .containsExactly("1", "15", "30")
            .inOrder()
    }

    @Test
    fun when_month_granularity_then_label_is_month_without_leading_zero() {
        val series = buildAnalyticsChartSeries(
            listOf(
                entity(date = "2024-01", granularity = AnalyticsBarGranularity.MONTH),
                entity(date = "2024-12", granularity = AnalyticsBarGranularity.MONTH),
            ),
        )

        assertThat(series.axisEntries.map { it.label }).containsExactly("1", "12").inOrder()
    }

    @Test
    fun when_year_granularity_then_label_is_year() {
        val series = buildAnalyticsChartSeries(
            listOf(
                entity(date = "2023", granularity = AnalyticsBarGranularity.YEAR),
                entity(date = "2024", granularity = AnalyticsBarGranularity.YEAR),
            ),
        )

        assertThat(series.axisEntries.map { it.label }).containsExactly("2023", "2024").inOrder()
    }

    @Test
    fun when_data_list_empty_then_series_is_empty() {
        val series = buildAnalyticsChartSeries(emptyList())

        assertThat(series.expenditureEntries).isEmpty()
        assertThat(series.incomeEntries).isEmpty()
        assertThat(series.balanceEntries).isEmpty()
        assertThat(series.axisEntries).isEmpty()
    }
}
