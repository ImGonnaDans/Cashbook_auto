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

package cn.wj.android.cashbook.core.model.model

/**
 * 账单来源
 *
 * @param transactionIdLabel 交易单号在备注中的标记名称，用于精确去重（如 `[微信单号:xxx]`）
 */
enum class BillSource(val transactionIdLabel: String) {
    /** 微信支付 */
    WECHAT(transactionIdLabel = "微信单号"),

    /** 支付宝 */
    ALIPAY(transactionIdLabel = "支付宝单号"),
}
