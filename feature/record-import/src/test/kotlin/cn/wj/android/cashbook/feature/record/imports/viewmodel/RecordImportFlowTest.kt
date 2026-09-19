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
import cn.wj.android.cashbook.core.common.FIXED_TYPE_ID_REFUND
import cn.wj.android.cashbook.core.common.tools.toDateTimeString
import cn.wj.android.cashbook.core.model.enums.RecordTypeCategoryEnum
import cn.wj.android.cashbook.core.model.enums.TypeLevelEnum
import cn.wj.android.cashbook.core.model.model.BillDirection
import cn.wj.android.cashbook.core.model.model.BillSource
import cn.wj.android.cashbook.core.testing.data.createRecordTypeModel
import cn.wj.android.cashbook.core.testing.repository.FakeAssetRepository
import cn.wj.android.cashbook.core.testing.repository.FakeBooksRepository
import cn.wj.android.cashbook.core.testing.repository.FakeRecordRepository
import cn.wj.android.cashbook.core.testing.repository.FakeTypeRepository
import cn.wj.android.cashbook.core.testing.util.TestDispatcherRule
import cn.wj.android.cashbook.domain.usecase.CreateImportTypeUseCase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 账单导入端到端流程测试。
 *
 * WechatBillParser 依赖 XmlPullParserFactory，纯 JVM 下抛 "not mocked"，故走 Robolectric。
 * 用程序化构造的最小微信 xlsx（ZIP）驱动 parseFile → Ready，再覆盖 confirmImport/toggle/selectAll。
 */
@RunWith(RobolectricTestRunner::class)
class RecordImportFlowTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var recordRepository: FakeRecordRepository
    private lateinit var typeRepository: FakeTypeRepository
    private lateinit var assetRepository: FakeAssetRepository
    private lateinit var booksRepository: FakeBooksRepository

    @Before
    fun setup() {
        recordRepository = FakeRecordRepository()
        typeRepository = FakeTypeRepository()
        assetRepository = FakeAssetRepository()
        booksRepository = FakeBooksRepository()
        // 播种一级支出/收入类型，使 defaultType 非空
        typeRepository.addType(
            createRecordTypeModel(id = 1L, name = "其它支出", typeCategory = RecordTypeCategoryEnum.EXPENDITURE),
        )
        typeRepository.addType(
            createRecordTypeModel(id = 2L, name = "其它收入", typeCategory = RecordTypeCategoryEnum.INCOME),
        )
    }

    private fun createViewModel(filePath: String): RecordImportViewModel = RecordImportViewModel(
        savedStateHandle = SavedStateHandle(mapOf("fileUri" to filePath)),
        recordRepository = recordRepository,
        typeRepository = typeRepository,
        assetRepository = assetRepository,
        booksRepository = booksRepository,
        createImportTypeUseCase = CreateImportTypeUseCase(
            typeRepository = typeRepository,
            coroutineContext = UnconfinedTestDispatcher(),
        ),
        coroutineContext = UnconfinedTestDispatcher(),
    )

    /** 支付宝账单来源的 ViewModel */
    private fun createAlipayViewModel(filePath: String): RecordImportViewModel = RecordImportViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf(
                "fileUri" to filePath,
                "source" to BillSource.ALIPAY.name,
            ),
        ),
        recordRepository = recordRepository,
        typeRepository = typeRepository,
        assetRepository = assetRepository,
        booksRepository = booksRepository,
        createImportTypeUseCase = CreateImportTypeUseCase(
            typeRepository = typeRepository,
            coroutineContext = UnconfinedTestDispatcher(),
        ),
        coroutineContext = UnconfinedTestDispatcher(),
    )

    /** 一条支出账单行：交易时间/类型/对方/商品/收支/金额/支付方式/状态/交易单号/商户单号/备注（11 列） */
    private val expenditureRow = listOf(
        "2026-03-26 11:50:04", "商户消费", "星巴克", "咖啡", "支出",
        "99.8", "零钱", "支付成功", "4200001", "/", "/",
    )

    /** 一条收入账单行 */
    private val incomeRow = listOf(
        "2026-03-25 09:00:00", "转账", "老板", "工资", "收入",
        "5000", "/", "已存入", "4200002", "/", "/",
    )

    /**
     * 构造最小微信 xlsx（ZIP）：含 xl/sharedStrings.xml（空池）+ xl/worksheets/sheet1.xml，
     * 数据行从 r=19 起，每行 11 个 raw `<v>` 单元格（无 t 属性，值即原文）。
     */
    private fun createWechatBillXlsx(rows: List<List<String>>): File {
        val file = tempFolder.newFile("wechat_bill.xlsx")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zip.write("""<?xml version="1.0" encoding="UTF-8"?><sst></sst>""".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            val sb = StringBuilder()
            sb.append("""<?xml version="1.0" encoding="UTF-8"?><worksheet><sheetData>""")
            rows.forEachIndexed { index, cells ->
                sb.append("""<row r="${19 + index}">""")
                cells.forEach { value ->
                    sb.append("<c><v>").append(value).append("</v></c>")
                }
                sb.append("</row>")
            }
            sb.append("</sheetData></worksheet>")
            zip.write(sb.toString().toByteArray())
            zip.closeEntry()
        }
        return file
    }

    /** XML 文本转义（与真实 xlsx 一致：& / < / > 需转义） */
    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /** 生成支付宝 sheet1.xml 内容（便于单测断言与调试） */
    private fun alipaySheetXml(dataRows: List<Map<String, String>>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?><worksheet><sheetData>""")
        sb.append("""<row r="1"><c r="A1"><v>导出信息：</v></c></row>""")
        val headers = listOf(
            "A" to "交易时间", "B" to "交易分类", "C" to "交易对方", "D" to "对方账号",
            "E" to "商品说明", "F" to "收/支", "G" to "金额", "H" to "收/付款方式",
            "I" to "交易状态", "J" to "交易订单号", "K" to "商家订单号", "L" to "备注",
        )
        sb.append("""<row r="2">""")
        headers.forEach { (col, name) ->
            sb.append("<c r=\"").append(col).append("2\"><v>").append(xmlEscape(name)).append("</v></c>")
        }
        sb.append("</row>")
        dataRows.forEachIndexed { index, cells ->
            val rowNum = 3 + index
            sb.append("<row r=\"").append(rowNum).append("\">")
            cells.forEach { (col, value) ->
                sb.append("<c r=\"").append(col).append(rowNum).append("\"><v>")
                    .append(xmlEscape(value)).append("</v></c>")
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    /**
     * 构造最小支付宝 xlsx（ZIP）：第 1 行元数据 + 第 2 行表头 + 数据行，
     * 每个单元格带 r 引用（A1/B2…），与真实支付宝导出一致（可覆盖缺列场景）。
     */
    private fun createAlipayBillXlsx(dataRows: List<Map<String, String>>): File {
        val file = tempFolder.newFile("alipay_bill.xlsx")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zip.write("""<?xml version="1.0" encoding="UTF-8"?><sst></sst>""".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(alipaySheetXml(dataRows).toByteArray())
            zip.closeEntry()
        }
        return file
    }

    /** 支付宝数据行（列引用 → 值），按真实导出字段构造 */
    private fun alipayRow(
        time: String,
        category: String,
        party: String,
        amount: String,
        status: String,
        order: String,
        direction: String = "支出",
    ): Map<String, String> = mapOf(
        "A" to time,
        "B" to category,
        "C" to party,
        "E" to "商品说明",
        "F" to direction,
        "G" to amount,
        "H" to "平安银行信用购&红包",
        "I" to status,
        "J" to order,
    )

    @Test
    fun when_valid_bill_parsed_then_ready_with_preview_items() = runTest {
        val file = createWechatBillXlsx(listOf(expenditureRow, incomeRow))
        val viewModel = createViewModel(file.absolutePath)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state).isInstanceOf(RecordImportUiState.Ready::class.java)
        val ready = state as RecordImportUiState.Ready
        assertThat(ready.previewItems).hasSize(2)
        assertThat(ready.summary.totalCount).isEqualTo(2)
        assertThat(ready.summary.expenditureCount).isEqualTo(1)
        assertThat(ready.summary.incomeCount).isEqualTo(1)
        // 无重复 → 默认全选
        assertThat(ready.previewItems.all { it.selected }).isTrue()
    }

    @Test
    fun when_confirm_import_then_done_and_records_built() = runTest {
        val file = createWechatBillXlsx(listOf(expenditureRow, incomeRow))
        val viewModel = createViewModel(file.absolutePath)
        advanceUntilIdle()

        viewModel.confirmImport()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state).isInstanceOf(RecordImportUiState.Done::class.java)
        val done = state as RecordImportUiState.Done
        assertThat(done.imported).isEqualTo(2)
        assertThat(done.skipped).isEqualTo(0)

        // 忠实桩捕获构建的记录，校验 remark 拼接 / toCent / finalAmount
        val imported = recordRepository.lastImportedRecords
        assertThat(imported).hasSize(2)
        val expenditure = imported.first { it.remark.contains("星巴克") }
        assertThat(expenditure.remark).isEqualTo("星巴克 - 咖啡 [微信单号:4200001]")
        // 99.8 元 → 9980 分，finalAmount 同步
        assertThat(expenditure.amount).isEqualTo(9980L)
        assertThat(expenditure.finalAmount).isEqualTo(9980L)
        assertThat(expenditure.booksId).isEqualTo(1L)
    }

    @Test
    fun when_deselect_one_then_skipped_counted_and_only_selected_imported() = runTest {
        val file = createWechatBillXlsx(listOf(expenditureRow, incomeRow))
        val viewModel = createViewModel(file.absolutePath)
        advanceUntilIdle()

        // 取消第 0 条
        viewModel.toggleItemSelection(0)
        viewModel.confirmImport()
        advanceUntilIdle()

        val done = viewModel.uiState.value as RecordImportUiState.Done
        assertThat(done.imported).isEqualTo(1)
        assertThat(done.skipped).isEqualTo(1)
        assertThat(recordRepository.lastImportedRecords).hasSize(1)
    }

    @Test
    fun when_select_all_false_then_confirm_import_no_op() = runTest {
        val file = createWechatBillXlsx(listOf(expenditureRow, incomeRow))
        val viewModel = createViewModel(file.absolutePath)
        advanceUntilIdle()

        viewModel.selectAll(false)
        viewModel.confirmImport()
        advanceUntilIdle()

        // selectedItems 为空 → confirmImport 直接 return，保持 Ready
        assertThat(viewModel.uiState.value).isInstanceOf(RecordImportUiState.Ready::class.java)
    }

    @Test
    fun when_alipay_bill_then_only_success_imported_and_category_mapped_or_created() = runTest {
        // 已有二级分类「餐饮」（父类型：其它支出 id=1）
        typeRepository.addType(
            createRecordTypeModel(
                id = 10L,
                parentId = 1L,
                name = "餐饮",
                typeLevel = TypeLevelEnum.SECOND,
                typeCategory = RecordTypeCategoryEnum.EXPENDITURE,
            ),
        )
        val file = createAlipayBillXlsx(
            listOf(
                alipayRow(
                    time = "45998.6131944444",
                    category = "餐饮",
                    party = "淘宝闪购",
                    amount = "30.3",
                    status = "交易成功",
                    order = "2025120722001192861424014358\t",
                ),
                alipayRow(
                    time = "45998.5",
                    category = "保险",
                    party = "某某保险",
                    amount = "88",
                    status = "交易成功",
                    order = "2025120722001192861424014999",
                ),
                alipayRow(
                    time = "45998.4",
                    category = "餐饮",
                    party = "支付成功行",
                    amount = "10",
                    status = "支付成功",
                    order = "2",
                ),
                alipayRow(
                    time = "45998.3",
                    category = "娱乐",
                    party = "交易关闭行",
                    amount = "20",
                    status = "交易关闭",
                    order = "3",
                ),
            ),
        )
        val viewModel = createAlipayViewModel(file.absolutePath)
        advanceUntilIdle()

        val ready = viewModel.uiState.value as RecordImportUiState.Ready
        // 需求 5：只导入「交易成功」（支付成功 / 交易关闭 均被过滤）
        assertThat(ready.previewItems).hasSize(2)
        assertThat(ready.summary.totalCount).isEqualTo(2)

        // 需求 2：命中二级分类 → 使用该二级分类
        val matched = ready.previewItems.first { it.billItem.counterparty == "淘宝闪购" }
        assertThat(matched.mappedTypeId).isEqualTo(10L)
        assertThat(matched.mappedTypeName).isEqualTo("餐饮")
        assertThat(matched.pendingCreateTypeName).isNull()

        // 需求 2：未命中二级分类 → 待创建同名一级分类
        val needCreate = ready.previewItems.first { it.billItem.counterparty == "某某保险" }
        assertThat(needCreate.mappedTypeName).isEqualTo("保险")
        assertThat(needCreate.pendingCreateTypeName).isEqualTo("保险")

        // 需求 1/4：交易时间（Excel 序列号 → 本地时间）与金额
        assertThat(matched.billItem.transactionTime.toDateTimeString()).startsWith("2025-12-07 14:43")
        assertThat(matched.billItem.amount).isEqualTo(30.3)

        viewModel.confirmImport()
        advanceUntilIdle()

        val done = viewModel.uiState.value as RecordImportUiState.Done
        assertThat(done.imported).isEqualTo(2)
        assertThat(done.skipped).isEqualTo(0)

        val imported = recordRepository.lastImportedRecords
        assertThat(imported).hasSize(2)

        // 需求 3：备注为交易对方 + 支付宝单号（订单号已去除制表符）
        val matchedRecord = imported.first { it.remark.contains("淘宝闪购") }
        assertThat(matchedRecord.remark).isEqualTo("淘宝闪购 [支付宝单号:2025120722001192861424014358]")
        assertThat(matchedRecord.typeId).isEqualTo(10L)
        assertThat(matchedRecord.amount).isEqualTo(3030L)
        assertThat(matchedRecord.finalAmount).isEqualTo(3030L)
        assertThat(matchedRecord.recordTime.toDateTimeString()).startsWith("2025-12-07 14:43")

        // 未命中分类的记录：确认导入时创建同名一级分类并使用其 id
        val createdType = typeRepository.firstExpenditureTypeListData.first().first { it.name == "保险" }
        assertThat(createdType.parentId).isEqualTo(-1L)
        assertThat(createdType.typeCategory).isEqualTo(RecordTypeCategoryEnum.EXPENDITURE)
        val createdRecord = imported.first { it.remark.contains("某某保险") }
        assertThat(createdRecord.typeId).isEqualTo(createdType.id)
        assertThat(createdRecord.amount).isEqualTo(8800L)
    }

    @Test
    fun when_alipay_category_matches_existing_first_level_then_reuse_without_create() = runTest {
        // 一级分类已存在「保险」（支出）→ 复用它，不创建新分类
        typeRepository.addType(
            createRecordTypeModel(
                id = 30L,
                name = "保险",
                typeCategory = RecordTypeCategoryEnum.EXPENDITURE,
            ),
        )
        val file = createAlipayBillXlsx(
            listOf(
                alipayRow(
                    time = "45998.6131944444",
                    category = "保险",
                    party = "某某保险",
                    amount = "88",
                    status = "交易成功",
                    order = "2025120722001192861424014999",
                ),
            ),
        )
        val viewModel = createAlipayViewModel(file.absolutePath)
        advanceUntilIdle()

        val ready = viewModel.uiState.value as RecordImportUiState.Ready
        val item = ready.previewItems.first()
        // 命中一级分类 → 复用该分类，无待创建标记
        assertThat(item.mappedTypeId).isEqualTo(30L)
        assertThat(item.mappedTypeName).isEqualTo("保险")
        assertThat(item.pendingCreateTypeName).isNull()

        viewModel.confirmImport()
        advanceUntilIdle()

        val done = viewModel.uiState.value as RecordImportUiState.Done
        assertThat(done.imported).isEqualTo(1)
        assertThat(recordRepository.lastImportedRecords.first().typeId).isEqualTo(30L)
        // 未产生新的同名一级分类
        assertThat(
            typeRepository.firstExpenditureTypeListData.first().count { it.name == "保险" },
        ).isEqualTo(1)
    }

    @Test
    fun when_alipay_bill_has_refund_row_then_uses_builtin_refund_type() = runTest {
        // 预置内置「退款」分类（收入大类，id 为固定负数 id）
        typeRepository.addType(
            createRecordTypeModel(
                id = FIXED_TYPE_ID_REFUND,
                name = "退款",
                typeCategory = RecordTypeCategoryEnum.INCOME,
            ),
        )
        val file = createAlipayBillXlsx(
            listOf(
                alipayRow(
                    time = "45998.6",
                    category = "餐饮",
                    party = "退款商户",
                    amount = "10",
                    status = "交易成功",
                    order = "REFUND0001",
                    direction = "退款",
                ),
                alipayRow(
                    time = "45998.6131944444",
                    category = "餐饮",
                    party = "淘宝闪购",
                    amount = "30.3",
                    status = "交易成功",
                    order = "PAY0001",
                ),
            ),
        )
        val viewModel = createAlipayViewModel(file.absolutePath)
        advanceUntilIdle()

        val ready = viewModel.uiState.value as RecordImportUiState.Ready
        // 退款纳入导入：按收入方向、固定挂内置「退款」分类，不参与交易分类映射
        assertThat(ready.previewItems).hasSize(2)
        val refundItem = ready.previewItems.first { it.billItem.counterparty == "退款商户" }
        assertThat(refundItem.billItem.isRefund).isTrue()
        assertThat(refundItem.billItem.direction).isEqualTo(BillDirection.INCOME)
        assertThat(refundItem.mappedTypeId).isEqualTo(FIXED_TYPE_ID_REFUND)
        assertThat(refundItem.mappedTypeName).isEqualTo("退款")
        assertThat(refundItem.pendingCreateTypeName).isNull()

        viewModel.confirmImport()
        advanceUntilIdle()

        val done = viewModel.uiState.value as RecordImportUiState.Done
        assertThat(done.imported).isEqualTo(2)
        val refundRecord = recordRepository.lastImportedRecords.first { it.remark.contains("退款商户") }
        assertThat(refundRecord.typeId).isEqualTo(FIXED_TYPE_ID_REFUND)
        assertThat(refundRecord.amount).isEqualTo(1000L)
        assertThat(refundRecord.finalAmount).isEqualTo(1000L)
    }
}
