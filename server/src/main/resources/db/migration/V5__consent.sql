-- Шаг 2.12: согласие из кабинета (ТЗ 6.1, 3.7, 10.3) — доказательство того, что согласие получено и кем.

CREATE TABLE consent_record (
    id            BIGSERIAL PRIMARY KEY,
    coach_id      TEXT NOT NULL REFERENCES coach (id) ON DELETE CASCADE,
    -- id клиента в данных тренера (sync_row: entity = 'client'): согласие у клиента, а не у ссылки —
    -- новая ссылка без отзыва согласие не спрашивает.
    client_id     TEXT NOT NULL,
    accepted_at   TIMESTAMPTZ NOT NULL,
    -- Редакция текста (docs/legal); новая редакция с новыми целями спрашивает снова.
    text_version  INT NOT NULL,
    -- Отмеченные пункты: 'personal_data' всегда, 'photo_video' — по желанию.
    items         TEXT[] NOT NULL,
    -- Фамилия и имя родителя; у взрослого клиента пусто.
    given_by_name TEXT,
    ip            TEXT,
    user_agent    TEXT,
    revoked_at    TIMESTAMPTZ
);
CREATE INDEX consent_record_client ON consent_record (coach_id, client_id);
