package com.akenory.personalka.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme

// Элементы форм дизайн-системы 0.2 (Button, Chip, TextField, Toggles): первыми их собрали первый запуск
// и конструктор типа (шаг 1.6), дальше ими пользуются все формы.

/** Главная кнопка экрана: графит на кремовом, высота 48, скругление 12. Одна на экран. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = PersonalkaTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = Dimens.button),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.primary,
            contentColor = colors.onPrimary,
            // Недоступная: surface-alt и text-muted (4,6 : 1).
            disabledContainerColor = colors.surfaceAlt,
            disabledContentColor = colors.textMuted,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Вторая кнопка: заливка surface-alt, значок слева — «+ Место», «+ Новый тип». */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val colors = PersonalkaTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = Dimens.button),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceAlt, contentColor = colors.text),
        elevation = null,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Dimens.space2))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Контурная кнопка для редкого действия рядом с главной: «Убрать в архив». */
@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = PersonalkaTheme.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = Dimens.button),
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Низ формы: тонкая линия и главная кнопка во всю ширину. */
@Composable
fun BottomAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, hint: String? = null) {
    val colors = PersonalkaTheme.colors
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = colors.divider)
        if (hint != null) {
            Text(
                text = hint,
                modifier = Modifier.padding(start = Dimens.space4, end = Dimens.space4, top = Dimens.space3),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
        PrimaryButton(
            text = text,
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(Dimens.space4),
        )
    }
}

/** Подпись раздела формы: «Места», «Как работает», «Цвет». */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(top = Dimens.space5, bottom = Dimens.space2),
        style = MaterialTheme.typography.labelMedium,
        color = PersonalkaTheme.colors.textMuted,
    )
}

/**
 * Поле ввода с подписью над ним (TextField): высота 56, фон surface, рамка 1 dp outline, в фокусе — 2 dp primary.
 * Ошибка — рамка и строка под полем цветом error. [placeholder] — серым внутри пустого поля: «Необязательно».
 * Многострочное ([singleLine] = `false`) растёт вниз по тексту — план тренировки.
 */
@Composable
fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    hint: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    tag: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
) {
    val colors = PersonalkaTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border = when {
        error != null -> colors.error
        focused -> colors.primary
        else -> colors.outline
    }
    Column(modifier) {
        Text(
            text = label,
            modifier = Modifier.padding(bottom = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textMuted,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.field)
                .semantics {
                    contentDescription = label
                    if (error != null) this.error(error)
                }
                .then(if (tag != null) Modifier.testTag(tag) else Modifier),
            singleLine = singleLine,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.text),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = keyboardOptions,
            interactionSource = interaction,
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.field)
                        .background(colors.surface, MaterialTheme.shapes.medium)
                        .border(if (focused || error != null) 2.dp else 1.dp, border, MaterialTheme.shapes.medium)
                        .padding(horizontal = Dimens.space4, vertical = if (singleLine) 0.dp else Dimens.space4),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (placeholder != null && value.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.textMuted)
                    }
                    inner()
                }
            },
        )
        val below = error ?: hint
        if (below != null) {
            Text(
                text = below,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) colors.error else colors.textMuted,
            )
        }
    }
}

/**
 * Чип (Chip): высота 36, зона нажатия 48. Выбранный — primary с галочкой, невыбранный — surface-alt;
 * у типа до выбора — точка его цвета. Узкие чипы в ряд (дни недели) — без галочки и во всю свою ширину ([fill]).
 */
@Composable
fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dot: Color? = null,
    icon: ImageVector? = null,
    toggle: Boolean = false,
    fill: Boolean = false,
) {
    val colors = PersonalkaTheme.colors
    val shape = MaterialTheme.shapes.small
    val select = if (toggle) {
        Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
    } else {
        Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
    }
    Box(modifier.heightIn(min = Dimens.touch), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .then(if (fill) Modifier.fillMaxWidth() else Modifier)
                .height(Dimens.chip)
                .clip(shape)
                .background(if (selected) colors.primary else colors.surfaceAlt, shape)
                .then(select)
                .padding(horizontal = if (fill) 0.dp else Dimens.space3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (fill) Arrangement.Center else Arrangement.spacedBy(6.dp),
        ) {
            val content = if (selected) colors.onPrimary else colors.text
            when {
                fill -> Unit
                selected -> Icon(PIcons.Check, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
                icon != null -> Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
                dot != null -> Box(Modifier.size(8.dp).background(dot, CircleShape))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, color = content)
        }
    }
}

/** Флажок (Toggles): 20 dp, скругление 4; отмечен — primary с галочкой, пустой — рамка 2 dp outline. */
@Composable
fun CheckBox(checked: Boolean, modifier: Modifier = Modifier) {
    val colors = PersonalkaTheme.colors
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .size(20.dp)
            .then(
                if (checked) Modifier.background(colors.primary, shape)
                else Modifier.border(2.dp, colors.outline, shape),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(PIcons.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(16.dp))
    }
}

/** Строка с флажком: нажимается вся строка, высота 56, подпись и пояснение под ней. */
@Composable
fun CheckRow(title: String, checked: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle)
            .padding(vertical = Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckBox(checked, Modifier.padding(start = 2.dp))
        Spacer(Modifier.width(Dimens.space5))
        RowText(title, subtitle, Modifier.weight(1f))
    }
}

/** Строка с переключателем (Toggles): настройка в форме, 52 × 32, нажимается вся строка. */
@Composable
fun SwitchRow(title: String, checked: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null) {
    val colors = PersonalkaTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onToggle)
            .padding(vertical = Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowText(title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(Dimens.space4))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onPrimary,
                checkedTrackColor = colors.primary,
                checkedBorderColor = colors.primary,
                uncheckedThumbColor = colors.outline,
                uncheckedTrackColor = colors.surfaceAlt,
                uncheckedBorderColor = colors.outline,
            ),
        )
    }
}

@Composable
private fun RowText(title: String, subtitle: String?, modifier: Modifier) {
    val colors = PersonalkaTheme.colors
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.text)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
    }
}

/** Выбор одного из трёх-четырёх вариантов в ряд: «За тренировку · За клиента · За час». */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val colors = PersonalkaTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.touch)
            .background(colors.surfaceAlt, MaterialTheme.shapes.medium)
            .padding(4.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) colors.primary else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) colors.onPrimary else colors.text,
                )
            }
        }
    }
}

/** Пояснение на подложке surface-alt со значком «i»: что будет и где это поправить. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    val colors = PersonalkaTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceAlt, MaterialTheme.shapes.medium)
            .padding(Dimens.space3),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
    ) {
        Icon(PIcons.Info, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = colors.text)
    }
}

/** Карточка-список на surface со скруглением 12: строки разделены тонкой линией. */
@Composable
fun ListCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(PersonalkaTheme.colors.surface),
    ) { content() }
}

/** Тонкая линия между строками карточки, с отступом слева. */
@Composable
fun RowDivider(start: androidx.compose.ui.unit.Dp = Dimens.space4) {
    HorizontalDivider(Modifier.padding(start = start), thickness = 1.dp, color = PersonalkaTheme.colors.divider)
}

/** Строка, которая открывает экран: подпись, пояснение и стрелка справа. */
@Composable
fun NavRow(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, leading: (@Composable () -> Unit)? = null) {
    val colors = PersonalkaTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Dimens.space4, vertical = Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(Dimens.space4))
        }
        RowText(title, subtitle, Modifier.weight(1f))
        Icon(PIcons.ChevronRight, contentDescription = null, tint = colors.textMuted)
    }
}
