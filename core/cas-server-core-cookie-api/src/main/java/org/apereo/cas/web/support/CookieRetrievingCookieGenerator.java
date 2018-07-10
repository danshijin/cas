package org.apereo.cas.web.support;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.StringUtils;
import org.apereo.cas.authentication.RememberMeCredential;
import org.apereo.cas.util.CollectionUtils;
import org.springframework.web.util.CookieGenerator;
import org.springframework.webflow.execution.RequestContext;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Extends CookieGenerator to allow you to retrieve a value from a request.
 * The cookie is automatically marked as httpOnly, if the servlet container has support for it.
 * Also has support for remember-me.
 *
 * @author Scott Battaglia
 * @author Misagh Moayyed
 * @since 3.1
 */
@Slf4j
@Setter
public class CookieRetrievingCookieGenerator extends CookieGenerator {

    private static final int DEFAULT_REMEMBER_ME_MAX_AGE = 7889231;

    /**
     * The maximum age the cookie should be remembered for.
     * The default is three months ({@value} in seconds, according to Google)
     */
    private final int rememberMeMaxAge;

    /**
     * Responsible for manging and verifying the cookie value.
     **/
    private final CookieValueManager casCookieValueManager;

    public CookieRetrievingCookieGenerator(final String name, final String path, final int maxAge,
                                           final boolean secure, final String domain, final boolean httpOnly) {
        this(name, path, maxAge, secure, domain, new NoOpCookieValueManager(),
            DEFAULT_REMEMBER_ME_MAX_AGE, httpOnly);
    }

    public CookieRetrievingCookieGenerator(final String name, final String path, final int maxAge,
                                           final boolean secure, final String domain, final boolean httpOnly,
                                           final CookieValueManager cookieValueManager) {
        this(name, path, maxAge, secure, domain, cookieValueManager,
            DEFAULT_REMEMBER_ME_MAX_AGE, httpOnly);
    }

    public CookieRetrievingCookieGenerator(final String name, final String path, final int maxAge, final boolean secure,
                                           final String domain, final CookieValueManager casCookieValueManager,
                                           final int rememberMeMaxAge, final boolean httpOnly) {
        super.setCookieName(name);
        super.setCookiePath(path);
        this.setCookieDomain(domain);
        super.setCookieMaxAge(maxAge);
        super.setCookieSecure(secure);
        super.setCookieHttpOnly(httpOnly);
        this.casCookieValueManager = casCookieValueManager;
        this.rememberMeMaxAge = rememberMeMaxAge;
    }

    /**
     * Adds the cookie, taking into account {@link RememberMeCredential#REQUEST_PARAMETER_REMEMBER_ME}
     * in the request.
     *
     * @param requestContext the request context
     * @param cookieValue    the cookie value
     */
    public void addCookie(final RequestContext requestContext, final String cookieValue) {
        val request = WebUtils.getHttpServletRequestFromExternalWebflowContext(requestContext);
        val response = WebUtils.getHttpServletResponseFromExternalWebflowContext(requestContext);
        val theCookieValue = this.casCookieValueManager.buildCookieValue(cookieValue, request);
        if (isRememberMeAuthentication(requestContext)) {
            LOGGER.debug("Creating cookie [{}] for remember-me authentication with max-age [{}]", getCookieName(), this.rememberMeMaxAge);
            val cookie = createCookie(theCookieValue);
            cookie.setMaxAge(this.rememberMeMaxAge);
            cookie.setSecure(isCookieSecure());
            cookie.setHttpOnly(isCookieHttpOnly());
            cookie.setComment("CAS Cookie w/ Remember-Me");
            response.addCookie(cookie);
        } else {
            LOGGER.debug("Creating cookie [{}]", getCookieName());
            super.addCookie(response, theCookieValue);
        }
    }

    /**
     * Add cookie.
     *
     * @param request     the request
     * @param response    the response
     * @param cookieValue the cookie value
     */
    public void addCookie(final HttpServletRequest request, final HttpServletResponse response, final String cookieValue) {
        val theCookieValue = this.casCookieValueManager.buildCookieValue(cookieValue, request);
        LOGGER.debug("Creating cookie [{}]", getCookieName());
        super.addCookie(response, theCookieValue);
    }

    private Boolean isRememberMeAuthentication(final RequestContext requestContext) {
        if (isRememberMeProvidedInRequest(requestContext)) {
            LOGGER.debug("This request is from a remember-me authentication event");
            return Boolean.TRUE;
        }
        if (isRememberMeRecordedInAuthentication(requestContext)) {
            LOGGER.debug("The recorded authentication is from a remember-me request");
            return Boolean.TRUE;
        }
        return Boolean.FALSE;
    }

    private Boolean isRememberMeRecordedInAuthentication(final RequestContext requestContext) {
        LOGGER.debug("Request does not indicate a remember-me authentication event. Locating authentication object from the request context...");
        val auth = WebUtils.getAuthentication(requestContext);
        if (auth == null) {
            return Boolean.FALSE;
        }
        val attributes = auth.getAttributes();
        LOGGER.debug("Located authentication attributes [{}]", attributes);
        if (attributes.containsKey(RememberMeCredential.AUTHENTICATION_ATTRIBUTE_REMEMBER_ME)) {
            val rememberMeValue = attributes.getOrDefault(RememberMeCredential.AUTHENTICATION_ATTRIBUTE_REMEMBER_ME, Boolean.FALSE);
            LOGGER.debug("Located remember-me authentication attribute [{}]", rememberMeValue);
            return CollectionUtils.wrapSet(rememberMeValue).contains(Boolean.TRUE);
        }
        return Boolean.FALSE;
    }

    private boolean isRememberMeProvidedInRequest(final RequestContext requestContext) {
        val request = WebUtils.getHttpServletRequestFromExternalWebflowContext(requestContext);
        val value = request.getParameter(RememberMeCredential.REQUEST_PARAMETER_REMEMBER_ME);
        LOGGER.debug("Locating request parameter [{}] with value [{}]", RememberMeCredential.REQUEST_PARAMETER_REMEMBER_ME, value);
        return StringUtils.isNotBlank(value) && WebUtils.isRememberMeAuthenticationEnabled(requestContext);
    }

    /**
     * Retrieve cookie value.
     *
     * @param request the request
     * @return the cookie value
     */
    public String retrieveCookieValue(final HttpServletRequest request) {
        try {
            val cookie = org.springframework.web.util.WebUtils.getCookie(request, getCookieName());
            return cookie == null ? null : this.casCookieValueManager.obtainCookieValue(cookie, request);
        } catch (final Exception e) {
            LOGGER.debug(e.getMessage(), e);
        }
        return null;
    }

    @Override
    public void setCookieDomain(final String cookieDomain) {
        super.setCookieDomain(StringUtils.defaultIfEmpty(cookieDomain, null));
    }

    @Override
    protected Cookie createCookie(final String cookieValue) {
        val c = super.createCookie(cookieValue);
        c.setComment("CAS Cookie");
        return c;
    }
}
