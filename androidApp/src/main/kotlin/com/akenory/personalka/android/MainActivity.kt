package com.akenory.personalka.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.akenory.personalka.BuildConfig
import com.akenory.personalka.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Экран во весь размер, значки строки состояния — по теме телефона, как и тема приложения.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            App(versionName = BuildConfig.VERSION_NAME)
        }
    }
}
