-- Шаг 2.8: аккаунты тренеров, сессии, коды на почту и открытые возможности (ТЗ 7.3, 1.5).
-- Регистрация открытая: аккаунт появляется сразу, но входить можно только после подтверждения почты кодом.

CREATE TABLE coach (
    -- Тот же идентификатор, что у тренера в телефоне (строка UUID): синхронизация 2.9 фильтрует по нему.
    id                 TEXT PRIMARY KEY,
    -- Маленькими буквами, без пробелов по краям (AccountRules.email).
    email              TEXT NOT NULL UNIQUE,
    display_name       TEXT NOT NULL,
    -- Argon2id в виде строки $argon2id$v=19$m=…,t=…,p=…$соль$хэш.
    password_hash      TEXT NOT NULL,
    email_confirmed_at TIMESTAMPTZ,
    -- ACTIVE или SUSPENDED (приостановка — этап 5).
    status             TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Сессия — одно устройство. Токен — 32 случайных байта у телефона, здесь только SHA-256.
CREATE TABLE coach_session (
    id           BIGSERIAL PRIMARY KEY,
    coach_id     TEXT NOT NULL REFERENCES coach (id) ON DELETE CASCADE,
    token_hash   BYTEA NOT NULL UNIQUE,
    device_name  TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 180 дней от последнего использования.
    expires_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX coach_session_coach ON coach_session (coach_id);

-- Открытые возможности: тренер — возможность — до (включительно; NULL — без срока). Новая возможность — новая строка.
CREATE TABLE coach_feature (
    coach_id TEXT NOT NULL REFERENCES coach (id) ON DELETE CASCADE,
    feature  TEXT NOT NULL,
    until    DATE,
    PRIMARY KEY (coach_id, feature)
);

-- Коды на почту: подтверждение почты (confirm) и новый пароль (reset). Один действующий код на адрес и цель.
CREATE TABLE mail_code (
    email      TEXT NOT NULL,
    purpose    TEXT NOT NULL,
    code_hash  BYTEA NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts   INT NOT NULL DEFAULT 0,
    PRIMARY KEY (email, purpose)
);

-- Отправленные письма — для пределов: на адрес, с IP и всего за сутки.
CREATE TABLE mail_send (
    email   TEXT NOT NULL,
    ip      TEXT,
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX mail_send_email ON mail_send (email, sent_at);
CREATE INDEX mail_send_ip ON mail_send (ip, sent_at);

-- Попытки входа паролем — для предела 5 в минуту на адрес и на IP (7.3).
CREATE TABLE login_attempt (
    email TEXT NOT NULL,
    ip    TEXT,
    at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX login_attempt_email ON login_attempt (email, at);
CREATE INDEX login_attempt_ip ON login_attempt (ip, at);
