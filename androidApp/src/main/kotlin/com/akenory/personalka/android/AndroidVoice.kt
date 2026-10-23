package com.akenory.personalka.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.akenory.personalka.ui.training.VoiceInput

/**
 * Голосовой ввод в полях дневника (шаг 2.1, ТЗ 5.7): системное окно распознавания речи по-русски. Запись голоса
 * и распознавание — у системы (Google или Samsung), приложению не нужно разрешение на микрофон.
 * Создаётся в `onCreate` окна: окно результата регистрируется до его показа.
 */
class AndroidVoice(private val activity: ComponentActivity) : VoiceInput {
    private var onHeard: ((String) -> Unit)? = null

    private val recognizer = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = onHeard
        onHeard = null
        if (result.resultCode != Activity.RESULT_OK || callback == null) return@registerForActivityResult
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(callback)
    }

    override fun listen(onText: (String) -> Unit) {
        onHeard = onText
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        try {
            recognizer.launch(intent)
        } catch (_: ActivityNotFoundException) {
            onHeard = null
        }
    }

    companion object {
        /** Есть ли на телефоне распознавание речи; нет — микрофонов в полях нет, остаётся голосовой ввод клавиатуры. */
        fun availableOn(activity: ComponentActivity): Boolean = SpeechRecognizer.isRecognitionAvailable(activity)
    }
}
