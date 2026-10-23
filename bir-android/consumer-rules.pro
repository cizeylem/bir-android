# Çekirdek modül `isMinifyEnabled = false` ile yayınlanır; bu kurallar AAR'ın içinde tüketicinin R8'ine geçer.
# Amaç dar: genel API'yi (Java çağıranlar ve yansımayla erişen araçlar için) korumak.

# Genel yüzey: Cizeylem, Configuration, Consent, Stats, Logger.
# `com.cizeylem.bir.*` yalnız bu paketi kapsar; `com.cizeylem.bir.internal.**` bilinçli olarak dışarıdadır:
# iç sınıfların adı korunmaz, küçülmesi ve karartılması istenir.
-keep public class com.cizeylem.bir.* {
    public protected *;
}

# Kotlin'in genel API'de ürettiği yardımcılar (örn. @JvmOverloads köprüleri) yukarıdaki kuralla korunur.
# org.json platformun parçasıdır ve SDK onu YANSIMA ile kullanmaz; ek kural gerekmez.
# SDK hiçbir yerde Class.forName / newInstance kullanmaz, bu yüzden -keepclassmembers kuralı da yoktur.
