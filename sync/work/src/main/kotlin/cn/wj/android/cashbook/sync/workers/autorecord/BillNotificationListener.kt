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

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import cn.wj.android.cashbook.core.common.AUTO_RECORD_AMOUNT_CENTS_NONE
import cn.wj.android.cashbook.core.common.EXTRA_AUTO_RECORD_AMOUNT_CENTS
import cn.wj.android.cashbook.core.common.EXTRA_AUTO_RECORD_SOURCE
import cn.wj.android.cashbook.core.common.ext.logger
import cn.wj.android.cashbook.core.common.ext.toMoneyFormat
import cn.wj.android.cashbook.core.data.repository.SettingRepository
import cn.wj.android.cashbook.sync.R
import cn.wj.android.cashbook.sync.initializers.AutoRecordNotificationBaseId
import cn.wj.android.cashbook.sync.initializers.autoRecordNotificationBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 半自动记账通知监听服务。
 *
 * 监听系统通知 → 匹配用户配置的文本模式（含 `*` 金额通配）→ 解析金额 →
 * 发送记账通知（点击预填编辑记录：金额、备注=来源应用名、分类=上次记账分类）。
 *
 * 需用户在系统设置中授予通知使用权（BIND_NOTIFICATION_LISTENER_SERVICE）。
 *
 * 命中后按「通知槽位」投递提醒：同一条通知的更新、或去重窗口内同应用同金额的重复通知复用同一槽位
 * （覆盖而非堆叠），不同支付各占一个槽位（同时最多 [AUTO_RECORD_MAX_SLOTS] 条，用满后回收最早一条），
 * 因此一段时间内的多笔支付各有自己的提醒，不会被后到的通知覆盖。
 *
 * > [王杰](mailto:15555650921@163.com) 创建于 2026/9/14
 */
@AndroidEntryPoint
class BillNotificationListener : NotificationListenerService() {

    @Inject lateinit var settingRepository: SettingRepository

    private val scope = MainScope()

    /** 通知槽位占用表：多笔支付各占一槽，避免后到的提醒覆盖先到的 */
    private var autoRecordSlots = emptyAutoRecordSlotTable()

    /** 槽位表访问锁：保证并发回调下的分配不互相覆盖 */
    private val slotLock = Any()

    override fun onListenerConnected() {
        super.onListenerConnected()
        logger().i("BillNotificationListener connected")
    }

    override fun onListenerDisconnected() {
        scope.cancel()
        super.onListenerDisconnected()
        logger().i("BillNotificationListener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        scope.launch { handleNotification(sbn) }
    }

    private suspend fun handleNotification(sbn: StatusBarNotification) {
        // 忽略本应用自己的通知：提醒/同步等通知若命中用户配置的匹配文本，会出现自我触发的提醒循环
        if (sbn.packageName == packageName) return

        val settings = settingRepository.appSettingsModel.first()
        if (!settings.autoRecordEnable) return

        val matchTexts = settings.autoRecordMatchTexts
        if (matchTexts.isEmpty()) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return
        // 通知 extra 多为 CharSequence（可能是 Spannable），必须用 getCharSequence 取；
        // 若用 getString，非 String 的 CharSequence 会被 Bundle 判为类型不符并返回 null（功能静默失效）
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?: return
        // 标题与正文拼接后匹配：银行 App 常把 App 名/银行名放标题、「您有一笔…」放正文
        val candidate = if (title.isBlank()) body else "$title $body"
        if (candidate.isBlank()) return

        val source = getAppName(sbn.packageName)
        val match = parseAutoRecordFromPatterns(candidate, matchTexts)
        if (match == null) {
            logger().d("autoRecord 未命中 text=<$candidate> source=<$source>")
            return
        }
        logger().d("autoRecord 命中 raw=<${match.raw}> cents=<${match.amountCents}> source=<$source>")

        sendAutoRecordNotification(sbn = sbn, source = source, amountCents = match.amountCents)
    }

    private fun getAppName(packageName: String): String {
        val pm = packageManager ?: return packageName
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    /**
     * 投递记账提醒。
     *
     * 通知 id 按槽位派生（见 [allocateAutoRecordSlot]）：同一支付事件复用同一槽位，不同支付各占一槽，
     * 故短时间内多笔命中会同时保留多条提醒、互不覆盖。
     */
    private fun sendAutoRecordNotification(
        sbn: StatusBarNotification,
        source: String,
        amountCents: Long?,
    ) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val notificationId = allocateAutoRecordNotificationId(sbn = sbn, amountCents = amountCents)

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launchIntent.putExtra(EXTRA_AUTO_RECORD_SOURCE, source)
        launchIntent.putExtra(
            EXTRA_AUTO_RECORD_AMOUNT_CENTS,
            amountCents ?: AUTO_RECORD_AMOUNT_CENTS_NONE,
        )

        // requestCode 与通知 id 同源：不同槽位对应不同 PendingIntent，避免相互取消后点击取到别笔的 extras
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val contentText = if (amountCents != null) {
            getString(R.string.auto_record_notification_text, amountCents.toMoneyFormat())
        } else {
            getString(R.string.auto_record_notification_text_no_amount)
        }

        val notification = autoRecordNotificationBuilder()
            .setContentTitle(getString(R.string.auto_record_notification_title, source))
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(notificationId, notification)
    }

    /**
     * 分配本次提醒的通知 id（`基址 + 槽位`），复用规则见 [allocateAutoRecordSlot]。
     *
     * 槽位表只存在于内存：进程重启后从 0 号槽位重新分配，只影响「同一支付事件是否复用旧槽位」，
     * 不影响点击通知后的预填内容（金额与来源随 intent 一并更新）。
     */
    private fun allocateAutoRecordNotificationId(
        sbn: StatusBarNotification,
        amountCents: Long?,
    ): Int = synchronized(slotLock) {
        val allocation = allocateAutoRecordSlot(
            table = autoRecordSlots,
            notificationKey = sbn.key,
            contentKey = "${sbn.packageName}|${amountCents ?: AUTO_RECORD_AMOUNT_CENTS_NONE}",
            nowMs = System.currentTimeMillis(),
        )
        autoRecordSlots = allocation.table
        AutoRecordNotificationBaseId + allocation.slot
    }
}
