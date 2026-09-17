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

import arrow.core.nonEmptySetOf
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import eu.europa.ec.eudi.pidissuer.adapter.out.nonce.NonceEncryptionKey
import eu.europa.ec.eudi.pidissuer.adapter.out.persistence.InMemoryUsedPreAuthorizedCodeChecker
import eu.europa.ec.eudi.pidissuer.domain.CredentialConfigurationId
import eu.europa.ec.eudi.pidissuer.domain.CredentialIssuerId
import eu.europa.ec.eudi.pidissuer.domain.PreAuthorizedCode
import eu.europa.ec.eudi.pidissuer.domain.Scope
import eu.europa.ec.eudi.pidissuer.port.out.token.PreAuthorizedCodeRedemption
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal class PreAuthorizedCodeRoundTripTest {
    private val issuer = CredentialIssuerId.unsafe("https://eudi.ec.europa.eu/issuer")
    private val clock = Clock.System
    private val encryptionKey =
        NonceEncryptionKey(
            ECKeyGenerator(Curve.P_256).keyUse(KeyUse.ENCRYPTION).generate(),
        )
    private val generate = GeneratePreAuthorizedCodeWithNimbus(issuer, encryptionKey)

    private fun verifier() = VerifyAndConsumePreAuthorizedCodeWithNimbus(issuer, encryptionKey, InMemoryUsedPreAuthorizedCodeChecker())

    @Test
    fun `a freshly generated code is valid and carries the bound username and scopes`() =
        runTest {
            val now = clock.now()
            val scopes = nonEmptySetOf(Scope("eu.europa.ec.eudi.pid_mso_mdoc"))
            val code = generate(now, 10.minutes, "tneal", scopes)

            val redemption = verifier()(code, now)
            val valid = assertIs<PreAuthorizedCodeRedemption.Valid>(redemption)
            kotlin.test.assertEquals("tneal", valid.username)
            kotlin.test.assertEquals(scopes, valid.scopes)
        }

    @Test
    fun `a code cannot be redeemed twice`() =
        runTest {
            val now = clock.now()
            val code = generate(now, 10.minutes, "tneal", nonEmptySetOf(Scope("eu.europa.ec.eudi.pid_mso_mdoc")))
            val verifyAndConsume = verifier()

            assertIs<PreAuthorizedCodeRedemption.Valid>(verifyAndConsume(code, now))
            assertIs<PreAuthorizedCodeRedemption.Invalid>(verifyAndConsume(code, now))
        }

    @Test
    fun `an expired code is rejected`() =
        runTest {
            val now = clock.now()
            val code = generate(now, 1.seconds, "tneal", nonEmptySetOf(Scope("eu.europa.ec.eudi.pid_mso_mdoc")))

            val redemption = verifier()(code, now + 1.minutes)
            assertIs<PreAuthorizedCodeRedemption.Invalid>(redemption)
        }

    @Test
    fun `operator-entered custom data survives the generate-verify round trip`() =
        runTest {
            val now = clock.now()
            val configId = CredentialConfigurationId("urn:eudi:ehic:1:dc+sd-jwt-compact")
            val customData = mapOf(configId to buildJsonObject { put("family_name", "Doe") })
            val code =
                generate(
                    now,
                    10.minutes,
                    "tneal",
                    nonEmptySetOf(Scope("urn:eudi:ehic:1:dc+sd-jwt")),
                    customData,
                )

            val redemption = verifier()(code, now)
            val valid = assertIs<PreAuthorizedCodeRedemption.Valid>(redemption)
            assertEquals(customData, valid.customData)
        }

    @Test
    fun `a self-issued access token cannot be redeemed as a pre-authorized code`() =
        runTest {
            val now = clock.now()
            val generateAccessToken = GenerateSelfIssuedAccessTokenWithNimbus(issuer, encryptionKey)
            val accessToken =
                generateAccessToken(
                    generatedAt = now,
                    expiresIn = 10.minutes,
                    username = "tneal",
                    clientId = "wallet-abc",
                    scopes = nonEmptySetOf(Scope("eu.europa.ec.eudi.pid_mso_mdoc")),
                    dpopJwkThumbprint =
                        com.nimbusds.oauth2.sdk.dpop.JWKThumbprintConfirmation(
                            com.nimbusds.jose.util.Base64URL
                                .encode("thumbprint"),
                        ),
                    clientStatus =
                        eu.europa.ec.eudi.pidissuer.domain.ClientStatus(
                            status =
                                eu.europa.ec.eudi.pidissuer.domain.StatusClaim(
                                    eu.europa.ec.eudi.pidissuer.domain.StatusListToken(
                                        com.eygraber.uri.Uri
                                            .parse("urn:test:no-status"),
                                        0u,
                                    ),
                                ),
                            expiresAt = now + 365.minutes,
                        ),
                )

            val redemption = verifier()(PreAuthorizedCode(accessToken), now)
            assertIs<PreAuthorizedCodeRedemption.Invalid>(redemption)
        }
}
