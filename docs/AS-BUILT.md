# bir-android — as-built (çekirdek, SH)

## AAR boyutu (SPEC §1: ≤ 150 KB)

Ölçüm: `scripts/measure-aar.sh` (`:bir-android:assembleRelease`, küçültmesiz AAR).

| | Bayt | KB |
|---|---|---|
| `bir-android-release.aar` | **107.959** | **105,43** |
| Sınır | 153.600 | 150 |

Sınırın altında (yaklaşık 45 KB pay). Betik sınır aşılırsa 1 ile çıkar.

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
| Attribution protokol icatları (alan adları, gövde biçimi, event adı) | KARAR (Görev 14), **sunucu tarafıyla doğrulanmadı**. Toplu liste ve örnek JSON'lar için aşağıdaki "Attribution protokolü: bizim icatlarımız" bölümüne bakın. S3 (sunucu) henüz yazılmadı; bu maddeler Doğukan'la anlaşılmalı. |
| Fire TV yeni modeller Vega OS olabilir (APK yok) | AÇIK. Cihaz/pilot gerektirir; burada denenmedi. TV örneği yalnız derlendi. |

## Attribution protokolü: bizim icatlarımız (SUNUCUYLA ANLAŞILMADI)

SPEC §7 yalnız şunları veriyor: hangi sinyallerin toplanacağı, `install_source` alan adı, kimliksiz ucun
yolu (`POST /v1/attribution/anon`) ve "gövdede `device_id`/`user_id` yok" kuralı. Aşağıdakilerin hepsi
**Görev 14'te bizim seçtiğimiz** protokol ayrıntılarıdır; S3 (sunucu) henüz yazılmadığı için hiçbiri
doğrulanmadı. Sunucu farklı bekliyorsa değişecek tek yer `AttributionRun` (alan adları) ve
`EventJson.attributionAnon` (gövde biçimi) olur.

### 1. Alan adları — `play_` / `meta_` ön ekleri

Play ve Meta'nın ikisi de `install_referrer` anahtarını kullanıyor; tek gövdede birleşince
**çakışıyorlardı**. Ön ek eklendi. Değerler HAM taşınır (yorumlama/şifre çözme sunucuda).

| Kaynak | Gövdedeki anahtarlar |
|---|---|
| Play Install Referrer | `play_install_referrer`, `play_referrer_click_timestamp_seconds`, `play_referrer_click_timestamp_server_seconds`, `play_install_begin_timestamp_seconds`, `play_install_begin_timestamp_server_seconds`, `play_google_play_instant`, `play_install_version` |
| Meta Install Referrer | `meta_install_referrer`, `meta_is_ct`, `meta_actual_timestamp` |
| Mağaza kaynağı | `install_source` |
| Reklam kimliği | `gaid` |

Bir alan okunamadıysa gövdeye HİÇ yazılmaz (null gönderilmez): "bilmiyorum" ile "yok" aynı şey değildir.

### 2. Kimlikli gövde — `cz_attribution` event'i, `POST /v1/batch`

Event adı `cz_attribution` da bu görevde seçildi (SPEC'te yok). Alanlar event'in `props`'una girer; gövdenin
geri kalanı normal event protokolüdür (SPEC §2), yani `distinct_id`, `context`, `session_id` vb. hazırdır.

```json
{
  "sent_at": 1761500000000,
  "events": [
    {
      "event": "cz_attribution",
      "distinct_id": "9f1c4e2a-...",
      "time": 1761499999000,
      "insert_id": "b7e1...",
      "session_id": "1761499990000",
      "context": { "lib": "bir-android", "lib_version": "0.1.0", "...": "..." },
      "props": {
        "install_source": "com.android.vending",
        "meta_install_referrer": "AbCdEf...==",
        "meta_is_ct": 1,
        "play_install_referrer": "utm_source=google&utm_medium=cpc",
        "play_referrer_click_timestamp_seconds": 1761400001,
        "play_install_begin_timestamp_seconds": 1761400003,
        "play_google_play_instant": false,
        "play_install_version": "1.2.3",
        "gaid": "38400000-8cf0-11bd-b23e-10b96e40000d"
      }
    }
  ]
}
```

### 3. Kimliksiz gövde — `POST /v1/attribution/anon`

Düz bir JSON nesnesi: `sent_at` (duvar saati, ms) + ham alanlar. Sarmalayıcı yok, dizi yok.
`device_id`, `user_id`, `distinct_id` ve `gaid` anahtarları `EventJson.attributionAnon`'da **ayrıca
süzülür** (`IDENTITY_KEYS`).

**GAID BU UÇTA HİÇ BULUNMAZ.** Cihaz kapsamlı ve kalıcı bir reklam kimliğidir; bu uca girseydi buradaki
kayıtlar yeniden kimliklendirilebilir olurdu. `adSharing` rızası tek başına açık olsa bile GAID toplanmaz:
toplama `AttributionRun.route`'un yalnız kimlikli dalında yapılır, süzme son savunma katmanıdır. Sunucu
tarafı bu ucun kayıtlarını **kimliksiz** varsayabilir.

```json
{
  "sent_at": 1761500000000,
  "install_source": "com.android.vending",
  "meta_install_referrer": "AbCdEf...==",
  "meta_is_ct": 1,
  "play_install_referrer": "utm_source=google&utm_medium=cpc",
  "play_referrer_click_timestamp_seconds": 1761400001,
  "play_install_begin_timestamp_seconds": 1761400003,
  "play_google_play_instant": false,
  "play_install_version": "1.2.3"
}
```

Başlıklar her iki uçta da aynı: `Content-Type: application/json`, `Content-Encoding: gzip`,
`Authorization: Bearer <writeKey>`.

### 4. Tekrar gönderim beklenmeli (sunucu tekilleştirmeli)

"Okundu" işareti yalnız **kalıcı devirden sonra** yazılır: kimlikli yolda alanlar çekirdeğin dayanıklı
kuyruğuna yazıldığında, kimliksiz yolda sunucudan **2xx** geldiğinde. Başka her sonuçta işaret yazılmaz ve
sinyal bir sonraki açılışta **yeniden gönderilir**. Kimliksiz uçta `insert_id` benzeri bir tekilleştirme
anahtarı YOK; sunucunun aynı ham referrer'ı tekilleştirmesi gerekir. (Tekrar gönderim bilinçli seçimdir:
kaybolan atıf geri gelmez.)

## Rızasız kalıcı iz: tek istisna `attr_state` (BİLİNÇLİ KARAR)

SPEC §5'in "rızasız kalıcı iz bırakılmaz" kuralı **çekirdekte istisnasızdır**: `Cizeylem` rıza yokken ne
`<noBackupDir>/cizeylem/` dizinini yaratır, ne kuyruk, ne `device_id`, ne oturum dosyası yazar
(`CizeylemTest` → "rıza yokken çekirdek init-track-flush hiçbir dosya oluşturmaz").

Attribution modülü bir istisna yazar ve bu bilinçlidir:

| | |
|---|---|
| Dosya | `<noBackupDir>/cizeylem/attr_state` (dizini bu yazım yaratır) |
| İçerik | Sinyal başına tek bir boolean: `{"play_referrer": true, "meta": true, …}` |
| Ne DEĞİL | Kimlik değil. Kullanıcıya ait hiçbir değer, hiçbir rastgele kimlik, hiçbir zaman damgası yok |
| Ne zaman | Yalnız **kalıcı devirden sonra** (kimliksiz yolda sunucudan 2xx) |
| Rıza geri alınınca | Silinmez (çekirdeğin `clear()`'ı yalnız kendi dosyalarını siler) |

Gerekçe: işaret yazılmasaydı referrer HER açılışta yeniden okunup aynı kimliksiz sinyal yeniden
gönderilirdi. Sunucuda tekrarlanan bir sinyal akışı, diskteki tek bir booleandan daha fazla iz bırakır —
ve kullanıcı için daha kötüdür. "Davranışı koru, iddiayı kesinleştir" kararı verildi; `AttributionSink.
sendAnonymous` ve `Core.postAnonymous` KDoc'ları buna göre yazıldı.

## `Cizeylem : AttributionSink` genel yüzeyde (BİLİNÇLİ KARAR, 0.1.0)

`withConsent` / `sendIdentified` / `sendAnonymous` genel API'de görünür: Kotlin'de bir arayüz uygulaması
`internal` olamaz, attribution modülü ise ayrı bir Gradle modülü olduğu için çekirdeğin `internal`
üyelerini göremez. Rıza açısından delik yok (üçü de çekirdeğin rıza kapısından geçer), ama 0.1.0'ın
kalıcı sözleşmesine giriyor. Bu turda yeniden yapılandırılmadı; bunun yerine KDoc'larına "SDK iç
kullanımı içindir, kararlı API değildir" notu kondu ve kök `README.md`'deki genel API listesine
alınmadılar. 1.0 öncesi yeniden ele alınabilir.

## Ölçülen kabul kriterleri (SPEC §8)

- Birim testler: 224 test, 0 hata (çekirdek 154, attribution 70).
- AAR ≤ 150 KB: 105,43 KB.
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
- **AÇIK MADDE (S5):** `bir-js/spec/` ortak test seti henüz yok (SPEC §9), bu yüzden `spec-adapter` yazılmadı ve SPEC §8'deki "spec adaptörü yeşil" kriteri karşılanmadı; yerine birim testler var. Plan: `docs/RELEASE.md` "S5 geldiğinde".
## Lint (Görev 16'da ilk kez koşuldu; çözüldü)

`bir-android:lintDebug` ilk koşuda 4 hata verdi, ikisi de yanlış pozitif; şimdi 0 hata (7 uyarı: yalnız sürüm güncelliği).
- `EventQueue.kt` `NewApi`: bytecode'da `Iterable.forEach` (API 24) yok, Kotlin inline `forEach`'ti. Belirsizlik düz `for` döngüsüyle kaldırıldı.
- `ContextProvider.networkType` `MissingPermission`: izin çalışma anında denetleniyor; `checkSelfPermission` API 23 olduğundan 3 argümanlı `checkPermission` kullanılıyor, lint tanımıyor. `@SuppressLint` yalnız o fonksiyonda.
Baseline ve global kural kapatma yok.
