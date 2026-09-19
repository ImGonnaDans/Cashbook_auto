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

/**
 * 挑选可绘制的 X 轴标签下标。
 *
 * 按 [centers] 升序遍历，仅当标签与上一个已绘制标签的边缘间距不小于 [minGap]、
 * 且标签中心点落在 [[leftBound], [rightBound]] 内时才保留，从而避免标签字符重叠。
 * 画布宽度不足以放下全部标签时，越靠后的标签被跳过；边缘标签超出画布的部分由画布自动裁剪，
 * 不会因此丢失最后一个标签。
 *
 * @param centers 各标签中心点坐标，需按升序排列
 * @param halfWidths 各标签半宽，下标与 [centers] 一一对应
 * @param minGap 相邻两个标签之间的最小间距（像素）
 * @param leftBound 中心点可绘制区域左边界（像素）
 * @param rightBound 中心点可绘制区域右边界（像素）
 * @return 需要绘制的标签下标列表（升序，互不重叠）
 */
internal fun selectVisibleXLabelIndices(
    centers: List<Float>,
    halfWidths: List<Float>,
    minGap: Float,
    leftBound: Float,
    rightBound: Float,
): List<Int> {
    if (centers.isEmpty() || centers.size != halfWidths.size) return emptyList()
    val result = mutableListOf<Int>()
    var lastRight = Float.NEGATIVE_INFINITY
    centers.forEachIndexed { index, center ->
        val halfWidth = halfWidths[index]
        val left = center - halfWidth
        val right = center + halfWidth
        if (center < leftBound || center > rightBound) return@forEachIndexed
        if (result.isNotEmpty() && left - lastRight < minGap) return@forEachIndexed
        result.add(index)
        lastRight = right
    }
    return result
}
