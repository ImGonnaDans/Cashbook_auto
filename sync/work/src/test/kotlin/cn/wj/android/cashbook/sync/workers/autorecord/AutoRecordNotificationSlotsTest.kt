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
 * [allocateAutoRecordSlot] 纯函数单测（纯 JVM）。
 *
 * 守护四件事：多笔支付各占一槽（互不覆盖）、同一条通知的更新复用槽位、
 * 同应用同金额的重复通知在窗口内合并、槽位用满后回收最早一条。
 */
class AutoRecordNotificationSlotsTest {

    private val emptyTable = emptyAutoRecordSlotTable()

    @Test
    fun when_two_different_payments_then_occupy_distinct_slots() {
        val wechat = allocateAutoRecordSlot(emptyTable, "key-wechat", "com.tencent.mm|1999", 1_000L)
        val alipay = allocateAutoRecordSlot(wechat.table, "key-alipay", "com.eg.android.AlipayGphone|18800", 11_000L)

        assertThat(wechat.slot).isEqualTo(0)
        assertThat(alipay.slot).isEqualTo(1)
        assertThat(alipay.table.slots.count { it != null }).isEqualTo(2)
    }

    @Test
    fun when_same_notification_updated_then_reuses_slot_beyond_window() {
        val posted = allocateAutoRecordSlot(emptyTable, "key-wechat", "com.tencent.mm|1999", 1_000L)
        // 同一条通知的更新即使远晚于去重窗口，也复用原槽位（不新占、不堆叠）
        val updated = allocateAutoRecordSlot(posted.table, "key-wechat", "com.tencent.mm|1999", 600_000L)

        assertThat(updated.slot).isEqualTo(posted.slot)
        assertThat(updated.table.slots.count { it != null }).isEqualTo(1)
    }

    @Test
    fun when_same_app_and_amount_within_window_then_reuses_slot() {
        val first = allocateAutoRecordSlot(emptyTable, "key-a", "com.tencent.mm|1999", 1_000L)
        val duplicate = allocateAutoRecordSlot(first.table, "key-b", "com.tencent.mm|1999", 9_000L)

        assertThat(duplicate.slot).isEqualTo(first.slot)
        assertThat(duplicate.table.slots.count { it != null }).isEqualTo(1)
    }

    @Test
    fun when_same_app_and_amount_beyond_window_then_occupies_new_slot() {
        val first = allocateAutoRecordSlot(emptyTable, "key-a", "com.tencent.mm|1999", 1_000L)
        val laterPayment = allocateAutoRecordSlot(first.table, "key-b", "com.tencent.mm|1999", 11_001L)

        assertThat(laterPayment.slot).isNotEqualTo(first.slot)
    }

    @Test
    fun when_duplicate_refreshes_time_then_window_extends_from_latest() {
        val first = allocateAutoRecordSlot(emptyTable, "key-a", "com.tencent.mm|1999", 1_000L)
        val duplicate = allocateAutoRecordSlot(first.table, "key-b", "com.tencent.mm|1999", 5_000L)
        // 窗口以最近一次占用为基准：距 5_000 仅 9 秒，仍应合并到同一槽位
        val stillDuplicate = allocateAutoRecordSlot(duplicate.table, "key-c", "com.tencent.mm|1999", 14_000L)

        assertThat(duplicate.slot).isEqualTo(first.slot)
        assertThat(stillDuplicate.slot).isEqualTo(first.slot)
    }

    @Test
    fun when_notification_key_missing_then_dedupe_by_content() {
        val first = allocateAutoRecordSlot(emptyTable, null, "com.tencent.mm|1999", 1_000L)
        val blankKey = allocateAutoRecordSlot(first.table, "  ", "com.tencent.mm|1999", 2_000L)
        val otherAmount = allocateAutoRecordSlot(blankKey.table, null, "com.tencent.mm|2500", 3_000L)

        assertThat(blankKey.slot).isEqualTo(first.slot)
        assertThat(otherAmount.slot).isNotEqualTo(first.slot)
    }

    @Test
    fun when_slots_exhausted_then_recycles_oldest_slot() {
        var table = emptyTable
        val occupiedSlots = mutableListOf<Int>()
        (0 until AUTO_RECORD_MAX_SLOTS).forEach { index ->
            val allocation = allocateAutoRecordSlot(
                table = table,
                notificationKey = "key-$index",
                contentKey = "app|${1_000 + index}",
                nowMs = 1_000L + index * 1_000L,
            )
            occupiedSlots += allocation.slot
            table = allocation.table
        }
        val fifth = allocateAutoRecordSlot(table, "key-fourth", "app|9999", 60_000L)

        assertThat(occupiedSlots.toSet()).hasSize(AUTO_RECORD_MAX_SLOTS)
        assertThat(fifth.slot).isEqualTo(occupiedSlots.first())
        assertThat(fifth.table.slots.count { it != null }).isEqualTo(AUTO_RECORD_MAX_SLOTS)
    }

    @Test
    fun when_many_payments_then_slot_stays_within_table_range() {
        var table = emptyTable
        repeat(50) { index ->
            val allocation = allocateAutoRecordSlot(table, "key-$index", "app|$index", index * 100_000L)

            assertThat(allocation.slot).isAtLeast(0)
            assertThat(allocation.slot).isLessThan(AUTO_RECORD_MAX_SLOTS)
            assertThat(allocation.table.slots).hasSize(AUTO_RECORD_MAX_SLOTS)
            table = allocation.table
        }
    }

    @Test
    fun when_allocated_then_original_table_not_mutated() {
        val allocation = allocateAutoRecordSlot(emptyTable, "key-a", "app|1999", 1_000L)

        assertThat(emptyTable.slots.count { it != null }).isEqualTo(0)
        assertThat(allocation.table.slots.count { it != null }).isEqualTo(1)
    }
}
