package com.akenory.personalka.domain.format

/** Строки аватарок (шаг 3.13): меню у аватарки в карточке клиента и в «Профиле». */
object AvatarFormat {
    const val PICK = "Выбрать фото"
    const val CHANGE = "Другое фото"
    const val REMOVE = "Убрать фото"

    /** Родитель не дал согласия на фото или отозвал его (6.1): фото не хранится, в меню — только эта строка. */
    const val NO_CONSENT = "Нет согласия на фото"

    /** Подпись к аватарке: для чтения с экрана. */
    const val DESCRIPTION = "Аватарка"
}
