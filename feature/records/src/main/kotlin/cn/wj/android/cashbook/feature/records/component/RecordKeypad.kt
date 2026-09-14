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

package cn.wj.android.cashbook.feature.records.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.wj.android.cashbook.core.design.component.CbIconButton
import cn.wj.android.cashbook.core.design.component.CbTextButton
import cn.wj.android.cashbook.core.design.icon.CbIcons
import cn.wj.android.cashbook.core.design.util.CalculatorUtils
import cn.wj.android.cashbook.core.ui.R

/** 单键高度 */
private val KeyHeight = 48.dp

/** 确认键跨占行数 */
private const val ConfirmKeyRows = 2

/**
 * 添加账单页常驻数字键盘
 *
 * 固定于页面底部（不再以底部抽屉形态弹出）；按键行为复用 [CalculatorUtils]，
 * 顶部显示当前编辑目标与表达式，表达式需要求值时「保存」键显示为「＝」；
 * 「再记」键保存后不退出页面，继续记下一笔。
 *
 * @param targetLabel 当前编辑目标名称（金额 / 手续费 / 优惠）
 * @param expression 当前表达式或金额文本
 * @param primaryColor 主色调
 * @param onExpressionChange 表达式变化回调
 * @param onSaveClick 保存回调（表达式无需再求值时触发）
 * @param onSaveAgainClick 再记回调：保存后不退出页面继续记账（表达式无需再求值时触发）
 *
 * > [王杰](mailto:15555650921@163.com) 创建于 2026/9/14
 */
@Composable
internal fun RecordKeypad(
    targetLabel: String,
    expression: String,
    primaryColor: Color,
    onExpressionChange: (String) -> Unit,
    onSaveClick: () -> Unit,
    onSaveAgainClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(color = MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        // 当前编辑目标 + 表达式
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = targetLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = expression.ifBlank { "0" },
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                color = primaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            // 第 1 列：C 1 4 7 再记
            Column(modifier = Modifier.weight(1f)) {
                KeypadButton(text = "C", onClick = { onExpressionChange("0") })
                KeypadButton(
                    text = "1",
                    onClick = { onExpressionChange(CalculatorUtils.onNumberClick(expression, "1")) },
                )
                KeypadButton(
                    text = "4",
                    onClick = { onExpressionChange(CalculatorUtils.onNumberClick(expression, "4")) },
                )
                KeypadButton(
                    text = "7",
                    onClick = { onExpressionChange(CalculatorUtils.onNumberClick(expression, "7")) },
                )
                // 再记：保存后不退出页面继续记账（表达式待求值时先求值，与「=」键一致）
                KeypadButton(
                    text = stringResource(id = R.string.record_save_again),
                    onClick = {
                        if (CalculatorUtils.needShowEqualSign(expression)) {
                            onExpressionChange(CalculatorUtils.onEqualsClick(expression))
                        } else {
                            onSaveAgainClick()
                        }
                    },
                )
            }
            // 第 2 列：÷ 2 5 8 0
            Column(modifier = Modifier.weight(1f)) {
                KeypadButton(
                    text = "÷",
                    onClick = { onExpressionChange(CalculatorUtils.onComputeSignClick(expression, "÷")) },
                )
                KeypadButton(
                    text = "2",
                    onClick = { onExpressionChange(CalculatorUtils.onNumberClick(expression, "2")) },
                )
                KeypadButton(
                    text = "5",
                    onClick = { onExpressionChange(CalculatorUtils.onNumberClick(expression, "5")) },
                )
                KeypadButton(
                    text = "8",
                    onClick = { onExpressionChange(CalculatorUtils.onNumberClick(expression, "8")) },
                )
                KeypadButton(
                    text = "0",
                    onClick = { onExpressionChange(CalculatorUtils.onNumberClick(expression, "0")) },
                )
            }
            // 第 3 列：× 3 6 9 .
            Column(modifier = Modifier.weight(1f)) {
                KeypadButton(
                    text = "×",
                    onClick = { onExpressionChange(CalculatorUtils.onComputeSignClick(expression, "×")) },
                )
                KeypadButton(
                    text = "3",
                    onClick = { onExpressionChange(CalculatorUtils.onNumberClick(expression, "3")) },
                )
                KeypadButton(
                    text = "6",
                    onClick = { onExpressionChange(CalculatorUtils.onNumberClick(expression, "6")) },
                )
                KeypadButton(
                    text = "9",
                    onClick = { onExpressionChange(CalculatorUtils.onNumberClick(expression, "9")) },
                )
                KeypadButton(
                    text = ".",
                    onClick = { onExpressionChange(CalculatorUtils.onPointClick(expression)) },
                )
            }
            // 第 4 列：⌫ - + 保存(=)
            Column(modifier = Modifier.weight(1f)) {
                CbIconButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(KeyHeight),
                    onClick = { onExpressionChange(CalculatorUtils.onBackPressed(expression)) },
                ) {
                    Icon(
                        imageVector = CbIcons.Backspace,
                        contentDescription = stringResource(
                            id = cn.wj.android.cashbook.core.design.R.string.cd_design_backspace,
                        ),
                    )
                }
                KeypadButton(
                    text = "-",
                    onClick = { onExpressionChange(CalculatorUtils.onComputeSignClick(expression, "-")) },
                )
                KeypadButton(
                    text = "+",
                    onClick = { onExpressionChange(CalculatorUtils.onComputeSignClick(expression, "+")) },
                )
                CbTextButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(KeyHeight * ConfirmKeyRows)
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .background(color = primaryColor, shape = RoundedCornerShape(8.dp)),
                    onClick = {
                        if (CalculatorUtils.needShowEqualSign(expression)) {
                            onExpressionChange(CalculatorUtils.onEqualsClick(expression))
                        } else {
                            onSaveClick()
                        }
                    },
                ) {
                    Text(
                        text = if (CalculatorUtils.needShowEqualSign(expression)) {
                            "="
                        } else {
                            stringResource(id = R.string.save)
                        },
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

/** 数字键盘普通按键 */
@Composable
private fun KeypadButton(text: String, onClick: () -> Unit) {
    CbTextButton(
        modifier = Modifier
            .fillMaxWidth()
            .height(KeyHeight),
        onClick = onClick,
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium)
    }
}
