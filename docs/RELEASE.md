# Yayın rehberi

Koordinatlar (`samples/*` yayınlanmaz):

| Maven koordinatı | Modül |
|---|---|
| `com.cizeylem:bir-android` | `:bir-android` |
| `com.cizeylem:bir-android-attribution` | `:bir-android-attribution` |

## Sürüm numarası

- Tek kaynak: `gradle.properties` içindeki `VERSION_NAME` (grup: `GROUP`).
- Maven sürümü ve `context.lib_version` aynı değerden gelir: `VERSION_NAME` -> `BuildConfig.LIB_VERSION`
  (`bir-android/build.gradle.kts`, `buildConfigField`). Çekirdeğe çalışma zamanı bağımlılığı eklenmez.
- Biçim semver: `MAJOR.MINOR.PATCH` (ön sürüm: `0.2.0-rc.1`). 1.0.0'a kadar küçük sürümde kırıcı değişiklik olabilir.
- İki modül **aynı sürümle** çıkar (attribution, çekirdeğe aynı sürümden bağlanır).
- `LibVersionTest` eşleşmeyi kilitler: `BuildConfig.LIB_VERSION` == `VERSION_NAME` == Gradle proje sürümü ve
  `Core.kt` elle sabit taşımaz.

## Tag biçimi

`android-v<VERSION_NAME>`, örneğin `android-v0.1.0`. Tag push edilince `.github/workflows/publish.yml` çalışır:
tag ile `VERSION_NAME` eşleşmesini doğrular, testleri ve 150 KB AAR sınırını çalıştırır, sonra
`publishToMavenCentral` çağırır. Eşleşmezse yayın başlamaz.

## Repo secrets (Doğukan girer)

| Secret | İçerik |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Central Portal kullanıcı belirteci adı (Account > Generate User Token) |
| `MAVEN_CENTRAL_PASSWORD` | Aynı belirtecin parolası |
| `SIGNING_IN_MEMORY_KEY` | ASCII-armored GPG özel anahtarı (`gpg --export-secret-keys --armor <KEYID>`), tek parça metin |
| `SIGNING_IN_MEMORY_KEY_PASSWORD` | Anahtarın parolası |

İş akışı bunları `ORG_GRADLE_PROJECT_mavenCentralUsername`, `..._mavenCentralPassword`, `..._signingInMemoryKey`,
`..._signingInMemoryKeyPassword` ortam değişkenlerine bağlar. Sırlar koda ve depoya yazılmaz.

## İlk yayın öncesi kontrol listesi

- [ ] Sonatype Central Portal hesabı açıldı, `com.cizeylem` namespace'i doğrulandı (DNS TXT).
- [ ] GPG anahtarı üretildi, açık anahtar bir anahtar sunucusuna (ör. keyserver.ubuntu.com) yüklendi.
- [ ] Dört secret repoya girildi.
- [ ] Lisans: Apache-2.0 onaylandı (kardeş depo `bir-js` ile aynı); depoda `LICENSE` ve `NOTICE` var, POM lisansı ile tutarlı.
- [ ] POM'daki geliştirici/SCM bilgisi (`github.com/cizeylem/bir-android`, geliştirici "Çizeylem") doğru.
- [ ] `VERSION_NAME` yayınlanacak sürüme ayarlandı ve ana dala alındı.
- [ ] Yerel doğrulama temiz (aşağıda); `./scripts/measure-aar.sh` 150 KB altında.
- [ ] Tag atıldı: `git tag android-v<VERSION_NAME> && git push origin android-v<VERSION_NAME>`.
- [ ] Central Portal'da dağıtım doğrulandı; (otomatik yayın kapalıysa) elle "Publish" edildi.
- [ ] Yayınlanan bir sürüm Central'dan silinemez; hatalı sürümü düzeltmek için yeni PATCH sürümü çıkar.

## Yerel doğrulama (gerçek yayın yok)

```bash
./gradlew publishToMavenLocal
ls ~/.m2/repository/com/cizeylem/bir-android/<sürüm>/
ls ~/.m2/repository/com/cizeylem/bir-android-attribution/<sürüm>/
./scripts/measure-aar.sh
```

Her modül için `.aar`, `-sources.jar`, `-javadoc.jar`, `.pom` (ve `.module`) oluşmalıdır.

İmza: `signingInMemoryKey` verilmemişse imzalama atlanır, bu yüzden yerelde `.asc` dosyaları **oluşmaz** ve
`publishToMavenLocal` anahtarsız geçer. CI'da anahtar verildiğinde `signAllPublications()` devreye girer.
İmzayı yerelde denemek için geçici bir anahtarla:

```bash
ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --export-secret-keys --armor <KEYID>)" \
ORG_GRADLE_PROJECT_signingInMemoryKeyPassword='...' \
./gradlew publishToMavenLocal   # .asc dosyaları da çıkar
```

## Notlar

- Yerelde imzasız doğrulama bilinçlidir. CI'da imzasız yayın **mümkün değildir**: yayın adımından önceki ön kontrol, dört secret'tan biri boşsa işi anlamlı bir hata mesajıyla düşürür.
- Entegrasyon iki modülü de ekler: `bir-android-attribution` çekirdeğe `implementation` ile bağlıdır (genel yüzeyinde
  çekirdek tipi yok, `api` gerekmez); tüketici zaten `Cizeylem.initialize` çağırdığı için çekirdeği açıkça ekler.
- **İmzalı yol (`signAllPublications()`) ve `publishToMavenCentral` gerçek ortamda hiç çalıştırılmadı** (GPG anahtarı
  ve Sonatype hesabı henüz yok). İlk gerçek yayın bir doğrulama adımıdır; yalnız `publishToMavenLocal` (imzasız) denendi.
