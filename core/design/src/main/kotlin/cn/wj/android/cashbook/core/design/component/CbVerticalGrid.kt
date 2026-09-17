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

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 横向网格布局（懒加载）
 *
 * 基于 [LazyVerticalGrid] 实现，只组合可见区域内的格子：类型等条目较多的场景下，
 * 首帧与每次重组的组合成本由 O(全部条目) 降为 O(可见条目)。
 *
 * 注意：调用方必须为网格提供**有界高度**（例如 `Modifier.heightIn(max = ...)`），
 * 不可放在 `Column(Modifier.verticalScroll(...))` 这类高度无界的容器里，否则无法测量。
 *
 * > [王杰](mailto:15555650921@163.com) 创建于 2023/12/24
 */
@Composable
fun <T> CbVerticalGrid(
    columns: Int,
    items: List<T>,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) {
        // 空列表保留一个空占位：与原实现一致，避免根节点无可绘制内容
        Spacer(modifier = modifier.fillMaxWidth())
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = modifier,
        ) {
            items(count = items.size) { index ->
                content(items[index])
            }
        }
    }
}
