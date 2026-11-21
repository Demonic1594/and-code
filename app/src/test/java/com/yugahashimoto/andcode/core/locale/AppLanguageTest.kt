package com.yugahashimoto.andcode.core.locale

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Locale

class AppLanguageTest {
    private val originalDefault = Locale.getDefault()

    @After
    fun restoreDefault() {
        Locale.setDefault(originalDefault)
    }

    @Test
    fun `every picked language maps to its own locale`() {
        assertEquals(Locale("en"), AppLanguage.localeFor("en"))
        assertEquals(Locale("ja"), AppLanguage.localeFor("ja"))
        assertEquals(Locale("zh", "CN"), AppLanguage.localeFor("zh-CN"))
        assertEquals(Locale("ru"), AppLanguage.localeFor("ru"))
        assertEquals(Locale("es"), AppLanguage.localeFor("es"))
        assertEquals(Locale("fr"), AppLanguage.localeFor("fr"))
        assertEquals(Locale("pt", "BR"), AppLanguage.localeFor("pt-BR"))
        assertEquals(Locale("ar"), AppLanguage.localeFor("ar"))
    }

    @Test
    fun `following the system reads the system language at the time of the call`() {
        Locale.setDefault(Locale.FRENCH)
        assertEquals(Locale.FRENCH, AppLanguage.localeFor(AppLanguage.SYSTEM))
        // A process that outlives a change of the system language must not keep the first one.
        Locale.setDefault(Locale.GERMAN)
        assertEquals(Locale.GERMAN, AppLanguage.localeFor(AppLanguage.SYSTEM))
    }

    @Test
    fun `the language in the loaded settings wins, and the saved file is not read then`() {
        var reads = 0
        val language = AppLanguage.resolveLanguage(loaded = "ja") { "fr".also { reads++ } }
        assertEquals("ja", language)
        assertEquals(0, reads)
    }

    @Test
    fun `before the settings are loaded the saved language is used`() {
        assertEquals("ru", AppLanguage.resolveLanguage(loaded = null) { "ru" })
    }

    @Test
    fun `text built after a language change is read in the new language`() {
        // The scenario behind the fix: the process starts in Japanese, the user picks Spanish.
        var picked = "ja"
        val cache = LocaleCache<String>()
        val build = { locale: Locale -> "context in ${locale.toLanguageTag()}" }
        val before = cache.get(AppLanguage.localeFor(AppLanguage.resolveLanguage(loaded = picked) { "en" }), build)
        picked = "es"
        val after = cache.get(AppLanguage.localeFor(AppLanguage.resolveLanguage(loaded = picked) { "en" }), build)
        assertEquals("context in ja", before)
        assertEquals("context in es", after)
    }

    @Test
    fun `the cache reuses what it built while the locale stays the same`() {
        var builds = 0
        val cache = LocaleCache<Any>()
        val build = { _: Locale -> Any().also { builds++ } }
        val first = cache.get(Locale("ja"), build)
        val second = cache.get(Locale("ja"), build)
        assertSame(first, second)
        assertEquals(1, builds)
    }

    @Test
    fun `the cache builds again when the locale changes, and again when it changes back`() {
        var builds = 0
        val cache = LocaleCache<Any>()
        val build = { _: Locale -> Any().also { builds++ } }
        val japanese = cache.get(Locale("ja"), build)
        val spanish = cache.get(Locale("es"), build)
        val japaneseAgain = cache.get(Locale("ja"), build)
        assertNotSame(japanese, spanish)
        assertNotSame(spanish, japaneseAgain)
        assertEquals(3, builds)
    }
}
