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

package cn.wj.android.cashbook.feature.settings.screen

import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import cn.wj.android.cashbook.core.design.theme.CashbookTheme
import cn.wj.android.cashbook.core.model.enums.AutoBackupModeEnum
import cn.wj.android.cashbook.core.ui.DialogState
import cn.wj.android.cashbook.feature.settings.viewmodel.BackupAndRecoveryUiState
import cn.wj.android.cashbook.feature.settings.viewmodel.ExportState
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * 设置页「导入账单」入口 UI 测试
 *
 * 校验「从支付宝导入」与「从微信导入」并列存在且可见（截图基线可视区域不包含该区域，
 * 故用滚动断言守护新入口的渲染）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, qualifiers = "480dpi")
@LooperMode(LooperMode.Mode.PAUSED)
class BackupAndRecoveryImportEntryTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun importEntries_hasWechatAndAlipay() {
        composeTestRule.setContent {
            CashbookTheme {
                BackupAndRecoveryScreen(
                    uiState = BackupAndRecoveryUiState.Success(
                        webDAVDomain = "https://dav.jianguoyun.com/dav/",
                        webDAVAccount = "",
                        webDAVPassword = "",
                        backupPath = "",
                        lastBackupTime = "",
                        autoBackup = AutoBackupModeEnum.CLOSE,
                        keepLatestBackup = false,
                        mobileNetworkBackupEnable = false,
                    ),
                    shouldDisplayBookmark = 0,
                    onRequestDismissBookmark = {},
                    dialogState = DialogState.Dismiss,
                    onBackupListItemClick = {},
                    onRequestDismissDialog = {},
                    isConnected = false,
                    onConnectStateClick = { _, _, _ -> },
                    onBackupPathSelected = {},
                    onBackupClick = {},
                    onRecoveryClick = { _, _ -> },
                    onAutoBackupClick = {},
                    onKeepLatestBackupChanged = {},
                    onMobileNetworkBackupEnableChanged = {},
                    onNoWifiConfirmBackupClick = {},
                    onAutoBackupModeSelected = {},
                    onDbMigrateClick = {},
                    onRequestNaviToRecordImport = { _, _ -> },
                    booksList = emptyList(),
                    currentBook = null,
                    exportState = ExportState.Idle,
                    onGetEarliestRecordTime = { null },
                    onCountExportRecords = { _, _, _ -> 0 },
                    onExportRecords = { _, _, _, _, _, _, _ -> },
                    onResetExportState = {},
                    onBackClick = {},
                    onShowSnackbar = { _, _ -> SnackbarResult.Dismissed },
                )
            }
        }

        val scrollable = composeTestRule.onAllNodes(hasScrollAction())[0]

        // 微信入口
        scrollable.performScrollToNode(hasText("从微信导入"))
        composeTestRule.onNodeWithText("从微信导入").assertIsDisplayed()
        composeTestRule.onNodeWithText("导入微信支付账单(.xlsx)").assertIsDisplayed()

        // 支付宝入口（与微信入口并列）
        scrollable.performScrollToNode(hasText("从支付宝导入"))
        composeTestRule.onNodeWithText("从支付宝导入").assertIsDisplayed()
        composeTestRule.onNodeWithText("导入支付宝账单(.xlsx)").assertIsDisplayed()
    }
}
