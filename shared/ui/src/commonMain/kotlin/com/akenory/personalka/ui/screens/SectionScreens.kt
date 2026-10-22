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
import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.groups.GroupRepository
import com.akenory.personalka.data.prefs.DevicePrefs
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.ui.calendar.CalendarScreen
import com.akenory.personalka.ui.calendar.TRAINING_CARD_LATER
import com.akenory.personalka.ui.clients.ClientsScreen
import com.akenory.personalka.ui.components.EmptyState
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.groups.GroupRoute
import com.akenory.personalka.ui.groups.GroupsScreen
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.settings.SettingsRoute
import com.akenory.personalka.ui.settings.SettingsScreen
import com.akenory.personalka.ui.shell.LocalWide
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.ShellState
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.LocalDateTime

/**
 * Экраны разделов. С шага 1.5 — заготовки: заголовок и пустой экран с тем, в каком шаге раздел появится.
 * Каждый следующий шаг заменяет свою заготовку настоящим экраном: «Ещё → Настройки» — с шага 1.6,
 * «Клиенты» — с шага 1.7, «Ещё → Группы» — с шага 1.8, «Календарь» — с шага 1.9.
 *
 * @param now «сейчас» по клубу: от него сегодняшняя дата, возраст и ближайшие тренировки.
 * @param sample показать в календаре пример дня — до шага 1.13.
 */
@Composable
fun SectionScreen(
    section: Section,
    shell: ShellState,
    now: LocalDateTime,
    versionName: String,
    settings: SettingsRepository,
    clients: ClientRepository,
    groups: GroupRepository,
    calendar: CalendarRepository,
    prefs: DevicePrefs,
    sample: Boolean,
    messages: MessageState,
    scope: CoroutineScope,
) {
    val today = now.date
    when (section) {
        Section.HOME -> Placeholder(
            title = DateFormat.header(today),
            icon = PIcons.Home,
            heading = "Здесь будет ближайшая тренировка",
            text = "И список «Нужно сделать». Появится в шаге 1.16.",
        )
        Section.CALENDAR -> CalendarScreen(calendar, prefs, now, sample, messages, scope, onOpen = { messages.show(TRAINING_CARD_LATER) })
        Section.CLIENTS -> ClientsScreen(shell, clients, messages, scope, now)
        Section.SALARY -> Placeholder(
            title = "Зарплата",
            icon = PIcons.Payments,
            heading = "Здесь будут заработок и выплаты",
            text = "Месяц, выплаты, коллеги и год — этап 3.",
        )
        Section.MORE -> MoreScreen(shell, versionName) { route, onBack ->
            when {
                SettingsRoute.isSettings(route) -> SettingsScreen(route, shell, settings, messages, scope, onBack)
                GroupRoute.isGroups(route) -> GroupsScreen(route, shell, groups, clients, settings, messages, scope, now, onBack)
                else -> MoreItem.entries.firstOrNull { it.route == route }?.let { MoreDetail(it, onBack) }
            }
        }
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
    // У «Групп» с шага 1.8 свой экран (ui/groups), заготовка не показывается.
    GROUPS("groups", "Группы", PIcons.Groups, "Группы", "Состав с датами."),
    SLOTS("slots", "Свободные слоты", PIcons.EventAvailable, "Здесь будут свободные окна", "Поиск окон за период — шаг 4.3."),
    RECONCILE("reconcile", "Сверка с 1С", PIcons.FactCheck, "Здесь будет сверка с 1С", "Отчёт клуба против календаря — шаги 4.1 и 4.2."),
    // У «Настроек» с шага 1.6 свой экран (ui/settings), заготовка не показывается.
    SETTINGS("settings", "Настройки", PIcons.Settings, "Настройки", "Профиль, места и типы."),
    // «Клуб» — только администратору (ТЗ 5.1). Администратор бывает только с сервером (этап 2),
    // а выключенное не показывается (10.7), поэтому пункта пока нет.
}

internal fun moreRowTag(item: MoreItem) = "more-${item.route}"

/**
 * «Ещё»: на узком экране — список, пункт открывается поверх с «Назад»; на широком — список слева
 * и открытый пункт справа (ТЗ 5.1). У пункта бывают вложенные экраны («Настройки» → «Места и типы» →
 * тип): справа виден верхний, «Назад» ведёт к предыдущему.
 *
 * Открытый экран при сложении и разложении стоит на одном месте дерева — в правой части ряда, а список
 * слева то появляется, то нет. Поэтому форма с недописанным текстом переживает сложение, как и прокрутка
 * списка, поднятая над веткой «широкий / узкий».
 */
@Composable
private fun MoreScreen(
    shell: ShellState,
    versionName: String,
    detail: @Composable (route: String, onBack: (() -> Unit)?) -> Unit,
) {
    val wide = LocalWide.current
    val listScroll = rememberScrollState()
    val stack = shell.stack(Section.MORE)
    val root = MoreItem.entries.firstOrNull { it.route == stack.firstOrNull() }
    val top = stack.lastOrNull()
    val back: () -> Unit = { shell.back() }

    Row(Modifier.fillMaxSize()) {
        if (wide || top == null) {
            MoreList(
                selected = root,
                scroll = listScroll,
                versionName = versionName,
                onOpen = { if (wide) shell.reset(it.route) else shell.open(it.route) },
                modifier = if (wide) Modifier.width(360.dp).fillMaxHeight() else Modifier.weight(1f).fillMaxHeight(),
            )
            if (wide) VerticalDivider(thickness = 1.dp, color = PersonalkaTheme.colors.divider)
        }
        if (top != null) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                // На широком у первого экрана пункта «Назад» нет: список и так слева.
                detail(top, if (!wide || stack.size > 1) back else null)
            }
        }
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
