package com.akenory.personalka.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.akenory.personalka.BuildConfig
import com.akenory.personalka.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Экран во весь размер, значки строки состояния — по теме телефона, как и тема приложения.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Сложение и разложение Fold окно не пересоздаёт (configChanges в манифесте): Compose просто
        // перекладывает экран под новую ширину, открытый раздел и прокрутка остаются на месте.
        val settings = (application as PersonalkaApplication).settings
        setContent {
            App(
                versionName = BuildConfig.VERSION_NAME,
                settings = settings,
                backHandler = { enabled, onBack -> BackHandler(enabled = enabled, onBack = onBack) },
            )
        }
    }
}
