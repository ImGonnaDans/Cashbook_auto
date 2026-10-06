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

package cn.wj.android.cashbook.core.data.repository

import cn.wj.android.cashbook.core.common.SWITCH_INT_OFF
import cn.wj.android.cashbook.core.common.SWITCH_INT_ON
import cn.wj.android.cashbook.core.common.ext.orElse
import cn.wj.android.cashbook.core.database.table.TypeTable
import cn.wj.android.cashbook.core.model.enums.RecordTypeCategoryEnum
import cn.wj.android.cashbook.core.model.enums.TypeLevelEnum
import cn.wj.android.cashbook.core.model.model.RecordTypeModel
import kotlinx.coroutines.flow.Flow

/**
 * 记录类型数据仓库
 *
 * > [王杰](mailto:15555650921@163.com) 创建于 2023/2/20
 */
interface TypeRepository {

    val firstExpenditureTypeListData: Flow<List<RecordTypeModel>>

    val firstIncomeTypeListData: Flow<List<RecordTypeModel>>

    val firstTransferTypeListData: Flow<List<RecordTypeModel>>

    suspend fun getRecordTypeById(typeId: Long): RecordTypeModel?

    /**
     * 批量查询 [typeIds] 对应的类型分类，一次查询避免逐条 N+1。
     * 返回 typeId -> 分类的映射；库中不存在的类型（如平账合成类型 -1101/-1102）不在结果中，由调用方兜底解析。
     */
    suspend fun getRecordTypeCategories(typeIds: List<Long>): Map<Long, RecordTypeCategoryEnum>

    suspend fun getNoNullRecordTypeById(typeId: Long): RecordTypeModel

    suspend fun getNoNullDefaultRecordType(): RecordTypeModel

    suspend fun getSecondRecordTypeListByParentId(parentId: Long): List<RecordTypeModel>

    /**
     * 批量查询多个一级类型 id 对应的二级类型，一次查询避免逐个父类型查询（N+1）。
     * 返回 parentId -> 二级类型列表 的映射；没有子类型的父类型不会出现在结果中。
     */
    suspend fun getSecondRecordTypeMapByParentIds(parentIds: List<Long>): Map<Long, List<RecordTypeModel>>

    suspend fun needRelated(typeId: Long): Boolean

    suspend fun isReimburseType(typeId: Long): Boolean

    suspend fun isRefundType(typeId: Long): Boolean

    suspend fun changeTypeToSecond(id: Long, parentId: Long)

    suspend fun changeSecondTypeToFirst(id: Long)

    suspend fun deleteById(id: Long)

    suspend fun countByName(name: String): Int

    suspend fun update(model: RecordTypeModel)

    /**
     * 插入新分类（自动生成主键），返回新分类 id
     *
     * 与 [update] 的区别：`update` 走 `insertOrReplace` 会带上模型 id（负数为内置分类），
     * 新增分类必须使用自增正 id，避免与内置固定 id（-1、-2001…）冲突。
     */
    suspend fun insertType(model: RecordTypeModel): Long

    suspend fun generateSortById(id: Long, parentId: Long): Int

    /** 按 [sortedIds] 顺序为一级分类写入连续的 sort（0,1,2...） */
    suspend fun updateFirstTypeSort(sortedIds: List<Long>)

    suspend fun isCreditPaymentType(typeId: Long): Boolean

    suspend fun migrateSpecialTypes()

    /**
     * 自愈历史脏数据：把「`parentId == -1` 但 `typeLevel == [TypeLevelEnum.SECOND]`」的孤儿二级分类提升为一级分类。
     *
     * 不变量：`parentId == -1 ⟺ typeLevel == FIRST`。旧版本编辑二级分类时把 `parentId` 覆盖为 -1，
     * 使该分类既不出现在一级列表、也不在其父分类下（用户可见为「分类消失」），但仍占用名称（重建同名会撞重名校验）。
     * 因父 id 已彻底丢失、`sort` 反推父分类不可靠，此处仅纠正 `typeLevel`（`parentId` 已为 -1），
     * 其余字段（含 `sort`）保持原值，记录引用（`db_record.type_id`）不受影响；用户可再手动改回某一级的二级分类。
     *
     * 幂等：无脏数据时不写库并返回 0；有脏数据时返回修复行数。启动维护每次兜底（从旧备份恢复会重新引入脏数据）。
     */
    suspend fun healOrphanSecondLevelTypes(): Int
}

internal fun TypeTable.asModel(needRelated: Boolean): RecordTypeModel {
    return RecordTypeModel(
        id = this.id.orElse(-1L),
        parentId = this.parentId,
        name = this.name,
        iconName = this.iconName,
        typeLevel = TypeLevelEnum.ordinalOf(this.typeLevel),
        typeCategory = RecordTypeCategoryEnum.ordinalOf(this.typeCategory),
        protected = this.protected == SWITCH_INT_ON,
        sort = this.sort,
        needRelated = needRelated,
    )
}

internal fun RecordTypeModel.asTable(): TypeTable {
    return TypeTable(
        id = if (this.id == -1L) null else this.id,
        parentId = this.parentId,
        name = this.name,
        iconName = this.iconName,
        typeLevel = this.typeLevel.ordinal,
        typeCategory = this.typeCategory.ordinal,
        protected = if (this.protected) SWITCH_INT_ON else SWITCH_INT_OFF,
        sort = this.sort,
    )
}
