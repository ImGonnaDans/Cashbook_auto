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

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import cn.wj.android.cashbook.core.common.tools.funLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * drawable 资源名 -> 资源 id 的进程级缓存。
 *
 * `Resources.getIdentifier` 需要遍历资源表，单次开销较大；类型列表等场景会在同一帧内
 * 对大量条目逐个解析图标，缓存后每帧只做一次哈希查找，避免首帧掉帧。
 * 资源名在运行期不变，故此缓存无需失效。
 */
private val drawableIdCache = ConcurrentHashMap<String, Int>()

/** 解析 drawable 资源 id，带缓存；未命中的资源名按原实现返回 0 */
@SuppressLint("DiscouragedApi")
private fun Context.resolveDrawableIdByName(idStr: String): Int {
    drawableIdCache[idStr]?.let { return it }
    val resId = resources.getIdentifier(idStr, "drawable", packageName)
    // 仅缓存命中结果，避免把「未找到」固化，保持与原实现一致的异常行为
    if (resId != 0) {
        drawableIdCache[idStr] = resId
    }
    return resId
}

@Composable
fun painterDrawableResource(idStr: String): Painter {
    val context = LocalContext.current
    // Kotlin 2.3 + Compose 编译器禁止 runCatching 中包含 @Composable 调用，
    // 因此 LocalContext.current / painterResource 需移出 runCatching。
    val resId = runCatching {
        context.resolveDrawableIdByName(idStr)
    }.getOrElse { throwable ->
        funLogger("ResourcesKt").e(throwable, "painterDrawableResource(idStr = <$idStr>)")
        throw throwable
    }
    return if (resId != 0) {
        painterResource(id = resId)
    } else {
        // 兜底：资源名不存在（脏数据 / 图标资源被移除）时回退到默认图标，
        // 避免 painterResource(id = 0) 抛异常导致记账页、我的分类等界面崩溃
        funLogger("ResourcesKt").e("drawable resource not found: $idStr")
        rememberVectorPainter(image = Icons.Filled.MoreHoriz)
    }
}
