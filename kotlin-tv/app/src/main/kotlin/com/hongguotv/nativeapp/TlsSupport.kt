// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Old TVs and car head units ship a system trust store from before DigiCert Global Root G2 (and
 * G3) were added, so every request to the content site or the player API dies with
 * "Trust anchor for certification path not found". We bundle those public roots and accept a chain
 * that either the platform or the bundled roots validate — no hostname bypass, no "trust all".
 */
object TlsSupport {
    private const val ASSET_DIR = "certs"
    private val bundledFiles = listOf(
        "digicert_global_root_g2.pem",
        "digicert_global_root_g3.pem",
        "digicert_global_root_ca.pem"
    )

    private fun bundledTrustManager(context: Context): X509TrustManager? = runCatching {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        val factory = CertificateFactory.getInstance("X.509")
        bundledFiles.forEach { name ->
            context.assets.open("$ASSET_DIR/$name").use { input ->
                val certificate = factory.generateCertificate(input) as X509Certificate
                store.setCertificateEntry(certificate.subjectX500Principal.name, certificate)
            }
        }
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        manager.init(store)
        manager.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    }.getOrNull()

    private fun systemTrustManager(): X509TrustManager? = runCatching {
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        manager.init(null as KeyStore?)
        manager.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    }.getOrNull()

    private class CombinedTrustManager(
        private val system: X509TrustManager?,
        private val bundled: X509TrustManager?
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            system?.checkClientTrusted(chain, authType)
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            var failure: CertificateException? = null
            if (system != null) {
                try { system.checkServerTrusted(chain, authType); return }
                catch (problem: CertificateException) { failure = problem }
            }
            if (bundled != null) {
                try { bundled.checkServerTrusted(chain, authType); return }
                catch (problem: CertificateException) { failure = problem }
            }
            throw failure ?: CertificateException("no trust manager accepted the chain")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> =
            ((system?.acceptedIssuers ?: emptyArray()) + (bundled?.acceptedIssuers ?: emptyArray())).distinctBy { it.subjectX500Principal }.toTypedArray()
    }

    /** Adds the bundled roots to a client; the platform store keeps working as before. */
    fun client(builder: OkHttpClient.Builder, context: Context): OkHttpClient.Builder {
        val combined = CombinedTrustManager(systemTrustManager(), bundledTrustManager(context))
        val socketFactory = runCatching {
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(combined), SecureRandom()) }.socketFactory
        }.getOrNull() ?: return builder
        return builder.sslSocketFactory(socketFactory, combined)
    }

    /** True when the failure is a TLS trust problem rather than a plain network error. */
    fun isCertificateProblem(problem: Throwable): Boolean {
        var node: Throwable? = problem
        var depth = 0
        while (node != null && depth++ < 8) {
            val message = node.message.orEmpty()
            if (node is CertPathValidatorException ||
                (node is CertificateException && message.contains("Trust anchor", true)) ||
                (node is SSLHandshakeException && message.contains("certification path", true))) return true
            node = node.cause
        }
        return false
    }
}
