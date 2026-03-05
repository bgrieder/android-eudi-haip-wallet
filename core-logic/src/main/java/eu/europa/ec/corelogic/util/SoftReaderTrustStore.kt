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

    override fun createCertificationTrustPath(chain: List<X509Certificate>): List<X509Certificate>? {
        val path = delegate.createCertificationTrustPath(chain)
        val resultChain = path ?: chain
        
        if (path == null) {
            val issuer = chain.firstOrNull()?.issuerX500Principal?.name ?: "Unknown"
            logController.w("ReaderTrustStore") {
                "createCertificationTrustPath: No trusted certificate found in chain for issuer: $issuer. Proceeding anyway (Soft Trust)."
            }
        }

        // Wrap certificates to bypass SAN (Subject Alternative Name) checks
        return resultChain.map { SoftX509Certificate(it, logController) }
    }

    override fun validateCertificationTrustPath(chainToDocumentSigner: List<X509Certificate>): Boolean {
        val isValid = delegate.validateCertificationTrustPath(chainToDocumentSigner)
        if (!isValid) {
            val issuer = chainToDocumentSigner.firstOrNull()?.issuerX500Principal?.name ?: "Unknown"
            logController.w("ReaderTrustStore") {
                "validateCertificationTrustPath: Certificate chain is invalid or untrusted for issuer: $issuer. Proceeding anyway (Soft Trust)."
            }
        }
        return true
    }

    /**
     * A wrapper for X509Certificate that injects common development SANs
     * to bypass ClientId mismatch errors.
     */
    private class SoftX509Certificate(
        private val delegate: X509Certificate,
        private val logController: LogController
    ) : X509Certificate() {

        override fun getSubjectAlternativeNames(): MutableCollection<MutableList<*>>? {
            val sans = delegate.subjectAlternativeNames?.toMutableList() ?: mutableListOf()
            
            // Add common development hostnames/IPs as DNS names (type 2) and IP names (type 7)
            val devNames = listOf("127.0.0.1", "localhost", "192.168.1.6")
            
            logController.w("ReaderTrustStore") {
                "Injecting development SANs ($devNames) to bypass ClientId validation."
            }

            devNames.forEach { name ->
                sans.add(mutableListOf(2, name)) // DNS
                sans.add(mutableListOf(7, name)) // IP
            }
            
            return sans
        }

        // Delegate all other methods to the original certificate
        override fun checkValidity() = delegate.checkValidity()
        override fun checkValidity(date: Date?) = delegate.checkValidity(date)
        override fun getVersion() = delegate.version
        override fun getSerialNumber(): BigInteger = delegate.serialNumber
        override fun getIssuerDN(): Principal = delegate.issuerDN
        override fun getSubjectDN(): Principal = delegate.subjectDN
        override fun getNotBefore(): Date = delegate.notBefore
        override fun getNotAfter(): Date = delegate.notAfter
        override fun getTBSCertificate(): ByteArray = delegate.tbsCertificate
        override fun getSignature(): ByteArray = delegate.signature
        override fun getSigAlgName(): String = delegate.sigAlgName
        override fun getSigAlgOID(): String = delegate.sigAlgOID
        override fun getSigAlgParams(): ByteArray? = delegate.sigAlgParams
        override fun getIssuerUniqueID(): BooleanArray? = delegate.issuerUniqueID
        override fun getSubjectUniqueID(): BooleanArray? = delegate.subjectUniqueID
        override fun getKeyUsage(): BooleanArray? = delegate.keyUsage
        override fun getBasicConstraints(): Int = delegate.basicConstraints
        override fun getEncoded(): ByteArray = delegate.encoded
        override fun verify(key: PublicKey?) = delegate.verify(key)
        override fun verify(key: PublicKey?, sigProvider: String?) = delegate.verify(key, sigProvider)
        override fun toString(): String = delegate.toString()
        override fun getPublicKey(): PublicKey = delegate.publicKey
        override fun hasUnsupportedCriticalExtension(): Boolean = delegate.hasUnsupportedCriticalExtension()
        override fun getCriticalExtensionOIDs(): MutableSet<String>? = delegate.criticalExtensionOIDs
        override fun getNonCriticalExtensionOIDs(): MutableSet<String>? = delegate.nonCriticalExtensionOIDs
        override fun getExtensionValue(oid: String?): ByteArray? = delegate.getExtensionValue(oid)
    }
}
