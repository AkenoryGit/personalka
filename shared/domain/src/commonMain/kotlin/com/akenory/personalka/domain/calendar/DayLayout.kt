package com.akenory.personalka.domain.calendar

// ТЗ 5.3, «Раскладка наложений». Минуты, колонки и dp — без экрана: блоки рисует shared/ui.

/** Тренировка в дне для раскладки: минуты от полуночи, [start] включительно, [end] — нет. */
data class DaySpan(val id: String, val start: Int, val end: Int)

/**
 * Место блока по ширине: колонка [column] из [columns] колонок кластера, блок занимает [span] колонок
 * вправо — растянут на свободные. Ширина колонки — ширина дня без отступов, делённая на [columns].
 */
data class BlockPlace(val id: String, val column: Int, val span: Int, val columns: Int)

/** Метка «+N» в последней колонке кластера: скрытые тренировки [ids] с [start] по [end]. */
data class MorePlace(val column: Int, val columns: Int, val start: Int, val end: Int, val ids: List<String>)

/** Раскладка дня: видимые блоки и метки «+N». */
data class DayLayout(val blocks: List<BlockPlace>, val more: List<MorePlace>) {
    private val byId by lazy { blocks.associateBy { it.id } }

    fun place(id: String): BlockPlace? = byId[id]
}

/**
 * Раскладка наложений (ТЗ 5.3):
 *
 * 1. Тренировки дня сортируются по началу, при равенстве — более длинные первыми.
 * 2. Пересекающиеся цепочкой объединяются в кластер.
 * 3. В кластере каждая тренировка получает первую колонку, в которой предыдущая уже закончилась.
 * 4. Ширина = ширина дня / число колонок кластера; блок растягивается вправо на свободные колонки.
 * 5. Минимальная ширина 36 dp; если колонок больше 4, показываются 3 и метка «+N» со списком.
 *
 * Тренировка, которая кончается ровно в начале следующей, с ней не пересекается: 09:00–10:00 и 10:00–11:00 —
 * обе во всю ширину.
 */
object DayLayoutRules {
    /** Больше колонок не бывает: пятая и дальше — под меткой «+N». */
    const val MAX_COLUMNS = 4

    /** Блок не уже этого. */
    const val MIN_BLOCK_DP = 36f

    /**
     * Сколько колонок помещается в ширину [widthDp] (день без отступов слева и справа): не уже 36 dp, не больше
     * четырёх и не меньше двух — одна видимая колонка и «+N». День уже 72 dp бывает только в месяце, а там сетки нет.
     */
    fun maxColumns(widthDp: Float): Int = (widthDp / MIN_BLOCK_DP).toInt().coerceIn(2, MAX_COLUMNS)

    fun layout(items: List<DaySpan>, maxColumns: Int = MAX_COLUMNS): DayLayout {
        require(maxColumns >= 2) { "Нужно хотя бы две колонки: блок и «+N»" }
        val sorted = items.sortedWith(compareBy<DaySpan> { it.start }.thenByDescending { it.end - it.start }.thenBy { it.id })
        val blocks = mutableListOf<BlockPlace>()
        val more = mutableListOf<MorePlace>()
        clusters(sorted).forEach { cluster -> placeCluster(cluster, maxColumns, blocks, more) }
        return DayLayout(blocks, more)
    }

    /** Пересекающиеся цепочкой — один кластер; порядок внутри — порядок сортировки. */
    private fun clusters(sorted: List<DaySpan>): List<List<DaySpan>> {
        val result = mutableListOf<MutableList<DaySpan>>()
        var end = Int.MIN_VALUE
        sorted.forEach { item ->
            if (result.isEmpty() || item.start >= end) {
                result += mutableListOf(item)
                end = item.end
            } else {
                result.last() += item
                end = maxOf(end, item.end)
            }
        }
        return result
    }

    private fun placeCluster(cluster: List<DaySpan>, maxColumns: Int, blocks: MutableList<BlockPlace>, more: MutableList<MorePlace>) {
        // Колонка — список тренировок; новая встаёт в первую колонку, где последняя уже закончилась.
        val columns = mutableListOf<MutableList<DaySpan>>()
        val columnOf = mutableMapOf<String, Int>()
        cluster.forEach { item ->
            val free = columns.indexOfFirst { it.last().end <= item.start }
            val index = if (free >= 0) free else columns.size.also { columns += mutableListOf<DaySpan>() }
            columns[index] += item
            columnOf[item.id] = index
        }

        val overflow = columns.size > maxColumns
        // При переполнении последняя колонка — под «+N», видимых на одну меньше.
        val visible = if (overflow) maxColumns - 1 else columns.size
        val total = if (overflow) maxColumns else columns.size

        cluster.forEach { item ->
            val column = columnOf.getValue(item.id)
            if (column >= visible) return@forEach
            var span = 1
            while (column + span < visible && columns[column + span].none { it.overlaps(item) }) span++
            blocks += BlockPlace(item.id, column, span, total)
        }

        if (overflow) {
            // Скрытые, что идут в разное время, получают свои метки: одна метка на цепочку пересекающихся.
            val hidden = cluster.filter { columnOf.getValue(it.id) >= visible }
            clusters(hidden).forEach { chain ->
                more += MorePlace(
                    column = visible,
                    columns = total,
                    start = chain.minOf { it.start },
                    end = chain.maxOf { it.end },
                    ids = chain.map { it.id },
                )
            }
        }
    }

    private fun DaySpan.overlaps(other: DaySpan): Boolean = start < other.end && other.start < end
}
