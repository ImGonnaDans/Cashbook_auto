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

package cn.wj.android.cashbook.ui

import cn.wj.android.cashbook.core.common.AUTO_RECORD_AMOUNT_CENTS_NONE
import cn.wj.android.cashbook.core.common.REMINDER_TARGET_ASSET
import cn.wj.android.cashbook.core.common.REMINDER_TARGET_NONE
import cn.wj.android.cashbook.core.common.REMINDER_TARGET_REIMBURSEMENT
import cn.wj.android.cashbook.core.common.SHORTCUTS_TYPE_ADD
import cn.wj.android.cashbook.core.common.SHORTCUTS_TYPE_ASSET
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [parsePendingDeepLink] 纯函数单测（纯 JVM）。 */
class PendingDeepLinkTest {

    @Test
    fun reminderAsset_parsesToAssetInfo() {
        val result = parsePendingDeepLink(
            shortcutsType = -1,
            reminderTarget = REMINDER_TARGET_ASSET,
            reminderAssetId = 9L,
            autoRecordSource = null,
            autoRecordAmountCents = AUTO_RECORD_AMOUNT_CENTS_NONE,
        )
        assertThat(result).isEqualTo(PendingDeepLink.AssetInfo(9L))
    }

    @Test
    fun reminderReimbursement_parsesToReimbursement() {
        val result = parsePendingDeepLink(
            shortcutsType = -1,
            reminderTarget = REMINDER_TARGET_REIMBURSEMENT,
            reminderAssetId = -1L,
            autoRecordSource = null,
            autoRecordAmountCents = AUTO_RECORD_AMOUNT_CENTS_NONE,
        )
        assertThat(result).isEqualTo(PendingDeepLink.Reimbursement)
    }

    @Test
    fun shortcutAdd_parsesToAddRecord() {
        val result = parsePendingDeepLink(
            shortcutsType = SHORTCUTS_TYPE_ADD,
            reminderTarget = REMINDER_TARGET_NONE,
            reminderAssetId = -1L,
            autoRecordSource = null,
            autoRecordAmountCents = AUTO_RECORD_AMOUNT_CENTS_NONE,
        )
        assertThat(result).isEqualTo(PendingDeepLink.AddRecord)
    }

    @Test
    fun shortcutAsset_parsesToMyAsset() {
        val result = parsePendingDeepLink(
            shortcutsType = SHORTCUTS_TYPE_ASSET,
            reminderTarget = REMINDER_TARGET_NONE,
            reminderAssetId = -1L,
            autoRecordSource = null,
            autoRecordAmountCents = AUTO_RECORD_AMOUNT_CENTS_NONE,
        )
        assertThat(result).isEqualTo(PendingDeepLink.MyAsset)
    }

    @Test
    fun nothing_parsesToNone() {
        val result = parsePendingDeepLink(
            shortcutsType = -1,
            reminderTarget = REMINDER_TARGET_NONE,
            reminderAssetId = -1L,
            autoRecordSource = null,
            autoRecordAmountCents = AUTO_RECORD_AMOUNT_CENTS_NONE,
        )
        assertThat(result).isEqualTo(PendingDeepLink.None)
    }

    @Test
    fun reminderTakesPriorityOverShortcut() {
        // 同时带 reminder 与 shortcut → reminder 优先（构造上互斥，防御性）
        val result = parsePendingDeepLink(
            shortcutsType = SHORTCUTS_TYPE_ADD,
            reminderTarget = REMINDER_TARGET_ASSET,
            reminderAssetId = 3L,
            autoRecordSource = null,
            autoRecordAmountCents = AUTO_RECORD_AMOUNT_CENTS_NONE,
        )
        assertThat(result).isEqualTo(PendingDeepLink.AssetInfo(3L))
    }

    // ========== EditRecordPrefill（半自动记账）==========

    @Test
    fun autoRecordSource_parsesToEditRecordPrefill() {
        val result = parsePendingDeepLink(
            shortcutsType = -1,
            reminderTarget = REMINDER_TARGET_NONE,
            reminderAssetId = -1L,
            autoRecordSource = "微信",
            autoRecordAmountCents = 1999L,
        )
        assertThat(result).isEqualTo(PendingDeepLink.EditRecordPrefill("微信", 1999L))
    }

    @Test
    fun autoRecordSourceWithNoAmount_parsesToEditRecordPrefillWithNullAmount() {
        val result = parsePendingDeepLink(
            shortcutsType = -1,
            reminderTarget = REMINDER_TARGET_NONE,
            reminderAssetId = -1L,
            autoRecordSource = "微信",
            autoRecordAmountCents = AUTO_RECORD_AMOUNT_CENTS_NONE,
        )
        assertThat(result).isEqualTo(PendingDeepLink.EditRecordPrefill("微信", null))
    }

    @Test
    fun autoRecordTakesPriorityOverReminder() {
        // autoRecord 与 reminder 同时出现 → autoRecord 优先（构造上互斥，防御性）
        val result = parsePendingDeepLink(
            shortcutsType = -1,
            reminderTarget = REMINDER_TARGET_ASSET,
            reminderAssetId = 3L,
            autoRecordSource = "微信",
            autoRecordAmountCents = 1999L,
        )
        assertThat(result).isEqualTo(PendingDeepLink.EditRecordPrefill("微信", 1999L))
    }

    @Test
    fun blankAutoRecordSource_fallsThroughToReminderOrShortcut() {
        // 空 source 不触发 autoRecord，继续检查 reminder/shortcut
        val result = parsePendingDeepLink(
            shortcutsType = SHORTCUTS_TYPE_ADD,
            reminderTarget = REMINDER_TARGET_NONE,
            reminderAssetId = -1L,
            autoRecordSource = "",
            autoRecordAmountCents = AUTO_RECORD_AMOUNT_CENTS_NONE,
        )
        assertThat(result).isEqualTo(PendingDeepLink.AddRecord)
    }
}
