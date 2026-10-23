# bir-android — SDK şartnamesi

Kaynak: ClickUp Doc [Mobil SDK + MMP planı](https://app.clickup.com/6653229/docs/6b19d-8375)
sayfa "1 · SDK şartnamesi" (§4) ve "2 · SDK promptları" (SH / SH-A / SI);
`cizeylem-1/docs/PROTOCOL.md`; `cizeylem-1/docs/ARASTIRMA-MOBIL-CIHAZ-VERISI.md` §2.14, §3, §5.
Referans davranış: `cizeylem/bir-js` → `packages/core/src/client.ts`.

Bu dosya planın dayandığı sözleşmedir. Plan ile birlikte okunur.

## 0. Amaç

Mevcut AppsFlyer/Amplitude kurulumlarından Çizeylem'e geçiş için native Android analitik
SDK'sı: telefon + Android TV + Fire TV. Davranış web/JS SDK'sıyla **birebir aynı**.

## 1. Paketler

| Maven koordinatı | İçerik |
|---|---|
| `com.cizeylem:bir-android` | Çekirdek: kuyruk, taşıma, kimlik, oturum, rıza, context, yaşam döngüsü |
| `com.cizeylem:bir-android-attribution` | Opt-in: Play referrer (AIDL), Meta referrer, GAID, mağaza kaynağı |

- Sıfır üçüncü taraf bağımlılık (çekirdekte yalnız Kotlin stdlib + Android SDK).
- AAR ≤ 150 KB (ölçülüp as-built'e yazılır).
- `minSdk 21` [gerekçesiyle değiştirilebilir], `compileSdk`/`targetSdk 36`.
- Java'dan çağrılabilir API (`@JvmStatic`, `@JvmOverloads`).
- R8 consumer rules paket içinde.

## 2. Protokol (PROTOCOL.md v1)

- `POST {endpoint}/v1/batch`, `Authorization: Bearer ck_w_…`, `Content-Encoding: gzip`.
- Varsayılan endpoint `https://in.cizeylem.com`.
- Gövde: `{sent_at, events:[{event, distinct_id, time, insert_id, session_id, props?,
  $set?, $set_once?, $unset?, $add?, context}]}`.
- `insert_id` retry'da **değişmez** (dedup anahtarı).
- Batch sınırı: 500 event / 1 MB sıkıştırılmış.
- Yanıtlar: `200` sil · `400` at, retry etme · `401`/`403` SDK'yı durdur ·
  `413` batch'i böl · `429` `Retry-After` kadar bekle · `5xx` backoff.
- `$identify`: `distinct_id` (eski anonim) + `user_id`; sonrası `distinct_id = user_id`.
- `reset()`: yeni anonim UUID, sunucuya çağrı yok.

## 3. §4 şartname (üç platformda aynı)

| Konu | Karar |
|---|---|
| Kurulum | `init("ck_w_…")` tek satır; varsayılan `https://in.cizeylem.com` |
| Kuyruk | Dosya (SQLite değil), `.tmp` → atomik rename; ≤ 1.000 event / 5 MB; dolunca en eski düşer |
| Flush | 30 event · 30 sn · arka plana geçiş |
| Retry | 1 / 8 / 32 / 128 / 300 sn, ±%50 jitter; 5xx'te asla düşürme; 413 böl; 401 dur; 429 30 sn |
| device_id | Rastgele UUID v4, `noBackupFilesDir`. Yeniden kurulum = yeni id |
| Oturum | `session_id` = oturum başlangıcının epoch ms'i (string); 30 dk hareketsizlik → yeni oturum |
| Yaşam döngüsü | `cz_app_installed{existing_install}` · `cz_app_updated` · `cz_app_opened` · `cz_app_backgrounded` |
| Deep link | `handleDeepLink(url)` → `cz_deep_link_opened`; yalnız `utm_*` + `click_id` + Meta `campaign_ids` |
| Rıza | `setConsent(analytics, attribution, adSharing)`; **üçü de varsayılan kapalı**; `setOptOut` geriye uyumlu |
| Amplitude devralma | **Salt okuma**; geçersiz değer listesi; `$set_once {migrated_from}` |
| Eşzamanlılık | `track()` yalnız kuyruğa; Binder/disk **asla** çağıran thread'de |

## 4. Context alanları (ARASTIRMA §5.1 / §5.2)

Mevcut (PROTOCOL §2): `platform`, `app_version`, `os_name`, `os_version`, `device_model`,
`device_type`, `locale`, `timezone`, `screen_width`, `screen_height`.

Eklenenler (S1 ile sunucuya girecek; o gelene kadar gönderilir ama **saklanmaz**):

| Alan | Değer kümesi |
|---|---|
| `lib` | `bir-android` |
| `lib_version` | semver |
| `app_build` | `versionCode` |
| `device_manufacturer` | ham `Build.MANUFACTURER` |
| `network_type` | `wifi` · `cellular` · `ethernet` · `offline` · `other` |
| `install_source` | `play` · `galaxy_store` · `appgallery` · `amazon` · `xiaomi` · `sideload` · `dev` · `test_lab` · `other` · `unknown` |
| `app_state` | `foreground` · `background` |
| `platform` | `android` · `android_tv` · `fire_tv` (çalışma anında tespit) |
| `device_type` | `mobile` · `tablet` · `tv` |
| `consent_scope` | verilen rıza anahtarları (S3) |

TV tespiti: Android TV = `FEATURE_LEANBACK` / `uiMode == TELEVISION`;
Fire TV = `amazon.hardware.fire_tv` (model öneki güvenilmez).
Aynı APK telefonda `android`, TV'de `android_tv` yazar.

## 5. Rıza modeli

`setConsent(analytics, attribution, adSharing)` — üçü varsayılan `false`, kalıcı, geri alınabilir.

| Durum | SDK davranışı |
|---|---|
| Rıza yok | Kalıcı kimlik **yazmaz**, event **göndermez**. Yalnız: referrer'ı kimliksiz `/v1/attribution/anon`'a |
| `analytics` | Normal analitik |
| `+ attribution` | Referrer/token `device_id` ile |
| `+ adSharing` | GAID okunur (ayrıca `AD_ID` izni + sınırsız kimlik şartı) |

İlke: **parmak izi (IP+UA eşleşmesi) hiçbir yerde yazılmaz.**

## 6. Kimlik ve Amplitude devralma (ARASTIRMA §3)

device_id = rastgele UUID v4, `getNoBackupFilesDir()/cizeylem/device_id` (AtomicFile).
SharedPreferences **kullanılmaz**: Auto Backup kimliği yeni cihaza kopyalar.

Devralma sırası (salt okuma, ilk bulan kazanır):
1. Açık `Configuration(deviceId = …)`
2. Kendi depomuz
3. Amplitude-Kotlin `app_amplitude/<paket>/<instance>/analytics/identity.properties`
4. Eski SQLite `databases/com.amplitude.api`, tablo `store`, anahtar `device_id`
5. Eski SharedPreferences `com.amplitude.api.<paket>` / `com.amplitude.api.deviceId`
6. Yeni UUID

Geçersiz değerler elenir: `""`, `9774d56d682e549c`, `unknown`, `000000000000000`,
`Android`, `DEFACE`, sıfır-UUID.
Devralma olduysa `$set_once {migrated_from}` + `cz_app_installed{existing_install: true}`.
Amplitude'un `optOut` değeri korunur. Amplitude kaydı **silinmez/değiştirilmez**.

## 7. Attribution modülü (SH-A, §5.2)

| Sinyal | Kural |
|---|---|
| Play Install Referrer | AIDL doğrudan (kütüphanesiz); ilk açılışta bir kez; tüm alanlar (`*_server_seconds` dahil); Play yoksa sessiz geç |
| Meta Install Referrer | `<queries>` katana/instagram/lite; content provider; **ham değer** (şifre çözme sunucuda). FB ≥428, IG ≥296, Lite ≥411 |
| GAID | Yalnız `adSharing` + `AD_ID` izni + sınırsız kimlik; sıfırlı id gönderilmez |
| Mağaza kaynağı | `getInstallSourceInfo()` → `install_source` |

Rıza yoksa kimliksiz `POST /v1/attribution/anon` (gövdede `device_id`/`user_id` **yok**).

## 8. Kabul kriterleri

- [ ] Birim testler yeşil
- [ ] S5 gelince `bir-js/spec/` adaptörü yeşil
- [ ] AAR boyutu ölçülmüş, ≤ 150 KB
- [ ] StrictMode açık örnek uygulamada ihlal yok
- [ ] Central Portal'a `android-v*` tag'iyle yayınlandı (hesap Doğukan'da)
- [ ] İlk müşteri TV uygulamasında pilot entegrasyon: Central sürümüne bağlı, event'ler test projesinde
      görünüyor, eski `device_id` korunmuş (merge + canlı Doğukan'da)

## 9. Bizde olmayanlar (Doğukan'ı bekleyen)

| Ne | Etkisi |
|---|---|
| Test projesi + `ck_w_` write key | Canlı sunucuya deneme gönderilemez |
| S1 — mobil context kolonları | §4 alanları gönderilir, sunucuda saklanmaz |
| S3 — `context.consent` + `/v1/attribution/anon` | SH-A'nın rızasız yolu uçtan uca denenemez |
| S5 — ortak test seti `bir-js/spec/` | "spec yeşil" yerine birim testler |
| Sonatype Central Portal + `com.cizeylem` + GPG | SI yayın adımı |
| `research_notes/…` (sdk_engineering.md, android_google_play.md) | Doğukan'ın makinesinde; gerekirse istenecek |

## 10. Açık noktalar

- **[doğrula]** Play Install Referrer AIDL arayüz/intent adları → `installreferrer:2.2` AAR'ından.
- **[doğrula]** Fire TV'nin yeni modelleri Vega OS olabilir (APK yok) → pilot Android TV ile.
- **[sor]** §4'teki "tvOS ≤100 event / 5'te flush" yalnız tvOS için yazılmış.
  Android TV / Fire TV bu planda mobil sınırlarını (1.000 event, 30'da flush) kullanır.
- **[sor]** Fire TV'de Amazon reklam kimliği politikası (ARASTIRMA §8-3). Pilot TV uygulaması bugün
  kendi UUID'siyle yayında; plan UUID'yi sürdürür.
