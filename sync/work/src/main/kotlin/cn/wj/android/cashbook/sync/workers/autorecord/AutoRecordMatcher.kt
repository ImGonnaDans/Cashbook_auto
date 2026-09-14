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

package cn.wj.android.cashbook.sync.workers.autorecord

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 半自动记账：通知文本匹配 + 金额捕获纯函数。
 *
 * 用户配置的匹配文本用 `*` 标记金额位置：**第一个 `*` 为金额位**，其后的 `*` 视为「任意文本」通配。
 * 不再要求通知文本以配置文本开头/结尾，而是在（标题 + 正文）文本中**定位**：
 * 前缀出现位置 → 其后第一个后缀片段 → 两者之间即金额段，再从金额段里取金额形状片段。
 *
 * > [王杰](mailto:15555650921@163.com) 创建于 2026/9/14
 */

/** 金额形状正则：可选符号 + 千分位整数或普通整数 + 可选 1~2 位小数（允许全角句点） */
private val AMOUNT_REGEX = Regex("""[-+]?(?:\d{1,3}(?:,\d{3})+|\d+)(?:[.．]\d{1,2})?""")

/** 空白规范化正则：半角/全角空格、不间断空格、换行等折叠为单个空格 */
private val WHITESPACE_REGEX = Regex("""[\s\u00A0\u3000]+""")

/** 金额上限（分）= 1000 亿元；超出视为无效，避免 BigDecimal.toLong 静默溢出 */
internal const val MAX_AMOUNT_CENTS = 10_000_000_000_000L

/**
 * 匹配结果。
 *
 * @param raw 捕获到的原始字符串（金额形状片段或金额段原文）
 * @param amountCents 解析出的金额（分）；解析失败为 null（此时上层仍可提示记账，只是不带金额）
 */
data class AutoRecordMatch(
    val raw: String,
    val amountCents: Long?,
)

/** 把各类空白（含全角空格、换行）折叠为单个半角空格并去除首尾空白 */
internal fun normalizeWhitespace(text: String): String = text.replace(WHITESPACE_REGEX, " ").trim()

/** 全角字符转半角（ASCII 可见区 `FF01~FF5E` 与全角空格），用于数字与符号识别 */
internal fun normalizeWidth(text: String): String = buildString(text.length) {
    text.forEach { char ->
        when {
            char.code == 0x3000 -> append(' ')
            char.code in 0xFF01..0xFF5E -> append((char.code - 0xFEE0).toChar())
            else -> append(char)
        }
    }
}

/**
 * 用 [pattern] 从 [text] 中提取金额。
 *
 * 规则：
 * 1. [text] 与 [pattern] 先做空白规范化（跨行 / 多空格 / 全角空格均等价于单个空格）；
 * 2. [pattern] 第一个 `*` 之前为前缀、之后为后缀；前缀只需在 [text] 中**出现**（不要求开头）；
 * 3. 后缀按 `*` 拆分并 trim 后逐段定位：第一段起点即金额段终点（避免把后缀之后的内容一起吞下），
 *    其余段仅做先后顺序校验——即第一个 `*` 之后的 `*` 等价于「任意文本」；
 * 4. 金额段内先用 [AMOUNT_REGEX] 提取金额形状片段（如 "19.99 元，优惠 5" → "19.99"），
 *    取不到时回退为金额段去空白后的原文（供上层提示「命中但无金额」）。
 *
 * @param text 通知文本（建议为「标题 + 正文」拼接，如 "支付宝 你有一笔19.99元的支出"）
 * @param pattern 用户配置的匹配文本（如 "你有一笔*元的支出"）
 * @return 金额原始字符串（如 "19.99"）或金额段原文；未命中或 pattern 无 `*` 时返回 null
 */
fun parseAutoRecord(text: String, pattern: String): String? {
    if (pattern.isBlank() || !pattern.contains('*')) return null

    val haystack = normalizeWhitespace(text)
    val needle = normalizeWhitespace(pattern)
    val firstStar = needle.indexOf('*')
    if (firstStar < 0) return null

    val prefix = needle.substring(0, firstStar)
    val suffix = needle.substring(firstStar + 1)

    val prefixIndex = if (prefix.isEmpty()) 0 else haystack.indexOf(prefix)
    if (prefixIndex < 0) return null
    val captureStart = prefixIndex + prefix.length

    var captureEnd = haystack.length
    var cursor = captureStart
    suffix.split('*')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEachIndexed { index, segment ->
            val segmentIndex = haystack.indexOf(segment, cursor)
            if (segmentIndex < 0) return null
            if (index == 0) {
                captureEnd = segmentIndex
            }
            cursor = segmentIndex + segment.length
        }

    if (captureEnd < captureStart) return null
    val middle = haystack.substring(captureStart, captureEnd)
    AMOUNT_REGEX.find(middle)?.value?.let { return it }
    return middle.trim().takeIf { it.isNotBlank() }
}

/**
 * 依次用 [patterns] 匹配 [text]，返回**首个能解析出合法金额**的结果。
 *
 * 多条匹配文本常见于同时配置微信 / 支付宝 / 银行 App 的支付通知格式；
 * 若某条规则命中但金额解析失败（如捕获到的是说明文字），会继续尝试后续规则；
 * 若所有命中的规则都解析不出金额，则返回首个命中结果（[AutoRecordMatch.amountCents] 为 null），
 * 上层据此仍给出「点击记一笔」提醒，只是不预填金额。
 *
 * 不含 `*` 或空白的模式不会命中（[parseAutoRecord] 返回 null），自动跳过。
 *
 * @param text 通知文本（建议为「标题 + 正文」拼接）
 * @param patterns 用户配置的匹配文本列表
 * @return 匹配结果（首个可解析出金额者优先），全部未命中返回 null
 */
fun parseAutoRecordFromPatterns(text: String, patterns: List<String>): AutoRecordMatch? {
    var fallback: AutoRecordMatch? = null
    patterns.forEach { pattern ->
        val raw = parseAutoRecord(text, pattern) ?: return@forEach
        val amountCents = parseAmountCents(raw)
        if (amountCents != null) {
            return AutoRecordMatch(raw = raw, amountCents = amountCents)
        }
        if (fallback == null) {
            fallback = AutoRecordMatch(raw = raw, amountCents = null)
        }
    }
    return fallback
}

/**
 * 从文本中提取金额并转换为分（Long）。
 *
 * 先全角转半角，再用 [AMOUNT_REGEX] 取**首个金额形状片段**，
 * 故可容忍金额前后的单位与说明文字（"19.99人民币"、"19.99元整"、"19.99 （原价 29.99）"）。
 * 绝对值超过 [MAX_AMOUNT_CENTS] 视为无效（防 `BigDecimal.toLong` 静默溢出）。
 *
 * 示例：
 * - "19.99" → 1999
 * - "¥1,234.56" → 123456
 * - "￥20元" → 2000
 * - "19.99人民币" → 1999
 * - "19.99元整" → 1999
 * - " 12.50 " → 1250
 * - "-19.99" → -1999（负数透传，用于退款/红包等场景）
 * - "" / "abc" → null
 * - "99999999999999999999" → null（超上限）
 */
fun parseAmountCents(raw: String): Long? {
    val normalized = normalizeWidth(raw)
    val amountText = AMOUNT_REGEX.find(normalized)?.value ?: return null
    val cleaned = amountText.replace(",", "").trim()
    if (cleaned.isEmpty()) return null
    val bd = cleaned.toBigDecimalOrNull() ?: return null
    val cents = bd.multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP)
    if (cents.abs() > BigDecimal(MAX_AMOUNT_CENTS)) return null
    return cents.toLong()
}
