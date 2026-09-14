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
import cn.wj.android.cashbook.sync.initializers.AutoRecordNotificationId
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
 * > [王杰](mailto:15555650921@163.com) 创建于 2026/9/14
 */
@AndroidEntryPoint
class BillNotificationListener : NotificationListenerService() {

    @Inject lateinit var settingRepository: SettingRepository

    private val scope = MainScope()

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

        sendAutoRecordNotification(source, match.amountCents)
    }

    private fun getAppName(packageName: String): String {
        val pm = packageManager ?: return packageName
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    private fun sendAutoRecordNotification(source: String, amountCents: Long?) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launchIntent.putExtra(EXTRA_AUTO_RECORD_SOURCE, source)
        launchIntent.putExtra(
            EXTRA_AUTO_RECORD_AMOUNT_CENTS,
            amountCents ?: AUTO_RECORD_AMOUNT_CENTS_NONE,
        )

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
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

        nm.notify(AutoRecordNotificationId, notification)
    }
}
