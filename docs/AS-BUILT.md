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
| Play Install Referrer AIDL arayüz/intent adları | AÇIK. Attribution modülünün (`bir-android-attribution`) işi; çekirdekte referrer kodu yok. Bu görevde doğrulanmadı. |
| Fire TV yeni modeller Vega OS olabilir (APK yok) | AÇIK. Cihaz/pilot gerektirir; burada denenmedi. TV örneği yalnız derlendi. |

## Ölçülen kabul kriterleri (SPEC §8)

- Birim testler: 143 test, 0 hata.
- AAR ≤ 150 KB: 100,81 KB.
- StrictMode: `StrictModeTest` (`ThreadPolicy` + `VmPolicy`, `detectAll().penaltyDeath()`) altında
  `initialize → setConsent → track → flush` ihlalsiz geçti. Test ilk koşuda GERÇEK bir ihlali yakaladı:
  `ContextProvider.screen()` Application bağlamından `WindowManager` alıyordu
  (`IncorrectContextUseViolation`, API 30+). `DisplayManager` ile düzeltildi.
- Telefon örnek uygulaması (StrictMode `penaltyDeath` açık, `initialize` StrictMode'dan sonra) emülatörde
  açıldı, deep link ile yeniden açıldı; süreç ölmedi. Düğmelere dokunuş ve rıza anahtarları EL İLE
  denenmedi (UI otomasyonu yok); bu yollar `StrictModeTest` ile kapsanıyor.

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
