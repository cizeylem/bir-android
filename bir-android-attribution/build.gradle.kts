plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.cizeylem.bir.attribution"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        testOptions.targetSdk = 36
    }

    // Play Install Referrer servisi AIDL ile çağrılır (kütüphanesiz, SPEC §7).
    buildFeatures { aidl = true }

    compileOptions {
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
    // Üçüncü taraf bağımlılık yok; yalnız kardeş çekirdek modül (AttributionSink için).
    implementation(project(":bir-android"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
