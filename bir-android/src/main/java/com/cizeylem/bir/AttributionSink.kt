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

    /**
     * Kimlikli gönderim. [onDelivered], KALICI DEVRİN gerçekleşip gerçekleşmediğini bildirir: bu yolda
     * "alanlar çekirdeğin dayanıklı kuyruğuna yazıldı" demektir (kuyruğun kendi tekrarı devreye girer).
     *
     * Çağrı asenkrondur; [onDelivered] çekirdeğin thread'inde, en fazla bir kez çağrılır. Çekirdek yoksa
     * ya da kapatılmışsa `false` ile (ya da hiç) çağrılabilir — çağıran her iki durumu da kaldırmalıdır.
     */
    fun sendIdentified(fields: Map<String, Any?>, onDelivered: (Boolean) -> Unit)

    /**
     * Kimliksiz gönderim (`/v1/attribution/anon`). Bu yolda kuyruk YOKTUR (rızasız kalıcı iz bırakılamaz),
     * bu yüzden [onDelivered] yalnız sunucudan **2xx** alındığında `true` olur.
     */
    fun sendAnonymous(fields: Map<String, Any?>, onDelivered: (Boolean) -> Unit)
}
