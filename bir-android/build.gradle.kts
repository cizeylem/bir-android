plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.cizeylem.bir"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        // Kütüphane modüllerinde targetSdk yalnız test APK'sını etkiler; şartname gereği sabitlenir.
        testOptions.targetSdk = 36
        // Modül küçültülmeden yayınlanır; kurallar AAR ile tüketicinin R8'ine geçer (SPEC §1).
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        // Desugaring kapalı: API 21'de olmayan sınıf kullanılmaz.
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    // Çekirdekte üçüncü taraf bağımlılık yok: implementation/api satırı eklenmez.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
