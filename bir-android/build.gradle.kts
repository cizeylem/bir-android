import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.cizeylem.bir"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        // Kütüphane modüllerinde targetSdk yalnız test APK'sını etkiler; şartname gereği sabitlenir.
        testOptions.targetSdk = 36
        // Modül küçültülmeden yayınlanır; kurallar AAR ile tüketicinin R8'ine geçer (SPEC §1).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        // context.lib_version: yayın sürümüyle aynı kaynaktan (gradle.properties). Çalışma zamanı bağımlılığı değil.
        buildConfigField("String", "LIB_VERSION", "\"${providers.gradleProperty("VERSION_NAME").get()}\"")
    }

    compileOptions {
        // Desugaring kapalı: API 21'de olmayan sınıf kullanılmaz.
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// Sürüm eşleşme testi için yayın sürümünü ve gradle.properties yolunu teste geçirir.
tasks.withType<Test>().configureEach {
    systemProperty("bir.publishVersion", project.version.toString())
    systemProperty("bir.gradlePropertiesPath", rootProject.file("gradle.properties").absolutePath)
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
    // Yalnız androidTest: AAR'a girmez, çekirdeğin çalışma zamanı bağımlılığı değildir.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

// Yayın: Maven Central Portal (vanniktech). Sürüm ve grup gradle.properties'ten gelir.
group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    publishToMavenCentral()
    // İmza yalnız anahtar verilmişse (CI: ORG_GRADLE_PROJECT_signingInMemoryKey). Yerelde anahtar yoktur;
    // publishToMavenLocal imzasız geçer. Central imzasız yüklemeyi zaten reddeder.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates(group.toString(), "bir-android", version.toString())

    pom {
        name.set("bir-android")
        description.set("Çizeylem Android SDK: olay toplama, oturum, kuyruk ve onay yönetimi; üçüncü taraf bağımlılığı yoktur.")
        inceptionYear.set("2026")
        url.set("https://github.com/cizeylem/bir-android")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("cizeylem")
                name.set("Çizeylem")
                url.set("https://github.com/cizeylem")
            }
        }
        scm {
            url.set("https://github.com/cizeylem/bir-android")
            connection.set("scm:git:git://github.com/cizeylem/bir-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/cizeylem/bir-android.git")
        }
    }
}
