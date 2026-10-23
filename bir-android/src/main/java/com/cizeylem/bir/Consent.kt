package com.cizeylem.bir

/**
 * Üç ayrı rıza anahtarı (SPEC §5). Üçü de varsayılan KAPALI: rıza açıkça verilmedikçe SDK kalıcı kimlik yazmaz
 * ve event göndermez.
 *
 * `@JvmOverloads`: Java çağıranlar yalnız verdikleri anahtarlara kadar kısa kurucuyu kullanabilsin diye.
 */
data class Consent @JvmOverloads constructor(
    val analytics: Boolean = false,
    val attribution: Boolean = false,
    val adSharing: Boolean = false,
)
