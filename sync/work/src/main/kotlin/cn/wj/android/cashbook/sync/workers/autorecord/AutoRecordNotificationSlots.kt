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

/** 同时保留的待记提醒条数上限：通知栏最多堆 9 条提醒，超出后回收最早占用的槽位 */
internal const val AUTO_RECORD_MAX_SLOTS = 9

/** 同一次支付重复投递的判定窗口（毫秒）：窗口内「同应用 + 同金额」的不同通知视为同一笔 */
internal const val AUTO_RECORD_DEDUPE_WINDOW_MS = 10_000L

/**
 * 单个通知槽位的占用信息。
 *
 * @param notificationKey 来源通知标识（`StatusBarNotification.key`）；系统未提供时为空串，此时仅按 [contentKey] 判定
 * @param contentKey 内容标识（`来源包名|金额分`），用于识别「同一次支付被重复投递的多条通知」
 * @param useTimeMs 最近一次占用时间（毫秒），去重窗口以它为基准
 */
internal data class AutoRecordSlot(
    val notificationKey: String,
    val contentKey: String,
    val useTimeMs: Long,
)

/**
 * 通知槽位占用表：长度固定为 [AUTO_RECORD_MAX_SLOTS]，`null` 表示空闲槽位。
 *
 * 表本身不可变，[allocateAutoRecordSlot] 返回新表（纯函数、便于单测）。
 */
internal data class AutoRecordSlotTable(
    val slots: List<AutoRecordSlot?>,
)

/** 空槽位表 */
internal fun emptyAutoRecordSlotTable(): AutoRecordSlotTable =
    AutoRecordSlotTable(List(AUTO_RECORD_MAX_SLOTS) { null })

/**
 * 槽位分配结果。
 *
 * @param table 分配后的占用表（调用方须以新表替换旧表）
 * @param slot 本次使用的槽位下标；通知 id = 基址 + [slot]
 */
internal data class AutoRecordSlotAllocation(
    val table: AutoRecordSlotTable,
    val slot: Int,
)

/**
 * 为一次命中分配通知槽位（纯函数）。
 *
 * 分配顺序：
 * 1. **同一条来源通知**（[notificationKey] 相同）：通知被更新（如「处理中 → 支付成功」）只复用原槽位，不会新占一槽；
 * 2. **同一次支付的重复通知**：[AUTO_RECORD_DEDUPE_WINDOW_MS] 内 [contentKey] 相同（同应用 + 同金额）视为重复，复用原槽位并刷新占用时间，故连续更新期间窗口不会中途失效；
 * 3. **空闲槽位**；
 * 4. **回收最早占用**：槽位用满时覆盖 [useTimeMs] 最小者，使通知栏提醒条数始终有上界。
 *
 * 注：槽位只保留最近一次的通知标识；回收槽位会覆盖该槽位里较早的提醒 —— 这是「多笔都保留」与「通知栏不泛滥」的折中。
 *
 * @param table 当前占用表
 * @param notificationKey 来源通知标识，可为 `null`（系统未提供时退化为按内容判定）
 * @param contentKey 内容标识（`来源包名|金额分`）
 * @param nowMs 当前时间（毫秒）
 */
internal fun allocateAutoRecordSlot(
    table: AutoRecordSlotTable,
    notificationKey: String?,
    contentKey: String,
    nowMs: Long,
): AutoRecordSlotAllocation {
    val key = notificationKey?.takeIf { it.isNotBlank() }

    // 1. 同一条来源通知的更新：复用原槽位（与时间无关，避免更新被当成新的一笔）
    if (key != null) {
        val sameNotificationIndex = table.slots.indexOfFirst { it?.notificationKey == key }
        if (sameNotificationIndex >= 0) {
            return table.occupy(sameNotificationIndex, AutoRecordSlot(key, contentKey, nowMs))
        }
    }

    val occupiedSlot = AutoRecordSlot(key.orEmpty(), contentKey, nowMs)

    // 2. 去重窗口内同一次支付的重复通知：复用原槽位并刷新占用时间
    val duplicateIndex = table.slots.indexOfFirst { slot ->
        slot != null && slot.contentKey == contentKey && nowMs - slot.useTimeMs <= AUTO_RECORD_DEDUPE_WINDOW_MS
    }
    if (duplicateIndex >= 0) {
        return table.occupy(duplicateIndex, occupiedSlot)
    }

    // 3. 空闲槽位
    val freeIndex = table.slots.indexOfFirst { it == null }
    if (freeIndex >= 0) {
        return table.occupy(freeIndex, occupiedSlot)
    }

    // 4. 回收最早占用的槽位
    val oldestIndex = table.slots.indices.minByOrNull { table.slots[it]?.useTimeMs ?: Long.MIN_VALUE } ?: 0
    return table.occupy(oldestIndex, occupiedSlot)
}

/** 用 [slot] 占用 [index] 槽位，返回新占用表（不修改原表，保持纯函数语义） */
private fun AutoRecordSlotTable.occupy(index: Int, slot: AutoRecordSlot): AutoRecordSlotAllocation {
    val updatedSlots = slots.toMutableList()
    updatedSlots[index] = slot
    return AutoRecordSlotAllocation(AutoRecordSlotTable(updatedSlots), index)
}
