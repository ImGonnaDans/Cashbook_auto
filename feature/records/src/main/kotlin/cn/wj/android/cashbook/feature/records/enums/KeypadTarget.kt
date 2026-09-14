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

package cn.wj.android.cashbook.feature.records.enums

/**
 * 常驻数字键盘的编辑目标
 *
 * 添加账单页数字键盘固定于底部，切换目标决定「确认」写回哪个字段。
 *
 * > [王杰](mailto:15555650921@163.com) 创建于 2026/9/14
 */
enum class KeypadTarget {

    /** 金额 */
    AMOUNT,

    /** 手续费 */
    CHARGES,

    /** 优惠 */
    CONCESSIONS,
}
