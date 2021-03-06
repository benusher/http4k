package org.http4k.security.oauth.server

import org.http4k.core.Body
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Status.Companion.UNAUTHORIZED
import org.http4k.core.Uri
import org.http4k.lens.FormField
import org.http4k.lens.Validator
import org.http4k.lens.WebForm
import org.http4k.lens.uri
import org.http4k.lens.webForm
import org.http4k.security.oauth.server.GenerateAccessToken.Companion.authorizationCode
import org.http4k.security.oauth.server.GenerateAccessToken.Companion.clientId
import org.http4k.security.oauth.server.GenerateAccessToken.Companion.clientSecret
import org.http4k.security.oauth.server.GenerateAccessToken.Companion.redirectUri
import java.time.Clock

class GenerateAccessToken(
    private val clientValidator: ClientValidator,
    private val authorizationCodes: AuthorizationCodes,
    private val accessTokens: AccessTokens,
    private val clock: Clock
) : HttpHandler {

    override fun invoke(request: Request): Response {
        val form = accessTokenForm(request)
        val accessTokenRequest = form.accessTokenRequest()

        if (grantType(form) != "authorization_code") {
            return Response(BAD_REQUEST).body("Invalid grant type")
        }

        if (!clientValidator.validateCredentials(accessTokenRequest.clientId, accessTokenRequest.clientSecret)) {
            return Response(UNAUTHORIZED).body("Invalid client credentials")
        }

        val codeDetails = authorizationCodes.detailsFor(accessTokenRequest.authorizationCode)

        if (codeDetails.expiresAt.isBefore(clock.instant())) {
            return Response(BAD_REQUEST).body("Authorization code has expired")
        }

        if (codeDetails.clientId != accessTokenRequest.clientId) {
            return Response(BAD_REQUEST).body("Invalid client_id")
        }

        if (codeDetails.redirectUri != accessTokenRequest.redirectUri) {
            return Response(BAD_REQUEST).body("Invalid redirect_uri")
        }

        return Response(OK).body(accessTokens.create(accessTokenRequest.authorizationCode).value)
            .also { authorizationCodes.destroy(accessTokenRequest.authorizationCode) }
    }

    companion object {
        internal val authorizationCode = FormField.map(::AuthorizationCode, AuthorizationCode::value).required("code")
        internal val redirectUri = FormField.uri().required("redirect_uri")
        private val grantType = FormField.required("grant_type")
        internal val clientSecret = FormField.required("client_secret")
        internal val clientId = FormField.map(::ClientId, ClientId::value).required("client_id")

        val accessTokenForm = Body.webForm(Validator.Strict,
            authorizationCode, redirectUri, grantType, clientId, clientSecret
        ).toLens()
    }
}

private fun WebForm.accessTokenRequest() =
    AccessTokenRequest(
        clientId(this),
        clientSecret(this),
        redirectUri(this),
        authorizationCode(this)
    )

data class AccessTokenRequest(
    val clientId: ClientId,
    val clientSecret: String,
    val redirectUri: Uri,
    val authorizationCode: AuthorizationCode)