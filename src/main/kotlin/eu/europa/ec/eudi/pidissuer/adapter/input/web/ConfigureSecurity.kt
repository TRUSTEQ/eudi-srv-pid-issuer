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

import arrow.core.nonEmptyListOf
import com.nimbusds.oauth2.sdk.dpop.verifiers.DPoPProtectedResourceRequestVerifier
import com.nimbusds.oauth2.sdk.dpop.verifiers.InMemoryDPoPSingleUseChecker
import eu.europa.ec.eudi.pidissuer.adapter.input.web.security.*
import eu.europa.ec.eudi.pidissuer.domain.CredentialIssuerMetaData
import eu.europa.ec.eudi.pidissuer.domain.Scope
import eu.europa.ec.eudi.pidissuer.duration
import eu.europa.ec.eudi.pidissuer.log
import eu.europa.ec.eudi.pidissuer.port.out.nonce.GenerateNonce
import eu.europa.ec.eudi.pidissuer.port.out.nonce.VerifyNonce
import eu.europa.ec.eudi.pidissuer.port.out.token.IntrospectSelfIssuedAccessToken
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.security.config.web.server.SecurityWebFiltersOrder
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.config.web.server.invoke
import org.springframework.security.oauth2.server.resource.introspection.ReactiveOpaqueTokenIntrospector
import org.springframework.security.oauth2.server.resource.introspection.SpringReactiveOpaqueTokenIntrospector
import org.springframework.security.web.server.DelegatingServerAuthenticationEntryPoint
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authentication.AuthenticationConverterServerWebExchangeMatcher
import org.springframework.security.web.server.authentication.AuthenticationWebFilter
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler
import org.springframework.security.web.server.authorization.HttpStatusServerAccessDeniedHandler
import org.springframework.security.web.server.authorization.ServerWebExchangeDelegatingServerAccessDeniedHandler
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers
import org.springframework.web.reactive.function.client.WebClient
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

fun configureUiSecurity(
    environment: Environment,
    http: ServerHttpSecurity,
): SecurityWebFilterChain =
    http {
        val static = environment.getRequiredProperty("spring.webflux.static-path-pattern")
        val webJars = environment.getRequiredProperty("spring.webflux.webjars-path-pattern")
        val pathMatcher =
            ServerWebExchangeMatchers.pathMatchers(
                static,
                webJars,
                "",
                "/",
                IssuerUi.GENERATE_CREDENTIALS_OFFER,
                IssuerUi.SDK_EHIC_LANDING,
                IssuerUi.SDK_EHIC_GENERATE,
            )

        securityMatcher(pathMatcher)
        authorizeExchange {
            authorize(pathMatcher, permitAll)
            authorize(anyExchange, denyAll)
        }

        // enable csrf
        csrf { }

        // enable cors
        cors { }

        // configure scp
        headers {
            // X-Frame-Options only supports DENY/SAMEORIGIN (no equivalent
            // of CSP's frame-ancestors origin allow-list, and ALLOW-FROM is
            // long deprecated/unsupported) - disabled here so the
            // frame-ancestors directive below is the one thing browsers
            // actually enforce, instead of the header default (DENY)
            // silently overriding it.
            frameOptions {
                disable()
            }

            contentSecurityPolicy {
                // 'self' plus this demo's own combined-view dashboard (see
                // ../../../../../../../eudi-dashboard/) - not a wide-open
                // allow-list, just the one legitimate framer this demo
                // actually has. Read from a property (default matches the
                // original hardcoded value) rather than hardcoded outright,
                // since it has to track wherever the dashboard is actually
                // reachable - see issuer.ui.dashboardOrigin in
                // application.properties and ISSUER_UI_DASHBOARDORIGIN in
                // docker-compose.yaml. Unlike everything else in this
                // demo's deployment config, this one needs a rebuild to
                // change, since it's compiled into the jar - but only this
                // property; once set, further domain changes are still
                // config-only.
                val dashboardOrigin = environment.getProperty("issuer.ui.dashboardOrigin", "https://demo.localhost")

                // policies
                policyDirectives =
                    nonEmptyListOf(
                        "default-src 'self'",
                        "script-src 'self'",
                        "style-src 'self' 'unsafe-inline'",
                        "img-src 'self' data:",
                        "object-src 'none'",
                        "base-uri 'self'",
                        "frame-ancestors 'self' $dashboardOrigin",
                    ).joinToString(separator = "; ")

                // set enforcing mode
                reportOnly = false
            }
        }
    }

fun configureApiSecurity(
    clock: Clock,
    env: Environment,
    http: ServerHttpSecurity,
    oAuth2ResourceServerProperties: OAuth2ResourceServerProperties,
    metadata: CredentialIssuerMetaData,
    dPoPConfigurationProperties: DPoPConfigurationProperties,
    webClient: WebClient,
    verifyNonce: VerifyNonce,
    generateNonce: GenerateNonce,
    introspectSelfIssuedAccessToken: IntrospectSelfIssuedAccessToken,
): SecurityWebFilterChain {
    fun Scope.springConvention() = "SCOPE_$value"
    val scopes =
        metadata.credentialConfigurationsSupported
            .map { it.scope.springConvention() }
            .distinct()

    return http {
        authorizeExchange {
            authorize(WalletApi.CREDENTIAL_ENDPOINT, hasAnyAuthority(*scopes.toTypedArray()))
            authorize(WalletApi.DEFERRED_ENDPOINT, hasAnyAuthority(*scopes.toTypedArray()))
            authorize(WalletApi.NOTIFICATION_ENDPOINT, hasAnyAuthority(*scopes.toTypedArray()))
            authorize(WalletApi.NONCE_ENDPOINT, permitAll)
            authorize(MetaDataApi.WELL_KNOWN_OPENID_CREDENTIAL_ISSUER, permitAll)
            authorize(MetaDataApi.WELL_KNOWN_JWT_VC_ISSUER, permitAll)
            authorize(MetaDataApi.PUBLIC_KEYS, permitAll)
            authorize(MetaDataApi.TYPE_METADATA, permitAll)
            authorize(MetaDataApi.WELL_KNOWN_PROTECTED_RESOURCE_METADATA, permitAll)
            authorize(MetaDataApi.WELL_KNOWN_OAUTH_AUTHORIZATION_SERVER, permitAll)
            authorize(IssuerApi.CREATE_CREDENTIALS_OFFER, permitAll)
            authorize(TokenApi.TOKEN_ENDPOINT, permitAll)
            authorize(anyExchange, denyAll)
        }

        csrf {
            disable()
        }

        cors {
            disable()
        }

        log.info("Enabling DPoP AccessToken support")
        val dpopNonce =
            if (dPoPConfigurationProperties.dPoPNonceEnabled) {
                val dpopNonceExpiresIn = env.duration("issuer.dpop.nonce.expiration")
                val expiresIn = dpopNonceExpiresIn ?: 5.minutes
                DPoPNoncePolicy.Enforcing(verifyNonce, generateNonce, expiresIn)
            } else {
                DPoPNoncePolicy.Disabled
            }

        val entryPoint = DPoPTokenServerAuthenticationEntryPoint(dPoPConfigurationProperties.realm, dpopNonce, clock)
        val tokenConverter = ServerDPoPAuthenticationTokenAuthenticationConverter()

        val keycloakIntrospector = createTokenIntrospector(oAuth2ResourceServerProperties, webClient)
        val introspector =
            CompositeReactiveOpaqueTokenIntrospector(introspectSelfIssuedAccessToken, keycloakIntrospector, clock)
        val dpopFilter =
            createDpopFilter(clock, dPoPConfigurationProperties, introspector, dpopNonce, tokenConverter, entryPoint)
        http.addFilterAfter(dpopFilter, SecurityWebFiltersOrder.AUTHENTICATION)

        if (dpopNonce is DPoPNoncePolicy.Enforcing) {
            val dpopNonceFilter =
                DPoPNonceWebFilter(
                    dpopNonce,
                    clock,
                    listOf(
                        WalletApi.CREDENTIAL_ENDPOINT,
                        WalletApi.DEFERRED_ENDPOINT,
                        WalletApi.NOTIFICATION_ENDPOINT,
                        WalletApi.NONCE_ENDPOINT,
                    ),
                )
            http.addFilterAt(dpopNonceFilter, SecurityWebFiltersOrder.LAST)
        }

        exceptionHandling {
            authenticationEntryPoint =
                DelegatingServerAuthenticationEntryPoint(
                    listOf(
                        DelegatingServerAuthenticationEntryPoint.DelegateEntry(
                            AuthenticationConverterServerWebExchangeMatcher(tokenConverter),
                            entryPoint,
                        ),
                    ),
                ).apply {
                    setDefaultEntryPoint(HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED))
                }

            accessDeniedHandler =
                ServerWebExchangeDelegatingServerAccessDeniedHandler(
                    listOf(
                        ServerWebExchangeDelegatingServerAccessDeniedHandler.DelegateEntry(
                            AuthenticationConverterServerWebExchangeMatcher(tokenConverter),
                            DPoPTokenServerAccessDeniedHandler(dPoPConfigurationProperties.realm),
                        ),
                    ),
                ).apply {
                    setDefaultAccessDeniedHandler(HttpStatusServerAccessDeniedHandler(HttpStatus.FORBIDDEN))
                }
        }
    }
}

private fun createDpopFilter(
    clock: Clock,
    dPoPConfigurationProperties: DPoPConfigurationProperties,
    introspector: ReactiveOpaqueTokenIntrospector,
    dpopNonce: DPoPNoncePolicy,
    tokenConverter: ServerDPoPAuthenticationTokenAuthenticationConverter,
    entryPoint: DPoPTokenServerAuthenticationEntryPoint,
): AuthenticationWebFilter {
    val dPoPVerifier =
        DPoPProtectedResourceRequestVerifier(
            dPoPConfigurationProperties.algorithms,
            15.seconds.inWholeSeconds,
            30.seconds.inWholeSeconds,
            InMemoryDPoPSingleUseChecker(
                60.seconds.inWholeSeconds,
                10.minutes.inWholeSeconds,
            ),
        )

    val authenticationManager =
        DPoPTokenReactiveAuthenticationManager(introspector, dPoPVerifier, dpopNonce, clock)

    return AuthenticationWebFilter(authenticationManager).apply {
        setServerAuthenticationConverter(tokenConverter)
        setAuthenticationFailureHandler(ServerAuthenticationEntryPointFailureHandler(entryPoint))
    }
}

private fun createTokenIntrospector(
    introspectionProperties: OAuth2ResourceServerProperties,
    webClient: WebClient,
): SpringReactiveOpaqueTokenIntrospector {
    val introspectionEndpoint =
        checkNotNull(introspectionProperties.opaquetoken.introspectionUri) {
            "missing spring.security.oauth2.resourceserver.opaquetoken.introspection-uri configuration property"
        }
    return SpringReactiveOpaqueTokenIntrospector(
        introspectionEndpoint,
        webClient
            .mutate()
            .defaultHeaders {
                it.setBasicAuth(
                    introspectionProperties.opaquetoken.clientId!!,
                    introspectionProperties.opaquetoken.clientSecret!!,
                )
            }.build(),
    )
}
