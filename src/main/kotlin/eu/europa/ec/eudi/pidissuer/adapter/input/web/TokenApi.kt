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
package eu.europa.ec.eudi.pidissuer.adapter.input.web

import arrow.core.raise.effect
import arrow.core.raise.fold
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.oauth2.sdk.dpop.JWKThumbprintConfirmation
import com.nimbusds.oauth2.sdk.dpop.verifiers.DPoPIssuer
import com.nimbusds.oauth2.sdk.dpop.verifiers.DPoPTokenRequestVerifier
import com.nimbusds.oauth2.sdk.token.AccessTokenType
import eu.europa.ec.eudi.pidissuer.port.input.IssuePreAuthorizedCodeAccessToken
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.*

internal class TokenApi(
    private val issuePreAuthorizedCodeAccessToken: IssuePreAuthorizedCodeAccessToken,
    private val dPoPTokenRequestVerifier: DPoPTokenRequestVerifier,
) {
    val route =
        coRouter {
            POST(
                TOKEN_ENDPOINT,
                contentType(MediaType.APPLICATION_FORM_URLENCODED) and accept(MediaType.APPLICATION_JSON),
                ::handleIssueToken,
            )
        }

    private suspend fun handleIssueToken(request: ServerRequest): ServerResponse {
        val dpopThumbprint =
            try {
                request.verifiedDPoPThumbprint()
            } catch (exception: Exception) {
                log.debug("Rejected /token request with invalid DPoP proof", exception)
                return tokenErrorResponse("invalid_dpop_proof", "Missing or invalid DPoP proof")
            }
        val clientId = "wallet-${dpopThumbprint.value}"

        val formData = request.awaitFormData()
        val grantType = formData.getFirst("grant_type").orEmpty()
        val preAuthorizedCode = formData.getFirst("pre-authorized_code").orEmpty()

        return effect {
            issuePreAuthorizedCodeAccessToken(
                IssuePreAuthorizedCodeAccessToken.Request(grantType, preAuthorizedCode, dpopThumbprint, clientId),
            )
        }.fold(
            transform = { response -> tokenSuccessResponse(response) },
            recover = { error -> error.tokenErrorResponse() },
        )
    }

    private fun ServerRequest.verifiedDPoPThumbprint(): JWKThumbprintConfirmation {
        val values = headers().header(AccessTokenType.DPOP.value)
        require(values.size == 1) { "exactly one DPoP header is required" }
        val proof = SignedJWT.parse(values[0])
        return dPoPTokenRequestVerifier.verify(DPOP_ISSUER, proof, emptySet())
    }

    companion object {
        const val TOKEN_ENDPOINT = "/token"
        private val DPOP_ISSUER = DPoPIssuer("token-endpoint")
        private val log = LoggerFactory.getLogger(TokenApi::class.java)
    }
}

@Serializable
private data class TokenResponseTO(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "DPoP",
    @SerialName("expires_in") val expiresIn: Long,
)

@Serializable
private data class TokenErrorResponseTO(
    @SerialName("error") val error: String,
    @SerialName("error_description") val errorDescription: String? = null,
)

private suspend fun tokenSuccessResponse(response: IssuePreAuthorizedCodeAccessToken.Response): ServerResponse =
    ServerResponse
        .ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValueAndAwait(
            TokenResponseTO(
                accessToken = response.accessToken,
                expiresIn = response.expiresIn.inWholeSeconds,
            ),
        )

private suspend fun tokenErrorResponse(
    error: String,
    description: String? = null,
): ServerResponse =
    ServerResponse
        .badRequest()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValueAndAwait(TokenErrorResponseTO(error, description))

private suspend fun IssuePreAuthorizedCodeAccessToken.Error.tokenErrorResponse(): ServerResponse =
    when (this) {
        IssuePreAuthorizedCodeAccessToken.Error.UnsupportedGrantType -> {
            tokenErrorResponse("unsupported_grant_type")
        }

        IssuePreAuthorizedCodeAccessToken.Error.InvalidGrant -> {
            tokenErrorResponse("invalid_grant")
        }
    }
