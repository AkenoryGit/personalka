-- Шаг 5.2в: возможности тренеров — в админке на сайте (решение Дмитрия 27.10.2026; ТЗ 1.5, 5.17).

-- Новым тренерам по умолчанию: строка есть — возможность открыта сразу при регистрации, без срока.
-- Решение Дмитрия 27.10.2026: «Копия на сервере» — всем сразу; «Кабинеты» и «С налогом 6,5 %» — нет.
CREATE TABLE feature_default (
    feature TEXT PRIMARY KEY
);
INSERT INTO feature_default (feature) VALUES ('server_copy');

-- «Копия у всех сразу» — и у тех, кто зарегистрировался раньше.
INSERT INTO coach_feature (coach_id, feature) SELECT id, 'server_copy' FROM coach ON CONFLICT (coach_id, feature) DO NOTHING;

-- У администратора до первого сохранения его возможностей в админке открыто всё (как до шага 5.2в);
-- после — только его строки coach_feature, как у всех: себе он включает и выключает сам.
ALTER TABLE coach ADD COLUMN features_set BOOLEAN NOT NULL DEFAULT false;
