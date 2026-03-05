/*
 * Copyright (c) 2024 European Commission
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

package eu.europa.ec.corelogic.util

import eu.europa.ec.businesslogic.controller.log.LogController
import eu.europa.ec.eudi.iso18013.transfer.readerauth.ReaderTrustStore
import java.math.BigInteger
import java.security.Principal
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.util.Date

class SoftReaderTrustStore(
    private val delegate: ReaderTrustStore,
    private val logController: LogController
) : ReaderTrustStore {

    override fun createCertificationTrustPath(chain: List<X509Certificate>): List<X509Certificate> {
        val path = delegate.createCertificationTrustPath(chain)
        val resultChain = path ?: chain

        if (path == null) {
            val issuer = chain.firstOrNull()?.issuerX500Principal?.name ?: "Unknown"
            logController.w("ReaderTrustStore") {
                "createCertificationTrustPath: No trusted certificate found in chain for issuer: $issuer. Proceeding anyway (Soft Trust)."
            }
        }

        return resultChain
    }

    override fun validateCertificationTrustPath(chainToDocumentSigner: List<X509Certificate>): Boolean {
        val isValid = delegate.validateCertificationTrustPath(chainToDocumentSigner)
        if (!isValid) {
            val commonName = chainToDocumentSigner.firstOrNull()?.subjectX500Principal?.name ?: "Unknown"
            val issuer = chainToDocumentSigner.firstOrNull()?.issuerX500Principal?.name ?: "Unknown"
            logController.w("ReaderTrustStore") {
                "validateCertificationTrustPath: Certificate chain of $commonName is invalid or untrusted for issuer: $issuer. Proceeding anyway (Soft Trust)."
            }
        }
        return true
    }

}
