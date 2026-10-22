package com.akenory.personalka.domain.model

// Перечисления модели данных (ТЗ 3.2–3.5). В базе хранятся строками — именами значений (ТЗ 3.1),
// поэтому имена значений не переименовываются: это формат базы, копий и синхронизации.

// --- 3.1, 3.2. Тренер и настройки ---

/** Область данных: одного тренера или общая для клуба (ТЗ 3.1). */
enum class Scope { COACH, CLUB }

/** Режим работы (ТЗ 1.5): «Только телефон» или «С сервером». */
enum class CoachMode { LOCAL, SERVER }

/** Роль на сервере. */
enum class CoachRole { COACH, ADMIN }

/** Частота автокопии (ТЗ 7.6). */
enum class BackupFrequency { OFF, DAILY, WEEKLY }

/** Категория тренера: персональный тренер, Мастер, Элит. */
enum class CoachCategory { TRAINER, MASTER, ELITE }

/** Схема выплат: половина официально, всё официально, ИП, без официальной части. */
enum class PaySchemeKind { HALF_OFFICIAL, FULL_OFFICIAL, IP, NONE }

// --- 3.3. Справочники и ставки ---

/** Поведение типа тренировки в расчётах. Мини-группа и грудничковое — GROUP или PERSONAL со своим именем. */
enum class TrainingKind { PERSONAL, SPLIT, GROUP, SPT, DUTY, OTHER }

/** За что платят: за тренировку, за клиента, за час. */
enum class PayUnit { PER_SESSION, PER_CLIENT, PER_HOUR }

// --- 3.5. Клиенты, группы, тренировки ---

/** Пол клиента (4.17, 10.6). В кабинет не передаётся. */
enum class Sex { MALE, FEMALE }

/** Как получено согласие: галочкой в кабинете или на бумаге (6.1). */
enum class ConsentSource { CABINET, PAPER }

/** Клиент занимается или ушёл в архив. */
enum class ClientStatus { ACTIVE, ARCHIVED }

/** Откуда запись: внесена в приложении или перенесена из таблиц (раздел 9). */
enum class RecordSource { MANUAL, IMPORT }

/** На ком числится группа или тренировка в 1С. */
enum class Owner { SELF, COLLEAGUE }

/** Кто ведёт или провёл: сам, вместе с коллегой, коллега (замена). */
enum class ConductedBy { SELF, SELF_AND_COLLEAGUE, COLLEAGUE }

/** Статус самой тренировки (4.1). «Отмена со списанием» — статус участия, а не тренировки. */
enum class TrainingStatus { PLANNED, DONE, CANCELLED }

/** Итог для одного клиента на тренировке (4.1, 4.2). */
enum class ParticipationStatus { EXPECTED, ATTENDED, CANCELLED, CANCELLED_CHARGED }

/** Стиль: вольный, на спине, брасс, баттерфляй, комплекс. */
enum class SwimStyle { FREESTYLE, BACKSTROKE, BREASTSTROKE, BUTTERFLY, MEDLEY }

/** Результат показан на тренировке или на соревнованиях. */
enum class ResultKind { TRAINING, COMPETITION }

/** Стартовая тренировка: ждёт покупки, закрыта покупкой, не закрыта в срок. */
enum class SptCaseStatus { OPEN, CLOSED, NOT_CLOSED }

/** Запись листа ожидания (4.16): ждёт, записан, закрыта. */
enum class WaitStatus { WAITING, BOOKED, CLOSED }

/**
 * Родственная связь (4.17). PARENT, GRANDPARENT и AUNT_UNCLE — направленные: клиент A — родитель,
 * бабушка или дедушка, дядя или тётя для клиента B. Остальные — взаимные. OTHER — со своими подписями.
 */
enum class RelationKind { PARENT, GRANDPARENT, AUNT_UNCLE, SIBLING, COUSIN, SPOUSE, OTHER }
