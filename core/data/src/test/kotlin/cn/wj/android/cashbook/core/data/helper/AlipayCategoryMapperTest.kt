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
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [AlipayCategoryMapper] 单元测试
 *
 * 映射规则：只在**二级分类**中按名称精确匹配（限同大类），未命中则需创建同名一级分类。
 */
class AlipayCategoryMapperTest {

    private fun type(
        id: Long,
        name: String,
        level: TypeLevelEnum,
        category: RecordTypeCategoryEnum,
        parentId: Long = -1L,
    ) = RecordTypeModel(
        id = id,
        parentId = parentId,
        name = name,
        iconName = "vector_type_other_24",
        typeLevel = level,
        typeCategory = category,
        protected = false,
        sort = 0,
        needRelated = false,
    )

    @Test
    fun match_second_level_same_name_then_existing() {
        val types = listOf(
            type(1L, "餐饮", TypeLevelEnum.FIRST, RecordTypeCategoryEnum.EXPENDITURE),
            type(10L, "餐饮", TypeLevelEnum.SECOND, RecordTypeCategoryEnum.EXPENDITURE, parentId = 1L),
        )
        val result = AlipayCategoryMapper.match("餐饮", BillDirection.EXPENDITURE, types, emptyList())
        assertThat(result).isInstanceOf(AlipayCategoryMapper.Result.Existing::class.java)
        assertThat((result as AlipayCategoryMapper.Result.Existing).typeId).isEqualTo(10L)
    }

    @Test
    fun match_without_second_level_then_need_create() {
        // 二级分类无「保险」，一级分类也无「保险」→ 才需要新建一级分类
        val firstTypes = listOf(type(1L, "餐饮", TypeLevelEnum.FIRST, RecordTypeCategoryEnum.EXPENDITURE))
        val result = AlipayCategoryMapper.match("保险", BillDirection.EXPENDITURE, emptyList(), firstTypes)
        assertThat(result).isEqualTo(AlipayCategoryMapper.Result.NeedCreateFirstLevel("保险"))
    }

    @Test
    fun match_ignores_second_level_of_other_category() {
        // 收入下的「餐饮」二级分类不应命中支出行
        val secondTypes = listOf(
            type(2L, "其它收入", TypeLevelEnum.FIRST, RecordTypeCategoryEnum.INCOME),
            type(20L, "餐饮", TypeLevelEnum.SECOND, RecordTypeCategoryEnum.INCOME, parentId = 2L),
        )
        val result = AlipayCategoryMapper.match("餐饮", BillDirection.EXPENDITURE, secondTypes, emptyList())
        assertThat(result).isEqualTo(AlipayCategoryMapper.Result.NeedCreateFirstLevel("餐饮"))
    }

    @Test
    fun match_income_row_with_income_second_level_then_existing() {
        val types = listOf(
            type(20L, "餐饮", TypeLevelEnum.SECOND, RecordTypeCategoryEnum.INCOME, parentId = 2L),
        )
        val result = AlipayCategoryMapper.match("餐饮", BillDirection.INCOME, types, emptyList())
        assertThat(result).isInstanceOf(AlipayCategoryMapper.Result.Existing::class.java)
        assertThat((result as AlipayCategoryMapper.Result.Existing).typeId).isEqualTo(20L)
    }

    @Test
    fun match_first_level_same_name_then_existing_without_create() {
        // 二级分类无「保险」，一级分类已有「保险」(支出) → 复用一级分类，不再创建
        val firstTypes = listOf(type(30L, "保险", TypeLevelEnum.FIRST, RecordTypeCategoryEnum.EXPENDITURE))
        val result = AlipayCategoryMapper.match("保险", BillDirection.EXPENDITURE, emptyList(), firstTypes)
        assertThat(result).isInstanceOf(AlipayCategoryMapper.Result.Existing::class.java)
        assertThat((result as AlipayCategoryMapper.Result.Existing).typeId).isEqualTo(30L)
    }

    @Test
    fun match_prefers_second_level_over_first_level() {
        // 两级都有同名分类时，优先命中二级分类
        val firstTypes = listOf(type(1L, "餐饮", TypeLevelEnum.FIRST, RecordTypeCategoryEnum.EXPENDITURE))
        val secondTypes = listOf(
            type(10L, "餐饮", TypeLevelEnum.SECOND, RecordTypeCategoryEnum.EXPENDITURE, parentId = 1L),
        )
        val result = AlipayCategoryMapper.match("餐饮", BillDirection.EXPENDITURE, secondTypes, firstTypes)
        assertThat(result).isInstanceOf(AlipayCategoryMapper.Result.Existing::class.java)
        assertThat((result as AlipayCategoryMapper.Result.Existing).typeId).isEqualTo(10L)
    }

    @Test
    fun match_first_level_of_other_category_then_need_create() {
        // 一级分类「餐饮」属收入，支出行不能复用（类型与大类不匹配会被保存校验拦截）
        val firstTypes = listOf(type(40L, "餐饮", TypeLevelEnum.FIRST, RecordTypeCategoryEnum.INCOME))
        val result = AlipayCategoryMapper.match("餐饮", BillDirection.EXPENDITURE, emptyList(), firstTypes)
        assertThat(result).isEqualTo(AlipayCategoryMapper.Result.NeedCreateFirstLevel("餐饮"))
    }

    @Test
    fun match_blank_name_then_absent() {
        assertThat(AlipayCategoryMapper.match("  ", BillDirection.EXPENDITURE, emptyList(), emptyList()))
            .isEqualTo(AlipayCategoryMapper.Result.Absent)
    }

    @Test
    fun iconNameFor_known_and_unknown() {
        assertThat(AlipayCategoryMapper.iconNameFor("餐饮")).isEqualTo("vector_type_buy_food_24")
        assertThat(AlipayCategoryMapper.iconNameFor("未知分类")).isEqualTo(AlipayCategoryMapper.DEFAULT_TYPE_ICON)
    }
}
