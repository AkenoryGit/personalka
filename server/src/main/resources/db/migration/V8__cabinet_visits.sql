-- Шаг 5.35: посещения кабинетов — для админки (просьба Дмитрия 26.11.2026: кто пользуется кабинетом, а кто нет).
-- Только число посещений по дням клуба: без IP-адреса, браузера и времени каждого открытия.
-- Посещение — открытие по ссылке через 30 минут и больше после предыдущего; «Как видит родитель» не считается.
-- По клиенту, а не по ссылке: новая ссылка историю не сбрасывает.
CREATE TABLE cabinet_visit (
    coach_id  TEXT NOT NULL REFERENCES coach (id) ON DELETE CASCADE,
    client_id TEXT NOT NULL,
    day       DATE NOT NULL,
    visits    INT NOT NULL,
    PRIMARY KEY (coach_id, client_id, day)
);

-- С какого момента считаются посещения: раньше сервер помнил только последнее открытие (last_opened_at).
CREATE TABLE cabinet_visit_since (
    at TIMESTAMPTZ NOT NULL
);
INSERT INTO cabinet_visit_since (at) VALUES (now());
