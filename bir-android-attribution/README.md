# `bir-android-attribution`

Çizeylem SDK'sının attribution modülü (SPEC §7): Play Install Referrer, Meta Install Referrer,
mağaza kaynağı (`install_source`) ve Google reklam kimliği (GAID). Üçüncü taraf bağımlılığı yoktur;
Play ve GMS servisleriyle konuşan wire protokolü elle kurulmuştur.

## Kurulum

`Cizeylem.initialize`'dan **sonra** çağırın:

```kotlin
Cizeylem.initialize(this, Configuration("ck_w_..."))
Attribution.initialize(this)                       // Meta kaynağı olmadan
Attribution.initialize(this, "1234567890")         // Facebook App ID ile (Meta install referrer)
```

Java:

```java
Cizeylem.initialize(this, new Configuration("ck_w_..."));
Attribution.initialize(this, "1234567890");
```

`Cizeylem.initialize` çağrılmamışsa `Attribution.initialize` hiçbir şey yapmaz: ne thread açar ne de
sinyal okur. Her sinyal kurulum başına **bir kez** okunur ve gönderilir.

## Gerekli manifest izni: `AD_ID`

GAID istiyorsanız **ana uygulamanızın** manifest'ine şunu ekleyin:

```xml
<uses-permission android:name="com.google.android.gms.permission.AD_ID" />
```

Bu izin **bilerek kütüphanede bildirilmemiştir**. Manifest birleştirmeyle modülü alan her uygulamaya
geçerdi ve Play Console'da reklam kimliği beyanını zorunlu kılardı — uygulama reklam paylaşımı rızasını
hiç açmasa bile. SDK izni çalışma anında denetler; yoksa GAID'i sessizce atlar, başka hiçbir şey etkilenmez.
(Çekirdekte `ACCESS_NETWORK_STATE` için de aynı desen uygulandı.)

GAID **üç kapıdan** geçerse okunur:

1. `Consent.adSharing` **ve** kimlikli yolu açan `analytics` + `attribution` açık,
2. `com.google.android.gms.permission.AD_ID` izni verilmiş,
3. cihazda `isLimitAdTrackingEnabled == false`.

Üçü birden sağlanmazsa GMS'e bağlanılmaz bile. GMS'in döndürebildiği sıfır kimlik
(`00000000-0000-0000-0000-000000000000`) "kullanılabilir kimlik yok" demektir; **asla gönderilmez**.

## Rıza ve gönderim yolu (SPEC §5)

| Rıza | Yol |
|---|---|
| `analytics` **ve** `attribution` | `cz_attribution` event'i, normal kuyruk, `POST /v1/batch`, `device_id` ile |
| Diğer her durum (rıza yok ya da yalnız `attribution`) | `POST /v1/attribution/anon`; gövdede `device_id`/`user_id`/`distinct_id`/`gaid` **yok** |

`adSharing` yalnız GAID kapısıdır; tek başına gönderimi açmaz. **GAID yalnız kimlikli yolda toplanır:**
`adSharing` açık olsa bile, `analytics` + `attribution` yoksa GMS'e hiç bağlanılmaz ve kimliksiz gövdeye
reklam kimliği girmez — o uç sözleşmesi gereği hiçbir kalıcı kimlik taşıyamaz.

## Tekrar gönderim

Bir sinyalin "okundu" işareti yalnız **kalıcı devirden sonra** yazılır: kimlikli yolda alanlar dayanıklı
kuyruğa yazıldığında, kimliksiz yolda sunucudan **2xx** geldiğinde. Başka her sonuçta işaret yazılmaz ve
sinyal bir sonraki açılışta yeniden okunup yeniden gönderilir. Sunucunun aynı ham referrer'ı
tekilleştirmesi beklenir; tekrar gönderim, kaybolan atıftan iyidir.

Gövde biçimi ve alan adları (`play_` / `meta_` ön ekleri, `cz_attribution` event adı) için
`docs/AS-BUILT.md` → "Attribution protokolü: bizim icatlarımız".
