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

package cn.wj.android.cashbook.core.design.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 折线图横轴标签排布单元测试
 *
 * 覆盖：密集标签互不重叠、越界标签被跳过、空数据与非法入参。
 */
class LineChartLabelLayoutTest {

    private val minGap = 8f
    private val leftBound = 0f
    private val rightBound = 360f

    private fun selected(
        centers: List<Float>,
        halfWidths: List<Float>,
    ) = selectVisibleXLabelIndices(
        centers = centers,
        halfWidths = halfWidths,
        minGap = minGap,
        leftBound = leftBound,
        rightBound = rightBound,
    )

    @Test
    fun when_centers_empty_then_no_label_selected() {
        assertThat(selected(emptyList(), emptyList())).isEmpty()
    }

    @Test
    fun when_single_label_then_selected() {
        assertThat(selected(listOf(180f), listOf(6f))).containsExactly(0)
    }

    @Test
    fun when_sizes_mismatch_then_no_label_selected() {
        assertThat(selected(listOf(10f, 20f), listOf(6f))).isEmpty()
    }

    @Test
    fun when_labels_dense_then_selected_labels_never_overlap() {
        // 31 天，标签中心点在绘图区内均匀分布，标签宽度 13px（如 "1"、"15"、"31"）
        val centers = (1..31).map { index -> 60f + (index - 1) * (284f / 30f) }
        val halfWidths = List(centers.size) { 6.5f }

        val result = selected(centers, halfWidths)

        assertThat(result).isNotEmpty()
        // 并非所有标签都能放下，必须做取舍
        assertThat(result.size).isLessThan(centers.size)
        // 首个标签在绘图区左侧且未越界，应被保留
        assertThat(result.first()).isEqualTo(0)
        // 结果升序
        assertThat(result).isInOrder()
        assertNoOverlap(centers, halfWidths, result)
    }

    @Test
    fun when_adjacent_labels_only_13px_apart_then_overlapping_label_skipped() {
        // 旧实现按点数跳步，13px 间距的相邻标签会全部画出并重叠
        val centers = listOf(60f, 73f, 86f)
        val halfWidths = listOf(6.5f, 6.5f, 6.5f)

        val result = selected(centers, halfWidths)

        // 中间标签与首个标签重叠被跳过，第三个标签满足间距要求
        assertThat(result).containsExactly(0, 2)
        assertNoOverlap(centers, halfWidths, result)
    }

    @Test
    fun when_label_center_out_of_bounds_then_skipped() {
        // 中心点 -10f、400f 超出绘制区域，180f 在区域以内
        val centers = listOf(-10f, 180f, 400f)
        val halfWidths = listOf(6.5f, 6.5f, 6.5f)

        assertThat(selected(centers, halfWidths)).containsExactly(1)
    }

    @Test
    fun when_edge_label_wider_than_canvas_then_kept() {
        // 边缘标签超出画布的部分由画布裁剪，不应被丢弃（否则最后一个日期会没有标签）
        val centers = listOf(60f, 350f)
        val halfWidths = listOf(20f, 20f)

        assertThat(selected(centers, halfWidths)).containsExactly(0, 1)
    }

    @Test
    fun when_gap_equals_min_gap_then_next_label_selected() {
        // 左边缘恰好落在上一个标签右边缘 + minGap 上，应保留
        val centers = listOf(60f, 81f)
        val halfWidths = listOf(6.5f, 6.5f)

        assertThat(selected(centers, halfWidths)).containsExactly(0, 1)
    }

    private fun assertNoOverlap(
        centers: List<Float>,
        halfWidths: List<Float>,
        selectedIndices: List<Int>,
    ) {
        selectedIndices.zipWithNext { previous, next ->
            val previousRight = centers[previous] + halfWidths[previous]
            val nextLeft = centers[next] - halfWidths[next]
            assertThat(nextLeft - previousRight).isAtLeast(minGap)
        }
        selectedIndices.forEach { index ->
            assertThat(centers[index]).isAtLeast(leftBound)
            assertThat(centers[index]).isAtMost(rightBound)
        }
    }
}
