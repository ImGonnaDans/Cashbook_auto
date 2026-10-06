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

import cn.wj.android.cashbook.core.model.enums.RecordTypeCategoryEnum
import cn.wj.android.cashbook.core.model.enums.TypeLevelEnum
import cn.wj.android.cashbook.core.model.model.RecordTypeModel
import cn.wj.android.cashbook.core.model.model.TempKeysModel
import cn.wj.android.cashbook.core.testing.repository.FakeRecordRepository
import cn.wj.android.cashbook.core.testing.repository.FakeSettingRepository
import cn.wj.android.cashbook.core.testing.repository.FakeTypeRepository
import cn.wj.android.cashbook.core.testing.util.TestDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * [RunStartupMaintenanceUseCase] 编排测试：验证 gate 放行时机与各维护步骤按 tempKeys 标志触发/跳过。
 *
 * 编排逻辑从 LauncherContentViewModel.init 收编而来，此处以 UseCase 单元覆盖（原 VM 编排断言迁入本类）。
 */
class RunStartupMaintenanceUseCaseTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private lateinit var recordRepository: FakeRecordRepository
    private lateinit var settingRepository: FakeSettingRepository
    private lateinit var typeRepository: FakeTypeRepository
    private lateinit var useCase: RunStartupMaintenanceUseCase

    @Before
    fun setup() {
        recordRepository = FakeRecordRepository()
        settingRepository = FakeSettingRepository()
        typeRepository = FakeTypeRepository()
        useCase = RunStartupMaintenanceUseCase(recordRepository, settingRepository, typeRepository)
    }

    @Test
    fun db9To10_not_migrated_then_migrate_then_ready() = runTest {
        settingRepository.setTempKeys(
            TempKeysModel(db9To10DataMigrated = false, preferenceSplit = false),
        )
        var ready = false

        useCase { ready = true }

        // migrate 在放行前调用；此分支不跑后台维护（净自付/backfill/compact）
        assertThat(recordRepository.migrateAfter9To10Count).isEqualTo(1)
        assertThat(ready).isTrue()
        assertThat(recordRepository.recalculateAllFinalAmountCount).isEqualTo(0)
        assertThat(recordRepository.backfillImagesToFilesCount).isEqualTo(0)
        // 类型不变量自愈每次启动都跑（与 tempKeys 标志无关）
        assertThat(typeRepository.healOrphanSecondLevelTypesCount).isEqualTo(1)
        // orphanScan 每分支兜底
        assertThat(recordRepository.cleanupOrphanImageFilesCount).isEqualTo(1)
    }

    @Test
    fun db9To10_done_all_flags_pending_then_full_maintenance() = runTest {
        settingRepository.setTempKeys(
            TempKeysModel(
                db9To10DataMigrated = true,
                preferenceSplit = true,
                finalAmountNetRecalcDone = false,
                imagesToFilesMigrated = false,
                dbVacuumDone = false,
            ),
        )
        var ready = false

        useCase { ready = true }

        assertThat(ready).isTrue()
        assertThat(recordRepository.migrateAfter9To10Count).isEqualTo(0)
        assertThat(recordRepository.recalculateAllFinalAmountCount).isEqualTo(1)
        assertThat(recordRepository.backfillImagesToFilesCount).isEqualTo(1)
        // compact gate：本次快照 imagesToFilesMigrated=false → 不跑 compact（顺延下次启动）
        assertThat(recordRepository.compactDatabaseIfNeededCount).isEqualTo(0)
        assertThat(recordRepository.cleanupOrphanImageFilesCount).isEqualTo(1)
        assertThat(typeRepository.healOrphanSecondLevelTypesCount).isEqualTo(1)
    }

    @Test
    fun images_migrated_but_vacuum_pending_then_compact() = runTest {
        settingRepository.setTempKeys(
            TempKeysModel(
                db9To10DataMigrated = true,
                preferenceSplit = true,
                finalAmountNetRecalcDone = true,
                imagesToFilesMigrated = true,
                dbVacuumDone = false,
            ),
        )

        useCase { }

        assertThat(recordRepository.recalculateAllFinalAmountCount).isEqualTo(0)
        assertThat(recordRepository.backfillImagesToFilesCount).isEqualTo(0)
        assertThat(recordRepository.compactDatabaseIfNeededCount).isEqualTo(1)
        assertThat(recordRepository.cleanupOrphanImageFilesCount).isEqualTo(1)
        assertThat(typeRepository.healOrphanSecondLevelTypesCount).isEqualTo(1)
    }

    @Test
    fun all_done_then_only_orphan_scan() = runTest {
        settingRepository.setTempKeys(
            TempKeysModel(
                db9To10DataMigrated = true,
                preferenceSplit = true,
                finalAmountNetRecalcDone = true,
                imagesToFilesMigrated = true,
                dbVacuumDone = true,
            ),
        )

        useCase { }

        assertThat(recordRepository.recalculateAllFinalAmountCount).isEqualTo(0)
        assertThat(recordRepository.backfillImagesToFilesCount).isEqualTo(0)
        assertThat(recordRepository.compactDatabaseIfNeededCount).isEqualTo(0)
        assertThat(recordRepository.cleanupOrphanImageFilesCount).isEqualTo(1)
        assertThat(typeRepository.healOrphanSecondLevelTypesCount).isEqualTo(1)
    }

    @Test
    fun netRecalc_fails_but_subsequent_steps_still_run() = runTest {
        // runCatchingMaintenance「失败隔离」契约：某步 Throwable 被吞、不连累后续步骤
        settingRepository.setTempKeys(
            TempKeysModel(
                db9To10DataMigrated = true,
                preferenceSplit = true,
                finalAmountNetRecalcDone = false,
                imagesToFilesMigrated = false,
                dbVacuumDone = false,
            ),
        )
        recordRepository.recalcThrowable = RuntimeException("boom")

        useCase { } // 不得抛（Throwable 被 runCatchingMaintenance 吞掉）

        // netRecalc 失败被吞后 backfill + orphanScan 仍执行
        assertThat(recordRepository.recalculateAllFinalAmountCount).isEqualTo(1)
        assertThat(recordRepository.backfillImagesToFilesCount).isEqualTo(1)
        assertThat(recordRepository.cleanupOrphanImageFilesCount).isEqualTo(1)
        assertThat(typeRepository.healOrphanSecondLevelTypesCount).isEqualTo(1)
    }

    @Test
    fun cancellation_in_maintenance_step_propagates_and_halts() = runTest {
        // CancellationException 必须先于 Throwable rethrow（不吞协程取消）：逃逸中断编排
        settingRepository.setTempKeys(
            TempKeysModel(
                db9To10DataMigrated = true,
                preferenceSplit = true,
                finalAmountNetRecalcDone = false,
            ),
        )
        recordRepository.recalcThrowable = CancellationException("cancelled")

        try {
            useCase { }
            throw AssertionError("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // 期望：CancellationException 被 rethrow（不吞协程取消）
        }
        // 取消逃逸中断编排，后续 orphanScan 未到达
        assertThat(recordRepository.cleanupOrphanImageFilesCount).isEqualTo(0)
        // 自愈在 netRecalc 之前已完成（不受后续步骤取消影响）
        assertThat(typeRepository.healOrphanSecondLevelTypesCount).isEqualTo(1)
    }

    @Test
    fun migrate_failure_escapes_and_gate_not_released() = runTest {
        // migrateAfter9To10 故意不包 try/catch：异常逃逸触发全局 handler，标志未置位下次幂等重试
        settingRepository.setTempKeys(
            TempKeysModel(db9To10DataMigrated = false, preferenceSplit = false),
        )
        recordRepository.migrateThrowable = RuntimeException("migration boom")
        var ready = false

        try {
            useCase { ready = true }
            throw AssertionError("expected migrate exception to escape (不包 try/catch)")
        } catch (e: RuntimeException) {
            // 期望：迁移异常逃逸到调用方
        }
        assertThat(ready).isFalse() // 迁移失败不放行首屏 gate
        assertThat(recordRepository.cleanupOrphanImageFilesCount).isEqualTo(0) // 逃逸中断编排，orphanScan 未到达
        assertThat(typeRepository.healOrphanSecondLevelTypesCount).isEqualTo(1) // 自愈先于迁移步骤
    }

    @Test
    fun type_orphan_heal_runs_before_first_screen_gate() = runTest {
        // 自愈必须先于 onFirstScreenReady：首屏不得读到「parent_id=-1 但二级」的脏分类
        settingRepository.setTempKeys(
            TempKeysModel(db9To10DataMigrated = true, preferenceSplit = true),
        )
        var ready = false
        var readyWhenHealCalled: Boolean? = null
        typeRepository.onHealOrphanSecondLevelTypes = { readyWhenHealCalled = ready }

        useCase { ready = true }

        assertThat(readyWhenHealCalled).isNotNull()
        assertThat(readyWhenHealCalled).isFalse()
        assertThat(ready).isTrue()
        assertThat(typeRepository.healOrphanSecondLevelTypesCount).isEqualTo(1)
    }

    @Test
    fun type_orphan_heal_promotes_dirty_type_to_first_level() = runTest {
        // 端到端（Fake 内存库）：脏二级分类被提升为一级，parent_id 保持 -1
        settingRepository.setTempKeys(
            TempKeysModel(db9To10DataMigrated = true, preferenceSplit = true),
        )
        typeRepository.addType(
            RecordTypeModel(
                id = 100L,
                parentId = -1L,
                name = "早餐",
                iconName = "icon_breakfast",
                typeLevel = TypeLevelEnum.SECOND,
                typeCategory = RecordTypeCategoryEnum.EXPENDITURE,
                protected = false,
                sort = 7000,
                needRelated = false,
            ),
        )

        useCase { }

        val healed = typeRepository.getRecordTypeById(100L)!!
        assertThat(healed.typeLevel).isEqualTo(TypeLevelEnum.FIRST)
        assertThat(healed.parentId).isEqualTo(-1L)
        assertThat(healed.name).isEqualTo("早餐")
    }

    @Test
    fun type_orphan_heal_failure_swallowed_and_gate_still_released() = runTest {
        // 失败隔离：自愈抛异常被吞、不阻塞首屏、不连累后续 orphanScan
        settingRepository.setTempKeys(
            TempKeysModel(db9To10DataMigrated = true, preferenceSplit = true),
        )
        typeRepository.healThrowable = RuntimeException("heal boom")
        var ready = false

        useCase { ready = true } // 不得抛

        assertThat(ready).isTrue()
        assertThat(typeRepository.healOrphanSecondLevelTypesCount).isEqualTo(1)
        assertThat(recordRepository.cleanupOrphanImageFilesCount).isEqualTo(1)
    }
}
