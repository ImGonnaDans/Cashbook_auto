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

package cn.wj.android.cashbook.domain.usecase

import cn.wj.android.cashbook.core.common.annotation.CashbookDispatchers
import cn.wj.android.cashbook.core.common.annotation.Dispatcher
import cn.wj.android.cashbook.core.data.repository.TypeRepository
import cn.wj.android.cashbook.core.model.enums.RecordTypeCategoryEnum
import cn.wj.android.cashbook.core.model.enums.TypeLevelEnum
import cn.wj.android.cashbook.core.model.model.RecordTypeModel
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

/**
 * 创建一级分类用例（导入账单时按账单分类补建）
 *
 * id 由数据库自增生成，排序取当前一级分类最大 sort + 1，避免与内置固定 id（负数）冲突。
 *
 * @param typeRepository 类型数据仓库
 */
class CreateImportTypeUseCase @Inject constructor(
    private val typeRepository: TypeRepository,
    @Dispatcher(CashbookDispatchers.IO) private val coroutineContext: CoroutineContext,
) {

    /**
     * 创建一级分类
     *
     * @param name 分类名称
     * @param typeCategory 记录大类（支出/收入）
     * @param iconName 图标资源名（需为已存在的 drawable 资源名）
     * @return 新分类 id
     */
    suspend operator fun invoke(
        name: String,
        typeCategory: RecordTypeCategoryEnum,
        iconName: String,
    ): Long = withContext(coroutineContext) {
        // 传入不存在的 id，generateSortById 会按「当前一级分类最大 sort + 1」返回排序值
        val sort = typeRepository.generateSortById(id = -1L, parentId = -1L)
        typeRepository.insertType(
            RecordTypeModel(
                id = -1L,
                parentId = -1L,
                name = name,
                iconName = iconName,
                typeLevel = TypeLevelEnum.FIRST,
                typeCategory = typeCategory,
                protected = false,
                sort = sort,
                needRelated = false,
            ),
        )
    }
}
