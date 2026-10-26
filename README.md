# bir-android

Çizeylem'in Android SDK'sı: olay toplama, oturum, dayanıklı kuyruk ve rıza yönetimi.
**Üçüncü taraf bağımlılığı yoktur** (çekirdek AAR ~105 KB), `minSdk 21`, Kotlin ve Java'dan çağrılabilir.

İki modül:

| Modül | İş |
|---|---|
| `bir-android` | Çekirdek: `track`, `identify`, kullanıcı özellikleri, oturum, kuyruk, rıza, deep link |
| `bir-android-attribution` | Attribution (isteğe bağlı): Play/Meta install referrer, `install_source`, GAID |

## Gradle

```kotlin
dependencies {
    implementation("com.cizeylem:bir-android:0.1.0")
    // Attribution isteniyorsa (çekirdeğe bağımlıdır, tek başına kullanılmaz):
    implementation("com.cizeylem:bir-android-attribution:0.1.0")
}
```

## En küçük kurulum

`Application.onCreate` içinde, ilk Activity başlamadan **önce**:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Cizeylem.initialize(this, Configuration("ck_w_..."))
        // Rıza varsayılan olarak KAPALIDIR: bu çağrı yapılmadan hiçbir şey toplanmaz ve
        // diskte hiçbir iz bırakılmaz. Değeri kendi rıza ekranınızdan getirin.
        Cizeylem.setConsent(Consent(analytics = true))
    }
}
```

```kotlin
Cizeylem.track("sepete_eklendi", mapOf("urun_id" to "42"))
Cizeylem.identify("kullanici-123")
```

Java'dan aynısı: `Cizeylem.initialize(this, new Configuration("ck_w_..."));`

Tüm çağrılar çağıran thread'de **anında** döner; disk, Binder ve ağ işi SDK'nın kendi thread'ine
gider. `initialize` çağrılmadan yapılan çağrılar sessizce yok sayılır — analitik, uygulamanızı
çökertmenin gerekçesi olamaz.

## `INTERNET` izni

Kütüphanenin manifest'inde `android.permission.INTERNET` bildirilir ve manifest birleştirmeyle
uygulamanıza geçer; ayrıca bir şey yapmanız gerekmez. Normal izindir: kurulumda verilir, kullanıcıya
sorulmaz, Play Console'da beyan gerektirmez.

Attribution modülü GAID istiyorsa **ana uygulamanızın** manifest'ine
`com.google.android.gms.permission.AD_ID` eklemeniz gerekir; nedeni ve sonuçları için
[`bir-android-attribution/README.md`](bir-android-attribution/README.md).

## Rıza modeli

Rıza üç anahtarlıdır ve üçü de **varsayılan kapalıdır**: `Consent(analytics, attribution, adSharing)`.
Kalıcı kimliği (`device_id`), kuyruğu ve diskteki her izi açan tek anahtar `analytics`'tir; `attribution`
onun üstüne eklenen kademedir ve tek başına kimlikli gönderimi açmaz; `adSharing` yalnız GAID kapısıdır.
Rıza yokken attribution sinyalleri kimliksiz uca (`POST /v1/attribution/anon`) gider — gövdede
`device_id`, `user_id`, `distinct_id` ve `gaid` **bulunmaz**. `setConsent` ile `analytics` geri alınınca
kuyruk, `device_id` ve kullanıcı kimliği diskten silinir.

## Genel API

`Cizeylem.initialize` · `track` · `identify` · `setUserProperties` · `setUserPropertiesOnce` ·
`addUserProperties` · `unsetUserProperties` · `sendUserProperties` · `setConsent` · `setOptOut` ·
`handleDeepLink` · `flush` · `reset` · `getDistinctId` · `stats`
— ve `Configuration`, `Consent`, `Stats`, `Logger`.
Attribution modülünde tek giriş: `Attribution.initialize(app, facebookAppId?)`.

> `Cizeylem` üzerindeki `withConsent` / `sendIdentified` / `sendAnonymous` üyeleri attribution modülünün
> iç kapısıdır; genel API'nin parçası **değildir** ve uyarısız değişebilir. Çağırmayın.

## Belgeler

- [`docs/SPEC.md`](docs/SPEC.md) — şartname (ne yapılacak)
- [`docs/AS-BUILT.md`](docs/AS-BUILT.md) — as-built: ölçümler, protokol icatları, bilinçli kararlar
- [`docs/RELEASE.md`](docs/RELEASE.md) — yayın akışı (Maven Central)
- [`bir-android-attribution/README.md`](bir-android-attribution/README.md) — attribution entegrasyonu

Lisans: [`LICENSE`](LICENSE).
