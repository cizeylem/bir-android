import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.DeploymentValidation

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.cizeylem.bir.attribution"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        testOptions.targetSdk = 36
    }


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

// Yayın: Maven Central Portal (vanniktech). Sürüm ve grup gradle.properties'ten gelir.
group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    // Otomatik release: yükleme sonrası Portal'da elle "Publish" gerekmez. İki modül tek dağıtımda
    // (plugin'in paylaşılan build servisi) yayınlanır. PUBLISHED: görev, sürüm Central'da yayınlanana
    // kadar bekler; böylece publish.yml'deki GitHub Release yalnız gerçekten yayınlanmış sürüm için açılır.
    publishToMavenCentral(automaticRelease = true, validateDeployment = DeploymentValidation.PUBLISHED)
    // İmza yalnız anahtar verilmişse (CI: ORG_GRADLE_PROJECT_signingInMemoryKey). Yerelde anahtar yoktur;
    // publishToMavenLocal imzasız geçer. Central imzasız yüklemeyi zaten reddeder.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates(group.toString(), "bir-android-attribution", version.toString())

    pom {
        name.set("bir-android-attribution")
        description.set("Çizeylem Android SDK için attribution eklentisi: Play ve Meta referrer, GAID, mağaza kaynağı.")
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
