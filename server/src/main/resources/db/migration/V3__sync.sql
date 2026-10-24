-- Шаг 2.9: синхронизация (ТЗ 7.2, 3.8). Записи телефона хранятся как есть — строкой таблицы в JSON: сервер
-- сравнивает только служебные поля, а новая таблица или столбец в телефоне не требуют миграции здесь.

-- Номер изменения (seq) — сквозной на весь сервер. Отправки идут по одной (блокировка в транзакции), поэтому
-- номера видны в порядке записи и выдача «после номера N» ничего не пропускает.
CREATE SEQUENCE sync_seq;

CREATE TABLE sync_row (
    -- Чьё: id тренера (область COACH) или '' — данные клуба (CLUB), их получают все тренеры с копией на сервере.
    owner      TEXT NOT NULL,
    -- Таблица телефона (SyncRules.ENTITIES) и id записи (UUID v7 с устройства).
    entity     TEXT NOT NULL,
    id         TEXT NOT NULL,
    version    BIGINT NOT NULL,
    -- Миллисекунды UTC, как в базе телефона; deleted_at — запись удалена.
    updated_at BIGINT NOT NULL,
    deleted_at BIGINT,
    -- Устройство последнего изменения: при равном updated_at побеждает большее.
    device_id  TEXT NOT NULL,
    data       JSONB NOT NULL,
    seq        BIGINT NOT NULL,
    -- Кто записал (для данных клуба — какой администратор).
    coach_id   TEXT NOT NULL,
    PRIMARY KEY (owner, entity, id)
);
CREATE INDEX sync_row_seq ON sync_row (owner, seq);
CREATE INDEX sync_row_deleted ON sync_row (deleted_at) WHERE deleted_at IS NOT NULL;
