/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2011, 2012, 2013, 2014, 2015, 2016 Synacor, Inc.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software Foundation,
 * version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with this program.
 * If not, see <https://www.gnu.org/licenses/>.
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.cs.servlet.util;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.codec.binary.Base64;

import com.zimbra.common.account.Key;
import com.zimbra.common.account.Key.AccountBy;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.soap.AccountConstants;
import com.zimbra.common.soap.AdminConstants;
import com.zimbra.common.soap.Element;
import com.zimbra.common.util.HttpUtil;
import com.zimbra.common.util.Log;
import com.zimbra.common.util.LogFactory;
import com.zimbra.common.util.ZimbraCookie;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.AuthToken;
import com.zimbra.cs.account.AuthTokenException;
import com.zimbra.cs.account.Domain;
import com.zimbra.cs.account.GuestAccount;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.account.Server;
import com.zimbra.cs.account.AccountServiceException.AuthFailedServiceException;
import com.zimbra.cs.account.AuthToken.TokenType;
import com.zimbra.cs.account.AuthToken.Usage;
import com.zimbra.cs.account.auth.AuthContext;
import com.zimbra.cs.account.auth.AuthContext.Protocol;
import com.zimbra.cs.dav.service.DavServlet;
import com.zimbra.cs.httpclient.URLUtil;
import com.zimbra.cs.listeners.AuthListener;
import com.zimbra.cs.service.AuthProvider;
import com.zimbra.cs.service.UserServletException;
import com.zimbra.cs.servlet.ZimbraServlet;
import com.zimbra.soap.SoapServlet;
import com.zimbra.soap.ZimbraSoapContext;

public class AuthUtil {
    private static Log mLog = LogFactory.getLog(AuthUtil.class);

    public static final String WWW_AUTHENTICATE_HEADER = "WWW-Authenticate";
    public static final String HTTP_AUTH_HEADER = "Authorization";
    public final static String IGNORE_LOGIN_URL = "?ignoreLoginURL=1";

    /**
     * Checks to see if this is an admin request
     * @param req
     * @return
     * @throws ServiceException
     */
    public static boolean isAdminRequest(HttpServletRequest req) throws ServiceException {
        int adminPort = Provisioning.getInstance().getLocalServer().getIntAttr(Provisioning.A_zimbraAdminPort, -1);
        if (req.getLocalPort() == adminPort) {
            //can still be in offline server where port=adminPort
            int mailPort = Provisioning.getInstance().getLocalServer().getIntAttr(Provisioning.A_zimbraMailPort, -1);
            if (mailPort == adminPort) //we are in offline, so check cookie
                return getAdminAuthTokenFromCookie(req) != null;
            else
                return true;
        }
        return false;
    }

    public static AuthToken getAuthTokenFromCookie(HttpServletRequest req, HttpServletResponse resp)
    throws IOException {
        return getAuthTokenFromHttpReq(req, resp, false, false);
    }

    public static AuthToken getAuthTokenFromCookie(HttpServletRequest req, HttpServletResponse resp, boolean doNotSendHttpError)
    throws IOException {
        return getAuthTokenFromHttpReq(req, resp, false, doNotSendHttpError);
    }

    public static AuthToken getAuthTokenFromHttpReq(HttpServletRequest req, boolean isAdminReq) {
        AuthToken authToken = null;
        try {
            authToken = AuthProvider.getAuthToken(req, isAdminReq);
            if (authToken == null)
                return null;

            if (authToken.isExpired() || !authToken.isRegistered())
                return null;

            return authToken;
        } catch (AuthTokenException e) {
            return null;
        }
    }

    public static AuthToken getAuthTokenFromHttpReq(HttpServletRequest req,
                                                    HttpServletResponse resp,
                                                    boolean isAdminReq,
                                                    boolean doNotSendHttpError) throws IOException {
        AuthToken authToken = null;
        try {
            authToken = AuthProvider.getAuthToken(req, isAdminReq);
            if (authToken == null) {
                if (!doNotSendHttpError)
                    resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "no authtoken cookie");
                return null;
            }

            if (authToken.isExpired() || !authToken.isRegistered()) {
                if (!doNotSendHttpError)
                    resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "authtoken expired");
                return null;
            }
            return authToken;
        } catch (AuthTokenException e) {
            if (!doNotSendHttpError)
                resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "unable to parse authtoken");
            return null;
        }
    }

    public static AuthToken getAdminAuthTokenFromCookie(HttpServletRequest req) {
        return getAuthTokenFromHttpReq(req, true);
    }

    public static AuthToken getAdminAuthTokenFromCookie(HttpServletRequest req, HttpServletResponse resp)
    throws IOException {
        return getAuthTokenFromHttpReq(req, resp, true, false);
    }

    public static AuthToken getAdminAuthTokenFromCookie(HttpServletRequest req, HttpServletResponse resp, boolean doNotSendHttpError)
    throws IOException {
        return getAuthTokenFromHttpReq(req, resp, true, doNotSendHttpError);
    }

    /**
     * Note - this method can return null having called "sendError" and NOT having called "sendError".
     * Seems like a bad idea...
     */
    public static Account basicAuthRequest(HttpServletRequest req, HttpServletResponse resp,
                                           ZimbraServlet servlet, boolean sendChallenge)
    throws IOException, ServiceException
    {
        if (!AuthProvider.allowBasicAuth(req, servlet)) {
            return null;
        }

        return basicAuthRequest(req, resp, sendChallenge);
    }

    public static final class AuthResult {
        public final boolean sendErrorCalled;
        public final Account authorizedAccount;
        public AuthResult(Account authAcct, boolean calledSendError) {
            sendErrorCalled = calledSendError;
            authorizedAccount = authAcct;
        }
    }

    public static AuthResult basicAuthRequest(HttpServletRequest req, HttpServletResponse resp,
                                           boolean sendChallenge, ZimbraServlet servlet)
    throws IOException, ServiceException {
        if (!AuthProvider.allowBasicAuth(req, servlet)) {
            return new AuthResult(null, false);
        }
        boolean isDav = (servlet instanceof DavServlet);
        Account acct = basicAuthRequest(req, resp, sendChallenge, isDav);
        return new AuthResult(acct, (acct == null));
    }

    public static Account basicAuthRequest(HttpServletRequest req, HttpServletResponse resp, boolean sendChallenge)
            throws IOException, ServiceException {
        return basicAuthRequest(req, resp, sendChallenge, false);
    }

    /**
     * If returns null, then resp.sendError has been called.
     */
    public static Account basicAuthRequest(HttpServletRequest req, HttpServletResponse resp, boolean sendChallenge, boolean isDav)
            throws IOException, ServiceException
    {
        try {
            return basicAuthRequest(req, !sendChallenge, isDav);
        } catch (UserServletException e) {
            if (e.getHttpStatusCode() == HttpServletResponse.SC_UNAUTHORIZED) {
                if (sendChallenge) {
                    resp.addHeader(WWW_AUTHENTICATE_HEADER, getRealmHeader(req, null));
                    resp.sendError(e.getHttpStatusCode(), e.getMessage());
                }
            } else {
                resp.sendError(e.getHttpStatusCode(), e.getMessage());
            }
            return null;
        }
    }

    public static Account basicAuthRequest(HttpServletRequest req, boolean allowGuest)
            throws IOException, ServiceException, UserServletException {
        return basicAuthRequest(req, allowGuest, false);
    }

    public static Account basicAuthRequest(HttpServletRequest req, boolean allowGuest, boolean isDav)
        throws IOException, ServiceException, UserServletException
    {
        String auth = req.getHeader(HTTP_AUTH_HEADER);

        // TODO: more liberal parsing of Authorization value...
        if (auth == null || !auth.startsWith("Basic ")) {
            throw new UserServletException(HttpServletResponse.SC_UNAUTHORIZED, "must authenticate");
        }

        // 6 comes from "Basic ".length();
        String userPass = new String(Base64.decodeBase64(auth.substring(6).getBytes()), "UTF-8");

        int loc = userPass.indexOf(":");
        if (loc == -1) {
            throw new UserServletException(HttpServletResponse.SC_BAD_REQUEST, "invalid basic auth credentials");
        }

        String userPassedIn = userPass.substring(0, loc);
        String user = userPassedIn;
        String pass = userPass.substring(loc + 1);

        Provisioning prov = Provisioning.getInstance();

        if (user.indexOf('@') == -1) {
            String host = HttpUtil.getVirtualHost(req);
            if (host != null) {
                Domain d = prov.get(Key.DomainBy.virtualHostname, host.toLowerCase());
                if (d != null) user += "@" + d.getName();
            }
        }

        Account acct = prov.get(AccountBy.name, user);

        if (acct == null) {
            if (allowGuest) {
                return new GuestAccount(user, pass);
            }

            throw new UserServletException(HttpServletResponse.SC_UNAUTHORIZED, "invalid username/password");
        }
        try {
            Map<String, Object> authCtxt = new HashMap<String, Object>();
            authCtxt.put(AuthContext.AC_ORIGINATING_CLIENT_IP, ZimbraServlet.getOrigIp(req));
            authCtxt.put(AuthContext.AC_REMOTE_IP, ZimbraServlet.getClientIp(req));
            authCtxt.put(AuthContext.AC_ACCOUNT_NAME_PASSEDIN, userPassedIn);
            authCtxt.put(AuthContext.AC_USER_AGENT, req.getHeader("User-Agent"));
            Protocol proto = isDav? Protocol.http_dav: Protocol.http_basic;
            prov.authAccount(acct, pass, proto, authCtxt);
        } catch (ServiceException se) {
            throw new UserServletException(HttpServletResponse.SC_UNAUTHORIZED, "invalid username/password");
        }

        return acct;
    }

    public AuthToken cookieAuthRequest(HttpServletRequest req, HttpServletResponse resp)
    throws IOException, ServiceException {
        AuthToken at = AuthUtil.isAdminRequest(req) ? AuthUtil.getAdminAuthTokenFromCookie(req, resp, true) : AuthUtil.getAuthTokenFromCookie(req, resp, true);
        return at;
    }

    public static String getRealmHeader(HttpServletRequest req, Domain domain)  {
        String realm = null;

        if (domain == null) {
            // get domain by virtual host
            String host = HttpUtil.getVirtualHost(req);
            if (host != null) {
                // to defend against DOS attack, use the negative domain cache
                try {
                    domain = Provisioning.getInstance().getDomain(Key.DomainBy.virtualHostname, host.toLowerCase(), true);
                } catch (ServiceException e) {
                    mLog.warn("caught exception while getting domain by virtual host: " + host, e);
                }
            }
        }

        if (domain != null)
            realm = domain.getBasicAuthRealm();

        return getRealmHeader(realm);
    }

    public static String getRealmHeader(String realm)  {
        if (realm == null)
            realm = "Zimbra";
        return "BASIC realm=\"" + realm + "\"";
    }

    private static String getAdminURL(Server server, boolean relative) throws ServiceException {
        String serviceUrl = server.getAdminURL();
        if (relative) {
            return serviceUrl;
        } else {
            return URLUtil.getAdminURL(server, serviceUrl, true);
        }
    }

    private static String getMailURL(Server server, boolean relative) throws ServiceException {
        String serviceUrl = server.getMailURL();
        if (relative) {
            return serviceUrl;
        } else {
            return URLUtil.getServiceURL(server, serviceUrl, true);
        }
    }

    public static String getRedirectURL(HttpServletRequest req, Server server,
        boolean isAdminRequest, boolean relative) throws ServiceException, MalformedURLException {
        String redirectUrl;
        if (isAdminRequest) {
            redirectUrl = getAdminURL(server, relative);
        } else {
            redirectUrl = getMailURL(server, relative);
        }
        if (!relative) {
            URL url = new URL(redirectUrl);

            // replace host of the URL to the host the request was sent to
            String reqHost = req.getServerName();
            String host = url.getHost();

            if (!reqHost.equalsIgnoreCase(host)) {
                URL destUrl = new URL(url.getProtocol(), reqHost, url.getPort(), url.getFile());
                redirectUrl = destUrl.toString();
            }
        }
        return redirectUrl;
    }
    
    /**
     * This method is used to validate Auth Token
     * This method will only called when client provide Auth Token with TOTP
     * @param authTokenEl
     * @param prov
     * @param acct
     * @throws ServiceException
     */
    public static void validateAuthToken(Element authTokenEl, Provisioning prov, Account acct) throws ServiceException{
        try {
            ZimbraLog.account.debug("Validating two factor Auth token for account %s ", acct.getName());
            AuthToken twoFactorToken = AuthProvider.getAuthToken(authTokenEl, acct);
            Account twoFactorTokenAcct = AuthProvider.validateAuthToken(prov, twoFactorToken, false, Usage.TWO_FACTOR_AUTH);
            boolean  verifyAccount = authTokenEl.getAttributeBool(AccountConstants.A_VERIFY_ACCOUNT, false);
            if (verifyAccount && !twoFactorTokenAcct.getId().equalsIgnoreCase(acct.getId())) {
                throw new AuthTokenException("two-factor auth token doesn't match the named account");
            }
            ZimbraLog.account.debug("two-factor auth token has been matched for account %s ", acct.getName());
        } catch (AuthTokenException e) {
            AuthFailedServiceException exception = AuthFailedServiceException
                    .AUTH_FAILED("bad auth token");
            AuthListener.invokeOnException(exception);
            throw exception;
        }
    }
    
    public static AuthToken getAuthToken(Element request, Provisioning prov, ZimbraSoapContext zsc)
        throws ServiceException {
        //Fetch Auth token from cookies
        AuthToken at = zsc.getAuthToken();
        if(at == null) {
            Element authTokenEl = request.getOptionalElement(AdminConstants.E_AUTH_TOKEN);
            if(authTokenEl != null) {
                try {
                    at = AuthProvider.getAuthToken(request, new HashMap<String, Object>());
                } catch (AuthTokenException e) {
                    throw ServiceException.AUTH_REQUIRED();
                }
            }
        }
        if (at == null) {
            //neither login credentials nor valid auth token could be retrieved
            throw ServiceException.AUTH_REQUIRED();
        }

        if (at.isExpired()) {
            throw ServiceException.AUTH_EXPIRED();
        }

        if (!at.isRegistered()) {
            throw ServiceException.AUTH_EXPIRED("authtoken is invalid");
        }

        return at;
    }
    
    /**
     * This method will return the Auth token that will used by client to make 
     * request with two factor code + will return CSRF token if CSRF is enabled
     * @param account
     * @param twoFactorManager
     * @param zsc
     * @param tokenType
     * @param context
     * @param csrfSupport
     * @return
     * @throws ServiceException
     */
    public static Element getTwoFactorAuthToken(Account account, ZimbraSoapContext zsc,
            TokenType tokenType, Map<String, Object> context, boolean csrfSupport) throws ServiceException {
        ZimbraLog.account.debug("Generating auth token used to validate two factor code.");
        AuthToken twoFactorToken = AuthProvider.getAuthToken(account, Usage.TWO_FACTOR_AUTH, tokenType);
        
        Element response = zsc.createElement(AccountConstants.AUTH_RESPONSE);
        response.addUniqueElement(AccountConstants.E_TWO_FACTOR_AUTH_REQUIRED).setText("true");
        response.addAttribute(AccountConstants.E_LIFETIME, twoFactorToken.getExpires() - System.currentTimeMillis(), Element.Disposition.CONTENT);
        twoFactorToken.encodeAuthResp(response, false);

        HttpServletRequest httpReq = (HttpServletRequest) context.get(SoapServlet.SERVLET_REQUEST);
        HttpServletResponse httpResp = (HttpServletResponse) context.get(SoapServlet.SERVLET_RESPONSE);
        CsrfUtil.setCSRFToken(httpReq, httpResp, twoFactorToken, csrfSupport, response);
        try {
            ZimbraCookie.addHttpOnlyCookie(httpResp, ZimbraCookie.COOKIE_ZM_ADMIN_AUTH_TOKEN, twoFactorToken.getEncoded(), ZimbraCookie.PATH_ROOT, -1, true);
        } catch (AuthTokenException e) {
            throw ServiceException.AUTH_REQUIRED();
        }
        return response;
    }
}
