package com.cizeylem.bir

/**
 * Attribution modülünün çekirdeğe veri bıraktığı kapı. Arayüz çekirdekte durur: modül çekirdeğe bağımlıdır,
 * tersi olamaz. `Cizeylem` bunu uygular; rıza durumuna göre kimlikli ya da kimliksiz uca yönlendirir.
 * Alan değerleri HAM gönderilir; yorumlama/şifre çözme sunucudadır.
 */
interface AttributionSink {
    /**
     * [task]'ı çekirdeğin KENDİ thread'inde, rıza diskten okunduktan SONRA çalıştırır. Çekirdek kurulmadıysa
     * (ya da kapatıldıysa) HİÇ çalıştırmaz: modülün tek tetikleyicisi budur, böylece `Cizeylem.initialize`
     * çağrılmamışken attribution ne thread açar ne de toplamaya başlar (gidecek yer yoktur).
     *
     * Rıza "çek" yerine "it" ile veriliyor: anlık bir okuyucu, çekirdeğin bootstrap'ından önce çalışıp
     * verilmiş rızayı kaçırabilirdi; buradaki sıralama çekirdeğin görev kuyruğunun garantisidir.
     */
    fun withConsent(task: (Consent) -> Unit)

    fun sendIdentified(fields: Map<String, Any?>)
    fun sendAnonymous(fields: Map<String, Any?>)
}
