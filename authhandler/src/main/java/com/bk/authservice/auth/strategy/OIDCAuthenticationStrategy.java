package com.bk.authservice.auth.strategy;

import com.bk.authservice.auth.handler.AuthenticationType;
import com.bk.authservice.auth.handler.oidc.OIDCAuthenticationHandler;
import com.bk.authservice.auth.handler.oidc.OIDCCredentials;
import com.bk.authservice.auth.policy.OIDCPolicy;
import com.bk.authservice.auth.policy.PolicyManager;
import com.bk.authservice.auth.util.CookieUtils;
import com.bk.authservice.model.MemCache;
import com.bk.authservice.model.RequestData;
import com.okta.jwt.Jwt;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.bk.authservice.auth.util.CookieUtils.TOKEN_COOKIE_NAME;

/**
 * Created By: bhushan.karmarkar12@gmail.com
 * Date: 10/02/22
 */
public class OIDCAuthenticationStrategy extends AbstractAuthenticationStrategy<OIDCAuthenticationHandler, OIDCCredentials> {

    public OIDCAuthenticationStrategy(PolicyManager policyManager, AuthenticationStrategyResolver authenticationStrategyResolver) {
        super(new OIDCAuthenticationHandler(), policyManager, authenticationStrategyResolver);
    }

    @Override
    public void handleRequest(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws Exception {
        // Step 0 - Get request data
        Cookie preAuthCookie = CookieUtils.extractCookie(httpServletRequest, CookieUtils.PRE_AUTH_COOKIE_NAME);
        if(preAuthCookie == null || MemCache.getRequestData(preAuthCookie.getName()) == null) {
            redirectToAuthorizationEndpoint(httpServletRequest, httpServletResponse);
            return;
        }

        // else request data is not null means we have authorization code in the request
        // Step 2 - exchange the authorization code with server and obtain access token in response
        RequestData requestData = MemCache.getRequestData(preAuthCookie.getName());

        OIDCCredentials oidcCredentials = extractCredentials(httpServletRequest);
        Map authenticate = authenticationHandler.authenticate(oidcCredentials, policyManager.getPolicy(OIDCPolicy.class));

        // Step 4 - create principal based on the user profile

        Jwt jwtToken = (Jwt) authenticate.get("JWT");
        // TODO resolve authentication
        String tokenValue = preAuthCookie.getValue() + "_" + jwtToken.getClaims().get("preferred_username");
        manageCookies(tokenValue, httpServletResponse);

        // redirect to original url
        httpServletResponse.sendRedirect(requestData.getAccessURL());
    }

    private void manageCookies(String tokenValue, HttpServletResponse httpServletResponse) {
        // set sec token as a cookie
        httpServletResponse.addCookie(CookieUtils.generateCookie(TOKEN_COOKIE_NAME, tokenValue));

        removePreAuthCookie(httpServletResponse);
    }


    private RequestData prepareRequestData(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
        // create temp cookie for maintaining track
        Cookie preAuthCookie = generatePreAuthCookie();

        // add request data to cache
        RequestData requestData = new RequestData();
        requestData.setAuthenticationType(AuthenticationType.OIDC);
        requestData.setRequestId(preAuthCookie.getName());
        requestData.setAccessURL(httpServletRequest.getServletPath());
        Map<String, String> stateData = new HashMap<>();
        stateData.put("state", UUID.randomUUID().toString());
        requestData.setAuthenticationSpecificData(stateData);
        MemCache.putRequestData(requestData.getRequestId(), requestData);

        // add cookie on response
        httpServletResponse.addCookie(preAuthCookie);
        return requestData;
    }

    private String extractAuthCode(HttpServletRequest httpServletRequest, RequestData requestData) throws Exception {
        String code = httpServletRequest.getParameter("code");
        String state = httpServletRequest.getParameter("state");
        Map oidcAuthData = requestData.getAuthenticationSpecificData();
        // necessary to have this Null checks, ow attacker may bring down the app
        if(oidcAuthData != null && oidcAuthData.get("state") != null && !oidcAuthData.get("state").equals(state)) {
            throw new Exception("Invalid State ! - may be someone intercepted the request and trying the replay attack?");
        }
        return code;
    }

    @Override
    public OIDCCredentials extractCredentials(HttpServletRequest httpServletRequest) throws Exception {
        OIDCPolicy oidcPolicy = policyManager.getPolicy(OIDCPolicy.class);
        OIDCCredentials oidcCredentials = new OIDCCredentials();
        oidcCredentials.setClientId(oidcPolicy.getClientId());
        oidcCredentials.setClientSecret(oidcPolicy.getClientSecret());

        Cookie preAuthCookie = CookieUtils.extractCookie(httpServletRequest, CookieUtils.PRE_AUTH_COOKIE_NAME);
        RequestData requestData = MemCache.getRequestData(preAuthCookie.getName());
        String code = extractAuthCode(httpServletRequest, requestData);
        oidcCredentials.setCode(code); // will be null for non-authorization code calls
        return oidcCredentials;
    }

    private void redirectToAuthorizationEndpoint(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws IOException {
        // get authorization endpoint from policy
        // append following
            /*
            client_id -> the client id from the policy/credentials
            response_type -> "code" for authorization server flow
            response_mode -> optional,
            scope -> {"profile"}
            redirect_uri -> Callback location where the authorization code or tokens should be sent. It must match the value preregistered in Okta during client registration.
            state -> relay state, random uid for this authentication request - oidc provider will return this state after authentication
            nonce -> optional, similar to the relay state, include it in the request and server will include the same value in response
             */
        OIDCPolicy oidcPolicy = policyManager.getPolicy(OIDCPolicy.class);
        RequestData requestData = prepareRequestData(httpServletRequest, httpServletResponse);

        StringBuilder sb = new StringBuilder();
        // {{url}}/oauth2/v1/authorize?client_id={{clientId}}
        // &response_type=code&&scope={{scopes}}&redirect_uri={{redirectUri}}&state={{state}}&nonce=7773bf3d-6a23-4352-b294-117be0e5529f

        //TODO define constants
        sb.append(oidcPolicy.getAuthorizationCodeEndpoint());
        sb.append("?");
        sb.append("client_id=" + oidcPolicy.getClientId());
        sb.append("&");
        sb.append("response_type=" + "code"); // hardcoded for now
        sb.append("&");
        sb.append("scope=" + "profile openid");
        sb.append("&");
        sb.append("redirect_uri=" + "http://localhost:8080/auth/oidc/code");
        sb.append("&");
        sb.append("state=" + requestData.getAuthenticationSpecificData().get("state"));

        httpServletResponse.sendRedirect(sb.toString());
    }
}
