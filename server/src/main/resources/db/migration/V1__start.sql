-- Шаг 2.7: первая миграция сервера. Таблиц тренеров ещё нет: учётные записи — шаг 2.8, синхронизация — 2.9.
-- Здесь — только сведения о самой базе, чтобы было видно, когда сервер впервые её открыл.
CREATE TABLE server_info (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO server_info (key, value) VALUES ('created_at', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'));
