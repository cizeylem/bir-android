package com.cizeylem.bir

/**
 * Attribution modülünün çekirdeğe veri bıraktığı kapı. Arayüz çekirdekte durur: modül çekirdeğe bağımlıdır,
 * tersi olamaz. `Cizeylem` bunu uygular; rıza durumuna göre kimlikli ya da kimliksiz uca yönlendirir.
 * Alan değerleri HAM gönderilir; yorumlama/şifre çözme sunucudadır.
 */
interface AttributionSink {
    fun sendIdentified(fields: Map<String, Any?>)
    fun sendAnonymous(fields: Map<String, Any?>)
}
