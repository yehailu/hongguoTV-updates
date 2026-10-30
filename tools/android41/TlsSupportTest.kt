// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

class TlsSupportTest {
    private fun manager(accept: Boolean) = object : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, auth: String?) { if (!accept) throw CertificateException("rejected") }
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, auth: String?) { if (!accept) throw CertificateException("rejected") }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    private fun combined(system: X509TrustManager?, bundled: X509TrustManager?): X509TrustManager {
        val cls=Class.forName("com.hongguotv.nativeapp.TlsSupport\$CombinedTrustManager")
        val ctor=cls.getDeclaredConstructor(X509TrustManager::class.java,X509TrustManager::class.java)
        ctor.isAccessible=true
        return ctor.newInstance(system,bundled) as X509TrustManager
    }
    @Test fun rejectsWhenNeitherTrustManagerAccepts() {
        try { combined(manager(false),manager(false)).checkServerTrusted(emptyArray(),"RSA"); fail("untrusted chain accepted") }
        catch (_: CertificateException) { }
    }
    @Test fun permitsValidatedBundledRootAfterSystemRejection() {
        combined(manager(false),manager(true)).checkServerTrusted(emptyArray(),"RSA")
    }
    @Test fun rejectsWhenNoTrustManagerExists() {
        try { combined(null,null).checkServerTrusted(emptyArray(),"RSA"); fail("missing trust manager must fail closed") }
        catch (_: CertificateException) { }
        try { combined(null,manager(true)).checkClientTrusted(emptyArray(),"RSA"); fail("missing client trust manager must fail closed") }
        catch (_: CertificateException) { }
    }
    @Test fun bundledIsrgRootHasPinnedIdentity() {
        val input=requireNotNull(javaClass.classLoader.getResourceAsStream("certs/isrg_root_x1.pem"))
        val certificate=input.use { java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(it) } as X509Certificate
        val fingerprint=java.security.MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02X".format(it.toInt() and 255) }
        assertEquals("96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6",fingerprint)
        assertTrue(certificate.basicConstraints>=0)
        assertEquals(certificate.subjectX500Principal,certificate.issuerX500Principal)
        certificate.checkValidity()
        certificate.verify(certificate.publicKey)
    }
    @Test fun legacySocketFactoryDoesNotEnableTls10Or11() {
        val cls=Class.forName("com.hongguotv.nativeapp.TlsSupport\$Tls12SocketFactory")
        val ctor=cls.getDeclaredConstructor(SSLSocketFactory::class.java).apply { isAccessible=true }
        val factory=ctor.newInstance(SSLSocketFactory.getDefault()) as SSLSocketFactory
        (factory.createSocket() as SSLSocket).use { socket ->
            assertTrue(socket.enabledProtocols.isNotEmpty())
            assertTrue(socket.enabledProtocols.all { it=="TLSv1.2" || it=="TLSv1.3" })
        }
    }
}
