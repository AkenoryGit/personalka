package com.akenory.personalka.data.series

import com.akenory.personalka.data.db.entity.SeriesEntity
import com.akenory.personalka.domain.series.Series
import com.akenory.personalka.domain.series.SeriesPattern

// Серии в базе и правила 4.4 (`SeriesRules`, шаг 1.3). Форма серии (1.13) пишет через то же.

/** Серия для правил; [clientIds] — клиенты серии (`series_client`), у группы и дежурства пусто. */
fun SeriesEntity.toSeries(clientIds: List<String>): Series = Series(
    id = id,
    pattern = SeriesPattern(
        typeId = typeId,
        locationId = locationId,
        weekdays = weekdays,
        startTime = startTime,
        durationMin = durationMin,
        clientIds = clientIds,
        groupId = groupId,
    ),
    validFrom = validFrom,
    validTo = validTo,
    generatedUntil = generatedUntil,
    skipDates = skipDates.toSet(),
)
