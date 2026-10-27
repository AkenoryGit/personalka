package com.akenory.personalka.data.backup

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSDataBase64DecodingIgnoreUnknownCharacters
import platform.Foundation.NSURL
import platform.Foundation.NSURLBookmarkResolutionWithoutUI
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create

/** Закладки на папки из «Файлов»: выбранная папка остаётся доступной после перезапуска приложения. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object IosBookmarks {
    /** Закладка папки [url] (её только что выбрали) текстом; `null` — система не дала закладку. */
    fun of(url: NSURL): String? {
        val scoped = url.startAccessingSecurityScopedResource()
        try {
            val data = url.bookmarkDataWithOptions(0u, null, null, null) ?: return null
            return data.base64EncodedStringWithOptions(0u)
        } finally {
            if (scoped) url.stopAccessingSecurityScopedResource()
        }
    }

    fun resolve(bookmark: String): NSURL? {
        val data = NSData.create(base64EncodedString = bookmark, options = NSDataBase64DecodingIgnoreUnknownCharacters) ?: return null
        return NSURL.URLByResolvingBookmarkData(data, NSURLBookmarkResolutionWithoutUI, null, null, null)
    }
}
