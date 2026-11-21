package com.yugahashimoto.andcode.core.locale

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.StringRes
import com.yugahashimoto.andcode.AndCodeApplication
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

    /** Built on demand, one for the latest locale; see [current]. */
    private val currentContexts = LocaleCache<Context>()

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

    fun applyTo(base: Context): Context = withLocale(base, localeFor(SecureSettingsRepository.readLanguage(base)))

    /**
     * A context whose resources are in the language picked in AndCode right now, for text read
     * outside an Activity: notifications, their channels, and what services put in them.
     *
     * The Application context will not do. [applyTo] fixes the language of its resources when the
     * process starts, so after the user picks another language every notification would stay in the
     * old one until the process died; a Service's own context ignores the app's language altogether.
     * Read only text from the result, and keep [context] for intents and system services.
     */
    fun current(context: Context): Context = contextFor(context, currentLocale(context))

    /** [current] for a [locale] the caller already resolved with [currentLocale]. */
    fun contextFor(
        context: Context,
        locale: Locale,
    ): Context {
        // The Application, never a Service or Activity: the cache outlives either.
        val app = context.applicationContext ?: context
        return currentContexts.get(locale) { withLocale(app, it) }
    }

    /** The locale of [current]: the picked language's, or the system's current one when it follows the system. */
    fun currentLocale(context: Context): Locale =
        localeFor(
            resolveLanguage((context.applicationContext as? AndCodeApplication)?.loadedLanguage) {
                SecureSettingsRepository.readLanguage(context)
            },
        )

    /**
     * The picked language: [loaded] from the settings in memory once the app has read them, else
     * [stored] from the encrypted file, which costs a Keystore round trip and so is read only then.
     */
    internal fun resolveLanguage(
        loaded: String?,
        stored: () -> String,
    ): String = loaded ?: stored()

    private fun withLocale(
        base: Context,
        locale: Locale,
    ): Context {
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList(locale))
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
        // Through [current]: the language from the settings in memory and a context cached per
        // locale, instead of a Keystore read and a new context on every call.
        return { current(appContext).getString(id) }
    }
}

/**
 * Keeps what was built for the latest locale: reused while the locale stays the same, so a
 * notification updated on every step of a run does not build new Resources each time, and built
 * again the moment the locale changes. Only the latest is kept; the language rarely changes.
 */
internal class LocaleCache<T : Any> {
    @Volatile
    private var latest: Pair<Locale, T>? = null

    fun get(
        locale: Locale,
        build: (Locale) -> T,
    ): T {
        latest?.let { (cachedLocale, value) -> if (cachedLocale == locale) return value }
        return build(locale).also { latest = locale to it }
    }
}
