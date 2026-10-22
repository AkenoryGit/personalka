package com.akenory.personalka.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular

/** Высота нижней панели (дизайн-система, BottomNav). */
internal val NavBarHeight: Dp = 72.dp

/** Ширина боковой панели на разложенном Fold (макеты «Разложенный Fold» шагов 0.6). */
internal val RailWidth: Dp = 80.dp

/** Ширина, с которой экран считается широким: разложенный Fold и планшет (ТЗ 5.1). */
val WideFrom: Dp = 600.dp

internal fun navTag(section: Section) = "nav-${section.name}"

/**
 * Нижняя панель на обычном и сложенном экране (ТЗ 5.1): пять разделов поровну, фон `surface`,
 * сверху линия `divider`. Фон уходит под полосу жестов системы, кнопки — над ней.
 */
@Composable
internal fun BottomNav(
    selected: Section,
    todoCount: Int,
    insets: PaddingValues,
    onSelect: (Section) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PersonalkaTheme.colors
    Column(modifier.fillMaxWidth().background(colors.surface)) {
        HorizontalDivider(thickness = 1.dp, color = colors.divider)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(insets)
                .height(NavBarHeight - 1.dp)
                .selectableGroup(),
        ) {
            Section.entries.forEach { section ->
                NavItem(
                    section = section,
                    selected = section == selected,
                    badge = if (section == Section.HOME) todoCount else 0,
                    onClick = { onSelect(section) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

/**
 * Боковая панель на разложенном Fold (ТЗ 5.1): те же разделы столбиком слева, фон экрана и линия справа.
 */
@Composable
internal fun NavRail(
    selected: Section,
    todoCount: Int,
    insets: PaddingValues,
    onSelect: (Section) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PersonalkaTheme.colors
    Row(modifier.fillMaxHeight().background(colors.bg)) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(insets)
                .padding(top = 12.dp)
                .width(RailWidth - 1.dp)
                .selectableGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Section.entries.forEach { section ->
                NavItem(
                    section = section,
                    selected = section == selected,
                    badge = if (section == Section.HOME) todoCount else 0,
                    onClick = { onSelect(section) },
                    modifier = Modifier.width(RailWidth - 1.dp).height(64.dp),
                )
            }
        }
        VerticalDivider(thickness = 1.dp, color = colors.divider)
    }
}

/**
 * Раздел: значок над подписью. Выбранный — залитый значок на плашке 56 × 32 `surfaceAlt`, подпись `text`;
 * остальные — контурный значок и подпись `textMuted`. У «Главной» — оранжевый счётчик дел (одно из пяти
 * мест фирменного оранжевого), больше 99 — «99+», ноль не показывается.
 */
@Composable
private fun NavItem(
    section: Section,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PersonalkaTheme.colors
    val tint = if (selected) colors.text else colors.textMuted
    Column(
        modifier = modifier
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .testTag(navTag(section)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 32.dp)
                .background(if (selected) colors.surfaceAlt else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (selected) section.selectedIcon else section.icon,
                contentDescription = null,
                tint = tint,
            )
            if (badge > 0) {
                val text = if (badge > 99) "99+" else badge.toString()
                Text(
                    text = text,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = 32.dp, y = (-2).dp)
                        .heightIn(min = 20.dp)
                        .widthIn(min = 20.dp)
                        .background(colors.accent, CircleShape)
                        .padding(horizontal = 5.dp)
                        .clearAndSetSemantics { contentDescription = "Нужно сделать: $badge" },
                    style = MaterialTheme.typography.labelMedium.tabular(),
                    color = colors.onAccent,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Text(
            text = section.title,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
