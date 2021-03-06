package org.pac4j.oidc.profile.creator;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jwt.JWT;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.token.AccessToken;
import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import com.nimbusds.oauth2.sdk.token.RefreshToken;
import com.nimbusds.openid.connect.sdk.*;
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet;
import com.nimbusds.openid.connect.sdk.claims.UserInfo;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.exception.HttpAction;
import org.pac4j.core.exception.TechnicalException;
import org.pac4j.core.profile.creator.AbstractProfileCreator;
import org.pac4j.core.util.CommonHelper;
import org.pac4j.oidc.config.OidcConfiguration;
import org.pac4j.oidc.credentials.OidcCredentials;
import org.pac4j.oidc.profile.OidcProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.List;
import java.util.Map;

import static org.pac4j.core.util.CommonHelper.assertNotNull;

/**
 * OpenID Connect profile creator.
 *
 * @author Jerome Leleu
 * @since 1.9.2
 */
public class OidcProfileCreator<U extends OidcProfile> extends AbstractProfileCreator<OidcCredentials, U> {

    private static final Logger logger = LoggerFactory.getLogger(OidcProfileCreator.class);

    private OidcConfiguration configuration;

    protected IDTokenValidator idTokenValidator;

    public OidcProfileCreator() {}

    public OidcProfileCreator(final OidcConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    protected void internalInit(final WebContext context) {
        assertNotNull("configuration", configuration);
        configuration.init(context);

        // check algorithms
        final List<JWSAlgorithm> metadataAlgorithms = configuration.getProviderMetadata().getIDTokenJWSAlgs();
        CommonHelper.assertTrue(CommonHelper.isNotEmpty(metadataAlgorithms), "There must at least one JWS algorithm supported on the OpenID Connect provider side");
        JWSAlgorithm jwsAlgorithm;
        final JWSAlgorithm preferredAlgorithm = configuration.getPreferredJwsAlgorithm();
        if (metadataAlgorithms.contains(preferredAlgorithm)) {
            jwsAlgorithm = preferredAlgorithm;
        } else {
            jwsAlgorithm = metadataAlgorithms.get(0);
            logger.warn("Preferred JWS algorithm: {} not available. Defaulting to: {}", preferredAlgorithm, jwsAlgorithm);
        }
        if ("none".equals(jwsAlgorithm.getName())) {
            jwsAlgorithm = null;
        }

        final ClientID _clientID = new ClientID(configuration.getClientId());
        final Secret _secret = new Secret(configuration.getSecret());
        // Init IDTokenVerifier
        if (jwsAlgorithm == null) {
            this.idTokenValidator = new IDTokenValidator(configuration.getProviderMetadata().getIssuer(), _clientID);
        } else if (CommonHelper.isNotBlank(configuration.getSecret()) && (jwsAlgorithm == JWSAlgorithm.HS256 || jwsAlgorithm == JWSAlgorithm.HS384 || jwsAlgorithm == JWSAlgorithm.HS512)) {
            this.idTokenValidator = createHMACTokenValidator(jwsAlgorithm, _clientID, _secret);
        } else {
            this.idTokenValidator = createRSATokenValidator(jwsAlgorithm, _clientID);
        }
        this.idTokenValidator.setMaxClockSkew(configuration.getMaxClockSkew());

        setProfileFactory(() -> (U) new OidcProfile());
    }

    protected IDTokenValidator createRSATokenValidator(final JWSAlgorithm jwsAlgorithm, final ClientID clientID) {
        try {
            return new IDTokenValidator(configuration.getProviderMetadata().getIssuer(), clientID, jwsAlgorithm,
                    configuration.getProviderMetadata().getJWKSetURI().toURL(), configuration.getResourceRetriever());
        } catch (final MalformedURLException e) {
            throw new TechnicalException(e);
        }
    }

    protected IDTokenValidator createHMACTokenValidator(final JWSAlgorithm jwsAlgorithm, final ClientID clientID, final Secret secret) {
        return new IDTokenValidator(configuration.getProviderMetadata().getIssuer(), clientID, jwsAlgorithm, secret);
    }

    @Override
    @SuppressWarnings("unchecked")
    public U create(final OidcCredentials credentials, final WebContext context) throws HttpAction {
        init(context);

        final AccessToken accessToken = credentials.getAccessToken();

        // Create profile
        final U profile = getProfileFactory().get();
        profile.setAccessToken(accessToken);
        final JWT idToken = credentials.getIdToken();
        profile.setIdTokenString(idToken.getParsedString());
        // Check if there is a refresh token
        final RefreshToken refreshToken = credentials.getRefreshToken();
        if (refreshToken != null && !refreshToken.getValue().isEmpty()) {
            profile.setRefreshToken(refreshToken);
            logger.debug("Refresh Token successful retrieved");
        }

        try {

            // check idToken
            final Nonce nonce;
            if (configuration.isUseNonce()) {
                nonce = new Nonce((String) context.getSessionAttribute(OidcConfiguration.NONCE_SESSION_ATTRIBUTE));
            } else {
                nonce = null;
            }
            // Check ID Token
            final IDTokenClaimsSet claimsSet = this.idTokenValidator.validate(idToken, nonce);
            assertNotNull("claimsSet", claimsSet);
            profile.setId(claimsSet.getSubject());

            // User Info request
            if (configuration.getProviderMetadata().getUserInfoEndpointURI() != null && accessToken != null) {
                final UserInfoRequest userInfoRequest = new UserInfoRequest(configuration.getProviderMetadata().getUserInfoEndpointURI(), (BearerAccessToken) accessToken);
                final HTTPRequest userInfoHttpRequest = userInfoRequest.toHTTPRequest();
                userInfoHttpRequest.setConnectTimeout(configuration.getConnectTimeout());
                userInfoHttpRequest.setReadTimeout(configuration.getReadTimeout());
                final HTTPResponse httpResponse = userInfoHttpRequest.send();
                logger.debug("Token response: status={}, content={}", httpResponse.getStatusCode(),
                        httpResponse.getContent());

                final UserInfoResponse userInfoResponse = UserInfoResponse.parse(httpResponse);
                if (userInfoResponse instanceof UserInfoErrorResponse) {
                    logger.error("Bad User Info response, error={}",
                            ((UserInfoErrorResponse) userInfoResponse).getErrorObject());
                } else {
                    final UserInfoSuccessResponse userInfoSuccessResponse = (UserInfoSuccessResponse) userInfoResponse;
                    final UserInfo userInfo = userInfoSuccessResponse.getUserInfo();
                    if (userInfo != null) {
                        profile.addAttributes(userInfo.toJWTClaimsSet().getClaims());
                    }
                }
            }

            // add attributes of the ID token if they don't already exist
            for (final Map.Entry<String, Object> entry : idToken.getJWTClaimsSet().getClaims().entrySet()) {
                final String key = entry.getKey();
                final Object value = entry.getValue();
                if (profile.getAttribute(key) == null) {
                    profile.addAttribute(key, value);
                }
            }

            return profile;

        } catch (final IOException | ParseException | JOSEException | BadJOSEException | java.text.ParseException e) {
            throw new TechnicalException(e);
        }
    }

    public OidcConfiguration getConfiguration() {
        return configuration;
    }

    public void setConfiguration(final OidcConfiguration configuration) {
        this.configuration = configuration;
    }

    public IDTokenValidator getIdTokenValidator() {
        return idTokenValidator;
    }

    @Override
    public String toString() {
        return CommonHelper.toString(this.getClass(), "configuration", configuration);
    }
}
