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

package cn.wj.android.cashbook.feature.records.viewmodel

import cn.wj.android.cashbook.core.common.FIXED_TYPE_ID_REFUND
import cn.wj.android.cashbook.core.data.repository.AssetRepository
import cn.wj.android.cashbook.core.data.repository.RecordRepository
import cn.wj.android.cashbook.core.data.repository.TagRepository
import cn.wj.android.cashbook.core.model.enums.RecordTypeCategoryEnum
import cn.wj.android.cashbook.core.model.model.AssetModel
import cn.wj.android.cashbook.core.model.model.TagModel
import cn.wj.android.cashbook.core.testing.data.createAssetModel
import cn.wj.android.cashbook.core.testing.data.createRecordModel
import cn.wj.android.cashbook.core.testing.data.createRecordTypeModel
import cn.wj.android.cashbook.core.testing.data.createTagModel
import cn.wj.android.cashbook.core.testing.repository.FakeAssetRepository
import cn.wj.android.cashbook.core.testing.repository.FakeRecordRepository
import cn.wj.android.cashbook.core.testing.repository.FakeSettingRepository
import cn.wj.android.cashbook.core.testing.repository.FakeTagRepository
import cn.wj.android.cashbook.core.testing.repository.FakeTypeRepository
import cn.wj.android.cashbook.core.testing.util.TestDispatcherRule
import cn.wj.android.cashbook.core.ui.DialogState
import cn.wj.android.cashbook.core.ui.ProgressDialogController
import cn.wj.android.cashbook.domain.usecase.GetDefaultRecordUseCase
import cn.wj.android.cashbook.domain.usecase.SaveRecordUseCase
import cn.wj.android.cashbook.feature.records.enums.EditRecordBookmarkEnum
import cn.wj.android.cashbook.feature.records.enums.EditRecordBottomSheetEnum
import cn.wj.android.cashbook.feature.records.enums.KeypadTarget
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * EditRecordViewModel 的单元测试
 *
 * 使用 Robolectric 提供 Android 环境（ImageViewModel 依赖 android.graphics.Bitmap）。
 */
@RunWith(RobolectricTestRunner::class)
class EditRecordViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private lateinit var typeRepository: FakeTypeRepository
    private lateinit var assetRepository: FakeAssetRepository
    private lateinit var tagRepository: FakeTagRepository
    private lateinit var recordRepository: FakeRecordRepository
    private lateinit var settingRepository: FakeSettingRepository
    private lateinit var viewModel: EditRecordViewModel

    /** 测试用 ProgressDialogController 空实现 */
    private val fakeProgressDialogController = FakeProgressDialogController()

    @Before
    fun setup() {
        typeRepository = FakeTypeRepository()
        assetRepository = FakeAssetRepository()
        tagRepository = FakeTagRepository()
        recordRepository = FakeRecordRepository()
        settingRepository = FakeSettingRepository()

        // 添加默认支出类型
        typeRepository.addType(
            createRecordTypeModel(
                id = 1L,
                name = "餐饮",
                typeCategory = RecordTypeCategoryEnum.EXPENDITURE,
            ),
        )
        // 添加收入类型
        typeRepository.addType(
            createRecordTypeModel(
                id = 2L,
                name = "工资",
                typeCategory = RecordTypeCategoryEnum.INCOME,
            ),
        )
        // 添加转账类型
        typeRepository.addType(
            createRecordTypeModel(
                id = 3L,
                name = "转账",
                typeCategory = RecordTypeCategoryEnum.TRANSFER,
            ),
        )

        val getDefaultRecordUseCase = GetDefaultRecordUseCase(
            recordRepository = recordRepository,
            typeRepository = typeRepository,
            coroutineContext = dispatcherRule.testDispatcher,
        )
        val saveRecordUseCase = SaveRecordUseCase(
            recordRepository = recordRepository,
            typeRepository = typeRepository,
            coroutineContext = dispatcherRule.testDispatcher,
        )

        viewModel = EditRecordViewModel(
            typeRepository = typeRepository,
            assetRepository = assetRepository,
            tagRepository = tagRepository,
            recordRepository = recordRepository,
            settingRepository = settingRepository,
            getDefaultRecordUseCase = getDefaultRecordUseCase,
            saveRecordUseCase = saveRecordUseCase,
        )
    }

    // region 初始状态

    @Test
    fun when_initialized_then_uiState_is_loading() {
        assertThat(viewModel.uiState.value).isEqualTo(EditRecordUiState.Loading)
    }

    @Test
    fun when_initialized_then_bottomSheetType_is_none() {
        assertThat(viewModel.bottomSheetType).isEqualTo(EditRecordBottomSheetEnum.NONE)
    }

    @Test
    fun when_initialized_then_shouldDisplayBookmark_is_none() {
        assertThat(viewModel.shouldDisplayBookmark).isEqualTo(EditRecordBookmarkEnum.NONE)
    }

    @Test
    fun when_initialized_then_dialogState_is_dismiss() {
        assertThat(viewModel.dialogState).isEqualTo(DialogState.Dismiss)
    }

    @Test
    fun when_initialized_then_selectedTypeCategoryData_is_expenditure() {
        assertThat(viewModel.selectedTypeCategoryData.value)
            .isEqualTo(RecordTypeCategoryEnum.EXPENDITURE)
    }

    @Test
    fun when_initRecordId_with_new_record_then_uiState_becomes_success() = runTest {
        // 收集 uiState 以激活 WhileSubscribed 上游
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state).isInstanceOf(EditRecordUiState.Success::class.java)
        val success = state as EditRecordUiState.Success
        // 新建记录金额为 0
        assertThat(success.amountText).isEqualTo("0")
    }

    // endregion

    // region 底部 Sheet 状态管理

    @Test
    fun when_displayAssetSheet_then_bottomSheetType_is_assets() {
        viewModel.displayAssetSheet()
        assertThat(viewModel.bottomSheetType).isEqualTo(EditRecordBottomSheetEnum.ASSETS)
    }

    @Test
    fun when_displayRelatedAssetSheet_then_bottomSheetType_is_related_assets() {
        viewModel.displayRelatedAssetSheet()
        assertThat(viewModel.bottomSheetType).isEqualTo(EditRecordBottomSheetEnum.RELATED_ASSETS)
    }

    @Test
    fun when_displayTagSheet_then_bottomSheetType_is_tags() {
        viewModel.displayTagSheet()
        assertThat(viewModel.bottomSheetType).isEqualTo(EditRecordBottomSheetEnum.TAGS)
    }

    @Test
    fun when_displayImageSheet_then_bottomSheetType_is_images() {
        viewModel.displayImageSheet()
        assertThat(viewModel.bottomSheetType).isEqualTo(EditRecordBottomSheetEnum.IMAGES)
    }

    @Test
    fun when_dismissBottomSheet_then_bottomSheetType_is_none() {
        viewModel.displayAssetSheet()
        assertThat(viewModel.bottomSheetType).isEqualTo(EditRecordBottomSheetEnum.ASSETS)

        viewModel.dismissBottomSheet()
        assertThat(viewModel.bottomSheetType).isEqualTo(EditRecordBottomSheetEnum.NONE)
    }

    // endregion

    // region 金额修改

    @Test
    fun when_updateAmount_then_uiState_reflects_new_amount() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        viewModel.updateAmount("19.99")
        advanceUntilIdle()

        val success = viewModel.uiState.value as EditRecordUiState.Success
        assertThat(success.amountText).isEqualTo("19.99")
    }

    @Test
    fun when_updateCharge_then_uiState_reflects_charges() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        viewModel.updateCharge("5.50")
        advanceUntilIdle()

        val success = viewModel.uiState.value as EditRecordUiState.Success
        assertThat(success.chargesText).isEqualTo("5.5")
    }

    @Test
    fun when_updateConcessions_then_uiState_reflects_concessions() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        viewModel.updateConcessions("3")
        advanceUntilIdle()

        val success = viewModel.uiState.value as EditRecordUiState.Success
        assertThat(success.concessionsText).isEqualTo("3")
    }

    // endregion

    // region 类型切换

    @Test
    fun when_updateTypeCategory_then_selectedTypeCategoryData_updated() = runTest {
        // 收集 selectedTypeCategoryData 以激活 WhileSubscribed 上游
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.selectedTypeCategoryData.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        viewModel.updateTypeCategory(RecordTypeCategoryEnum.INCOME)
        advanceUntilIdle()

        assertThat(viewModel.selectedTypeCategoryData.value)
            .isEqualTo(RecordTypeCategoryEnum.INCOME)
    }

    @Test
    fun when_updateType_with_valid_id_then_uiState_reflects_type() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        viewModel.updateType(2L)
        advanceUntilIdle()

        val success = viewModel.uiState.value as EditRecordUiState.Success
        assertThat(success.selectedTypeId).isEqualTo(2L)
    }

    @Test
    fun when_updateType_with_invalid_id_then_type_not_changed() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        val typeIdBefore = (viewModel.uiState.value as EditRecordUiState.Success).selectedTypeId

        viewModel.updateType(-1L)
        advanceUntilIdle()

        val typeIdAfter = (viewModel.uiState.value as EditRecordUiState.Success).selectedTypeId
        assertThat(typeIdAfter).isEqualTo(typeIdBefore)
    }

    // endregion

    // region 资产切换

    @Test
    fun when_updateAsset_then_uiState_reflects_asset() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        val asset = createAssetModel(id = 10L, name = "现金", balance = 100000L)
        assetRepository.addAsset(asset)

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        viewModel.updateAsset(10L)
        advanceUntilIdle()

        val success = viewModel.uiState.value as EditRecordUiState.Success
        assertThat(success.selectedAssetId).isEqualTo(10L)
        assertThat(success.assetText).contains("现金")
    }

    @Test
    fun when_updateAsset_then_bottomSheet_dismissed() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        viewModel.displayAssetSheet()
        viewModel.updateAsset(10L)
        advanceUntilIdle()

        assertThat(viewModel.bottomSheetType).isEqualTo(EditRecordBottomSheetEnum.NONE)
    }

    @Test
    fun when_updateRelatedAsset_then_bottomSheet_dismissed() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        viewModel.displayRelatedAssetSheet()
        viewModel.updateRelatedAsset(10L)
        advanceUntilIdle()

        assertThat(viewModel.bottomSheetType).isEqualTo(EditRecordBottomSheetEnum.NONE)
    }

    @Test
    fun when_initAssetId_with_valid_id_then_asset_set() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        val asset = createAssetModel(id = 20L, name = "银行卡", balance = 500000L)
        assetRepository.addAsset(asset)

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        viewModel.initAssetId(20L)
        advanceUntilIdle()

        val success = viewModel.uiState.value as EditRecordUiState.Success
        assertThat(success.selectedAssetId).isEqualTo(20L)
    }

    @Test
    fun when_initAssetId_with_negative_one_then_asset_not_changed() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        val assetIdBefore =
            (viewModel.uiState.value as EditRecordUiState.Success).selectedAssetId

        viewModel.initAssetId(-1L)
        advanceUntilIdle()

        val assetIdAfter =
            (viewModel.uiState.value as EditRecordUiState.Success).selectedAssetId
        assertThat(assetIdAfter).isEqualTo(assetIdBefore)
    }

    // endregion

    // region 备注修改

    @Test
    fun when_updateRemark_then_uiState_reflects_remark() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        viewModel.updateRemark("午餐费用")
        advanceUntilIdle()

        val success = viewModel.uiState.value as EditRecordUiState.Success
        assertThat(success.remarkText).isEqualTo("午餐费用")
    }

    // endregion

    // region 可报销状态

    @Test
    fun when_switchReimbursable_then_reimbursable_toggled() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        // 默认不可报销
        assertThat((viewModel.uiState.value as EditRecordUiState.Success).reimbursable)
            .isFalse()

        // 切换为可报销
        viewModel.switchReimbursable()
        advanceUntilIdle()

        assertThat((viewModel.uiState.value as EditRecordUiState.Success).reimbursable)
            .isTrue()

        // 再次切换回不可报销
        viewModel.switchReimbursable()
        advanceUntilIdle()

        assertThat((viewModel.uiState.value as EditRecordUiState.Success).reimbursable)
            .isFalse()
    }

    // endregion

    // region 标签关联

    @Test
    fun when_updateTag_then_displayTagIdListData_updated() = runTest {
        // 收集标签相关流以激活上游
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.displayTagIdListData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        val tag1 = createTagModel(id = 1L, name = "旅行")
        val tag2 = createTagModel(id = 2L, name = "聚餐")
        tagRepository.addTag(tag1)
        tagRepository.addTag(tag2)

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        viewModel.updateTag(listOf(1L, 2L))
        advanceUntilIdle()

        assertThat(viewModel.displayTagIdListData.value).containsExactly(1L, 2L)
    }

    @Test
    fun when_updateTag_then_tagTextData_updated() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.tagTextData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        val tag1 = createTagModel(id = 1L, name = "旅行")
        val tag2 = createTagModel(id = 2L, name = "聚餐")
        tagRepository.addTag(tag1)
        tagRepository.addTag(tag2)

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        viewModel.updateTag(listOf(1L, 2L))
        advanceUntilIdle()

        assertThat(viewModel.tagTextData.value).isEqualTo("旅行,聚餐")
    }

    @Test
    fun when_initialized_then_tagTextData_is_empty() {
        assertThat(viewModel.tagTextData.value).isEmpty()
    }

    // endregion

    // region 关联记录

    @Test
    fun when_updateRelatedRecord_then_currentRelatedRecord_updated() = runTest {
        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        viewModel.updateRelatedRecord(listOf(100L, 200L))
        advanceUntilIdle()

        val relatedIds = viewModel.currentRelatedRecord().first()
        assertThat(relatedIds).containsExactly(100L, 200L)
    }

    // endregion

    // region 弹窗状态

    @Test
    fun when_dismissDialog_then_dialogState_is_dismiss() {
        viewModel.dismissDialog()
        assertThat(viewModel.dialogState).isEqualTo(DialogState.Dismiss)
    }

    @Test
    fun when_dismissBookmark_then_bookmark_is_none() {
        viewModel.dismissBookmark()
        assertThat(viewModel.shouldDisplayBookmark).isEqualTo(EditRecordBookmarkEnum.NONE)
    }

    // endregion

    // region 保存记录

    @Test
    fun when_trySave_with_zero_amount_then_bookmark_amount_must_not_be_zero() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.selectedTypeCategoryData.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        // 金额为 0 不修改
        var successCalled = false
        viewModel.trySave(fakeProgressDialogController, "保存中") { successCalled = true }
        advanceUntilIdle()

        assertThat(viewModel.shouldDisplayBookmark)
            .isEqualTo(EditRecordBookmarkEnum.AMOUNT_MUST_NOT_BE_ZERO)
        assertThat(successCalled).isFalse()
    }

    @Test
    fun when_trySave_with_type_category_mismatch_then_bookmark_type_not_match() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.selectedTypeCategoryData.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        // 设置金额
        viewModel.updateAmount("10")
        advanceUntilIdle()

        // 类型是支出（typeId=1），但切换分类为收入
        viewModel.updateTypeCategory(RecordTypeCategoryEnum.INCOME)
        advanceUntilIdle()

        var successCalled = false
        viewModel.trySave(fakeProgressDialogController, "保存中") { successCalled = true }
        advanceUntilIdle()

        assertThat(viewModel.shouldDisplayBookmark)
            .isEqualTo(EditRecordBookmarkEnum.TYPE_NOT_MATCH_CATEGORY)
        assertThat(successCalled).isFalse()
    }

    @Test
    fun when_trySave_with_valid_data_then_onSuccess_called() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.selectedTypeCategoryData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.displayTagIdListData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.displayImageData.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        // 设置金额
        viewModel.updateAmount("50")
        advanceUntilIdle()

        var successCalled = false
        viewModel.trySave(fakeProgressDialogController, "保存中") { successCalled = true }
        advanceUntilIdle()

        assertThat(successCalled).isTrue()
        // 验证记录已保存到 repository
        assertThat(recordRepository.lastUpdatedRecord).isNotNull()
        assertThat(recordRepository.lastUpdatedRecord!!.amount).isEqualTo(5000L)
    }

    @Test
    fun when_trySave_with_keypad_amount_target_then_saved_with_keypad_value() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.selectedTypeCategoryData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.displayTagIdListData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.displayImageData.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        var successCalled = false
        viewModel.trySave(
            controller = fakeProgressDialogController,
            hintText = "保存中",
            keypadTarget = KeypadTarget.AMOUNT,
            keypadValue = "19.99",
        ) { successCalled = true }
        advanceUntilIdle()

        // 键盘值先写回再保存，不会保存旧金额
        assertThat(successCalled).isTrue()
        assertThat(recordRepository.lastUpdatedRecord!!.amount).isEqualTo(1999L)
    }

    @Test
    fun when_trySave_with_keypad_charges_target_then_charges_applied() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.selectedTypeCategoryData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.displayTagIdListData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.displayImageData.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()
        // 金额必须有值，否则保存校验不通过
        viewModel.updateAmount("50")
        advanceUntilIdle()

        viewModel.trySave(
            controller = fakeProgressDialogController,
            hintText = "保存中",
            keypadTarget = KeypadTarget.CHARGES,
            keypadValue = "3",
        ) {}
        advanceUntilIdle()

        assertThat(recordRepository.lastUpdatedRecord!!.amount).isEqualTo(5000L)
        assertThat(recordRepository.lastUpdatedRecord!!.charges).isEqualTo(300L)
    }

    @Test
    fun when_trySave_with_keypad_zero_value_then_bookmark_amount_must_not_be_zero() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.selectedTypeCategoryData.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        var successCalled = false
        viewModel.trySave(
            controller = fakeProgressDialogController,
            hintText = "保存中",
            keypadTarget = KeypadTarget.AMOUNT,
            keypadValue = "0",
        ) { successCalled = true }
        advanceUntilIdle()

        assertThat(successCalled).isFalse()
        assertThat(viewModel.shouldDisplayBookmark)
            .isEqualTo(EditRecordBookmarkEnum.AMOUNT_MUST_NOT_BE_ZERO)
    }

    @Test
    fun when_prepareNextRecord_then_amount_zero_and_other_fields_kept() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.selectedTypeCategoryData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.displayTagIdListData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.displayImageData.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()
        viewModel.updateRemark("午餐")
        advanceUntilIdle()

        // 保存成功后触发「再记」
        viewModel.trySave(
            controller = fakeProgressDialogController,
            hintText = "保存中",
            keypadTarget = KeypadTarget.AMOUNT,
            keypadValue = "19.99",
        ) {
            viewModel.prepareNextRecord()
        }
        advanceUntilIdle()

        val next = viewModel.uiState.value as EditRecordUiState.Success
        // 金额清零，其余表单字段保留
        assertThat(next.amountText).isEqualTo("0")
        assertThat(next.remarkText).isEqualTo("午餐")
        assertThat(next.selectedTypeId).isEqualTo(1L)

        // 再记后保存的是新的一笔（id 仍为 -1，即新建）
        viewModel.trySave(
            controller = fakeProgressDialogController,
            hintText = "保存中",
            keypadTarget = KeypadTarget.AMOUNT,
            keypadValue = "8.88",
        ) {}
        advanceUntilIdle()

        assertThat(recordRepository.lastUpdatedRecord!!.id).isEqualTo(-1L)
        assertThat(recordRepository.lastUpdatedRecord!!.amount).isEqualTo(888L)
    }

    @Test
    fun when_trySave_success_then_lock_until_prepareNextRecord() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.selectedTypeCategoryData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.displayTagIdListData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.displayImageData.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        var successCount = 0
        // 第一笔保存成功
        viewModel.trySave(
            controller = fakeProgressDialogController,
            hintText = "保存中",
            keypadTarget = KeypadTarget.AMOUNT,
            keypadValue = "19.99",
        ) { successCount++ }
        advanceUntilIdle()
        assertThat(successCount).isEqualTo(1)

        // 退出页面的保存路径成功后保持加锁：重复调用被忽略，避免退出过程中重复入库
        viewModel.trySave(
            controller = fakeProgressDialogController,
            hintText = "保存中",
            keypadTarget = KeypadTarget.AMOUNT,
            keypadValue = "19.99",
        ) { successCount++ }
        advanceUntilIdle()
        assertThat(successCount).isEqualTo(1)

        // 「再记」释放保存锁，可以继续记录下一笔
        viewModel.prepareNextRecord()
        advanceUntilIdle()
        viewModel.trySave(
            controller = fakeProgressDialogController,
            hintText = "保存中",
            keypadTarget = KeypadTarget.AMOUNT,
            keypadValue = "8.88",
        ) { successCount++ }
        advanceUntilIdle()

        assertThat(successCount).isEqualTo(2)
        assertThat(recordRepository.lastUpdatedRecord!!.amount).isEqualTo(888L)
    }

    @Test
    fun when_trySave_expenditure_then_reimbursable_preserved() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.selectedTypeCategoryData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.displayTagIdListData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.displayImageData.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        viewModel.updateAmount("100")
        viewModel.switchReimbursable()
        advanceUntilIdle()

        viewModel.trySave(fakeProgressDialogController, "保存中") {}
        advanceUntilIdle()

        // 支出类型应保留 reimbursable
        assertThat(recordRepository.lastUpdatedRecord).isNotNull()
        assertThat(recordRepository.lastUpdatedRecord!!.reimbursable).isTrue()
    }

    @Test
    fun when_trySave_income_then_reimbursable_forced_false() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.selectedTypeCategoryData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.displayTagIdListData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.displayImageData.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        // 先设置为收入类型
        viewModel.updateType(2L)
        viewModel.updateTypeCategory(RecordTypeCategoryEnum.INCOME)
        viewModel.updateAmount("100")
        viewModel.switchReimbursable()
        advanceUntilIdle()

        viewModel.trySave(fakeProgressDialogController, "保存中") {}
        advanceUntilIdle()

        // 收入类型 reimbursable 强制为 false
        assertThat(recordRepository.lastUpdatedRecord).isNotNull()
        assertThat(recordRepository.lastUpdatedRecord!!.reimbursable).isFalse()
    }

    @Test
    fun when_trySave_expenditure_then_reimbursed_preserved() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.selectedTypeCategoryData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.displayTagIdListData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.displayImageData.collect {}
        }

        // 加载已有「已手动标记已报销」的支出记录（typeId=1L 为支出）
        recordRepository.addRecord(
            createRecordModel(id = 1L, typeId = 1L, amount = 10000L, reimbursable = true, reimbursed = true),
        )
        viewModel.initRecordId(1L)
        advanceUntilIdle()

        viewModel.trySave(fakeProgressDialogController, "保存中") {}
        advanceUntilIdle()

        // 支出类型应保留 reimbursed
        assertThat(recordRepository.lastUpdatedRecord).isNotNull()
        assertThat(recordRepository.lastUpdatedRecord!!.reimbursed).isTrue()
    }

    @Test
    fun when_trySave_income_then_reimbursed_forced_false() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.selectedTypeCategoryData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.displayTagIdListData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.displayImageData.collect {}
        }

        // 加载已有「已手动标记已报销」的支出记录，后改为收入类型 → reimbursed 应清零
        recordRepository.addRecord(
            createRecordModel(id = 1L, typeId = 1L, amount = 10000L, reimbursable = true, reimbursed = true),
        )
        viewModel.initRecordId(1L)
        advanceUntilIdle()

        viewModel.updateType(2L)
        viewModel.updateTypeCategory(RecordTypeCategoryEnum.INCOME)
        advanceUntilIdle()

        viewModel.trySave(fakeProgressDialogController, "保存中") {}
        advanceUntilIdle()

        // 改为收入类型 reimbursed 强制为 false
        assertThat(recordRepository.lastUpdatedRecord).isNotNull()
        assertThat(recordRepository.lastUpdatedRecord!!.reimbursed).isFalse()
    }

    @Test
    fun when_trySave_income_then_concessions_forced_zero() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.selectedTypeCategoryData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.displayTagIdListData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.displayImageData.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        viewModel.updateType(2L)
        viewModel.updateTypeCategory(RecordTypeCategoryEnum.INCOME)
        viewModel.updateAmount("100")
        viewModel.updateConcessions("5")
        advanceUntilIdle()

        viewModel.trySave(fakeProgressDialogController, "保存中") {}
        advanceUntilIdle()

        // 收入类型优惠强制为 0
        assertThat(recordRepository.lastUpdatedRecord).isNotNull()
        assertThat(recordRepository.lastUpdatedRecord!!.concessions).isEqualTo(0L)
    }

    @Test
    fun when_trySave_non_transfer_then_relatedAssetId_forced_negative_one() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.selectedTypeCategoryData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.displayTagIdListData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.displayImageData.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        viewModel.updateAmount("100")
        viewModel.updateRelatedAsset(10L)
        advanceUntilIdle()

        viewModel.trySave(fakeProgressDialogController, "保存中") {}
        advanceUntilIdle()

        // 非转账类型关联资产强制为 -1
        assertThat(recordRepository.lastUpdatedRecord).isNotNull()
        assertThat(recordRepository.lastUpdatedRecord!!.relatedAssetId).isEqualTo(-1L)
    }

    // endregion

    // region 编辑已有记录

    @Test
    fun given_existing_record_when_initRecordId_then_uiState_reflects_record() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        val existingRecord = createRecordModel(
            id = 100L,
            typeId = 1L,
            amount = 9999L,
            remark = "已有记录",
        )
        recordRepository.addRecord(existingRecord)

        viewModel.initRecordId(100L)
        advanceUntilIdle()

        val success = viewModel.uiState.value as EditRecordUiState.Success
        assertThat(success.amountText).isEqualTo("99.99")
        assertThat(success.remarkText).isEqualTo("已有记录")
    }

    @Test
    fun given_existing_record_with_tags_when_init_then_tags_displayed() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.tagTextData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.displayTagIdListData.collect {}
        }

        val existingRecord = createRecordModel(id = 100L, typeId = 1L, amount = 1000L)
        recordRepository.addRecord(existingRecord)

        val tag = createTagModel(id = 5L, name = "出差")
        tagRepository.addTag(tag)
        tagRepository.setRelatedTags(100L, listOf(tag))

        viewModel.initRecordId(100L)
        advanceUntilIdle()

        assertThat(viewModel.tagTextData.value).isEqualTo("出差")
        assertThat(viewModel.displayTagIdListData.value).containsExactly(5L)
    }

    // endregion

    // region initRecordId 幂等性

    @Test
    fun when_initRecordId_called_twice_then_second_call_ignored() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        val existingRecord = createRecordModel(id = 200L, typeId = 1L, amount = 5000L)
        recordRepository.addRecord(existingRecord)

        // 第二次调用应被忽略
        viewModel.initRecordId(200L)
        advanceUntilIdle()

        val success = viewModel.uiState.value as EditRecordUiState.Success
        // 仍然是新建记录的金额 0，而非 200L 记录的 5000
        assertThat(success.amountText).isEqualTo("0")
    }

    @Test
    fun when_initAssetId_called_twice_then_second_call_ignored() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        val asset1 = createAssetModel(id = 10L, name = "现金", balance = 100000L)
        val asset2 = createAssetModel(id = 20L, name = "银行卡", balance = 500000L)
        assetRepository.addAsset(asset1)
        assetRepository.addAsset(asset2)

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        viewModel.initAssetId(10L)
        advanceUntilIdle()

        // 第二次调用应被忽略
        viewModel.initAssetId(20L)
        advanceUntilIdle()

        val success = viewModel.uiState.value as EditRecordUiState.Success
        assertThat(success.selectedAssetId).isEqualTo(10L)
    }

    // endregion

    // region initPrefill（半自动记账预填）

    @Test
    fun when_initPrefill_with_amount_then_amount_prefilled() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        viewModel.initPrefill(source = "", amountCents = 1999L)
        advanceUntilIdle()

        val success = viewModel.uiState.value as EditRecordUiState.Success
        assertThat(success.amountText).isEqualTo("19.99")
    }

    @Test
    fun when_initPrefill_with_source_then_remark_prefilled() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        viewModel.initPrefill(source = "微信", amountCents = null)
        advanceUntilIdle()

        val success = viewModel.uiState.value as EditRecordUiState.Success
        assertThat(success.remarkText).isEqualTo("微信")
    }

    @Test
    fun when_initPrefill_with_latest_typeId_then_type_prefilled() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        // 添加一条 booksId=1, typeId=2（工资）的记录作为"上次记账分类"
        recordRepository.addRecord(createRecordModel(id = 100L, booksId = 1L, typeId = 2L))

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        viewModel.initPrefill(source = "微信", amountCents = 1999L)
        advanceUntilIdle()

        val success = viewModel.uiState.value as EditRecordUiState.Success
        assertThat(success.selectedTypeId).isEqualTo(2L)
    }

    @Test
    fun when_initPrefill_called_twice_then_second_ignored() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        viewModel.initPrefill(source = "微信", amountCents = 1999L)
        advanceUntilIdle()

        // 第二次调用应被忽略
        viewModel.initPrefill(source = "支付宝", amountCents = 5000L)
        advanceUntilIdle()

        val success = viewModel.uiState.value as EditRecordUiState.Success
        assertThat(success.amountText).isEqualTo("19.99")
        assertThat(success.remarkText).isEqualTo("微信")
    }

    @Test
    fun when_initPrefill_with_null_source_and_null_amount_then_noop() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        val beforeAmount = (viewModel.uiState.value as EditRecordUiState.Success).amountText

        viewModel.initPrefill(source = "", amountCents = null)
        advanceUntilIdle()

        val success = viewModel.uiState.value as EditRecordUiState.Success
        assertThat(success.amountText).isEqualTo(beforeAmount)
    }

    // endregion

    // region needRelated

    @Test
    fun given_type_needs_related_when_init_then_needRelated_is_true() = runTest {
        // 添加固定退款类型，自动判断为 needRelated
        typeRepository.addType(
            createRecordTypeModel(
                id = FIXED_TYPE_ID_REFUND,
                name = "退款",
                typeCategory = RecordTypeCategoryEnum.INCOME,
            ),
        )

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        // 切换到退款类型
        viewModel.updateType(FIXED_TYPE_ID_REFUND)
        advanceUntilIdle()

        val success = viewModel.uiState.value as EditRecordUiState.Success
        assertThat(success.needRelated).isTrue()
    }

    @Test
    fun given_type_not_needs_related_when_init_then_needRelated_is_false() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        val success = viewModel.uiState.value as EditRecordUiState.Success
        assertThat(success.needRelated).isFalse()
    }

    // endregion

    // region defaultTypeIdData

    @Test
    fun when_initRecordId_for_new_record_then_defaultTypeIdData_is_default_type() = runTest {
        // 收集 defaultTypeIdData 以激活 WhileSubscribed 上游
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.defaultTypeIdData.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        // 默认类型是 typeRepository 中第一个支出类型的 id
        assertThat(viewModel.defaultTypeIdData.value).isEqualTo(1L)
    }

    // endregion

    // region currentRecord

    @Test
    fun when_updateAmount_then_currentRecord_reflects_change() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.initRecordId(-1L)
        advanceUntilIdle()

        viewModel.updateAmount("25.5")
        advanceUntilIdle()

        val record = viewModel.currentRecord().first()
        assertThat(record.amount).isEqualTo(2550L)
    }

    // region 性能：避免重复查询

    @Test
    fun when_collect_uiState_then_related_and_tag_queried_once_and_asset_not_requeried() = runTest {
        // 用接口委托做查询计数：验证共享流去重（标签 / 关联记录各只查一次）
        // 与「资产 id 未变化时不重复查询资产」
        val relatedIdCalls = mutableListOf<Long>()
        val relatedTagCalls = mutableListOf<Long>()
        val assetCalls = mutableListOf<Long>()
        val countingRecordRepository = object : RecordRepository by recordRepository {
            override suspend fun getRelatedIdListById(id: Long): List<Long> {
                relatedIdCalls += id
                return recordRepository.getRelatedIdListById(id)
            }
        }
        val countingTagRepository = object : TagRepository by tagRepository {
            override suspend fun getRelatedTag(recordId: Long): List<TagModel> {
                relatedTagCalls += recordId
                return tagRepository.getRelatedTag(recordId)
            }
        }
        val countingAssetRepository = object : AssetRepository by assetRepository {
            override suspend fun getAssetById(assetId: Long): AssetModel? {
                assetCalls += assetId
                return assetRepository.getAssetById(assetId)
            }
        }
        val countingViewModel = EditRecordViewModel(
            typeRepository = typeRepository,
            assetRepository = countingAssetRepository,
            tagRepository = countingTagRepository,
            recordRepository = countingRecordRepository,
            settingRepository = settingRepository,
            getDefaultRecordUseCase = GetDefaultRecordUseCase(
                recordRepository = countingRecordRepository,
                typeRepository = typeRepository,
                coroutineContext = dispatcherRule.testDispatcher,
            ),
            saveRecordUseCase = SaveRecordUseCase(
                recordRepository = countingRecordRepository,
                typeRepository = typeRepository,
                coroutineContext = dispatcherRule.testDispatcher,
            ),
        )

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            countingViewModel.uiState.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            countingViewModel.displayTagIdListData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            countingViewModel.tagTextData.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            countingViewModel.selectedTypeCategoryData.collect {}
        }

        countingViewModel.initRecordId(-1L)
        advanceUntilIdle()

        // 标签共享流：displayTagIdListData 与 tagTextData 两个下游只触发一次查询
        assertThat(relatedTagCalls.size).isEqualTo(1)
        // 关联记录 id 共享流：列表派生与保存逻辑复用同一次查询
        assertThat(relatedIdCalls.size).isEqualTo(1)
        // 资产 + 关联资产最多各一次
        val assetCallsAfterOpen = assetCalls.size
        assertThat(assetCallsAfterOpen <= 2).isTrue()

        // 未修改资产 id 的字段编辑不应再次查询资产
        countingViewModel.updateRemark("晚餐")
        advanceUntilIdle()
        assertThat(assetCalls.size).isEqualTo(assetCallsAfterOpen)
    }

    // endregion
}

/** ProgressDialogController 测试用空实现 */
private class FakeProgressDialogController : ProgressDialogController {
    override val dialogState: DialogState = DialogState.Dismiss
    override fun dismiss() = Unit
    override fun show(hint: String?, cancelable: Boolean, onDismiss: () -> Unit) = Unit
}
