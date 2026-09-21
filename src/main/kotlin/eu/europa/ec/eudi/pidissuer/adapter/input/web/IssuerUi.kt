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
import com.eygraber.uri.Uri
import eu.europa.ec.eudi.pidissuer.DemoBrandProperties
import eu.europa.ec.eudi.pidissuer.adapter.out.attestation.arbeitsvertrag.ArbeitsvertragMsoMdocConfigurationId
import eu.europa.ec.eudi.pidissuer.adapter.out.attestation.arbeitsvertrag.IssueSdJwtVcArbeitsvertrag
import eu.europa.ec.eudi.pidissuer.adapter.out.attestation.ehic.IssueEhic
import eu.europa.ec.eudi.pidissuer.adapter.out.attestation.learningcredential.IssueLearningCredential
import eu.europa.ec.eudi.pidissuer.adapter.out.attestation.residencepermit.IssueResidencePermit
import eu.europa.ec.eudi.pidissuer.adapter.out.attestation.schufa.IssueSdJwtVcSchufa
import eu.europa.ec.eudi.pidissuer.adapter.out.attestation.schufa.SchufaMsoMdocConfigurationId
import eu.europa.ec.eudi.pidissuer.appendPath
import eu.europa.ec.eudi.pidissuer.domain.*
import eu.europa.ec.eudi.pidissuer.port.input.CreateCredentialsOffer
import eu.europa.ec.eudi.pidissuer.port.out.qr.Dimensions
import eu.europa.ec.eudi.pidissuer.port.out.qr.Format
import eu.europa.ec.eudi.pidissuer.port.out.qr.GenerateQqCode
import eu.europa.ec.eudi.pidissuer.port.out.qr.Pixels
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.*
import kotlin.io.encoding.Base64

data class CredentialOption(
    val id: String,
    val label: String,
)

/**
 * A conceptual credential offered in both mdoc and SD-JWT VC format, rendered in the offer-generation form as a
 * single row with a format switch rather than doubling the flat checkbox list (see [CREDENTIAL_FAMILIES]).
 */
data class CredentialFamily(
    val key: String,
    val label: String,
    val mdoc: CredentialOption?,
    val mdocDeferred: CredentialOption?,
    val sdJwtVc: CredentialOption?,
    val sdJwtVcDeferred: CredentialOption?,
)

private data class FamilyDefinition(
    val key: String,
    val label: String,
    val mdocId: CredentialConfigurationId,
    val sdJwtVcId: CredentialConfigurationId,
)

private val CREDENTIAL_FAMILIES =
    listOf(
        FamilyDefinition(
            key = "schufa",
            label = "Schufa Credit Report",
            mdocId = SchufaMsoMdocConfigurationId,
            sdJwtVcId = IssueSdJwtVcSchufa.CONFIGURATION_ID,
        ),
        FamilyDefinition(
            key = "arbeitsvertrag",
            label = "Employment Certificate (TRUSTEQ)",
            mdocId = ArbeitsvertragMsoMdocConfigurationId,
            sdJwtVcId = IssueSdJwtVcArbeitsvertrag.CONFIGURATION_ID,
        ),
    )

private fun deferredId(id: CredentialConfigurationId): CredentialConfigurationId = CredentialConfigurationId(id.value + "_deferred")

class IssuerUi(
    private val metadata: CredentialIssuerMetaData,
    private val createCredentialsOffer: CreateCredentialsOffer,
    private val generateQrCode: GenerateQqCode,
    private val demoBrand: DemoBrandProperties,
) {
    val router: RouterFunction<ServerResponse> =
        coRouter {
            // Redirect / to whichever branded subsite DEMO_BRAND selects (the
            // demo's own front door - see the top-level plan), or to the
            // generic 'generate credentials offer' form in neutral mode,
            // unchanged from before either subsite existed.
            (GET("") or GET("/")) {
                val target =
                    when {
                        demoBrand.sdk -> SDK_EHIC_LANDING
                        demoBrand.tqk -> TQK_EHIC_LANDING
                        else -> GENERATE_CREDENTIALS_OFFER
                    }
                log.info("Redirecting to {}", target)
                ServerResponse
                    .status(HttpStatus.TEMPORARY_REDIRECT)
                    .renderAndAwait("redirect:$target")
            }

            // Display 'generate credentials offer' form
            GET(
                GENERATE_CREDENTIALS_OFFER,
                contentType(MediaType.ALL) and accept(MediaType.TEXT_HTML),
            ) { handleDisplayGenerateCredentialsOfferForm() }

            // Submit 'generate credentials offer' form
            POST(
                GENERATE_CREDENTIALS_OFFER,
                contentType(MediaType.APPLICATION_FORM_URLENCODED) and accept(MediaType.TEXT_HTML),
                ::handleGenerateCredentialsOffer,
            )

            // SDK/EHIC subsite (DEMO_BRAND=sdk) - a dedicated, SDK-Kundenportal-
            // styled flow for issuing just the Health Insurance Card, reusing
            // the exact same createCredentialsOffer use case as the generic
            // form above with a hardcoded credential id and pre-authorized_code
            // grant - see sdk-ehic-landing.html/sdk-ehic-offer.html. Reachable
            // regardless of DEMO_BRAND (nothing about the route itself depends
            // on it), just not the default landing page in neutral mode.
            GET(
                SDK_EHIC_LANDING,
                contentType(MediaType.ALL) and accept(MediaType.TEXT_HTML),
            ) { handleDisplaySdkEhicLanding() }

            POST(
                SDK_EHIC_GENERATE,
                contentType(MediaType.APPLICATION_FORM_URLENCODED) and accept(MediaType.TEXT_HTML),
            ) { handleGenerateSdkEhicOffer() }

            // TQK/EHIC subsite (DEMO_BRAND=tqk) - a second fictional insurer
            // narrative alongside SDK, same shape in every way (own landing/
            // offer templates, own theme, same hardcoded-EHIC/pre-authorized_code
            // use case) - see tqk-ehic-landing.html/tqk-ehic-offer.html. Also
            // reachable regardless of DEMO_BRAND, for the same reason as SDK's
            // routes above.
            GET(
                TQK_EHIC_LANDING,
                contentType(MediaType.ALL) and accept(MediaType.TEXT_HTML),
            ) { handleDisplayTqkEhicLanding() }

            POST(
                TQK_EHIC_GENERATE,
                contentType(MediaType.APPLICATION_FORM_URLENCODED) and accept(MediaType.TEXT_HTML),
            ) { handleGenerateTqkEhicOffer() }
        }

    private suspend fun handleDisplayGenerateCredentialsOfferForm(): ServerResponse {
        log.info("Displaying 'Generate Credentials Offer' page")
        val supportedById = metadata.credentialConfigurationsSupported.associateBy { it.id }
        val familyConfigIds =
            CREDENTIAL_FAMILIES
                .flatMap { listOf(it.mdocId, deferredId(it.mdocId), it.sdJwtVcId, deferredId(it.sdJwtVcId)) }
                .toSet()

        fun CredentialConfiguration.toOption() = CredentialOption(id.value, display.firstOrNull()?.name?.name ?: id.value)

        val credentialConfigurationIds =
            metadata.credentialConfigurationsSupported
                .filterNot { it.id in familyConfigIds }
                .groupBy({ it.category }, { it.toOption() })

        val credentialFamilies =
            CREDENTIAL_FAMILIES
                .mapNotNull { family ->
                    val mdocCfg = supportedById[family.mdocId]
                    val sdJwtCfg = supportedById[family.sdJwtVcId]
                    val category = (mdocCfg ?: sdJwtCfg)?.category ?: return@mapNotNull null
                    category to
                        CredentialFamily(
                            key = family.key,
                            label = family.label,
                            mdoc = mdocCfg?.toOption(),
                            mdocDeferred = supportedById[deferredId(family.mdocId)]?.toOption(),
                            sdJwtVc = sdJwtCfg?.toOption(),
                            sdJwtVcDeferred = supportedById[deferredId(family.sdJwtVcId)]?.toOption(),
                        )
                }.groupBy({ it.first }, { it.second })

        val usefulLinks = createUsefulLinks(metadata.id, metadata.authorizationServers[0])
        return ServerResponse
            .ok()
            .contentType(MediaType.TEXT_HTML)
            .renderAndAwait(
                "generate-credentials-offer-form",
                mapOf(
                    "credentialConfigurationIds" to credentialConfigurationIds,
                    "credentialFamilies" to credentialFamilies,
                    "credentialsOfferUri" to createCredentialsOffer.defaultCredentialOfferUri.toString(),
                    "openid4VciVersion" to OpenId4VciSpec.VERSION,
                    "usefulLinks" to usefulLinks,
                    "brand" to demoBrand,
                ),
            )
    }

    private suspend fun handleGenerateCredentialsOffer(request: ServerRequest): ServerResponse =
        effect {
            log.debug("Generating Credentials Offer")
            val createCredentialOfferRequest = request.createCredentialOfferRequest()
            createCredentialsOffer(createCredentialOfferRequest)
        }.fold(
            transform = { credentialsOfferUri ->
                context(generateQrCode) { credentialsOfferUri.credentialOfferSuccessResponse(demoBrand) }
            },
            recover = { error ->
                log.warn("Unable to generated Credentials Offer. Error: {}", error)
                error.credentialOfferErrorResponse(demoBrand)
            },
        )

    private suspend fun handleDisplaySdkEhicLanding(): ServerResponse {
        log.info("Displaying SDK/EHIC subsite landing page")
        return ServerResponse
            .ok()
            .contentType(MediaType.TEXT_HTML)
            .renderAndAwait("sdk-ehic-landing", mapOf("brand" to demoBrand))
    }

    // Same createCredentialsOffer use case the generic form's
    // handleGenerateCredentialsOffer() above drives, just with a hardcoded
    // credential id (EHIC), no custom claims, and pre-authorized_code
    // grant instead of a full form - this subsite's whole point is "one
    // click, no form," full stop (an earlier version also read an
    // optional "enter your own details" disclosure from the request, but
    // that form was removed from sdk-ehic-landing.html - the generic
    // form's own EHIC fieldset still covers that case for anyone who
    // wants it). This demo issuer's own auto-generated sample data is
    // used for every claim, exactly like the generic form's own
    // behaviour when its optional fields are left blank.
    private suspend fun handleGenerateSdkEhicOffer(): ServerResponse =
        effect {
            log.debug("Generating SDK/EHIC Credentials Offer")
            createCredentialsOffer(
                CreateCredentialsOffer.Request(
                    credentialConfigurationIds = setOf(IssueEhic.CONFIGURATION_ID),
                    preAuthorizedCode = true,
                    customData = emptyMap(),
                ),
            )
        }.fold(
            transform = { credentialsOfferUri ->
                context(generateQrCode) { credentialsOfferUri.sdkEhicOfferSuccessResponse(demoBrand) }
            },
            recover = { error ->
                log.warn("Unable to generate SDK/EHIC Credentials Offer. Error: {}", error)
                error.credentialOfferErrorResponse(demoBrand)
            },
        )

    private suspend fun handleDisplayTqkEhicLanding(): ServerResponse {
        log.info("Displaying TQK/EHIC subsite landing page")
        return ServerResponse
            .ok()
            .contentType(MediaType.TEXT_HTML)
            .renderAndAwait("tqk-ehic-landing", mapOf("brand" to demoBrand))
    }

    // Same shape as handleGenerateSdkEhicOffer() above, right down to the
    // comment: one click, no form, this demo issuer's own auto-generated
    // sample data for every claim.
    private suspend fun handleGenerateTqkEhicOffer(): ServerResponse =
        effect {
            log.debug("Generating TQK/EHIC Credentials Offer")
            createCredentialsOffer(
                CreateCredentialsOffer.Request(
                    credentialConfigurationIds = setOf(IssueEhic.CONFIGURATION_ID),
                    preAuthorizedCode = true,
                    customData = emptyMap(),
                ),
            )
        }.fold(
            transform = { credentialsOfferUri ->
                context(generateQrCode) { credentialsOfferUri.tqkEhicOfferSuccessResponse(demoBrand) }
            },
            recover = { error ->
                log.warn("Unable to generate TQK/EHIC Credentials Offer. Error: {}", error)
                error.credentialOfferErrorResponse(demoBrand)
            },
        )

    private fun createUsefulLinks(
        credentialIssuer: CredentialIssuerId,
        authorizationServer: HttpsUrl,
    ): Map<String, String> {
        fun HttpsUrl.wellKnown(path: String): HttpsUrl =
            HttpsUrl.unsafe(
                value
                    .buildUpon()
                    .path(null)
                    .appendPath(".well-known")
                    .appendPath(path)
                    .apply {
                        value.pathSegments
                            .filterNot { it.isBlank() }
                            .forEach { appendPath(it) }
                    }.build()
                    .toString(),
            )

        val credentialIssuerMetadata = credentialIssuer.wellKnown("openid-credential-issuer")
        val protectedResourceMetadata = credentialIssuer.wellKnown("oauth-protected-resource")
        val authorizationServerMetadata = authorizationServer.wellKnown("oauth-authorization-server")
        val sdJwtVcIssuerMetadata = credentialIssuer.wellKnown("jwt-vc-issuer")
        val pidSdJwtVcTypeMetadata = credentialIssuer.appendPath("/type-metadata/urn:eudi:pid:1")
        val learningCredentialSdJwtVcTypeMetadata =
            credentialIssuer.appendPath(
                "/type-metadata/urn:eu.europa.ec.eudi:learning:credential:1",
            )

        return mapOf(
            "credential_issuer_metadata" to credentialIssuerMetadata.externalForm,
            "protected_resource_metadata" to protectedResourceMetadata.externalForm,
            "authorization_server_metadata" to authorizationServerMetadata.externalForm,
            "sdjwt_vc_issuer_metadata" to sdJwtVcIssuerMetadata.externalForm,
            "pid_sdjwt_vc_type_metadata" to pidSdJwtVcTypeMetadata.externalForm,
            "learning_credential_sdjwt_vc_type_metadata" to learningCredentialSdJwtVcTypeMetadata.externalForm,
        )
    }

    companion object {
        const val GENERATE_CREDENTIALS_OFFER: String = "/issuer/credentialsOffer/generate"
        const val SDK_EHIC_LANDING: String = "/issuer/sdk/ehic"
        const val SDK_EHIC_GENERATE: String = "/issuer/sdk/ehic/generate"
        const val TQK_EHIC_LANDING: String = "/issuer/tqk/ehic"
        const val TQK_EHIC_GENERATE: String = "/issuer/tqk/ehic/generate"
        private val log = LoggerFactory.getLogger(IssuerUi::class.java)
    }
}

private suspend fun ServerRequest.createCredentialOfferRequest(): CreateCredentialsOffer.Request {
    val formData = awaitFormData()
    val credentialIds = formData["credentialIds"].orEmpty().map(::CredentialConfigurationId).toMutableSet()
    val credentialsOfferUri = formData["credentialsOfferUri"]?.firstOrNull { it.isNotBlank() }
    val preAuthorizedCode = formData["preAuthorizedCode"].orEmpty().isNotEmpty()

    fun customDataFrom(
        formPrefix: String,
        fields: List<String>,
    ): JsonObject? {
        val entries =
            fields.mapNotNull { field ->
                formData["${formPrefix}_$field"]?.firstOrNull { it.isNotBlank() }?.let { field to it }
            }
        return entries
            .takeIf { it.isNotEmpty() }
            ?.let { present -> buildJsonObject { present.forEach { (field, value) -> put(field, value) } } }
    }

    // CreateCredentialsOffer only keeps customData entries whose key is among the actually-selected credential
    // ids (see CreateCredentialsOffer.kt's `customData.filterKeys { it in this }`) - so every id a given operator
    // selection could resolve to (its deferred sibling, and - for dual-format families - its other format) needs
    // its own copy of the same data, not just the "canonical" id.
    val customData =
        buildMap {
            fun putForBaseAndDeferred(
                id: CredentialConfigurationId,
                data: JsonObject,
            ) {
                put(id, data)
                put(deferredId(id), data)
            }

            customDataFrom("diploma", listOf("family_name", "given_name", "degree_title", "institution"))
                ?.let { putForBaseAndDeferred(IssueLearningCredential.CONFIGURATION_ID, it) }
            customDataFrom("ehic", listOf("family_name", "given_name", "birth_date", "personal_administrative_number"))
                ?.let { putForBaseAndDeferred(IssueEhic.CONFIGURATION_ID, it) }
            customDataFrom(
                "residencePermit",
                listOf("family_name", "given_name", "birth_date", "nationality", "document_number", "resident_address"),
            )?.let { putForBaseAndDeferred(IssueResidencePermit.CONFIGURATION_ID, it) }
            customDataFrom("schufa", listOf("family_name", "given_name", "birth_date", "credit_score"))
                ?.let { data ->
                    putForBaseAndDeferred(IssueSdJwtVcSchufa.CONFIGURATION_ID, data)
                    putForBaseAndDeferred(SchufaMsoMdocConfigurationId, data)
                }
            customDataFrom(
                "arbeitsvertrag",
                listOf("employee_family_name", "employee_given_name", "job_title", "employment_start_date"),
            )?.let { data ->
                putForBaseAndDeferred(IssueSdJwtVcArbeitsvertrag.CONFIGURATION_ID, data)
                putForBaseAndDeferred(ArbeitsvertragMsoMdocConfigurationId, data)
            }
        }

    // Resolve each selected credential family (format-switch picker row) into the concrete configuration id implied
    // by its format/deferred selectors, and fold it into the same set the flat checkbox list populates.
    formData["credentialFamilies"].orEmpty().forEach { familyKey ->
        val family = CREDENTIAL_FAMILIES.firstOrNull { it.key == familyKey } ?: return@forEach
        val useMdoc = formData["${familyKey}_format"]?.firstOrNull() == "mdoc"
        val deferred = formData["${familyKey}_deferred"].orEmpty().isNotEmpty()
        val baseId = if (useMdoc) family.mdocId else family.sdJwtVcId
        credentialIds += if (deferred) deferredId(baseId) else baseId
    }

    return CreateCredentialsOffer.Request(credentialIds, credentialsOfferUri, preAuthorizedCode, customData)
}

context(generateQrCode: GenerateQqCode)
private suspend fun Uri.credentialOfferSuccessResponse(demoBrand: DemoBrandProperties): ServerResponse {
    val uri = this@credentialOfferSuccessResponse
    val qrCode = generateQrCode(uri, Format.PNG, Dimensions(Pixels(640u), Pixels(640u)))
    return ServerResponse
        .ok()
        .contentType(MediaType.TEXT_HTML)
        .renderAndAwait(
            "display-credentials-offer",
            mapOf(
                "uri" to uri.toString(),
                "qrCode" to Base64.encode(qrCode),
                "qrCodeMediaType" to "image/png",
                "openid4VciVersion" to OpenId4VciSpec.VERSION,
                "brand" to demoBrand,
            ),
        )
}

// Same shape as credentialOfferSuccessResponse() above, just rendering the
// SDK/EHIC subsite's own reskinned QR-display template instead of the
// generic issuer's - see sdk-ehic-offer.html/public/css/sdk-theme.css.
context(generateQrCode: GenerateQqCode)
private suspend fun Uri.sdkEhicOfferSuccessResponse(demoBrand: DemoBrandProperties): ServerResponse {
    val uri = this@sdkEhicOfferSuccessResponse
    val qrCode = generateQrCode(uri, Format.PNG, Dimensions(Pixels(640u), Pixels(640u)))
    return ServerResponse
        .ok()
        .contentType(MediaType.TEXT_HTML)
        .renderAndAwait(
            "sdk-ehic-offer",
            mapOf(
                "uri" to uri.toString(),
                "qrCode" to Base64.encode(qrCode),
                "qrCodeMediaType" to "image/png",
                "openid4VciVersion" to OpenId4VciSpec.VERSION,
                "brand" to demoBrand,
            ),
        )
}

// Same shape as sdkEhicOfferSuccessResponse() above, rendering the TQK/EHIC
// subsite's own reskinned QR-display template - see tqk-ehic-offer.html/
// public/css/tqk-theme.css.
context(generateQrCode: GenerateQqCode)
private suspend fun Uri.tqkEhicOfferSuccessResponse(demoBrand: DemoBrandProperties): ServerResponse {
    val uri = this@tqkEhicOfferSuccessResponse
    val qrCode = generateQrCode(uri, Format.PNG, Dimensions(Pixels(640u), Pixels(640u)))
    return ServerResponse
        .ok()
        .contentType(MediaType.TEXT_HTML)
        .renderAndAwait(
            "tqk-ehic-offer",
            mapOf(
                "uri" to uri.toString(),
                "qrCode" to Base64.encode(qrCode),
                "qrCodeMediaType" to "image/png",
                "openid4VciVersion" to OpenId4VciSpec.VERSION,
                "brand" to demoBrand,
            ),
        )
}

private suspend fun CreateCredentialsOffer.Error.credentialOfferErrorResponse(demoBrand: DemoBrandProperties): ServerResponse =
    ServerResponse
        .badRequest()
        .contentType(MediaType.TEXT_HTML)
        .renderAndAwait(
            "generate-credentials-offer-error",
            mapOf(
                "error" to this::class.java.canonicalName,
                "openid4VciVersion" to OpenId4VciSpec.VERSION,
                "brand" to demoBrand,
            ),
        )
