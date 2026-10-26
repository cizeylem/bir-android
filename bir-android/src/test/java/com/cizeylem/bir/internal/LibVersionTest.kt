package com.cizeylem.bir.internal

import com.cizeylem.bir.BuildConfig
import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `context.lib_version` ile Maven'a giden sürüm tek kaynaktan (gradle.properties `VERSION_NAME`) gelir.
 * Biri sapar ve panelde yanlış sürüm görünürse bu test kırılır.
 */
class LibVersionTest {
    private val declared: String = Properties().also { p ->
        File(System.getProperty("bir.gradlePropertiesPath")!!).inputStream().use { p.load(it) }
    }.getProperty("VERSION_NAME")

    @Test fun `lib_version gradle properties daki VERSION_NAME ile ayni`() {
        assertEquals(declared, BuildConfig.LIB_VERSION)
    }

    @Test fun `lib_version Maven yayin surumuyle ayni`() {
        assertEquals(System.getProperty("bir.publishVersion"), BuildConfig.LIB_VERSION)
    }

    @Test fun `surum semver biciminde`() {
        assertTrue(BuildConfig.LIB_VERSION, Regex("""\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?""").matches(BuildConfig.LIB_VERSION))
    }

    @Test fun `ContextProvider sabit yerine BuildConfig surumunu tasir`() {
        // Core, ContextProvider'a BuildConfig.LIB_VERSION'ı verir; elle sabit geri gelirse kaynak taraması yakalar.
        val src = File(System.getProperty("bir.gradlePropertiesPath")!!).parentFile!!
            .resolve("bir-android/src/main/java/com/cizeylem/bir/internal/Core.kt").readText()
        assertTrue(src.contains("ContextProvider(app, BuildConfig.LIB_VERSION)"))
        assertTrue(!Regex("""LIB_VERSION\s*=\s*"""").containsMatchIn(src))
    }
}
