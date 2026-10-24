-- База и роль сервера (ТЗ 7.4): отдельно от Planovo, в том же PostgreSQL 16.
-- Запуск один раз: sudo -u postgres psql -v password='<пароль из trener.env>' -f setup.sql
CREATE ROLE trener LOGIN PASSWORD :'password';
CREATE DATABASE trener OWNER trener ENCODING 'UTF8' TEMPLATE template0;
-- Только роль trener подключается к базе trener.
REVOKE ALL ON DATABASE trener FROM PUBLIC;
-- Роль trener не получает прав в других базах: таблицы Planovo ей не видны, если Planovo сам не раздал их PUBLIC.
