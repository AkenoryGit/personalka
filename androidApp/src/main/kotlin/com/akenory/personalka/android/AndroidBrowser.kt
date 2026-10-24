package com.akenory.personalka.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Встроенный браузер «Как видит родитель» (6.4, шаг 2.13б): `WebView` без JavaScript и доступа к файлам — кабинету
 * они не нужны. Страницы своего сервера открываются внутри, чужие ссылки (папка «Фото и видео») — в браузере телефона.
 * «Назад» — назад по страницам, с первой — закрыть.
 */
@Composable
fun AndroidBrowser(url: String, onClose: () -> Unit, modifier: Modifier) {
    var view by remember { mutableStateOf<WebView?>(null) }
    val host = remember(url) { Uri.parse(url).host }
    BackHandler {
        val web = view
        if (web != null && web.canGoBack()) web.goBack() else onClose()
    }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (request.url.host == host) return false
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                        } catch (_: ActivityNotFoundException) {
                            // Браузера нет — ссылка просто не открывается.
                        }
                        return true
                    }
                }
                loadUrl(url)
                view = this
            }
        },
        onRelease = { web ->
            web.stopLoading()
            web.destroy()
            view = null
        },
        modifier = modifier,
    )
}
