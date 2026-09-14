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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [parseAutoRecord] + [parseAmountCents] 纯函数单测（纯 JVM）
 */
class AutoRecordMatcherTest {

    // ========== parseAutoRecord ==========

    @Test
    fun when_pattern_matches_then_returns_amount() {
        assertThat(parseAutoRecord("微信支付 19.99 元", "微信支付 * 元")).isEqualTo("19.99")
    }

    @Test
    fun when_pattern_has_no_star_then_returns_null() {
        assertThat(parseAutoRecord("微信支付 19.99 元", "微信支付 19.99 元")).isNull()
    }

    @Test
    fun when_text_contains_no_prefix_then_returns_null() {
        assertThat(parseAutoRecord("支付宝 19.99 元", "微信支付 * 元")).isNull()
    }

    @Test
    fun when_text_contains_no_suffix_after_prefix_then_returns_null() {
        assertThat(parseAutoRecord("微信支付 19.99", "微信支付 * 元")).isNull()
    }

    @Test
    fun when_captured_is_blank_then_returns_null() {
        assertThat(parseAutoRecord("微信支付  元", "微信支付 * 元")).isNull()
    }

    @Test
    fun when_pattern_starts_with_star_then_prefix_is_empty() {
        assertThat(parseAutoRecord("19.99 元到账", "* 元到账")).isEqualTo("19.99")
    }

    @Test
    fun when_pattern_ends_with_star_then_suffix_is_empty() {
        assertThat(parseAutoRecord("微信支付 19.99", "微信支付 *")).isEqualTo("19.99")
    }

    @Test
    fun when_pattern_is_only_star_then_returns_first_amount_in_text() {
        assertThat(parseAutoRecord("19.99", "*")).isEqualTo("19.99")
        assertThat(parseAutoRecord("你有一笔19.99元的支出", "*")).isEqualTo("19.99")
    }

    @Test
    fun when_pattern_is_blank_then_returns_null() {
        assertThat(parseAutoRecord("微信支付 19.99 元", "")).isNull()
    }

    @Test
    fun when_text_is_blank_then_returns_null() {
        assertThat(parseAutoRecord("", "* 元")).isNull()
    }

    @Test
    fun when_prefix_not_at_text_start_then_still_matches() {
        // 通知正文常带前缀（如"已支付"/"支付成功"），不再要求以配置文本开头
        assertThat(parseAutoRecord("已支付 微信支付 19.99 元", "微信支付 * 元")).isEqualTo("19.99")
    }

    @Test
    fun when_suffix_appears_multiple_times_then_takes_first_after_prefix() {
        // 取前缀之后第一个后缀，避免把后缀之后的内容一起吞进金额段
        assertThat(parseAutoRecord("微信支付 19.99 元，优惠 5.00 元", "微信支付 * 元")).isEqualTo("19.99")
    }

    @Test
    fun when_text_has_newline_or_fullwidth_space_then_whitespace_normalized() {
        // 跨行 / 全角空格 / 多空格等价于单个空格
        assertThat(parseAutoRecord("微信支付\n19.99 元", "微信支付*元")).isEqualTo("19.99")
        assertThat(parseAutoRecord("微信支付\u300019.99 元", "微信支付 * 元")).isEqualTo("19.99")
    }

    @Test
    fun when_pattern_has_multiple_stars_then_extra_stars_are_wildcards() {
        // 第一个 * 是金额位，其余 * 等价于「任意文本」
        assertThat(parseAutoRecord("微信支付 19.99 *", "微信支付 * *")).isEqualTo("19.99")
    }

    @Test
    fun when_capture_has_no_amount_then_returns_capture_text() {
        // 命中但金额段内无数字：返回原文，由上层给出「无金额」提醒
        assertThat(parseAutoRecord("微信支付 已完成 元", "微信支付 * 元")).isEqualTo("已完成")
    }

    // ========== parseAmountCents ==========

    @Test
    fun when_amount_is_decimal_then_returns_cents() {
        assertThat(parseAmountCents("19.99")).isEqualTo(1999L)
    }

    @Test
    fun when_amount_is_integer_then_returns_cents() {
        assertThat(parseAmountCents("20")).isEqualTo(2000L)
    }

    @Test
    fun when_amount_has_cny_symbol_then_strips_and_returns_cents() {
        assertThat(parseAmountCents("¥19.99")).isEqualTo(1999L)
    }

    @Test
    fun when_amount_has_fullwidth_cny_symbol_then_strips_and_returns_cents() {
        assertThat(parseAmountCents("￥19.99")).isEqualTo(1999L)
    }

    @Test
    fun when_amount_has_dollar_symbol_then_strips_and_returns_cents() {
        assertThat(parseAmountCents("$19.99")).isEqualTo(1999L)
    }

    @Test
    fun when_amount_has_thousands_separator_then_strips_and_returns_cents() {
        assertThat(parseAmountCents("¥1,234.56")).isEqualTo(123456L)
    }

    @Test
    fun when_amount_has_fullwidth_comma_thousands_then_strips_and_returns_cents() {
        assertThat(parseAmountCents("1，234.56")).isEqualTo(123456L)
    }

    @Test
    fun when_amount_has_chinese_unit_then_strips_and_returns_cents() {
        assertThat(parseAmountCents("￥20元")).isEqualTo(2000L)
    }

    @Test
    fun when_amount_has_surrounding_spaces_then_trims_and_returns_cents() {
        assertThat(parseAmountCents(" 19.99 ")).isEqualTo(1999L)
    }

    @Test
    fun when_amount_is_empty_then_returns_null() {
        assertThat(parseAmountCents("")).isNull()
    }

    @Test
    fun when_amount_is_non_numeric_then_returns_null() {
        assertThat(parseAmountCents("abc")).isNull()
    }

    @Test
    fun when_amount_is_zero_then_returns_zero() {
        assertThat(parseAmountCents("0")).isEqualTo(0L)
    }

    @Test
    fun when_amount_has_mixed_symbols_and_units_then_strips_all() {
        assertThat(parseAmountCents("￥1,234.56元")).isEqualTo(123456L)
    }

    @Test
    fun when_amount_followed_by_rmb_word_then_returns_cents() {
        // 真实文案：您有一笔*人民币的消费（中信银行）
        assertThat(parseAmountCents("19.99人民币")).isEqualTo(1999L)
    }

    @Test
    fun when_amount_followed_by_yuan_zheng_then_returns_cents() {
        assertThat(parseAmountCents("19.99元整")).isEqualTo(1999L)
    }

    @Test
    fun when_amount_has_trailing_extra_text_then_returns_cents() {
        assertThat(parseAmountCents("19.99 （原价 29.99）")).isEqualTo(1999L)
        assertThat(parseAmountCents("11.67元，优惠 3.33 元")).isEqualTo(1167L)
    }

    @Test
    fun when_amount_uses_fullwidth_digits_then_returns_cents() {
        assertThat(parseAmountCents("￥２０元")).isEqualTo(2000L)
    }

    @Test
    fun when_amount_is_negative_then_returns_negative_cents() {
        assertThat(parseAmountCents("-19.99")).isEqualTo(-1999L)
    }

    @Test
    fun when_amount_exceeds_limit_then_returns_null() {
        assertThat(parseAmountCents("99999999999999999999")).isNull()
    }

    // ========== parseAutoRecordFromPatterns ==========

    private val wxPattern = "微信支付 * 元"
    private val alipayPattern = "支付宝 * 元"

    @Test
    fun when_first_pattern_matches_then_returns_its_amount() {
        val match = parseAutoRecordFromPatterns("微信支付 19.99 元", listOf(wxPattern, alipayPattern))
        assertThat(match?.raw).isEqualTo("19.99")
        assertThat(match?.amountCents).isEqualTo(1999L)
    }

    @Test
    fun when_first_pattern_misses_then_falls_back_to_next() {
        val match = parseAutoRecordFromPatterns("支付宝 8.80 元", listOf(wxPattern, alipayPattern))
        assertThat(match?.raw).isEqualTo("8.80")
        assertThat(match?.amountCents).isEqualTo(880L)
    }

    @Test
    fun when_no_pattern_matches_then_returns_null() {
        assertThat(parseAutoRecordFromPatterns("云闪付 5 元", listOf(wxPattern, alipayPattern))).isNull()
    }

    @Test
    fun when_patterns_empty_then_returns_null() {
        assertThat(parseAutoRecordFromPatterns("微信支付 19.99 元", emptyList())).isNull()
    }

    @Test
    fun when_pattern_without_star_comes_first_then_later_pattern_still_matches() {
        val match = parseAutoRecordFromPatterns("微信支付 19.99 元", listOf("微信支付", wxPattern))
        assertThat(match?.amountCents).isEqualTo(1999L)
    }

    @Test
    fun when_blank_pattern_comes_first_then_later_pattern_still_matches() {
        val match = parseAutoRecordFromPatterns("微信支付 19.99 元", listOf("", wxPattern))
        assertThat(match?.amountCents).isEqualTo(1999L)
    }

    @Test
    fun when_first_matched_pattern_has_no_amount_then_next_pattern_amount_used() {
        // 首个命中的规则解析不出金额时，继续用后续规则取金额
        val match = parseAutoRecordFromPatterns(
            "微信支付 已完成收款，19.99 元",
            listOf("微信支付 * 收款", "* 元"),
        )
        assertThat(match?.raw).isEqualTo("19.99")
        assertThat(match?.amountCents).isEqualTo(1999L)
    }

    @Test
    fun when_all_matched_patterns_have_no_amount_then_returns_first_with_null_cents() {
        // 全部命中但都解析不出金额：返回首个命中（amountCents=null），仍提示记账但不预填金额
        val match = parseAutoRecordFromPatterns("云闪付 已完成交易", listOf("云闪付 * 交易"))
        assertThat(match?.raw).isEqualTo("已完成")
        assertThat(match?.amountCents).isNull()
    }

    // ========== 真实通知文案（用户实测，标题 + 正文拼接）==========

    @Test
    fun alipay_expense_notification_then_returns_cents() {
        // 支付宝：「你有一笔*元的支出」
        val match = parseAutoRecordFromPatterns(
            "支付宝 你有一笔19.99元的支出",
            listOf("你有一笔*元的支出"),
        )
        assertThat(match?.raw).isEqualTo("19.99")
        assertThat(match?.amountCents).isEqualTo(1999L)
    }

    @Test
    fun alipay_pattern_without_currency_unit_then_returns_cents() {
        // 用户规则省略"元"字时，靠金额形状提取仍能取到 19.99
        val match = parseAutoRecordFromPatterns(
            "支付宝 你有一笔19.99元的支出",
            listOf("你有一笔*的支出"),
        )
        assertThat(match?.raw).isEqualTo("19.99")
        assertThat(match?.amountCents).isEqualTo(1999L)
    }

    @Test
    fun cib_consumption_notification_then_returns_cents() {
        // 中信银行：「您有一笔*人民币的消费」
        val match = parseAutoRecordFromPatterns(
            "中信银行 您有一笔199.00人民币的消费",
            listOf("您有一笔*人民币的消费"),
        )
        assertThat(match?.raw).isEqualTo("199.00")
        assertThat(match?.amountCents).isEqualTo(19900L)
    }

    @Test
    fun cmb_quick_payment_notification_then_returns_cents() {
        // 招商银行：「发生快捷支付扣款，人民币*」（金额在"人民币"之后，规则以 * 结尾）
        val match = parseAutoRecordFromPatterns(
            "招商银行 发生快捷支付扣款，人民币19.99",
            listOf("发生快捷支付扣款，人民币*"),
        )
        assertThat(match?.raw).isEqualTo("19.99")
        assertThat(match?.amountCents).isEqualTo(1999L)
    }

    @Test
    fun when_pattern_omits_bank_name_then_matches_any_bank_notification() {
        // 规则不含银行名，可覆盖多家银行同类文案
        val patterns = listOf("您有一笔*人民币的消费")
        assertThat(parseAutoRecordFromPatterns("中信银行 您有一笔199.00人民币的消费", patterns)?.amountCents)
            .isEqualTo(19900L)
        assertThat(parseAutoRecordFromPatterns("工商银行 您有一笔88.88人民币的消费", patterns)?.amountCents)
            .isEqualTo(8888L)
    }

    @Test
    fun when_three_real_patterns_configured_then_each_notification_matches() {
        val patterns = listOf(
            "你有一笔*元的支出",
            "您有一笔*人民币的消费",
            "发生快捷支付扣款，人民币*",
        )
        assertThat(parseAutoRecordFromPatterns("支付宝 你有一笔19.99元的支出", patterns)?.amountCents)
            .isEqualTo(1999L)
        assertThat(parseAutoRecordFromPatterns("中信银行 您有一笔199.00人民币的消费", patterns)?.amountCents)
            .isEqualTo(19900L)
        assertThat(parseAutoRecordFromPatterns("招商银行 发生快捷支付扣款，人民币19.99", patterns)?.amountCents)
            .isEqualTo(1999L)
    }

    // ========== 归一化辅助 ==========

    @Test
    fun normalizeWhitespace_then_collapses_and_trims() {
        assertThat(normalizeWhitespace("  微信支付\n\t19.99\u3000元 ")).isEqualTo("微信支付 19.99 元")
    }

    @Test
    fun normalizeWidth_then_converts_fullwidth_ascii() {
        assertThat(normalizeWidth("￥２０．５")).isEqualTo("￥20.5")
    }
}
