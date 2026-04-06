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

package eu.europa.ec.corelogic.di

import android.content.Context
import eu.europa.ec.businesslogic.config.AppBuildType
import eu.europa.ec.businesslogic.config.ConfigLogic
import eu.europa.ec.businesslogic.controller.log.LogController
import eu.europa.ec.businesslogic.provider.UuidProvider
import eu.europa.ec.corelogic.config.WalletCoreConfig
import eu.europa.ec.corelogic.config.WalletCoreConfigImpl
import eu.europa.ec.corelogic.controller.WalletCoreDocumentsController
import eu.europa.ec.corelogic.controller.WalletCoreDocumentsControllerImpl
import eu.europa.ec.corelogic.controller.WalletCoreLogController
import eu.europa.ec.corelogic.controller.WalletCoreLogControllerImpl
import eu.europa.ec.corelogic.controller.WalletCoreTransactionLogController
import eu.europa.ec.corelogic.controller.WalletCoreTransactionLogControllerImpl
import eu.europa.ec.corelogic.provider.WalletCoreAttestationProvider
import eu.europa.ec.corelogic.provider.WalletCoreAttestationProviderImpl
import eu.europa.ec.eudi.iso18013.transfer.readerauth.ReaderTrustStore
import eu.europa.ec.eudi.wallet.EudiWallet
import eu.europa.ec.networklogic.repository.WalletAttestationRepository
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.storagelogic.dao.BookmarkDao
import eu.europa.ec.storagelogic.dao.RevokedDocumentDao
import eu.europa.ec.storagelogic.dao.TransactionLogDao
import io.ktor.client.HttpClient
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Single
import org.koin.mp.KoinPlatform
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

const val PRESENTATION_SCOPE_ID = "presentation_scope_id"

@Module
@ComponentScan("eu.europa.ec.corelogic")
class LogicCoreModule

@Single
fun provideEudiWallet(
    context: Context,
    walletCoreConfig: WalletCoreConfig,
    walletCoreLogController: WalletCoreLogController,
    walletCoreTransactionLogController: WalletCoreTransactionLogController,
    walletCoreAttestationProvider: WalletCoreAttestationProvider,
    httpClient: HttpClient,
    configLogic: ConfigLogic
): EudiWallet {
    return EudiWallet(
        context = context,
        config = walletCoreConfig.config,
        walletProvider = walletCoreAttestationProvider
    ) {
        withLogger(walletCoreLogController)
        withTransactionLogger(walletCoreTransactionLogController)
        withKtorHttpClientFactory { httpClient }
        if (configLogic.appBuildType == AppBuildType.DEBUG) {
            val devCas = loadDevCas(context)
            if (devCas.isNotEmpty()) {
                withReaderTrustStore(ReaderTrustStore.getDefault(devCas))
            }
        }
        // In RELEASE builds: no withReaderTrustStore() call here.
        // configureReaderTrustStore() in WalletCoreConfigImpl provides the production trust anchors.
    }
}

@Single
fun provideWalletCoreConfig(
    context: Context,
): WalletCoreConfig = WalletCoreConfigImpl(context)

/**
 * Loads all CA certificates from `assets/ewqwe_dev_cas/` for use in the debug Reader Trust Store.
 * Any `.pem` or `.crt` file dropped into that directory is automatically trusted — no code change
 * needed when adding a new developer CA.
 *
 * **Only called in DEBUG builds.**
 */
private fun loadDevCas(context: Context): List<X509Certificate> {
    val certFactory = CertificateFactory.getInstance("X.509")
    val certs = mutableListOf<X509Certificate>()
    try {
        val files = context.assets.list("ewqwe_dev_cas") ?: return emptyList()
        for (fileName in files) {
            if (!fileName.endsWith(".pem") && !fileName.endsWith(".crt")) continue
            context.assets.open("ewqwe_dev_cas/$fileName").use { stream ->
                @Suppress("UNCHECKED_CAST")
                certs.addAll(certFactory.generateCertificates(stream) as Collection<X509Certificate>)
            }
        }
    } catch (_: Exception) {
        // Missing directory or unreadable file — return whatever was loaded so far
    }
    return certs
}

@Single
fun provideWalletCoreLogController(logController: LogController): WalletCoreLogController =
    WalletCoreLogControllerImpl(logController)

@Single
fun provideWalletCoreTransactionLogController(
    transactionLogDao: TransactionLogDao,
    uuidProvider: UuidProvider
): WalletCoreTransactionLogController = WalletCoreTransactionLogControllerImpl(
    transactionLogDao = transactionLogDao,
    uuidProvider = uuidProvider
)

@Single
fun provideWalletCoreAttestationProvider(
    walletAttestationRepository: WalletAttestationRepository,
    walletCoreConfig: WalletCoreConfig
): WalletCoreAttestationProvider =
    WalletCoreAttestationProviderImpl(
        walletCoreConfig = walletCoreConfig,
        walletAttestationRepository = walletAttestationRepository
    )

@Factory
fun provideWalletCoreDocumentsController(
    resourceProvider: ResourceProvider,
    eudiWallet: EudiWallet,
    walletCoreConfig: WalletCoreConfig,
    bookmarkDao: BookmarkDao,
    transactionLogDao: TransactionLogDao,
    revokedDocumentDao: RevokedDocumentDao
): WalletCoreDocumentsController =
    WalletCoreDocumentsControllerImpl(
        resourceProvider,
        eudiWallet,
        walletCoreConfig,
        bookmarkDao,
        transactionLogDao,
        revokedDocumentDao
    )

/**
 * Koin scope that lives for all the document presentation flow. It is manually handled from the
 * ViewModels that start and participate on the presentation process
 * */
@Scope
class WalletPresentationScope

/**
 * Get Koin scope that lives during document presentation flow
 * */
fun getOrCreatePresentationScope(): org.koin.core.scope.Scope =
    KoinPlatform.getKoin().getOrCreateScope<WalletPresentationScope>(PRESENTATION_SCOPE_ID)
