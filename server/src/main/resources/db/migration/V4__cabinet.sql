-- Шаг 2.11: кабинет родителя по ссылке (ТЗ 6.1, 6.2, 3.7).

-- Ссылка на кабинет клиента. Токен — 32 случайных байта, создаётся на телефоне; здесь только SHA-256.
-- Код доступа (4 цифры) — Argon2id, как пароль; сам код хранится только на телефоне.
CREATE TABLE cabinet_access (
    id                BIGSERIAL PRIMARY KEY,
    coach_id          TEXT NOT NULL REFERENCES coach (id) ON DELETE CASCADE,
    -- id клиента в данных тренера (sync_row: entity = 'client').
    client_id         TEXT NOT NULL,
    token_hash        BYTEA NOT NULL UNIQUE,
    pin_hash          TEXT,
    -- Неверные коды подряд; на пятом — блокировка на 15 минут, счёт заново.
    pin_failures      INT NOT NULL DEFAULT 0,
    pin_blocked_until TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Новая ссылка отзывает прежнюю.
    revoked_at        TIMESTAMPTZ,
    last_opened_at    TIMESTAMPTZ
);
CREATE UNIQUE INDEX cabinet_access_active ON cabinet_access (coach_id, client_id) WHERE revoked_at IS NULL;

-- Пропуск после верного кода: cookie на 30 дней (здесь только SHA-256 её значения).
CREATE TABLE cabinet_pass (
    pass_hash  BYTEA PRIMARY KEY,
    access_id  BIGINT NOT NULL REFERENCES cabinet_access (id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX cabinet_pass_access ON cabinet_pass (access_id);

-- «Данные на …» в шапке кабинета (6.3): последний обмен тренера с сервером.
ALTER TABLE coach ADD COLUMN synced_at TIMESTAMPTZ;
