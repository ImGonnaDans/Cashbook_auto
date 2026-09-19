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

package cn.wj.android.cashbook.feature.record.imports.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.wj.android.cashbook.core.common.FIXED_TYPE_ID_REFUND
import cn.wj.android.cashbook.core.common.annotation.CashbookDispatchers
import cn.wj.android.cashbook.core.common.annotation.Dispatcher
import cn.wj.android.cashbook.core.common.ext.logger
import cn.wj.android.cashbook.core.common.ext.toCent
import cn.wj.android.cashbook.core.data.helper.AlipayBillParser
import cn.wj.android.cashbook.core.data.helper.AlipayCategoryMapper
import cn.wj.android.cashbook.core.data.helper.BillCategoryMatcher
import cn.wj.android.cashbook.core.data.helper.BillPaymentMatcher
import cn.wj.android.cashbook.core.data.helper.WechatBillParser
import cn.wj.android.cashbook.core.data.repository.AssetRepository
import cn.wj.android.cashbook.core.data.repository.BooksRepository
import cn.wj.android.cashbook.core.data.repository.RecordRepository
import cn.wj.android.cashbook.core.data.repository.TypeRepository
import cn.wj.android.cashbook.core.database.table.RecordTable
import cn.wj.android.cashbook.core.model.enums.RecordTypeCategoryEnum
import cn.wj.android.cashbook.core.model.model.BillDirection
import cn.wj.android.cashbook.core.model.model.BillParseResult
import cn.wj.android.cashbook.core.model.model.BillSource
import cn.wj.android.cashbook.core.model.model.BillSummary
import cn.wj.android.cashbook.core.model.model.BooksModel
import cn.wj.android.cashbook.core.model.model.DuplicateStatus
import cn.wj.android.cashbook.core.model.model.ImportPreviewItem
import cn.wj.android.cashbook.core.model.model.ImportedBillItem
import cn.wj.android.cashbook.core.model.model.PaymentMethodMapping
import cn.wj.android.cashbook.core.model.model.RecordTypeModel
import cn.wj.android.cashbook.domain.usecase.CreateImportTypeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

/** 路由参数 - 账单来源 */
private const val KEY_SOURCE = "source"

/** 内置「退款」分类默认名称（库中读取失败时兜底） */
private const val DEFAULT_REFUND_TYPE_NAME = "退款"

/**
 * 账单导入 ViewModel
 */
@HiltViewModel
class RecordImportViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val recordRepository: RecordRepository,
    private val typeRepository: TypeRepository,
    private val assetRepository: AssetRepository,
    private val booksRepository: BooksRepository,
    private val createImportTypeUseCase: CreateImportTypeUseCase,
    @Dispatcher(CashbookDispatchers.IO) private val coroutineContext: CoroutineContext,
) : ViewModel() {

    /** 缓存文件路径（由文件选择回调复制到缓存目录） */
    private val filePath: String = savedStateHandle.get<String>("fileUri") ?: ""

    /** 账单来源（微信 / 支付宝），由路由参数传入，默认微信 */
    val source: BillSource = savedStateHandle.get<String>(KEY_SOURCE)
        ?.let { name -> runCatching { BillSource.valueOf(name) }.getOrNull() }
        ?: BillSource.WECHAT

    private val _uiState = MutableStateFlow<RecordImportUiState>(RecordImportUiState.Parsing)
    val uiState: StateFlow<RecordImportUiState> = _uiState

    private var parsedItems: List<ImportedBillItem> = emptyList()
    private var parsedSummary: BillSummary? = null
    private var expenditureTypes: List<RecordTypeModel> = emptyList()
    private var incomeTypes: List<RecordTypeModel> = emptyList()
    private var defaultExpenditureType: RecordTypeModel? = null
    private var defaultIncomeType: RecordTypeModel? = null

    /** 支付宝账单分类映射使用的二级分类列表（按名称精确匹配） */
    private var alipaySecondLevelTypes: List<RecordTypeModel> = emptyList()

    /** 内置「退款」分类名称（退款交易固定使用该分类） */
    private var refundTypeName: String = DEFAULT_REFUND_TYPE_NAME

    init {
        parseFile()
    }

    private fun parseFile() {
        viewModelScope.launch {
            _uiState.value = RecordImportUiState.Parsing
            try {
                val cacheFile = File(filePath)
                if (!cacheFile.exists()) {
                    _uiState.value = RecordImportUiState.Error("")
                    return@launch
                }

                val result = cacheFile.inputStream().use { parseBill(it) }
                if (result == null) {
                    _uiState.value = RecordImportUiState.Error("")
                    return@launch
                }

                parsedItems = result.items
                parsedSummary = result.summary

                // 加载依赖数据
                val booksList = booksRepository.booksListData.first()
                val currentBook = booksRepository.currentBook.first()
                expenditureTypes = typeRepository.firstExpenditureTypeListData.first()
                incomeTypes = typeRepository.firstIncomeTypeListData.first()
                defaultExpenditureType = expenditureTypes.lastOrNull()
                defaultIncomeType = incomeTypes.lastOrNull()
                // 支付宝账单按「交易分类」映射，需要用户已有的二级分类（按名称精确匹配）
                alipaySecondLevelTypes = if (source == BillSource.ALIPAY) {
                    typeRepository.getSecondRecordTypeMapByParentIds(
                        (expenditureTypes + incomeTypes).map { it.id },
                    ).values.flatten()
                } else {
                    emptyList()
                }
                // 内置「退款」分类名称（退款交易固定挂到该分类，名称以库中数据为准）
                refundTypeName = typeRepository.getRecordTypeById(FIXED_TYPE_ID_REFUND)?.name
                    ?: DEFAULT_REFUND_TYPE_NAME

                // 匹配支付方式
                val assets = assetRepository.getVisibleAssetsByBookId(currentBook.id)
                val paymentMethods = parsedItems.map { it.paymentMethod }.filter { it.isNotBlank() }.distinct()
                val mappings = BillPaymentMatcher.matchAll(paymentMethods, assets)

                // 构建映射表：支付方式原始文本 → assetId
                val mappingMap = mappings.associate { it.originalName to it.matchedAssetId }

                // 检测重复 + 匹配分类
                val previewItems = buildPreviewItems(
                    items = parsedItems,
                    booksId = currentBook.id,
                    mappingMap = mappingMap,
                )

                _uiState.value = RecordImportUiState.Ready(
                    fileName = cacheFile.name,
                    summary = result.summary,
                    selectedBooksId = currentBook.id,
                    booksList = booksList,
                    paymentMappings = mappings,
                    previewItems = previewItems,
                    hasUnmappedPayments = mappings.any { it.matchedAssetId == -1L },
                    visibleAssets = assets,
                    expenditureTypes = expenditureTypes,
                    incomeTypes = incomeTypes,
                )
            } catch (e: Exception) {
                logger().e(e, "parseFile failed")
                _uiState.value = RecordImportUiState.Error("")
            }
        }
    }

    /** 按账单来源选择解析器 */
    private fun parseBill(input: InputStream): BillParseResult? = when (source) {
        BillSource.WECHAT -> WechatBillParser.parse(input)
        BillSource.ALIPAY -> AlipayBillParser.parse(input)
    }

    private suspend fun buildPreviewItems(
        items: List<ImportedBillItem>,
        booksId: Long,
        mappingMap: Map<String, Long>,
    ): List<ImportPreviewItem> {
        return items.map { item ->
            val types = if (item.direction == BillDirection.EXPENDITURE) expenditureTypes else incomeTypes
            val defaultType = if (item.direction == BillDirection.EXPENDITURE) defaultExpenditureType else defaultIncomeType

            val mappedTypeId: Long
            val mappedTypeName: String
            var pendingCreateTypeName: String? = null
            if (item.isRefund) {
                // 退款交易（支付宝「收/支=退款」）：按收入方向导入，固定使用内置「退款」分类，
                // 不参与交易分类映射，也不触发分类创建
                mappedTypeId = FIXED_TYPE_ID_REFUND
                mappedTypeName = refundTypeName
            } else {
                // 分类映射：支付宝按账单「交易分类」列映射（二级分类同名优先 → 一级分类同名复用 →
                // 两级都没有才标记为待创建一级分类，确认导入时创建）；
                // 微信账单无分类列，沿用关键词匹配 + 默认分类兜底
                val alipayMatch = if (source == BillSource.ALIPAY) {
                    AlipayCategoryMapper.match(
                        categoryName = item.transactionType,
                        direction = item.direction,
                        secondLevelTypes = alipaySecondLevelTypes,
                        firstLevelTypes = types,
                    )
                } else {
                    AlipayCategoryMapper.Result.Absent
                }
                when (alipayMatch) {
                    is AlipayCategoryMapper.Result.Existing -> {
                        mappedTypeId = alipayMatch.typeId
                        mappedTypeName = alipayMatch.typeName
                    }

                    is AlipayCategoryMapper.Result.NeedCreateFirstLevel -> {
                        mappedTypeId = -1L
                        mappedTypeName = alipayMatch.typeName
                        pendingCreateTypeName = alipayMatch.typeName
                    }

                    AlipayCategoryMapper.Result.Absent -> {
                        val matchedType =
                            BillCategoryMatcher.match(item.counterparty, item.description, types) ?: defaultType
                        mappedTypeId = matchedType?.id ?: -1L
                        mappedTypeName = matchedType?.name ?: ""
                    }
                }
            }

            // 检测重复
            val duplicateStatus = checkDuplicate(item, booksId)

            // 映射资产
            val assetId = if (item.paymentMethod.isBlank()) -1L else (mappingMap[item.paymentMethod] ?: -1L)

            ImportPreviewItem(
                billItem = item,
                mappedTypeId = mappedTypeId,
                mappedTypeName = mappedTypeName,
                mappedAssetId = assetId,
                duplicateStatus = duplicateStatus,
                selected = duplicateStatus != DuplicateStatus.EXACT,
                pendingCreateTypeName = pendingCreateTypeName,
            )
        }
    }

    /** 重复检测（暴露为 internal 以便同模块单元测试直接驱动去重逻辑） */
    internal suspend fun checkDuplicate(
        item: ImportedBillItem,
        booksId: Long,
    ): DuplicateStatus {
        // 精确匹配：交易单号（备注中的来源标记，微信 / 支付宝各自独立）
        if (item.transactionId.isNotBlank()) {
            val existing = when (source) {
                BillSource.WECHAT -> recordRepository.queryByWechatTransactionId(booksId, item.transactionId)
                BillSource.ALIPAY -> recordRepository.queryByAlipayTransactionId(booksId, item.transactionId)
            }
            if (existing.isNotEmpty()) return DuplicateStatus.EXACT
        }

        // 模糊匹配：同天 + 同金额
        val dayStart = item.transactionTime / 86400000 * 86400000 // 当天0点
        val dayEnd = dayStart + 86400000 - 1 // 当天23:59:59
        // item.amount 为元（Double），DB amount 列为分（Long），需转分后再比对，否则口径不一致永不命中
        val amountCent = item.amount.toCent()
        val similar = recordRepository.queryByTimeAndAmount(booksId, dayStart, dayEnd, amountCent)
        if (similar.isNotEmpty()) return DuplicateStatus.POSSIBLE

        return DuplicateStatus.NONE
    }

    /** 切换目标账本 */
    fun selectBook(booksId: Long) {
        val state = _uiState.value as? RecordImportUiState.Ready ?: return
        viewModelScope.launch {
            val assets = assetRepository.getVisibleAssetsByBookId(booksId)
            val paymentMethods = parsedItems.map { it.paymentMethod }.filter { it.isNotBlank() }.distinct()
            val mappings = BillPaymentMatcher.matchAll(paymentMethods, assets)
            val mappingMap = mappings.associate { it.originalName to it.matchedAssetId }

            val previewItems = buildPreviewItems(parsedItems, booksId, mappingMap)

            _uiState.value = state.copy(
                selectedBooksId = booksId,
                paymentMappings = mappings,
                previewItems = previewItems,
                hasUnmappedPayments = mappings.any { it.matchedAssetId == -1L },
                visibleAssets = assets,
            )
        }
    }

    /** 更新支付方式映射 */
    fun updatePaymentMapping(originalName: String, assetId: Long, assetName: String) {
        val state = _uiState.value as? RecordImportUiState.Ready ?: return
        val updatedMappings = state.paymentMappings.map { mapping ->
            if (mapping.originalName == originalName) {
                mapping.copy(matchedAssetId = assetId, matchedAssetName = assetName)
            } else {
                mapping
            }
        }
        // 同步更新预览条目中的 assetId
        val mappingMap = updatedMappings.associate { it.originalName to it.matchedAssetId }
        val updatedPreviews = state.previewItems.map { preview ->
            val newAssetId = if (preview.billItem.paymentMethod.isBlank()) {
                -1L
            } else {
                mappingMap[preview.billItem.paymentMethod] ?: -1L
            }
            preview.copy(mappedAssetId = newAssetId)
        }
        _uiState.value = state.copy(
            paymentMappings = updatedMappings,
            previewItems = updatedPreviews,
            hasUnmappedPayments = updatedMappings.any { it.matchedAssetId == -1L },
        )
    }

    /** 切换单条记录的选中状态 */
    fun toggleItemSelection(index: Int) {
        val state = _uiState.value as? RecordImportUiState.Ready ?: return
        val updatedItems = state.previewItems.toMutableList()
        val item = updatedItems[index]
        updatedItems[index] = item.copy(selected = !item.selected)
        _uiState.value = state.copy(previewItems = updatedItems)
    }

    /** 全选/取消全选 */
    fun selectAll(selected: Boolean) {
        val state = _uiState.value as? RecordImportUiState.Ready ?: return
        val updatedItems = state.previewItems.map { it.copy(selected = selected) }
        _uiState.value = state.copy(previewItems = updatedItems)
    }

    /** 更新单条记录的分类 */
    fun updateItemType(index: Int, typeId: Long, typeName: String) {
        val state = _uiState.value as? RecordImportUiState.Ready ?: return
        val updatedItems = state.previewItems.toMutableList()
        updatedItems[index] = updatedItems[index].copy(
            mappedTypeId = typeId,
            mappedTypeName = typeName,
            // 用户手动指定分类后不再需要自动创建
            pendingCreateTypeName = null,
        )
        _uiState.value = state.copy(previewItems = updatedItems)
    }

    /** 确认导入 */
    fun confirmImport() {
        val state = _uiState.value as? RecordImportUiState.Ready ?: return
        val selectedItems = state.previewItems.filter { it.selected }
        if (selectedItems.isEmpty()) return

        viewModelScope.launch {
            _uiState.value = RecordImportUiState.Importing

            try {
                // 待创建的一级分类：按（分类名 + 收支方向）去重创建，创建结果决定记录的分类 id
                val createdTypeIds = createPendingTypes(selectedItems)

                val records = selectedItems.mapNotNull { preview ->
                    val typeId = if (preview.pendingCreateTypeName != null) {
                        createdTypeIds[preview.pendingCreateTypeName to preview.billItem.direction]
                            ?: return@mapNotNull null
                    } else {
                        preview.mappedTypeId
                    }

                    val remarkText = buildString {
                        append(preview.billItem.counterparty)
                        // 支付宝账单按需求备注只写交易对方；微信账单保留原有「对方 - 商品说明」格式
                        if (source == BillSource.WECHAT && preview.billItem.description.isNotBlank()) {
                            append(" - ")
                            append(preview.billItem.description)
                        }
                        if (preview.billItem.transactionId.isNotBlank()) {
                            append(" [")
                            append(source.transactionIdLabel)
                            append(":")
                            append(preview.billItem.transactionId)
                            append("]")
                        }
                    }

                    val amountCent = preview.billItem.amount.toCent()
                    RecordTable(
                        id = null,
                        typeId = typeId,
                        assetId = preview.mappedAssetId,
                        intoAssetId = -1L,
                        booksId = state.selectedBooksId,
                        amount = amountCent,
                        finalAmount = amountCent,
                        concessions = 0L,
                        charge = 0L,
                        remark = remarkText,
                        reimbursable = cn.wj.android.cashbook.core.common.SWITCH_INT_OFF,
                        recordTime = preview.billItem.transactionTime,
                    )
                }

                val ids = recordRepository.batchImportRecords(records)
                // 跳过数 = 未勾选条数 + 分类不可用而未落库条数
                val skipped = state.previewItems.size - records.size
                _uiState.value = RecordImportUiState.Done(
                    imported = ids.size,
                    skipped = skipped,
                )
            } catch (e: Exception) {
                logger().e(e, "import failed")
                _uiState.value = RecordImportUiState.Error("")
            }
        }
    }

    /**
     * 创建待创建的一级分类（支付宝账单分类映射未命中二级分类时）
     *
     * 按（分类名 + 收支方向）去重，避免同一分类重复创建；返回 key → 新建分类 id。
     */
    private suspend fun createPendingTypes(
        selectedItems: List<ImportPreviewItem>,
    ): Map<Pair<String, BillDirection>, Long> {
        val pendingKeys = selectedItems.mapNotNull { preview ->
            preview.pendingCreateTypeName?.let { name -> name to preview.billItem.direction }
        }.distinct()
        if (pendingKeys.isEmpty()) return emptyMap()

        val createdTypeIds = pendingKeys.associateWith { (name, direction) ->
            createImportTypeUseCase(
                name = name,
                typeCategory = when (direction) {
                    BillDirection.EXPENDITURE -> RecordTypeCategoryEnum.EXPENDITURE
                    BillDirection.INCOME -> RecordTypeCategoryEnum.INCOME
                },
                iconName = AlipayCategoryMapper.iconNameFor(name),
            )
        }
        // 分类创建后刷新列表，保证「再次导入 / 切换单条分类」时可选到新分类
        expenditureTypes = typeRepository.firstExpenditureTypeListData.first()
        incomeTypes = typeRepository.firstIncomeTypeListData.first()
        alipaySecondLevelTypes = typeRepository.getSecondRecordTypeMapByParentIds(
            (expenditureTypes + incomeTypes).map { it.id },
        ).values.flatten()
        return createdTypeIds
    }
}

/** 导入界面 UI 状态 */
sealed interface RecordImportUiState {
    /** 解析中 */
    data object Parsing : RecordImportUiState

    /** 就绪，可配置映射和预览 */
    data class Ready(
        val fileName: String,
        val summary: BillSummary,
        val selectedBooksId: Long,
        val booksList: List<BooksModel>,
        val paymentMappings: List<PaymentMethodMapping>,
        val previewItems: List<ImportPreviewItem>,
        val hasUnmappedPayments: Boolean,
        val visibleAssets: List<cn.wj.android.cashbook.core.model.model.AssetModel>,
        val expenditureTypes: List<RecordTypeModel>,
        val incomeTypes: List<RecordTypeModel>,
    ) : RecordImportUiState

    /** 导入中 */
    data object Importing : RecordImportUiState

    /** 导入完成 */
    data class Done(val imported: Int, val skipped: Int) : RecordImportUiState

    /** 错误 */
    data class Error(val message: String) : RecordImportUiState
}
