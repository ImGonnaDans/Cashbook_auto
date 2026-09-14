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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.wj.android.cashbook.core.common.ext.logger
import cn.wj.android.cashbook.core.common.ext.toAmountCent
import cn.wj.android.cashbook.core.common.ext.toMoneyCNY
import cn.wj.android.cashbook.core.common.ext.toMoneyFormat
import cn.wj.android.cashbook.core.common.ext.toMoneyString
import cn.wj.android.cashbook.core.common.tools.DATE_FORMAT_DATE
import cn.wj.android.cashbook.core.common.tools.DATE_FORMAT_NO_SECONDS
import cn.wj.android.cashbook.core.common.tools.dateFormat
import cn.wj.android.cashbook.core.common.tools.parseDateLong
import cn.wj.android.cashbook.core.common.tools.toDateTimeString
import cn.wj.android.cashbook.core.data.repository.AssetRepository
import cn.wj.android.cashbook.core.data.repository.RecordRepository
import cn.wj.android.cashbook.core.data.repository.SettingRepository
import cn.wj.android.cashbook.core.data.repository.TagRepository
import cn.wj.android.cashbook.core.data.repository.TypeRepository
import cn.wj.android.cashbook.core.model.enums.ImageQualityEnum
import cn.wj.android.cashbook.core.model.enums.RecordTypeCategoryEnum
import cn.wj.android.cashbook.core.model.model.RecordModel
import cn.wj.android.cashbook.core.model.model.TagModel
import cn.wj.android.cashbook.core.ui.DialogState
import cn.wj.android.cashbook.core.ui.ProgressDialogController
import cn.wj.android.cashbook.core.ui.runCatchWithProgress
import cn.wj.android.cashbook.domain.usecase.GetDefaultRecordUseCase
import cn.wj.android.cashbook.domain.usecase.SaveRecordUseCase
import cn.wj.android.cashbook.feature.records.enums.EditRecordBookmarkEnum
import cn.wj.android.cashbook.feature.records.enums.EditRecordBottomSheetEnum
import cn.wj.android.cashbook.feature.records.enums.KeypadTarget
import cn.wj.android.cashbook.feature.records.model.DateTimePickerModel
import cn.wj.android.cashbook.feature.records.model.ImageViewModel
import cn.wj.android.cashbook.feature.records.model.asModel
import cn.wj.android.cashbook.feature.records.model.asViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 编辑记录 ViewModel
 *
 * @param typeRepository 类型数据仓库
 * @param assetRepository 资产数据仓库
 * @param tagRepository 标签数据仓库
 * @param getDefaultRecordUseCase 获取新建默认记录数据用例
 * @param saveRecordUseCase 保存记录数据用例
 *
 * > [王杰](mailto:15555650921@163.com) 创建于 2023/7/5
 */
@HiltViewModel
class EditRecordViewModel @Inject constructor(
    private val typeRepository: TypeRepository,
    assetRepository: AssetRepository,
    tagRepository: TagRepository,
    private val recordRepository: RecordRepository,
    private val settingRepository: SettingRepository,
    getDefaultRecordUseCase: GetDefaultRecordUseCase,
    private val saveRecordUseCase: SaveRecordUseCase,
) : ViewModel() {

    /** 显示提示类型 */
    var shouldDisplayBookmark by mutableStateOf(EditRecordBookmarkEnum.NONE)
        private set

    /** 底部 sheet 类型 */
    var bottomSheetType by mutableStateOf(EditRecordBottomSheetEnum.NONE)
        private set

    /** 弹窗状态 */
    var dialogState by mutableStateOf<DialogState>(DialogState.Dismiss)
        private set

    /** 记录 id */
    private val _recordIdData = MutableStateFlow(-1L)

    /** 记录数据 */
    private val _mutableRecordData = MutableStateFlow<RecordModel?>(null)
    private val _defaultRecordData = _recordIdData.mapLatest {
        getDefaultRecordUseCase(it)
    }
        .shareIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            replay = 1,
        )
    private val _displayRecordData =
        combine(_mutableRecordData, _defaultRecordData) { mutable, default ->
            mutable ?: default
        }

    /** 关联图片 */
    private val _mutableImageData = MutableStateFlow<List<ImageViewModel>?>(null)
    private val _defaultImageData = _recordIdData.mapLatest { id ->
        recordRepository.queryImagesByRecordId(id)
            .map { it.asViewModel() }
    }
    val displayImageData =
        combine(_mutableImageData, _defaultImageData) { mutable, default ->
            mutable ?: default
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    /** 关联记录 */
    private val _mutableRelatedRecordIdData = MutableStateFlow<List<Long>?>(null)
    private val _defaultRelatedRecordIdData = _recordIdData.mapLatest {
        recordRepository.getRelatedIdListById(it)
    }
    private val _relatedRecordIdData =
        combine(_mutableRelatedRecordIdData, _defaultRelatedRecordIdData) { mutable, default ->
            mutable ?: default
        }
    private val _relatedRecordListData = _relatedRecordIdData.mapLatest { ids ->
        ids.mapNotNull {
            recordRepository.queryById(it)
        }
    }
    private val _relatedRecordTotalAmountData = _relatedRecordListData.mapLatest { list ->
        var total = 0L
        list.forEach {
            total += (it.amount + it.charges - it.concessions)
        }
        total.toMoneyString()
    }

    /** 界面 UI 状态 */
    val uiState =
        combine(
            _displayRecordData,
            _relatedRecordTotalAmountData,
            settingRepository.appSettingsModel,
        ) { record, relatedAmount, model ->
            val assetText = assetRepository.getAssetById(record.assetId)?.let { asset ->
                val displayBalance = if (asset.type.isCreditCard) {
                    (asset.totalAmount - asset.balance).toMoneyCNY()
                } else {
                    asset.balance.toMoneyCNY()
                }
                "${asset.name}($displayBalance)"
            }.orEmpty()
            val relatedAssetText =
                assetRepository.getAssetById(record.relatedAssetId)?.let { asset ->
                    val displayBalance = if (asset.type.isCreditCard) {
                        (asset.totalAmount - asset.balance).toMoneyCNY()
                    } else {
                        asset.balance.toMoneyCNY()
                    }
                    "${asset.name}($displayBalance)"
                }.orEmpty()
            val needRelated = typeRepository.needRelated(record.typeId)
            EditRecordUiState.Success(
                amountText = record.amount.toMoneyFormat().ifBlank { "0" },
                chargesText = record.charges.clearZero(),
                concessionsText = record.concessions.clearZero(),
                remarkText = record.remark,
                selectedAssetId = record.assetId,
                assetText = assetText,
                relatedAssetText = relatedAssetText,
                dateTimeText = record.recordTime.toDateTimeString(),
                reimbursable = record.reimbursable,
                selectedTypeId = record.typeId,
                needRelated = needRelated,
                relatedCount = _relatedRecordListData.first().size,
                relatedAmount = relatedAmount,
                imageQuality = model.imageQuality,
            )
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = EditRecordUiState.Loading,
            )

    /** 类型数据 */
    val defaultTypeIdData = _defaultRecordData.mapLatest { it.typeId }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = -1L,
        )
    private val _mutableTypeCategoryData = MutableStateFlow<RecordTypeCategoryEnum?>(null)
    val selectedTypeCategoryData =
        combine(_mutableTypeCategoryData, _defaultRecordData) { mutable, defaultRecord ->
            mutable ?: typeRepository.getRecordTypeById(defaultRecord.typeId)?.typeCategory
                ?: RecordTypeCategoryEnum.EXPENDITURE
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = RecordTypeCategoryEnum.EXPENDITURE,
            )

    /** 可变标签id列表数据 - 用户手动设置 */
    private val _mutableTagIdListData = MutableStateFlow<List<Long>?>(null)

    /** 可变标签信息列表数据 - 根据可变标签id列表数据获取对于数据 */
    private val _mutableTagListData = _mutableTagIdListData.mapLatest { list ->
        if (null == list) {
            null
        } else {
            mutableListOf<TagModel>().apply {
                list.map { tagId ->
                    tagRepository.getTagById(tagId)?.let { tagModel -> add(tagModel) }
                }
            }
        }
    }

    /** 默认标签列表数据 - 已保存的数据，新建记录为空 */
    private val _defaultTagListData = _recordIdData
        .mapLatest {
            tagRepository.getRelatedTag(it)
        }

    /** 最终用于显示的标签数据 */
    private val _displayTagListData =
        combine(_mutableTagListData, _defaultTagListData) { mutable, default ->
            mutable ?: default
        }

    /** 实际显示的标签id列表数据，用于控制选择标签Sheet中标签的选中状态 */
    val displayTagIdListData = _displayTagListData
        .mapLatest { list ->
            list.map {
                it.id
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /** 标签显示文本 */
    val tagTextData = _displayTagListData
        .mapLatest { list ->
            StringBuilder().run {
                list.forEach { tag ->
                    if (isNotBlank()) {
                        append(",")
                    }
                    append(tag.name)
                }
                toString()
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = "",
        )

    private var recordIdInit = false

    /** 更新记录 [id]，刷新界面数据 */
    fun initRecordId(id: Long) {
        if (recordIdInit) {
            return
        }
        recordIdInit = true
        _recordIdData.tryEmit(id)
    }

    private var assetIdInit = false

    /** 更新类型 */
    fun initAssetId(assetId: Long) {
        if (assetIdInit) {
            return
        }
        assetIdInit = true
        if (assetId == -1L) {
            return
        }
        viewModelScope.launch {
            _mutableRecordData.tryEmit(_displayRecordData.first().copy(assetId = assetId))
        }
    }

    private var prefillInit = false

    /**
     * 半自动记账预填：金额（来自通知匹配）+ 备注（来源应用名）+ 默认分类（上次记账分类）。
     *
     * 幂等：仅首次调用生效，防止 uiState 重发时重复预填覆盖用户修改。
     */
    fun initPrefill(source: String, amountCents: Long?) {
        if (prefillInit) {
            return
        }
        prefillInit = true
        if (source.isBlank() && amountCents == null) {
            return
        }
        viewModelScope.launch {
            val current = _displayRecordData.first()
            val bookId = settingRepository.recordSettingsModel.first().currentBookId
            val latestTypeId = recordRepository.queryLatestRecordTypeId(bookId)
            var updated = current
            if (amountCents != null) {
                updated = updated.copy(amount = amountCents)
            }
            if (source.isNotBlank()) {
                updated = updated.copy(remark = source)
            }
            if (latestTypeId != null && latestTypeId > 0L) {
                updated = updated.copy(typeId = latestTypeId)
            }
            _mutableRecordData.tryEmit(updated)
        }
    }

    /** 更新记录大类为 [typeCategory] */
    fun updateTypeCategory(typeCategory: RecordTypeCategoryEnum) {
        _mutableTypeCategoryData.tryEmit(typeCategory)
    }

    /** 更新金额（切换键盘编辑目标 / 提交键盘输入时触发） */
    fun updateAmount(amount: String) {
        viewModelScope.launch {
            _mutableRecordData.tryEmit(_displayRecordData.first().copy(amount = amount.toAmountCent()))
        }
    }

    /** 更新手续费（切换键盘编辑目标 / 提交键盘输入时触发） */
    fun updateCharge(charges: String) {
        viewModelScope.launch {
            _mutableRecordData.tryEmit(_displayRecordData.first().copy(charges = charges.toAmountCent()))
        }
    }

    /** 更新优惠（切换键盘编辑目标 / 提交键盘输入时触发） */
    fun updateConcessions(concessions: String) {
        viewModelScope.launch {
            _mutableRecordData.tryEmit(_displayRecordData.first().copy(concessions = concessions.toAmountCent()))
        }
    }

    /** 更新类型 */
    fun updateType(typeId: Long) {
        if (typeId == -1L) {
            return
        }
        viewModelScope.launch {
            _mutableRecordData.tryEmit(_displayRecordData.first().copy(typeId = typeId))
        }
    }

    /** 更新备注 */
    fun updateRemark(remark: String) {
        viewModelScope.launch {
            _mutableRecordData.tryEmit(_displayRecordData.first().copy(remark = remark))
        }
    }

    /** 显示资产抽屉 */
    fun displayAssetSheet() {
        bottomSheetType = EditRecordBottomSheetEnum.ASSETS
    }

    /** 更新资产 */
    fun updateAsset(assetId: Long) {
        viewModelScope.launch {
            _mutableRecordData.tryEmit(_displayRecordData.first().copy(assetId = assetId))
            dismissBottomSheet()
        }
    }

    /** 显示关联资产抽屉 */
    fun displayRelatedAssetSheet() {
        bottomSheetType = EditRecordBottomSheetEnum.RELATED_ASSETS
    }

    /** 更新关联资产 */
    fun updateRelatedAsset(assetId: Long) {
        viewModelScope.launch {
            _mutableRecordData.tryEmit(_displayRecordData.first().copy(relatedAssetId = assetId))
            dismissBottomSheet()
        }
    }

    /** 显示标签抽屉 */
    fun displayTagSheet() {
        bottomSheetType = EditRecordBottomSheetEnum.TAGS
    }

    /** 更新标签 */
    fun updateTag(tags: List<Long>) {
        _mutableTagIdListData.tryEmit(tags)
    }

    /** 显示选择照片抽屉 */
    fun displayImageSheet() {
        bottomSheetType = EditRecordBottomSheetEnum.IMAGES
    }

    fun updateImageData(list: List<ImageViewModel>) {
        dismissBottomSheet()
        _mutableImageData.tryEmit(list)
    }

    fun showImagePreviewDialog(list: List<ImageViewModel>, index: Int) {
        dialogState = DialogState.Shown(ImagePreviewData(list, index))
    }

    /** 切换可报销状态 */
    fun switchReimbursable() {
        viewModelScope.launch {
            val old = _displayRecordData.first()
            _mutableRecordData.tryEmit(old.copy(reimbursable = !old.reimbursable))
        }
    }

    /** 隐藏提示 */
    fun dismissBookmark() {
        shouldDisplayBookmark = EditRecordBookmarkEnum.NONE
    }

    /** 隐藏底部抽屉 */
    fun dismissBottomSheet() {
        bottomSheetType = EditRecordBottomSheetEnum.NONE
    }

    private var inSave = false

    /**
     * 保存记录
     *
     * [keypadTarget] 与 [keypadValue] 均非空时，先按键盘当前编辑目标把「键盘在编辑中的值」
     * 写回对应字段再保存——常驻键盘的输入是页面本地状态（不进 ViewModel）；
     * 写回与保存放在同一协程内串行，避免保存读到写回前的旧值。
     *
     * 保存成功的后续行为由 [onSuccess] 决定：保存 = 退出页面；再记 = 留在页面继续记账。
     *
     * @param controller 进度弹窗控制器
     * @param hintText 进度提示文案
     * @param keypadTarget 键盘当前编辑目标，为 null 表示无需写回（直接保存当前表单）
     * @param keypadValue 键盘当前表达式 / 金额文本
     * @param onSuccess 保存成功回调
     */
    fun trySave(
        controller: ProgressDialogController,
        hintText: String,
        keypadTarget: KeypadTarget? = null,
        keypadValue: String? = null,
        onSuccess: () -> Unit,
    ) {
        if (inSave) {
            return
        }
        inSave = true
        viewModelScope.launch {
            val target = keypadTarget
            if (target != null) {
                val cents = keypadValue.orEmpty().toAmountCent()
                val current = _displayRecordData.first()
                _mutableRecordData.tryEmit(
                    when (target) {
                        KeypadTarget.AMOUNT -> current.copy(amount = cents)
                        KeypadTarget.CHARGES -> current.copy(charges = cents)
                        KeypadTarget.CONCESSIONS -> current.copy(concessions = cents)
                    },
                )
            }
            // 保存失败复位锁允许重试；成功时锁保持，由「留在页面」的动作（prepareNextRecord）主动释放，
            // 避免退出动画期间连点导致重复入库
            if (doSave(controller, hintText)) {
                onSuccess.invoke()
            } else {
                inSave = false
            }
        }
    }

    /**
     * 连续记账（再记）：保存成功后不退出页面，除金额清零外其余表单数据保持不变，
     * 并切回「新建下一笔」——记录 id 归 -1、清空随上一笔已入库的关联图片与关联记录
     * （它们属于上一笔，保留会重复关联）；类型 / 资产 / 标签 / 日期 / 备注 / 手续费 / 优惠保留。
     *
     * 同时释放 [inSave] 保存锁，允许继续记录下一笔；退出页面的保存路径保持加锁语义。
     */
    fun prepareNextRecord() {
        viewModelScope.launch {
            _recordIdData.tryEmit(-1L)
            val current = _displayRecordData.first()
            _mutableImageData.tryEmit(null)
            _mutableRelatedRecordIdData.tryEmit(null)
            _mutableRecordData.tryEmit(current.copy(id = -1L, amount = 0L))
            inSave = false
        }
    }

    /**
     * 校验并保存当前记录数据（不含字段写回），返回是否保存成功
     */
    private suspend fun doSave(controller: ProgressDialogController, hintText: String): Boolean {
        val recordEntity = _displayRecordData.first()
        if (recordEntity.amount == 0L) {
            // 记录金额不能为 0
            shouldDisplayBookmark = EditRecordBookmarkEnum.AMOUNT_MUST_NOT_BE_ZERO
            return false
        }
        // 支出分类
        val typeCategory = selectedTypeCategoryData.first()
        if (typeRepository.getNoNullRecordTypeById(recordEntity.typeId).typeCategory != typeCategory) {
            // 类型与支出类型不匹配
            shouldDisplayBookmark = EditRecordBookmarkEnum.TYPE_NOT_MATCH_CATEGORY
            return false
        }
        return runCatchWithProgress(controller, hint = hintText, cancelable = false) {
            saveRecordUseCase(
                recordModel = recordEntity.copy(
                    relatedAssetId = if (typeCategory != RecordTypeCategoryEnum.TRANSFER) -1L else recordEntity.relatedAssetId,
                    concessions = if (typeCategory == RecordTypeCategoryEnum.INCOME) 0L else recordEntity.concessions,
                    reimbursable = if (typeCategory != RecordTypeCategoryEnum.EXPENDITURE) false else recordEntity.reimbursable,
                    reimbursed = if (typeCategory != RecordTypeCategoryEnum.EXPENDITURE) false else recordEntity.reimbursed,
                ),
                tagIdList = displayTagIdListData.first(),
                relatedRecordIdList = _relatedRecordIdData.first(),
                relatedImageList = displayImageData.first().map { it.asModel() },
            )
            Result.success(null)
        }.fold(
            onSuccess = { true },
            onFailure = { throwable ->
                // 保存失败
                this@EditRecordViewModel.logger().e(throwable, "onSaveClick()")
                shouldDisplayBookmark = EditRecordBookmarkEnum.SAVE_FAILED
                false
            },
        )
    }

    /** 显示选择日期弹窗 */
    fun displayDatePickerDialog() {
        viewModelScope.launch {
            val currentState = uiState.first()
            if (currentState is EditRecordUiState.Success) {
                dialogState =
                    DialogState.Shown(
                        DateTimePickerModel.DatePicker(
                            currentState.dateTimeText.parseDateLong(
                                format = DATE_FORMAT_NO_SECONDS,
                            ),
                        ),
                    )
            }
        }
    }

    /** 日期临时保存，选择时间后才会真正保存 */
    private var dateTemp = ""

    /** 选择日期 */
    fun onDateSelected(dateMs: Long) {
        dateTemp = dateMs.dateFormat(DATE_FORMAT_DATE)
        displayTimePickerDialog()
    }

    /** 显示选择时间弹窗 */
    private fun displayTimePickerDialog() {
        viewModelScope.launch {
            val currentState = uiState.first()
            if (currentState is EditRecordUiState.Success) {
                dialogState =
                    DialogState.Shown(
                        DateTimePickerModel.TimePicker(
                            currentState.dateTimeText.parseDateLong(
                                format = DATE_FORMAT_NO_SECONDS,
                            ),
                        ),
                    )
            }
        }
    }

    /** 选择时间 */
    fun onTimeSelected(time: String) {
        dismissDialog()
        viewModelScope.launch {
            val recordTimeMs = "$dateTemp $time".parseDateLong(format = DATE_FORMAT_NO_SECONDS)
            _mutableRecordData.tryEmit(
                _displayRecordData.first().copy(recordTime = recordTimeMs),
            )
        }
    }

    /** 隐藏弹窗 */
    fun dismissDialog() {
        dialogState = DialogState.Dismiss
    }

    fun currentRecord(): Flow<RecordModel> {
        return _displayRecordData
    }

    fun currentRelatedRecord(): Flow<List<Long>> {
        return _relatedRecordIdData
    }

    fun updateRelatedRecord(ids: List<Long>) {
        _mutableRelatedRecordIdData.tryEmit(ids)
    }
}

/** 界面 UI 状态 */
sealed interface EditRecordUiState {
    /** 加载中 */
    data object Loading : EditRecordUiState

    /**
     * 加载完成
     *
     * @param amountText 金额
     * @param chargesText 手续费
     * @param concessionsText 优惠
     * @param remarkText 备注
     * @param selectedAssetId 已选择资产 id
     * @param assetText 资产
     * @param relatedAssetText 关联资产
     * @param dateTimeText 日期时间
     * @param reimbursable 是否可报销
     * @param selectedTypeId 当前选择类型 id
     * @param needRelated 是否需要关联记录
     * @param imageQuality 图片质量
     */
    data class Success(
        val amountText: String,
        val chargesText: String,
        val concessionsText: String,
        val remarkText: String,
        val selectedAssetId: Long,
        val assetText: String,
        val relatedAssetText: String,
        val dateTimeText: String,
        val reimbursable: Boolean,
        val selectedTypeId: Long,
        val needRelated: Boolean,
        val relatedCount: Int,
        val relatedAmount: String,
        val imageQuality: ImageQualityEnum,
    ) : EditRecordUiState
}

data class ImagePreviewData(
    val list: List<ImageViewModel>,
    val index: Int,
)

private fun Long.clearZero(): String {
    return if (this == 0L) {
        ""
    } else {
        this.toMoneyFormat()
    }
}
