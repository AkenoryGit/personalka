package com.akenory.personalka.ui.clients

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.clients.ClientRow
import com.akenory.personalka.domain.format.ClientFormat
import com.akenory.personalka.domain.format.FollowupFormat
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.ui.components.ChoiceChip
import com.akenory.personalka.ui.components.EmptyState
import com.akenory.personalka.ui.components.SecondaryButton
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular
import kotlinx.datetime.LocalDate

const val CLIENT_SEARCH_TAG = "client-search"
const val CLIENT_ADD_TAG = "client-add"
const val CLIENT_SORT_TAG = "client-sort"
const val CLIENT_LIST_TAG = "client-list"
fun clientRowTag(name: String) = "client-row-$name"
fun filterChipTag(filter: ClientFilter) = "client-filter-${filter.name}"

/**
 * Список клиентов (5.8, макет ClList): поиск по фамилии и имени, «Активные · 42», «Без записи · 6» и «Архив»
 * чипами, редкие фильтры и сортировка — под значком в шапке, выбранный редкий фильтр — чипом с крестиком в начале
 * ряда. Строка: имя, возраст и постоянное расписание, справа — ближайшая тренировка.
 *
 * «Без записи» виден всегда (с шага 2.4), его содержимое — [noBooking] (макет ClNoBooking); [noBookingCount] —
 * число на чипе.
 */
@Composable
fun ClientList(
    state: ClientListState,
    scroll: LazyListState,
    rows: List<ClientRow>,
    today: LocalDate,
    selectedId: String?,
    onOpen: (String) -> Unit,
    onNew: (prefill: String) -> Unit,
    noBookingCount: Int,
    noBooking: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PersonalkaTheme.colors
    Column(modifier) {
        ListHeader(state, showMenu = rows.isNotEmpty(), onNew = { onNew("") })
        if (rows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 56.dp), contentAlignment = Alignment.Center) {
                // Макет ClEmpty. «Или введите имя прямо при создании тренировки» — когда появится форма тренировки (1.13).
                EmptyState(
                    icon = PIcons.Group,
                    title = "Пока нет клиентов",
                    text = "Добавьте первого клиента — его карточка появится в этом списке.",
                    actionLabel = "Добавить клиента",
                    onAction = { onNew("") },
                )
            }
            return@Column
        }

        val active = rows.count { it.client.status == ClientStatus.ACTIVE }
        val archived = rows.size - active
        // В архиве никого — фильтр «Архив» не нужен; если его выбрали, а архив опустел, — снова «Активные».
        LaunchedEffect(archived) { if (archived == 0 && state.filter == ClientFilter.ARCHIVE) state.filter = ClientFilter.ACTIVE }
        val visible = state.visible(rows, today)

        SearchField(
            value = state.query,
            onValueChange = { state.query = it },
            placeholder = "Фамилия или имя",
            modifier = Modifier.padding(horizontal = Dimens.space4).testTag(CLIENT_SEARCH_TAG),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Dimens.space4, vertical = Dimens.space1),
            horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
        ) {
            if (state.age != AgeFilter.ALL) RemovableChip(state.age.label, onRemove = { state.age = AgeFilter.ALL })
            ChoiceChip(
                text = "Активные · $active",
                selected = state.filter == ClientFilter.ACTIVE,
                onClick = { state.filter = ClientFilter.ACTIVE },
                modifier = Modifier.testTag(filterChipTag(ClientFilter.ACTIVE)),
            )
            ChoiceChip(
                text = FollowupFormat.chip(noBookingCount),
                selected = state.filter == ClientFilter.NO_BOOKING,
                onClick = { state.filter = ClientFilter.NO_BOOKING },
                modifier = Modifier.testTag(filterChipTag(ClientFilter.NO_BOOKING)),
            )
            if (archived > 0) {
                ChoiceChip(
                    text = "Архив",
                    selected = state.filter == ClientFilter.ARCHIVE,
                    onClick = { state.filter = ClientFilter.ARCHIVE },
                    modifier = Modifier.testTag(filterChipTag(ClientFilter.ARCHIVE)),
                )
            }
        }
        if (state.filter == ClientFilter.NO_BOOKING) {
            noBooking(Modifier.weight(1f).fillMaxWidth())
            return@Column
        }
        Text(
            text = "${ClientFormat.clients(visible.size)} · ${state.sort.label}",
            modifier = Modifier.padding(horizontal = Dimens.space4, vertical = Dimens.space2),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
        )

        if (visible.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(Dimens.space4),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Dimens.space3),
            ) {
                Text("Никого не нашлось", style = MaterialTheme.typography.bodyLarge, color = colors.textMuted)
                if (state.query.isNotBlank()) {
                    SecondaryButton("Новый клиент: ${state.query.trim()}", { onNew(state.query) }, icon = PIcons.PersonAdd)
                }
            }
            return@Column
        }

        LazyColumn(
            state = scroll,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag(CLIENT_LIST_TAG),
            contentPadding = PaddingValues(start = Dimens.space4, end = Dimens.space4, bottom = Dimens.space6),
        ) {
            itemsIndexed(visible, key = { _, row -> row.client.id }) { index, row ->
                val next = row.next
                val shape = when {
                    visible.size == 1 -> MaterialTheme.shapes.medium
                    index == 0 -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                    index == visible.lastIndex -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                    else -> RoundedCornerShape(0.dp)
                }
                Column(Modifier.clip(shape).background(colors.surface)) {
                    if (index > 0) HorizontalDivider(Modifier.padding(start = Dimens.space4), thickness = 1.dp, color = colors.divider)
                    PersonRow(
                        title = row.client.let { "${it.lastName} ${it.firstName}" },
                        subtitle = row.subtitle(today),
                        selected = row.client.id == selectedId,
                        onClick = { onOpen(row.client.id) },
                        modifier = Modifier.testTag(clientRowTag("${row.client.lastName} ${row.client.firstName}")),
                        trailing = if (next != null) {
                            {
                                Text(
                                    ClientFormat.next(next, today),
                                    style = MaterialTheme.typography.bodySmall.tabular(),
                                    color = colors.text,
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

/** Шапка списка: заголовок, «Сортировка и фильтры» с меню и «Новый клиент». */
@Composable
private fun ListHeader(state: ClientListState, showMenu: Boolean, onNew: () -> Unit) {
    val colors = PersonalkaTheme.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(Dimens.topBar).padding(start = Dimens.space4, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Клиенты",
            modifier = Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            color = colors.text,
        )
        if (showMenu) {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag(CLIENT_SORT_TAG)) {
                    Icon(PIcons.Tune, contentDescription = "Сортировка и фильтры", tint = colors.text)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = colors.surface) {
                    MenuLabel("Сортировка")
                    ClientSort.entries.forEach { sort ->
                        MenuChoice(sort.label.replaceFirstChar { it.uppercaseChar() }, state.sort == sort) {
                            state.sort = sort
                            menu = false
                        }
                    }
                    HorizontalDivider(color = colors.divider)
                    MenuLabel("Показать")
                    AgeFilter.entries.forEach { age ->
                        MenuChoice(age.label, state.age == age) {
                            state.age = age
                            menu = false
                        }
                    }
                }
            }
        }
        IconButton(onClick = onNew, modifier = Modifier.testTag(CLIENT_ADD_TAG)) {
            Icon(PIcons.PersonAdd, contentDescription = "Новый клиент", tint = colors.text)
        }
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = Dimens.space4, vertical = Dimens.space2),
        style = MaterialTheme.typography.labelMedium,
        color = PersonalkaTheme.colors.textMuted,
    )
}

/** Пункт меню с выбором: галочка справа у выбранного. */
@Composable
internal fun MenuChoice(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = PersonalkaTheme.colors
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.text) },
        onClick = onClick,
        modifier = Modifier.semantics { this.selected = selected },
        trailingIcon = if (selected) {
            { Icon(PIcons.Check, contentDescription = null, tint = colors.text) }
        } else {
            null
        },
    )
}

/** Пункт меню «…» со значком: «Изменить», «Убрать связь». [danger] — красным: «Удалить полностью». */
@Composable
internal fun MenuAction(text: String, icon: ImageVector, onClick: () -> Unit, danger: Boolean = false) {
    val color = if (danger) PersonalkaTheme.colors.error else PersonalkaTheme.colors.text
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyLarge, color = color) },
        onClick = onClick,
        leadingIcon = { Icon(icon, contentDescription = null, tint = color) },
    )
}

/** Выбранный редкий фильтр в начале ряда чипов: «Дети ✕». */
@Composable
private fun RemovableChip(text: String, onRemove: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Box(Modifier.heightIn(min = Dimens.touch), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .height(Dimens.chip)
                .clip(MaterialTheme.shapes.small)
                .background(colors.primary)
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "Убрать фильтр «$text»" }
                .padding(start = Dimens.space3, end = Dimens.space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text, style = MaterialTheme.typography.labelMedium, color = colors.onPrimary)
            Icon(PIcons.Close, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(18.dp))
        }
    }
}

/** Поле поиска (дизайн-система, Search): подложка surface-alt, лупа слева, крестик — очистить. */
@Composable
internal fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val colors = PersonalkaTheme.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = Dimens.button).semantics { contentDescription = placeholder },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.text),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.button)
                    .background(colors.surfaceAlt, MaterialTheme.shapes.medium)
                    .padding(start = Dimens.space3, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PIcons.Search, contentDescription = null, tint = colors.textMuted)
                Spacer(Modifier.width(Dimens.space3))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.textMuted)
                    inner()
                }
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(PIcons.Close, contentDescription = "Очистить", tint = colors.textMuted)
                    }
                }
            }
        },
    )
}

/**
 * Строка человека в списках раздела: имя, строка под ним, справа — дата, значок или ничего. Выбранная — на
 * подложке surface-alt с полосой слева (открытый клиент на разложенном Fold, выбранный родственник).
 */
@Composable
internal fun PersonRow(
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    muted: Boolean = false,
    label: String? = null,
    leading: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = PersonalkaTheme.colors
    val bar = colors.primary
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.row)
            .background(if (selected) colors.surfaceAlt else colors.surface)
            .drawBehind { if (selected) drawRect(bar, size = Size(3.dp.toPx(), size.height)) }
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics { this.selected = selected }
            .padding(horizontal = Dimens.space4, vertical = Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = if (muted) colors.textMuted else colors.text)
            Spacer(Modifier.width(Dimens.space4))
        }
        Column(Modifier.weight(1f)) {
            if (label != null) Text(label, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = if (muted) colors.textMuted else colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Dimens.space3))
            trailing()
        }
    }
}
