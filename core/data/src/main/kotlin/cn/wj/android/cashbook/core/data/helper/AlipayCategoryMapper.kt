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

package cn.wj.android.cashbook.core.data.helper

import cn.wj.android.cashbook.core.model.enums.RecordTypeCategoryEnum
import cn.wj.android.cashbook.core.model.enums.TypeLevelEnum
import cn.wj.android.cashbook.core.model.model.BillDirection
import cn.wj.android.cashbook.core.model.model.RecordTypeModel

/**
 * 支付宝账单交易分类映射器
 *
 * 支付宝账单自带「交易分类」列（餐饮 / 交通 / 购物 …），映射规则按优先级：
 * 1. 用户**二级分类**中按名称精确匹配（限同大类：支出/收入）→ 命中则使用该二级分类；
 * 2. 未命中 → 在用户**一级分类**中按名称精确匹配（限同大类）→ 命中则直接复用该一级分类，
 *    避免重复创建同名一级分类；
 * 3. 两级均未命中 → 由调用方**创建同名一级分类**并把记录挂到该分类下
 *    （见 [Result.NeedCreateFirstLevel]）。
 */
object AlipayCategoryMapper {

    /** 默认分类图标（用于创建的一级分类，需保证资源存在） */
    const val DEFAULT_TYPE_ICON = "vector_type_other_24"

    /** 分类名称 → 图标资源名（均为已存在的 vector_type_*_24 资源） */
    private val CATEGORY_ICON_MAP: Map<String, String> = mapOf(
        "餐饮" to "vector_type_buy_food_24",
        "交通" to "vector_type_traffic_24",
        "购物" to "vector_type_shopping_24",
        "医疗" to "vector_type_medical_24",
        "娱乐" to "vector_type_amusement_24",
        "保险" to "vector_type_benefit_24",
        "车" to "vector_type_car_24",
        "服装" to "vector_type_bag_24",
        "通讯" to "vector_type_call_charge_24",
        "其他" to "vector_type_other_24",
    )

    /** 匹配结果 */
    sealed interface Result {
        /** 命中已存在的分类（二级分类优先，其次一级分类） */
        data class Existing(val typeId: Long, val typeName: String) : Result

        /** 二级分类与一级分类均未命中，需要创建同名一级分类 */
        data class NeedCreateFirstLevel(val typeName: String) : Result

        /** 表格未提供交易分类，交由调用方使用默认分类 */
        data object Absent : Result
    }

    /**
     * 匹配交易分类
     *
     * 优先级：二级分类同名 → 一级分类同名（复用，避免重复创建）→ 需创建一级分类。
     * 匹配均**限同大类**（支出/收入），避免记录类型与其大类不一致导致保存被拦截。
     *
     * @param categoryName 表格「交易分类」原始值
     * @param direction 收支方向（决定大类）
     * @param secondLevelTypes 用户已有的二级分类列表（可通过
     * [cn.wj.android.cashbook.core.data.repository.TypeRepository.getSecondRecordTypeMapByParentIds] 获取）
     * @param firstLevelTypes 当前收支方向对应的一级分类列表
     */
    fun match(
        categoryName: String,
        direction: BillDirection,
        secondLevelTypes: List<RecordTypeModel>,
        firstLevelTypes: List<RecordTypeModel>,
    ): Result {
        val name = categoryName.trim()
        if (name.isEmpty()) return Result.Absent

        val typeCategory = when (direction) {
            BillDirection.EXPENDITURE -> RecordTypeCategoryEnum.EXPENDITURE
            BillDirection.INCOME -> RecordTypeCategoryEnum.INCOME
        }

        // 1. 二级分类同名优先
        secondLevelTypes.firstOrNull { type ->
            type.typeLevel == TypeLevelEnum.SECOND &&
                type.typeCategory == typeCategory &&
                type.name == name
        }?.let { return Result.Existing(typeId = it.id, typeName = it.name) }

        // 2. 一级分类同名复用（避免重复创建同名一级分类）
        firstLevelTypes.firstOrNull { type ->
            type.typeLevel == TypeLevelEnum.FIRST &&
                type.typeCategory == typeCategory &&
                type.name == name
        }?.let { return Result.Existing(typeId = it.id, typeName = it.name) }

        // 3. 两级均未命中，需要创建同名一级分类
        return Result.NeedCreateFirstLevel(typeName = name)
    }

    /** 创建一级分类时使用的图标资源名 */
    fun iconNameFor(categoryName: String): String =
        CATEGORY_ICON_MAP[categoryName.trim()] ?: DEFAULT_TYPE_ICON
}
