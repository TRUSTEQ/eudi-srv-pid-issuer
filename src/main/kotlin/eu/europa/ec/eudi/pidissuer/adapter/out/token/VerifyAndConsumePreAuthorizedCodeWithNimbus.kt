/*
 * Copyright (c) 2023-2026 European Commission
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.europa.ec.eudi.pidissuer.adapter.out.token

import arrow.core.raise.result
import arrow.core.toNonEmptySetOrNull
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.crypto.factories.DefaultJWEDecrypterFactory
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier
import com.nimbusds.jose.proc.JWEDecryptionKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.EncryptedJWT
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import eu.europa.ec.eudi.pidissuer.adapter.out.json.jsonSupport
import eu.europa.ec.eudi.pidissuer.adapter.out.nonce.NonceEncryptionKey
import eu.europa.ec.eudi.pidissuer.adapter.out.persistence.InMemoryUsedPreAuthorizedCodeChecker
import eu.europa.ec.eudi.pidissuer.domain.CredentialConfigurationId
import eu.europa.ec.eudi.pidissuer.domain.CredentialIssuerId
import eu.europa.ec.eudi.pidissuer.domain.PreAuthorizedCode
import eu.europa.ec.eudi.pidissuer.domain.Scope
import eu.europa.ec.eudi.pidissuer.domain.toKotlinInstant
import eu.europa.ec.eudi.pidissuer.port.out.token.PreAuthorizedCodeRedemption
import eu.europa.ec.eudi.pidissuer.port.out.token.VerifyAndConsumePreAuthorizedCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.bouncycastle.jce.provider.BouncyCastleProvider
import kotlin.time.Instant

/**
 * Decrypts a [PreAuthorizedCode] using Nimbus, verifies it's still active, and atomically consumes it so it
 * cannot be redeemed a second time. Mirrors
 * [eu.europa.ec.eudi.pidissuer.adapter.out.nonce.DecryptNonceWithNimbusAndVerify].
 */
internal class VerifyAndConsumePreAuthorizedCodeWithNimbus(
    private val issuer: CredentialIssuerId,
    private val decryptionKey: NonceEncryptionKey,
    private val usedCodeChecker: InMemoryUsedPreAuthorizedCodeChecker,
) : VerifyAndConsumePreAuthorizedCode {
    private val processor =
        DefaultJWTProcessor<SecurityContext>()
            .apply {
                jweTypeVerifier = DefaultJOSEObjectTypeVerifier(JOSEObjectType(PRE_AUTHORIZED_CODE_JWT_TYPE))
                jweKeySelector =
                    JWEDecryptionKeySelector(
                        decryptionKey.algorithm,
                        decryptionKey.method,
                        ImmutableJWKSet(JWKSet(decryptionKey.encryptionKey)),
                    )
                jweDecrypterFactory =
                    DefaultJWEDecrypterFactory()
                        .apply {
                            jcaContext.provider = BouncyCastleProvider()
                        }
                jwtClaimsSetVerifier =
                    DefaultJWTClaimsVerifier(
                        issuer.externalForm,
                        JWTClaimsSet
                            .Builder()
                            .issuer(issuer.externalForm)
                            .audience(issuer.externalForm)
                            .build(),
                        setOf("iss", "aud", "jti", "username", "scope", "iat", "exp"),
                    )
            }

    override suspend fun invoke(
        code: PreAuthorizedCode,
        at: Instant,
    ): PreAuthorizedCodeRedemption =
        withContext(Dispatchers.Default) {
            result {
                val jwt = EncryptedJWT.parse(code.value)
                val claimSet = processor.process(jwt, null)
                val expiresAt = requireNotNull(claimSet.expirationTime) { "expirationTime is required" }.toKotlinInstant()
                require(at < expiresAt) { "code has expired" }

                val jti = requireNotNull(claimSet.getJWTID()) { "jti is required" }
                val username = requireNotNull(claimSet.getStringClaim("username")) { "username is required" }
                val scopes =
                    requireNotNull(claimSet.getStringClaim("scope")) { "scope is required" }
                        .split(" ")
                        .filter { it.isNotBlank() }
                        .map { Scope(it) }
                        .toNonEmptySetOrNull()
                requireNotNull(scopes) { "scope must not be empty" }
                val customData =
                    claimSet
                        .getStringClaim("custom_data")
                        ?.let { raw ->
                            jsonSupport
                                .decodeFromString(JsonObject.serializer(), raw)
                                .entries
                                .associate { (id, data) -> CredentialConfigurationId(id) to data.jsonObject }
                        }.orEmpty()

                val firstUse = usedCodeChecker.markUsedIfNotAlready(jti, expiresAt, at)
                if (firstUse) {
                    PreAuthorizedCodeRedemption.Valid(username, scopes, customData)
                } else {
                    PreAuthorizedCodeRedemption.Invalid
                }
            }.getOrElse { PreAuthorizedCodeRedemption.Invalid }
        }
}
