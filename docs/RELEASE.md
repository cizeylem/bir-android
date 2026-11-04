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

`android-v<VERSION_NAME>`, örneğin `android-v0.1.0`; ön sürüm `android-v0.2.0-rc.1`.
Tag push edilince `.github/workflows/publish.yml` çalışır. İki iş vardır:

1. **`publish`** (yalnız `contents: read`; Central sırlarını bu iş görür):
   tag ile `VERSION_NAME` eşleşmesini ve dört secret'ın doluluğunu doğrular, testleri ve 150 KB AAR sınırını
   çalıştırır, sonra `publishAndReleaseToMavenCentral` çağırır. Eşleşme ya da sır eksikse yayın başlamaz.
2. **`github-release`** (`needs: publish`, yalnız `contents: write`; sır görmez, checkout etmez):
   aynı tag için `gh release create --generate-notes` ile GitHub Release açar. Sürümde `-` varsa
   (`0.2.0-rc.1`) Release `--prerelease` olarak işaretlenir. Release zaten varsa (iş yeniden çalıştırıldıysa) atlar.

### Otomatik release

Her iki modülün `build.gradle.kts`'inde
`publishToMavenCentral(automaticRelease = true, validateDeployment = DeploymentValidation.PUBLISHED)` vardır
(vanniktech `0.37.0`). Sonucu:

- Yükleme sonrası Central Portal'da elle **"Publish" yoktur**; dağıtım doğrulanınca kendiliğinden yayınlanır.
- İki modül **tek dağıtım** olarak yüklenir (plugin'in paylaşılan build servisi); biri yayınlanıp diğeri
  kalamaz.
- Gradle görevi, dağıtım `PUBLISHED` olana kadar bekler (genelde 10-30 dk; plugin zaman aşımı 60 dk, iş
  zaman aşımı 90 dk). Doğrulama `FAILED` olursa görev Portal'ın hata mesajlarını basarak düşer. Bu yüzden
  GitHub Release yalnız Central'da gerçekten yayınlanmış bir sürüm için açılır.
- Not: `PUBLISHED` olduktan sonra paketin `repo1.maven.org`'a ve aramaya düşmesi birkaç dakika daha sürebilir.

## Repo secrets (Doğukan girer)

| Secret | İçerik |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Central Portal **kullanıcı belirteci** adı (giriş e-postası/kullanıcı adı DEĞİL) |
| `MAVEN_CENTRAL_PASSWORD` | Aynı belirtecin parolası |
| `SIGNING_IN_MEMORY_KEY` | ASCII-armored GPG özel anahtarı (`gpg --export-secret-keys --armor <PARMAK_İZİ>`): `-----BEGIN PGP PRIVATE KEY BLOCK-----` ile `-----END PGP PRIVATE KEY BLOCK-----` satırları dahil, çok satırlı metnin **tamamı** |
| `SIGNING_IN_MEMORY_KEY_PASSWORD` | Anahtarın parolası |

İş akışı bunları `ORG_GRADLE_PROJECT_mavenCentralUsername`, `..._mavenCentralPassword`, `..._signingInMemoryKey`,
`..._signingInMemoryKeyPassword` ortam değişkenlerine bağlar. Sırlar koda ve depoya yazılmaz.
Nasıl girileceği: aşağıda "İlk kurulum", adım 4.

## İlk kurulum (adım adım, bir kez)

Komutlardaki `<...>` yerlerini kendi değerlerinizle doldurun. Hiçbir sırrı dosyaya, depoya ya da sohbete yazmayın.

### 1. Central Portal hesabı ve namespace

1. <https://central.sonatype.com> → **Sign in** → hesap açın (GitHub ile ya da e-posta ile).
2. **Namespaces** → **Add Namespace** → `com.cizeylem`. Portal bir **doğrulama anahtarı** verir.
3. `cizeylem.com` alan adının DNS'ine, kök (`@`) için bir **TXT** kaydı ekleyin; değeri o doğrulama anahtarıdır.
   Yayıldığını kontrol edin:

   ```bash
   dig +short TXT cizeylem.com
   ```

4. Portal'da **Verify Namespace**. Durum *Verified* olunca TXT kaydı silinebilir.
5. **Kullanıcı belirteci**: Portal'da sağ üst menü → **View Account** → **Generate User Token**. Çıkan
   *username* ve *password* adım 4'te secret olur (Portal bunları yalnız bir kez gösterir).

**Alan adı yoksa / DNS'e erişim yoksa:** `io.github.cizeylem` namespace'i kullanılabilir. GitHub ile giriş
yapan **kullanıcı** için `io.github.<kullanıcı>` kendiliğinden doğrulanır; `cizeylem` bir GitHub
**organizasyonu** ise Portal, adı doğrulama anahtarı olan geçici bir public depoyu o organizasyonda açmanızı ister.
Bu yolu seçerseniz:

- `gradle.properties`'te `GROUP=io.github.cizeylem` olur; Maven koordinatları `io.github.cizeylem:bir-android`
  ve `io.github.cizeylem:bir-android-attribution` olur.
- Kök `README.md`'deki `implementation(...)` satırları ve bu belgedeki koordinat tablosu buna göre değişir.
- Kotlin paket adları (`com.cizeylem.bir...`) ve Android `namespace` **değişmez**; koordinat ile paket adı bağımsızdır.
- Yayınlanmış bir koordinat sonradan taşınamaz: grup ilk yayından **önce** kesinleşmelidir.

### 2. GPG anahtarı üretme ve açık anahtarı yayma

```bash
gpg --version   # yoksa: brew install gnupg
# Yalnız imza için RSA 4096 anahtar, 3 yıl geçerli. Parola sorar: güçlü bir parola verin (secret 4 olur).
gpg --quick-generate-key "Çizeylem <EPOSTA>" rsa4096 sign 3y
# Parmak izini (40 karakter) alın; aşağıda <PARMAK_İZİ>:
gpg --list-secret-keys --keyid-format=long
```

Central, imzayı doğrulamak için açık anahtarı bir anahtar sunucusunda arar. İkisine de yükleyin:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <PARMAK_İZİ>
gpg --export --armor <PARMAK_İZİ> | curl -T - https://keys.openpgp.org
```

`keys.openpgp.org` bir doğrulama bağlantısı döndürür; açıp e-postayı doğrulayın (doğrulanmamış anahtar
kimliksiz yayınlanır). Yayıldığını kontrol edin:

```bash
gpg --keyserver keyserver.ubuntu.com --recv-keys <PARMAK_İZİ>
```

Anahtarın süresi dolarsa CI imzası Central'da reddedilir: süreyi `gpg --quick-set-expire <PARMAK_İZİ> 3y` ile
uzatıp açık anahtarı yeniden yükleyin. Özel anahtarın şifreli bir yedeğini güvenli bir yerde saklayın.

### 3. `SIGNING_IN_MEMORY_KEY` biçimi

vanniktech, `signingInMemoryKey` Gradle özelliğini Gradle'ın `useInMemoryPgpKeys` imzalayıcısına verir ve
**ASCII-armored özel anahtar** bekler; komut:

```bash
gpg --export-secret-keys --armor <PARMAK_İZİ>
```

Çıktı çok satırlıdır (`-----BEGIN PGP PRIVATE KEY BLOCK-----` … `-----END PGP PRIVATE KEY BLOCK-----`) ve
**olduğu gibi, tamamı** secret'a girer; satır sonlarını silmeyin, base64'e çevirmeyin. Anahtar parolalı olduğu
için `SIGNING_IN_MEMORY_KEY_PASSWORD` da gerekir. `signingInMemoryKeyId` gerekmez (tek anahtar dışa aktarılır).
Aşağıdaki adım çıktıyı ekrana ya da diske yazmadan doğrudan secret'a boru ile verir.

### 4. Dört secret'ı girme

`gh` CLI ile (depoya admin yetkisi gerekir). Değer verilmeyen komutlar değeri gizli olarak sorar;
böylece sırlar kabuk geçmişine düşmez:

```bash
gh secret set MAVEN_CENTRAL_USERNAME -R cizeylem/bir-android
gh secret set MAVEN_CENTRAL_PASSWORD -R cizeylem/bir-android
gpg --export-secret-keys --armor <PARMAK_İZİ> | gh secret set SIGNING_IN_MEMORY_KEY -R cizeylem/bir-android
gh secret set SIGNING_IN_MEMORY_KEY_PASSWORD -R cizeylem/bir-android
gh secret list -R cizeylem/bir-android   # dördü de görünmeli (değerler görünmez)
```

`gpg` boru hattında anahtar parolasını sorabilir (pinentry); bu normaldir.

### 5. Depoyu public yapma

Kaynak ve SCM bağlantısı (`https://github.com/cizeylem/bir-android`) POM'da yayınlanır; kullanıcıların
erişebilmesi için depo ilk yayından önce public olmalıdır (GitHub → Settings → General → Danger Zone →
Change visibility). Public yapmadan önce geçmişte sır olmadığından emin olun.

**Actions:** `publish.yml` ancak Actions koşabiliyorsa çalışır. Private depoda dakikalar faturalanır; ödeme
ya da harcama limiti sorunu varsa işler "recent account payments have failed" ile hiç başlamaz (Ekim 2026'da
böyle oldu, sonra çözüldü). Public depolarda standart runner'lar ücretsizdir. Tag atmadan önce `main`'de son
`CI` koşusunun gerçekten koşup yeşil bittiğini görün: `publish.yml` aynı testleri tekrar koşar.

### 6. İlk tag

`main` güncel ve `gradle.properties`'te `VERSION_NAME=0.1.0` iken:

```bash
git checkout main && git pull
git tag android-v0.1.0 && git push origin android-v0.1.0
```

Actions sekmesinde "Maven Central yayını" iş akışını izleyin: önce `publish` (dağıtım `PUBLISHED` olana kadar
bekler), sonra `github-release`.

### 7. Doğrulama

- Portal: <https://central.sonatype.com/publishing/deployments> → dağıtım **PUBLISHED**.
- Dosyalar (görünmesi birkaç dakika sürebilir):
  <https://repo1.maven.org/maven2/com/cizeylem/bir-android/0.1.0/> ve
  <https://repo1.maven.org/maven2/com/cizeylem/bir-android-attribution/0.1.0/> (her birinde `.aar`, `.pom`,
  `-sources.jar`, `-javadoc.jar`, `.module` ve her birinin `.asc` imzası).
- Arama (indekslenmesi saatler sürebilir):
  <https://search.maven.org/search?q=g:com.cizeylem> ve <https://central.sonatype.com/namespace/com.cizeylem>.
- GitHub: <https://github.com/cizeylem/bir-android/releases/tag/android-v0.1.0>.
- **Temiz bir projeden çözülme:** Android Studio'da yeni bir boş proje açın (*Empty Activity*, minSdk ≥ 21),
  `app/build.gradle.kts`'e README'deki iki `implementation(...)` satırını ekleyin, sonra:

  ```bash
  ./gradlew :app:dependencies --configuration releaseRuntimeClasspath | grep cizeylem
  ./gradlew :app:assembleDebug --refresh-dependencies
  ```

  Çıktıda `com.cizeylem:bir-android:0.1.0` ve `com.cizeylem:bir-android-attribution:0.1.0` görünmeli; derleme
  `mavenLocal()` olmadan geçmeli (`~/.m2`'deki yerel kopya yanıltmasın).

### Bir şey ters giderse

- **`publish` yükleme öncesi düştü** (tag/sürüm eşleşmesi, eksik secret, test): Central'a hiçbir şey gitmedi.
  Düzeltip tag'i silin ve yeniden atın:
  `git push --delete origin android-v0.1.0 && git tag -d android-v0.1.0`, sonra adım 6.
- **Doğrulama `FAILED`**: iş günlüğünde Portal'ın hata listesi vardır (çoğunlukla imza/açık anahtar bulunamadı ya
  da namespace doğrulanmamış). Sürüm yayınlanmamıştır; Portal'daki başarısız dağıtımı **Drop** edip düzeltin ve
  tag'i yeniden atın.
- **`publish` geçti, `github-release` düştü**: sürüm Central'dadır. Yalnız o işi "Re-run failed jobs" ile
  yeniden çalıştırın; Release zaten varsa iş atlar.
- Yayınlanmış bir sürüm **silinemez ve üzerine yazılamaz**; hatayı yeni PATCH sürümüyle düzeltin.

## İlk yayın öncesi kontrol listesi

- [ ] Sonatype Central Portal hesabı açıldı, `com.cizeylem` namespace'i doğrulandı (DNS TXT) — adım 1.
- [ ] GPG anahtarı üretildi, açık anahtar anahtar sunucularına yüklendi — adım 2.
- [ ] Dört secret repoya girildi — adım 3-4.
- [ ] Depo public — adım 5.
- [ ] Lisans: Apache-2.0 onaylandı (kardeş depo `bir-js` ile aynı); depoda `LICENSE` ve `NOTICE` var, POM lisansı ile tutarlı.
- [ ] POM'daki geliştirici/SCM bilgisi (`github.com/cizeylem/bir-android`, geliştirici "Çizeylem") doğru.
- [ ] `VERSION_NAME` yayınlanacak sürüme ayarlandı ve ana dala alındı.
- [ ] Yerel doğrulama temiz (aşağıda); `./scripts/measure-aar.sh` 150 KB altında.
- [ ] Tag atıldı: `git tag android-v<VERSION_NAME> && git push origin android-v<VERSION_NAME>` — adım 6.
- [ ] Dağıtım Central'da `PUBLISHED`, GitHub Release açıldı, temiz projeden çözülüyor — adım 7.
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
- **İmzalı yol (`signAllPublications()`), `publishAndReleaseToMavenCentral` ve `publish.yml` gerçek ortamda hiç
  çalıştırılmadı** (GPG anahtarı ve Sonatype hesabı henüz yok). İlk gerçek yayın bir doğrulama adımıdır; yalnız
  `publishToMavenLocal` (imzasız) denendi. İlk yayın için bir ön sürüm (`0.1.0-rc.1`) denemek mümkündür, ama o
  sürüm de Central'da kalıcıdır.

## CI (`.github/workflows/ci.yml`)

Her push ve pull request'te çalışır; sır kullanmaz (yayın yalnız `publish.yml`). İki iş:
`dogrula` (iki modülün `testDebugUnitTest`'i, `lint`, `scripts/measure-aar.sh` AAR boyut kapısı) ve
`ornekler` (`samples/phone` ve `samples/tv` `assembleDebug`; ayrı iş olduğundan paralel koşar).

## S5 geldiğinde: `spec-adapter` (HENÜZ YAZILMADI)

Ortak test seti `bir-js/spec/` henüz yok (SPEC §9; Doğukan/Claude tarafında bekliyor). Olmayan bir
sözleşmeye göre adaptör yazmak boşa gider, bu yüzden modül bilerek eklenmedi. Set gelince:

1. `spec-adapter` modülü (JVM uygulaması, Android'e bağlı olmayan çekirdek mantığı sürer) eklenir; `settings.gradle.kts`'e dahil edilir.
2. Adaptör **stdin'den JSON komut** okur, **stdout'a JSON sonuç** yazar; komut/sonuç biçimi ve alanları
   birebir `bir-js/spec/` sözleşmesindendir. Biçim burada ÖNCEDEN uydurulmaz; önce sözleşme okunur.
3. `ci.yml`'e, `bir-js/spec/` koşucusunu adaptöre bağlayan bir adım eklenir ve SPEC §8'deki
   "S5 gelince `bir-js/spec/` adaptörü yeşil" maddesi bu adımla kapanır.
