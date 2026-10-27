-- Шаг 5.2б: админка на сайте сервера (решение Дмитрия 27.10.2026; ТЗ 5.17, 1.5, 7.3).
-- Вход — почта и пароль администратора и код из письма; сессия живёт 12 часов от входа и не продлевается.

CREATE TABLE admin_session (
    -- Токен — 32 случайных байта в cookie, здесь только SHA-256.
    token_hash BYTEA PRIMARY KEY,
    coach_id   TEXT NOT NULL REFERENCES coach (id) ON DELETE CASCADE,
    -- Скрытое поле каждой формы админки: чужая страница не отправит форму от имени администратора.
    csrf       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

-- Журнал: входы и действия администратора. Имя тренера — снимком: строка остаётся, даже если аккаунт удалят.
CREATE TABLE admin_log (
    id       BIGSERIAL PRIMARY KEY,
    at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_id TEXT REFERENCES coach (id) ON DELETE SET NULL,
    action   TEXT NOT NULL,
    target   TEXT,
    ip       TEXT
);
CREATE INDEX admin_log_at ON admin_log (at DESC);
