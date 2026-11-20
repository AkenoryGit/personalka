package com.akenory.personalka.ui.shell

import androidx.compose.foundation.border
import androidx.compose.ui.zIndex
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.akenory.personalka.ui.theme.insetLevel
import com.akenory.personalka.ui.theme.raised
import com.akenory.personalka.ui.theme.tabular

/** Место под нижней панелью: сама панель 70 и отступ 10 под ней (шаг 5.13а — панель плавает). */
internal val NavBarHeight: Dp = 80.dp

/** Высота самой плавающей панели. */
private val NavPanelHeight: Dp = 70.dp

/** Ширина боковой панели на разложенном Fold (макеты «Разложенный Fold» шагов 0.6). */
internal val RailWidth: Dp = 80.dp

/** Ширина, с которой экран считается широким: разложенный Fold и планшет (ТЗ 5.1). */
val WideFrom: Dp = 600.dp

internal fun navTag(section: Section) = "nav-${section.name}"

/**
 * Нижняя панель на обычном и сложенном экране (ТЗ 5.1, стиль «Сияние» — шаг 5.13а): плавает над экраном
 * с отступом 10 по бокам и снизу, скругление 22, уровень «сверху». Пять разделов поровну; под панелью — полоса
 * жестов системы, панель стоит над ней.
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
    val shape = RoundedCornerShape(22.dp)
    Box(modifier.fillMaxWidth().padding(insets).padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(NavPanelHeight)
                .raised(shape, colors, elevation = 12)
                .background(colors.nav)
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
    Row(modifier.fillMaxHeight()) {
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
 * Раздел: значок над подписью. Выбранный — залитый значок во вдавленной плашке 56 × 32 (шаг 5.13а), подпись `text`;
 * остальные — контурный значок и подпись `textMuted`. У «Главной» — коралловый счётчик дел, больше 99 — «99+»,
 * ноль не показывается.
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
        // Шаг 5.21 (просьба Дмитрия 20.11.2026): вдавлена только подложка выбранного раздела, счётчик дел лежит поверх
        // неё на уровне «сверху» — с тенью и светлой каймой, и вдавление его не обрезает.
        Box(Modifier.size(width = 56.dp, height = 32.dp)) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .then(if (selected) Modifier.insetLevel(CircleShape, colors) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (selected) section.selectedIcon else section.icon,
                    contentDescription = null,
                    tint = tint,
                )
            }
            if (badge > 0) {
                val text = if (badge > 99) "99+" else badge.toString()
                Text(
                    text = text,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = 32.dp, y = (-8).dp)
                        .zIndex(1f)
                        .raised(CircleShape, colors, elevation = 4)
                        .border(1.5.dp, colors.nav, CircleShape)
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
