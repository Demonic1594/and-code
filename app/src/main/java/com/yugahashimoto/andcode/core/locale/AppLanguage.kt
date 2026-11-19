package com.yugahashimoto.andcode.core.locale

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.StringRes
import com.yugahashimoto.andcode.data.connection.SecureSettingsRepository
import java.util.Locale

object AppLanguage {
    const val SYSTEM = "system"

    val supported: List<String> =
        listOf(
            SYSTEM,
            "en",
            "ja",
            "zh-CN",
            "ru",
            "es",
            "fr",
            "pt-BR",
            "ar",
        )

    fun localeFor(language: String): Locale =
        when (language) {
            "en" -> Locale("en")
            "ja" -> Locale("ja")
            "zh-CN" -> Locale("zh", "CN")
            "ru" -> Locale("ru")
            "es" -> Locale("es")
            "fr" -> Locale("fr")
            "pt-BR" -> Locale("pt", "BR")
            "ar" -> Locale("ar")
            else -> Locale.getDefault()
        }

    fun applyTo(base: Context): Context {
        val language = SecureSettingsRepository.readLanguage(base)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList(localeFor(language)))
        return base.createConfigurationContext(configuration)
    }

    /**
     * Reads string [id] in the language the app is set to at the time of each call, for something
     * that outlives the Activity, such as a ViewModel. Changing the language recreates the Activity
     * but keeps its ViewModels, so a string resolved when one was built would stay in the old
     * language. Holds only the application context.
     */
    fun stringResolver(
        context: Context,
        @StringRes id: Int,
    ): () -> String {
        val appContext = context.applicationContext
        return { applyTo(appContext).getString(id) }
    }
}
