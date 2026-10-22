package com.akenory.personalka.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.akenory.personalka.ui.icons.PIcons

/** Пять разделов нижней панели (ТЗ 5.1). «+» — только на «Главной» и в «Календаре». */
enum class Section(val title: String, val icon: ImageVector, val selectedIcon: ImageVector, val hasFab: Boolean) {
    HOME("Главная", PIcons.Home, PIcons.HomeFilled, hasFab = true),
    CALENDAR("Календарь", PIcons.CalendarMonth, PIcons.CalendarMonthFilled, hasFab = true),
    CLIENTS("Клиенты", PIcons.Group, PIcons.GroupFilled, hasFab = false),
    SALARY("Зарплата", PIcons.Payments, PIcons.PaymentsFilled, hasFab = false),
    MORE("Ещё", PIcons.MoreHoriz, PIcons.MoreHoriz, hasFab = false),
}

/**
 * Где тренер находится в приложении: открытый раздел и в каждом разделе — стопка открытых экранов
 * поверх корневого («Ещё» → «Настройки»). Стопка у каждого раздела своя: ушёл в «Календарь» и вернулся —
 * «Ещё» открыт там же, где был.
 *
 * Экран в стопке — строка (`"settings"`, позже `"client/<id>"`): так состояние целиком сохраняется
 * в [Saver] и переживает пересоздание окна и выгрузку приложения из памяти.
 */
@Stable
class ShellState(section: Section = Section.HOME, stacks: Map<Section, List<String>> = emptyMap()) {
    var section: Section by mutableStateOf(section)
        private set

    private var stacks: Map<Section, List<String>> by mutableStateOf(stacks.filterValues { it.isNotEmpty() })

    /** Открытые экраны раздела поверх корневого; пусто — открыт сам раздел. */
    fun stack(of: Section = section): List<String> = stacks[of].orEmpty()

    /** Верхний экран раздела или `null`, если открыт корень. */
    fun top(of: Section = section): String? = stack(of).lastOrNull()

    /** Нажатие на раздел. Повторное нажатие на открытый раздел возвращает к его началу. */
    fun select(target: Section) {
        if (target == section) {
            stacks = stacks - target
        } else {
            section = target
        }
    }

    /** Открыть экран поверх текущего в открытом разделе. */
    fun open(route: String) {
        stacks = stacks + (section to stack() + route)
    }

    /**
     * Заменить верхний экран: на разложенном Fold подробности справа меняются, а не копятся в стопке
     * («Группы» → «Настройки» → «Назад» ведёт к списку, а не к «Группам»).
     */
    fun replaceTop(route: String) {
        stacks = stacks + (section to stack().dropLast(1) + route)
    }

    /**
     * «Назад» системы: закрыть верхний экран, иначе вернуться на «Главную». На «Главной» без открытых
     * экранов возвращает `false` — тогда приложение сворачивается, как обычно в Android.
     */
    fun back(): Boolean = when {
        stack().isNotEmpty() -> {
            val left = stack().dropLast(1)
            stacks = if (left.isEmpty()) stacks - section else stacks + (section to left)
            true
        }
        section != Section.HOME -> {
            section = Section.HOME
            true
        }
        else -> false
    }

    /** Есть ли куда вернуться внутри приложения. */
    val canGoBack: Boolean get() = stack().isNotEmpty() || section != Section.HOME

    companion object {
        private const val ROUTE_SEPARATOR = "\u001F"

        /** Сохраняется списком строк: раздел, затем по разделу — имя и экраны через служебный разделитель. */
        val Saver: Saver<ShellState, Any> = Saver(
            save = { state ->
                // ArrayList, а не buildList: на Android сохраняемое значение должно быть Serializable.
                arrayListOf(state.section.name).apply {
                    state.stacks.forEach { (section, routes) -> add(section.name + ROUTE_SEPARATOR + routes.joinToString(ROUTE_SEPARATOR)) }
                }
            },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST")
                val list = saved as List<String>
                val section = Section.entries.firstOrNull { it.name == list.firstOrNull() } ?: Section.HOME
                val stacks = list.drop(1).mapNotNull { line ->
                    val parts = line.split(ROUTE_SEPARATOR)
                    val owner = Section.entries.firstOrNull { it.name == parts.first() } ?: return@mapNotNull null
                    owner to parts.drop(1).filter { it.isNotEmpty() }
                }.toMap()
                ShellState(section, stacks)
            },
        )
    }
}

@Composable
fun rememberShellState(): ShellState = rememberSaveable(saver = ShellState.Saver) { ShellState() }
