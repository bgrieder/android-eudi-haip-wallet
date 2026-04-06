/*
 * Copyright (c) 2023 European Commission
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the European
 * Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work
 * except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific language
 * governing permissions and limitations under the Licence.
 */

package eu.europa.ec.networklogic.di

import android.content.Context
import eu.europa.ec.businesslogic.config.AppBuildType
import eu.europa.ec.businesslogic.config.ConfigLogic
import eu.europa.ec.networklogic.repository.WalletAttestationRepository
import eu.europa.ec.networklogic.repository.WalletAttestationRepositoryImpl
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@Module
@ComponentScan("eu.europa.ec.networklogic")
class LogicNetworkModule

@Single
fun provideJson(): Json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    isLenient = true
}

@Single
fun provideHttpClient(context: Context, json: Json, configLogic: ConfigLogic): HttpClient {
    return HttpClient(Android) {

        if (configLogic.appBuildType == AppBuildType.DEBUG) {
            val devTrustManager = buildDevTrustManager(context)
            if (devTrustManager != null) {
                engine {
                    sslManager = { connection ->
                        val sslContext = SSLContext.getInstance("TLS")
                        sslContext.init(null, arrayOf<TrustManager>(devTrustManager), null)
                        connection.sslSocketFactory = sslContext.socketFactory
                        // No HostnameVerifier override — standard hostname checking applies
                        // (trusted cert SANs must match the server hostname)
                    }
                }
            }
        }

        install(Logging) {
            logger = Logger.DEFAULT
            level = when (configLogic.appBuildType) {
                AppBuildType.DEBUG -> LogLevel.BODY
                AppBuildType.RELEASE -> LogLevel.NONE
            }
        }

        install(ContentNegotiation) {
            json(
                json = json,
                contentType = ContentType.Application.Json
            )
        }
    }
}

@Single
fun provideWalletAttestationRepository(httpClient: HttpClient): WalletAttestationRepository =
    WalletAttestationRepositoryImpl(httpClient)

/**
 * Builds an [X509TrustManager] that trusts only the CA certificates found in
 * `assets/ewqwe_dev_cas/`. Any `.pem` or `.crt` file placed in that directory is
 * automatically picked up at runtime — no code change needed when adding a new dev CA.
 *
 * Returns `null` if the directory is absent or contains no valid certificates, which causes
 * the caller to skip the custom SSL configuration and fall back to the system trust store.
 *
 * **Only called in DEBUG builds.**
 */
private fun buildDevTrustManager(context: Context): X509TrustManager? {
    val assetManager = context.assets
    val certFactory = CertificateFactory.getInstance("X.509")
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).also { it.load(null, null) }
    var certCount = 0

    try {
        val files = assetManager.list("ewqwe_dev_cas") ?: return null
        for (fileName in files) {
            if (!fileName.endsWith(".pem") && !fileName.endsWith(".crt")) continue
            assetManager.open("ewqwe_dev_cas/$fileName").use { stream ->
                @Suppress("UNCHECKED_CAST")
                val certs = certFactory.generateCertificates(stream) as Collection<X509Certificate>
                for (cert in certs) {
                    keyStore.setCertificateEntry("ewqwe_dev_ca_${certCount++}", cert)
                }
            }
        }
    } catch (_: Exception) {
        return null
    }

    if (certCount == 0) return null

    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(keyStore)
    return tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
}
