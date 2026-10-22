package com.akenory.personalka.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.ui.components.EmptyState
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.shell.LocalWide
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.ShellState
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular
import kotlinx.datetime.LocalDate

/**
 * Экраны разделов на шаге 1.5 — заготовки: заголовок и пустой экран с тем, в каком шаге раздел появится.
 * Каждый следующий шаг заменяет свою заготовку настоящим экраном.
 */
@Composable
fun SectionScreen(section: Section, shell: ShellState, today: LocalDate, versionName: String) {
    when (section) {
        Section.HOME -> Placeholder(
            title = DateFormat.header(today),
            icon = PIcons.Home,
            heading = "Здесь будет ближайшая тренировка",
            text = "И список «Нужно сделать». Появится в шаге 1.16.",
        )
        Section.CALENDAR -> Placeholder(
            title = DateFormat.monthYear(today),
            icon = PIcons.CalendarMonth,
            heading = "Здесь будет календарь",
            text = "День и неделя — шаг 1.9, месяц и список — шаг 1.12.",
        )
        Section.CLIENTS -> Placeholder(
            title = "Клиенты",
            icon = PIcons.Group,
            heading = "Здесь будет список клиентов",
            text = "С поиском и карточкой клиента. Появится в шаге 1.7.",
        )
        Section.SALARY -> Placeholder(
            title = "Зарплата",
            icon = PIcons.Payments,
            heading = "Здесь будут заработок и выплаты",
            text = "Месяц, выплаты, коллеги и год — этап 3.",
        )
        Section.MORE -> MoreScreen(shell, versionName)
    }
}

/** Пункты «Ещё» (ТЗ 5.1). Экран в стопке раздела называется [route]. */
enum class MoreItem(
    val route: String,
    val title: String,
    val icon: ImageVector,
    val heading: String,
    val text: String,
) {
    GROUPS("groups", "Группы", PIcons.Groups, "Здесь будут группы", "Состав по датам и дети «на раз» — шаг 1.8."),
    SLOTS("slots", "Свободные слоты", PIcons.EventAvailable, "Здесь будут свободные окна", "Поиск окон за период — шаг 4.3."),
    RECONCILE("reconcile", "Сверка с 1С", PIcons.FactCheck, "Здесь будет сверка с 1С", "Отчёт клуба против календаря — шаги 4.1 и 4.2."),
    SETTINGS("settings", "Настройки", PIcons.Settings, "Здесь будут настройки", "Профиль, места и типы — шаг 1.6, деньги — шаг 3.6."),
    // «Клуб» — только администратору (ТЗ 5.1). Администратор бывает только с сервером (этап 2),
    // а выключенное не показывается (10.7), поэтому пункта пока нет.
}

internal fun moreRowTag(item: MoreItem) = "more-${item.route}"

/**
 * «Ещё»: на узком экране — список, пункт открывается поверх с «Назад»; на широком — список слева
 * и открытый пункт справа (ТЗ 5.1). Прокрутка списка поднята над веткой «широкий / узкий» и переживает
 * сложение и разложение.
 */
@Composable
private fun MoreScreen(shell: ShellState, versionName: String) {
    val wide = LocalWide.current
    val listScroll = rememberScrollState()
    val open = MoreItem.entries.firstOrNull { it.route == shell.top(Section.MORE) }

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            MoreList(
                selected = open,
                scroll = listScroll,
                versionName = versionName,
                onOpen = { shell.replaceTop(it.route) },
                modifier = Modifier.width(360.dp).fillMaxHeight(),
            )
            VerticalDivider(thickness = 1.dp, color = PersonalkaTheme.colors.divider)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (open != null) MoreDetail(open, onBack = null)
            }
        }
    } else if (open == null) {
        MoreList(
            selected = null,
            scroll = listScroll,
            versionName = versionName,
            onOpen = { shell.open(it.route) },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        MoreDetail(open, onBack = { shell.back() })
    }
}

@Composable
private fun MoreList(
    selected: MoreItem?,
    scroll: ScrollState,
    versionName: String,
    onOpen: (MoreItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PersonalkaTheme.colors
    Column(modifier.verticalScroll(scroll)) {
        TopBar("Ещё")
        MoreItem.entries.forEach { item ->
            val isSelected = item == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .background(if (isSelected) colors.surfaceAlt else Color.Transparent)
                    .clickable(role = Role.Button) { onOpen(item) }
                    .semantics { this.selected = isSelected }
                    .testTag(moreRowTag(item))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(item.icon, contentDescription = null, tint = colors.textMuted)
                Spacer(Modifier.width(16.dp))
                Text(
                    text = item.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.text,
                )
                Icon(PIcons.ChevronRight, contentDescription = null, tint = colors.textMuted)
            }
        }
        Text(
            text = "Версия $versionName",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall.tabular(),
            color = colors.textMuted,
        )
    }
}

@Composable
private fun MoreDetail(item: MoreItem, onBack: (() -> Unit)?) {
    Placeholder(title = item.title, icon = item.icon, heading = item.heading, text = item.text, onBack = onBack)
}

/** Заготовка раздела: верхняя панель и пустой экран по центру. */
@Composable
private fun Placeholder(
    title: String,
    icon: ImageVector,
    heading: String,
    text: String,
    onBack: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title, onBack = onBack)
        Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 56.dp), contentAlignment = Alignment.Center) {
            EmptyState(icon = icon, title = heading, text = text)
        }
    }
}
