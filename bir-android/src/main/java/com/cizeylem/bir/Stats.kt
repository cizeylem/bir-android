package com.cizeylem.bir

/**
 * SDK'nın anlık durumu (`Cizeylem.stats()`). Genel API'nin parçası olduğu için `internal` değildir
 * ve `com.cizeylem.bir.internal` yerine genel pakette durur.
 *
 * `dropped`: kuyruğun taşmada düşürdüğü; `rejected`: sunucunun kabul etmediği (400/413). İkisi karıştırılmaz.
 * `queued`: gönderilmeyi bekleyen event sayısı. `disabled`: 401/403 sonrası SDK durduruldu mu.
 */
data class Stats(
    val queued: Int,
    val dropped: Int,
    val rejected: Int,
    val disabled: Boolean,
)
