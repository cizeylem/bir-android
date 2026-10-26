# bir-android — as-built (çekirdek, SH)

## AAR boyutu (SPEC §1: ≤ 150 KB)

Ölçüm: `scripts/measure-aar.sh` (`:bir-android:assembleRelease`, küçültmesiz AAR).

| | Bayt | KB |
|---|---|---|
| `bir-android-release.aar` | **103.230** | **100,81** |
| Sınır | 153.600 | 150 |

Sınırın altında (yaklaşık 49 KB pay). Betik sınır aşılırsa 1 ile çıkar.

## minSdk 21 gerekçesi

SPEC §1 `minSdk 21`'i varsayılan tutuyor; değiştirmek için neden bulunmadı. Desugaring kapalı
olduğundan API 21'de bulunmayan sınıf kullanılmıyor (API 23+ `NetworkCapabilities`, API 28+
`longVersionCode` çağrıları sürüm denetimiyle sarılı). Hedef TV cihazları (Android TV / Fire TV)
API 21+ olduğundan alt sınırı yükseltmek kazanç değil, kapsam kaybı olurdu.

## `[doğrula]` maddeleri

| Madde | Sonuç |
|---|---|
| Play Install Referrer AIDL arayüz/intent adları | DOĞRULANDI (`installreferrer-2.2` bytecode'u, `javap`). Descriptor `com.google.android.finsky.externalreferrer.IGetInstallReferrerService` (brief'teki `com.android.installreferrer` yanlıştı), transaction kodu 1, flags 0; action `com.google.android.finsky.BIND_GET_INSTALL_REFERRER_SERVICE`, component `com.android.vending/com.google.android.finsky.externalreferrer.GetInstallReferrerService`; istek `package_name`, yanıt 7 anahtar. `.aidl` KULLANILMADI: aynı paket adında sınıf üretmek `installreferrer` AAR'ıyla "Duplicate class" yaratırdı; wire `Parcel` ile elle kuruldu (`PlayReferrer.kt`). GERÇEK CİHAZDA OKUMA KAYDI YAPILAMADI (Play Store'lu cihaz yok; ClickUp kabul kriteri açık). Birim testler sahte Binder ile wire'ı sınar. Ertelenen test: "servis başka pakete ait" dalı (Robolectric ServiceInfo.packageName'i yeniden yazıyor). |
| GMS reklam kimliği (GAID) servisi, kütüphanesiz | DOĞRULANDI (`play-services-ads-identifier:18.3.0` bytecode'u, `javap`). Action `com.google.android.gms.ads.identifier.service.START`, hedef **`setPackage("com.google.android.gms")`** (`setComponent` YOK), bind bayrağı 1 (BIND_AUTO_CREATE), bağlanma beklemesi 10 sn. Descriptor `com.google.android.gms.ads.identifier.internal.IAdvertisingIdService`; kod 1 = `getId` (parametresiz, String), kod 2 = `isLimitAdTrackingEnabled` (isteğe `writeInt(1)`, int 0/1). Kod 6 (debug) kullanılmadı. `AdvertisingId.kt`. GERÇEK CİHAZDA OKUMA KAYDI YAPILAMADI (GMS'li cihaz yok): sıfır kimliğin hangi koşullarda döndüğü, `AD_ID` izni yokken GMS'in sıfır mı döndürdüğü yoksa `SecurityException` mı fırlattığı, ve `<queries>` girdisinin gerçekten gerekli olup olmadığı cihazda doğrulanmalı. Birim testler sahte Binder ile wire'ı sınar. |
| `AD_ID` izninin kütüphanede bildirilmemesi | KARAR (Kontrolcü). `<uses-permission AD_ID>` modül manifest'ine KONMADI: manifest birleştirmeyle modülü alan her uygulamaya geçer ve Play Console'da reklam kimliği beyanını zorunlu kılardı. İzin ana uygulamanın işidir; SDK çalışma anında `checkSelfPermission` ile denetler ve yoksa GAID'i atlar. Entegrasyon notu `bir-android-attribution/README.md`'de. |
| `<queries><package com.google.android.gms>` | EKLENDİ, cihazda doğrulanmadı. Araştırma "tahmin" işaretledi (Google'ın kendi AAR'ı eklemiyor); eksikse GAID sessizce hiç okunmaz, fazlalığın maliyeti yok. |
| Attribution gövdesinde Play/Meta alan adı çakışması | KARAR (bu görev). İki kaynak da `install_referrer` anahtarını kullanıyor; tek gövdede birleşince çakışıyorlardı. Alanlar `play_` / `meta_` ön ekiyle yazılır (`play_install_referrer`, `meta_install_referrer`, ...). SPEC bu adları tanımlamıyor; SUNUCU TARAFIYLA DOĞRULANMADI. |
| Kimliksiz attribution gövdesinin biçimi | KARAR (bu görev). SPEC yalnız ucu (`POST /v1/attribution/anon`) ve "gövdede `device_id`/`user_id` yok" kuralını veriyor. Gövde düz bir JSON nesnesidir: `sent_at` + ham alanlar. Kimlik anahtarları (`device_id`, `user_id`, `distinct_id`) `EventJson.attributionAnon`'da ayrıca süzülür. SUNUCU TARAFIYLA DOĞRULANMADI. |
| Fire TV yeni modeller Vega OS olabilir (APK yok) | AÇIK. Cihaz/pilot gerektirir; burada denenmedi. TV örneği yalnız derlendi. |

## Ölçülen kabul kriterleri (SPEC §8)

- Birim testler: 214 test, 0 hata (çekirdek 147, attribution 67).
- AAR ≤ 150 KB: 100,81 KB.
- StrictMode: `StrictModeTest` (`ThreadPolicy` + `VmPolicy`, `detectAll().penaltyDeath()`) altında
  `initialize → setConsent → track → flush` ihlalsiz geçti. Test ilk koşuda GERÇEK bir ihlali yakaladı:
  `ContextProvider.screen()` Application bağlamından `WindowManager` alıyordu
  (`IncorrectContextUseViolation`, API 30+). `DisplayManager` ile düzeltildi.
- Telefon örnek uygulaması (StrictMode `penaltyDeath` açık, `initialize` StrictMode'dan sonra) emülatörde
  açıldı, deep link ile yeniden açıldı; süreç ölmedi. Düğmelere dokunuş ve rıza anahtarları EL İLE
  denenmedi (UI otomasyonu yok); bu yollar `StrictModeTest` ile kapsanıyor.

### StrictModeTest kanıtının kapsamı (neyin kanıtlandığı, neyin kanıtlanmadığı)

`ThreadPolicy` yalnız kurulduğu thread'e uygulanır. SDK işini kendi arka plan
thread'inde yaptığı için, o thread'deki işin kapsamı kategoriye göre değişir:

- **Disk okuma/yazma** (`detectDiskReads`, `detectDiskWrites`): yalnız `ThreadPolicy`
  kategorisidir → SDK'nın arka plan thread'inde **hiçbir politika tarafından
  kapsanmaz**, yani bu testte kanıtsızdır.
- **Genel ağ kullanımı** (`detectNetwork`): yalnız `ThreadPolicy` kategorisidir →
  aynı şekilde **kapsanmaz**, kanıtsızdır.
- **Cleartext ağ** (`detectCleartextNetwork`): `VmPolicy` kategorisidir ve süreç
  genelinde çalışır → hangi thread'den gelirse gelsin **kapsanır**.

Özetle bu test, SDK'nın arka plan thread'inde disk ya da genel ağ işi yapmadığını
**kanıtlamaz**; yalnız çağıran thread'de ihlal olmadığını ve süreç genelinde
`VmPolicy` kategorilerinin (sızıntı, cleartext ağ, etiketlenmemiş soket,
`IncorrectContextUse`) tetiklenmediğini gösterir. `ContextProvider`'daki
`IncorrectContextUse` bu yolla yakalandı.

- Bekleme: `track`'in SDK thread'inde kuyruğa yazılması `stats().queued` yoklamasıyla (10 sn zaman aşımı; test, `@Before`'da `noBackupFilesDir/cizeylem` dizinini silerek temiz sayfadan başladığı için `queued >= 1` bu track'in yazdığı satırdır) deterministik beklenir. `flush`'ın ağ denemesinin bitişi için genel API'de tamamlanma sinyali
  yok (başarısız istekte `queued` değişmez; test kapalı bir porta gittiği için gerçek ağ alışverişi olmaz; cleartext kategorisi gerçek bir cleartext isteğinde tetiklenirdi), bu yüzden
  kalan 1,5 sn'lik sabit `Thread.sleep` bırakıldı; deterministik değildir.

## Instrumented testler

- Cihaz: Android emülatörü, API 36 (`google/sdk_gphone16k_arm64`, 16 KB sayfa imajı), arm64.
  `Pixel_9_Pro_XL` AVD'sinin veri bölümü doluydu (kurulum "not enough space" ile düştü); aynı sistem
  imajıyla geçici bir AVD (`SDD_Gecici`) kullanıldı ve iş bitince silindi.
- Sonuç: 4/4 geçti (`StrictModeTest` 1, `AmplitudeDbIntegrityTest` 3).
- Yalnız API 36'da koşturuldu; API 21-35 denenmedi.

### AmplitudeDbIntegrityTest hakkında dürüst not

Görev 3'ün gerekçesi, varsayılan `DatabaseErrorHandler`'ın bozuk veritabanını silmesiydi. API 36'da
yapılan denemede (geçici test, sonra kaldırıldı): `OPEN_READWRITE` ile açılan bozuk dosya SİLİNİYOR;
`OPEN_READONLY` ile açılan bozuk dosya (hem NOTADB hem başlığı sağlam/gövdesi bozuk) varsayılan
işleyiciyle de SİLİNMİYOR. SDK READONLY açtığı ve boş işleyici verdiği için testler geçer, ama
**bu testler boş işleyiciyi kaldırınca düşmez** (işleyiciyi `null` yapıp denendi, API 36'da hâlâ geçti).
Yani test bugün bir regresyon kilidi olarak yararlı (dosya var, baytlar aynı) ama boş işleyicinin
gerekliliğini API 36'da kanıtlamıyor. Eski API'lerde (READONLY'de silme davranışı farklı olabilir)
denenmedi.

## Koşturulamayan / yapılmayan

- TV örneği (`samples/tv`) derlendi, ÇALIŞTIRILMADI: TV AVD'si yok.
- Gerçek cihaz yok; yalnız emülatör.
- Canlı sunucuya gönderim yok (write key / test projesi yok, SPEC §9).
