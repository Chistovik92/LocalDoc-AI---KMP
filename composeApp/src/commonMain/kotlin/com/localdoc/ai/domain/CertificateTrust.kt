package com.localdoc.ai.domain

import com.localdoc.ai.net.CertificateAllowance
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Что пользователь разрешил для сайтов с непроверенным сертификатом: [allowAny] — галочка «открывать любые»,
 * [sites] — сайт (хост[:порт]) → отпечаток SHA-256 того сертификата, которому разрешено доверять на этом сайте.
 */
@Serializable
data class CertificateTrustState(
    val allowAny: Boolean = false,
    val sites: Map<String, String> = emptyMap()
) {
    fun allowanceFor(site: String) = CertificateAllowance(setOfNotNull(sites[site]), allowAny)

    /** Доверие сайту заменяет прежнее: у сайта один доверенный сертификат. */
    fun trusting(site: String, fingerprint: String) = copy(sites = sites + (site to fingerprint))

    fun forgetting(site: String) = copy(sites = sites - site)
}

/**
 * Согласия пользователя на сайты, чей сертификат система не признаёт (самоподписанный, неизвестный центр, истёк).
 * Согласие на сайт привязано к отпечатку сертификата: если сайт его сменит, приложение спросит снова.
 */
class CertificateTrust(private val setting: PersistedSetting<CertificateTrustState>) {
    val state: StateFlow<CertificateTrustState> get() = setting.value

    fun allowanceFor(site: String): CertificateAllowance = setting.value.value.allowanceFor(site)

    fun trust(site: String, fingerprint: String) = setting.update { it.trusting(site, fingerprint) }

    fun forget(site: String) = setting.update { it.forgetting(site) }

    fun setAllowAny(allow: Boolean) = setting.update { it.copy(allowAny = allow) }
}
