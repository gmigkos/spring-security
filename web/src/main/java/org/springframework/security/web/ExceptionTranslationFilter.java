/* Copyright 2004, 2005, 2006 Acegi Technology Pty Limited
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

package org.springframework.security.web;

import org.springframework.security.AccessDeniedException;
import org.springframework.security.SpringSecurityException;
import org.springframework.security.AuthenticationException;
import org.springframework.security.AuthenticationTrustResolver;
import org.springframework.security.AuthenticationTrustResolverImpl;
import org.springframework.security.InsufficientAuthenticationException;
import org.springframework.security.context.SecurityContextHolder;
import org.springframework.security.util.ThrowableAnalyzer;
import org.springframework.security.util.ThrowableCauseExtractor;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.beans.factory.InitializingBean;

import org.springframework.util.Assert;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Handles any <code>AccessDeniedException</code> and <code>AuthenticationException</code> thrown within the
 * filter chain.
 * <p>
 * This filter is necessary because it provides the bridge between Java exceptions and HTTP responses.
 * It is solely concerned with maintaining the user interface. This filter does not do any actual security enforcement.
 * <p>
 * If an {@link AuthenticationException} is detected, the filter will launch the <code>authenticationEntryPoint</code>.
 * This allows common handling of authentication failures originating from any subclass of
 * {@link org.springframework.security.intercept.AbstractSecurityInterceptor}.
 * <p>
 * If an {@link AccessDeniedException} is detected, the filter will determine whether or not the user is an anonymous
 * user. If they are an anonymous user, the <code>authenticationEntryPoint</code> will be launched. If they are not
 * an anonymous user, the filter will delegate to the {@link org.springframework.security.web.AccessDeniedHandler}.
 * By default the filter will use {@link org.springframework.security.web.AccessDeniedHandlerImpl}.
 * <p>
 * To use this filter, it is necessary to specify the following properties:
 * <ul>
 * <li><code>authenticationEntryPoint</code> indicates the handler that
 * should commence the authentication process if an
 * <code>AuthenticationException</code> is detected. Note that this may also
 * switch the current protocol from http to https for an SSL login.</li>
 * <li><code>portResolver</code> is used to determine the "real" port that a
 * request was received on.</li>
 * </ul>
 *
 * @author Ben Alex
 * @author colin sampaleanu
 * @version $Id$
 */
public class ExceptionTranslationFilter extends SpringSecurityFilter implements InitializingBean {

    //~ Instance fields ================================================================================================

    private AccessDeniedHandler accessDeniedHandler = new AccessDeniedHandlerImpl();
    private AuthenticationEntryPoint authenticationEntryPoint;
    private AuthenticationTrustResolver authenticationTrustResolver = new AuthenticationTrustResolverImpl();
    private PortResolver portResolver = new PortResolverImpl();
    private ThrowableAnalyzer throwableAnalyzer = new DefaultThrowableAnalyzer();
    private boolean createSessionAllowed = true;
    private boolean justUseSavedRequestOnGet;

    //~ Methods ========================================================================================================

    public void afterPropertiesSet() throws Exception {
        Assert.notNull(authenticationEntryPoint, "authenticationEntryPoint must be specified");
        Assert.notNull(portResolver, "portResolver must be specified");
        Assert.notNull(authenticationTrustResolver, "authenticationTrustResolver must be specified");
        Assert.notNull(throwableAnalyzer, "throwableAnalyzer must be specified");
    }

    public void doFilterHttp(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException,
            ServletException {

        try {
            chain.doFilter(request, response);

            if (logger.isDebugEnabled()) {
                logger.debug("Chain processed normally");
            }
        }
        catch (IOException ex) {
            throw ex;
        }
        catch (Exception ex) {
            // Try to extract a SpringSecurityException from the stacktrace
            Throwable[] causeChain = this.throwableAnalyzer.determineCauseChain(ex);
            SpringSecurityException ase = (SpringSecurityException)
                    this.throwableAnalyzer.getFirstThrowableOfType(SpringSecurityException.class, causeChain);

            if (ase != null) {
                handleException(request, response, chain, ase);
            }
            else {
                // Rethrow ServletExceptions and RuntimeExceptions as-is
                if (ex instanceof ServletException) {
                    throw (ServletException) ex;
                }
                else if (ex instanceof RuntimeException) {
                    throw (RuntimeException) ex;
                }

                // Wrap other Exceptions. These are not expected to happen
                throw new RuntimeException(ex);
            }
        }
    }

    public AuthenticationEntryPoint getAuthenticationEntryPoint() {
        return authenticationEntryPoint;
    }

    public AuthenticationTrustResolver getAuthenticationTrustResolver() {
        return authenticationTrustResolver;
    }

    public PortResolver getPortResolver() {
        return portResolver;
    }

    private void handleException(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
            SpringSecurityException exception) throws IOException, ServletException {
        if (exception instanceof AuthenticationException) {
            if (logger.isDebugEnabled()) {
                logger.debug("Authentication exception occurred; redirecting to authentication entry point", exception);
            }

            sendStartAuthentication(request, response, chain, (AuthenticationException) exception);
        }
        else if (exception instanceof AccessDeniedException) {
            if (authenticationTrustResolver.isAnonymous(SecurityContextHolder.getContext().getAuthentication())) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Access is denied (user is anonymous); redirecting to authentication entry point",
                            exception);
                }

                sendStartAuthentication(request, response, chain, new InsufficientAuthenticationException(
                        "Full authentication is required to access this resource"));
            }
            else {
                if (logger.isDebugEnabled()) {
                    logger.debug("Access is denied (user is not anonymous); delegating to AccessDeniedHandler",
                            exception);
                }

                accessDeniedHandler.handle(request, response, (AccessDeniedException) exception);
            }
        }
    }

    /**
     * If <code>true</code>, indicates that <code>ExceptionTranslationFilter</code> is permitted to store the target
     * URL and exception information in a new <code>HttpSession</code> (the default).
     * In situations where you do not wish to unnecessarily create <code>HttpSession</code>s - because the user agent
     * will know the failed URL, such as with BASIC or Digest authentication - you may wish to set this property to
     * <code>false</code>.
     * <p>
     * Remember to also set
     * {@link org.springframework.security.web.context.HttpSessionSecurityContextRepository#setAllowSessionCreation(boolean)}
     * to <code>false</code> if you set this property to <code>false</code>.
     *
     * @return <code>true</code> if the <code>HttpSession</code> will be
     * used to store information about the failed request, <code>false</code>
     * if the <code>HttpSession</code> will not be used
     */
    public boolean isCreateSessionAllowed() {
        return createSessionAllowed;
    }

    protected void sendStartAuthentication(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
            AuthenticationException reason) throws ServletException, IOException {
        // SEC-112: Clear the SecurityContextHolder's Authentication, as the
        // existing Authentication is no longer considered valid
        SecurityContextHolder.getContext().setAuthentication(null);
        saveRequestIfAllowed(request);
        logger.debug("Calling Authentication entry point.");
        authenticationEntryPoint.commence(request, response, reason);
    }

    private void saveRequestIfAllowed(HttpServletRequest request) {
        if (!justUseSavedRequestOnGet || "GET".equals(request.getMethod())) {
            SavedRequest savedRequest = new SavedRequest(request, portResolver);

            if (createSessionAllowed || request.getSession(false) != null) {
                // Store the HTTP request itself. Used by AbstractProcessingFilter
                // for redirection after successful authentication (SEC-29)
                request.getSession().setAttribute(SavedRequest.SPRING_SECURITY_SAVED_REQUEST_KEY, savedRequest);
                logger.debug("SavedRequest added to Session: " + savedRequest);
            }
        }
    }

    public void setAccessDeniedHandler(AccessDeniedHandler accessDeniedHandler) {
        Assert.notNull(accessDeniedHandler, "AccessDeniedHandler required");
        this.accessDeniedHandler = accessDeniedHandler;
    }

    public void setAuthenticationEntryPoint(AuthenticationEntryPoint authenticationEntryPoint) {
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    public void setAuthenticationTrustResolver(AuthenticationTrustResolver authenticationTrustResolver) {
        this.authenticationTrustResolver = authenticationTrustResolver;
    }

    public void setCreateSessionAllowed(boolean createSessionAllowed) {
        this.createSessionAllowed = createSessionAllowed;
    }

    public void setPortResolver(PortResolver portResolver) {
        this.portResolver = portResolver;
    }

    public void setThrowableAnalyzer(ThrowableAnalyzer throwableAnalyzer) {
        this.throwableAnalyzer = throwableAnalyzer;
    }

    /**
     * If <code>true</code>, will only use <code>SavedRequest</code> to determine the target URL on successful
     * authentication if the request that caused the authentication request was a GET. Defaults to false.
     */
    public void setJustUseSavedRequestOnGet(boolean justUseSavedRequestOnGet) {
        this.justUseSavedRequestOnGet = justUseSavedRequestOnGet;
    }

    public int getOrder() {
        return FilterChainOrder.EXCEPTION_TRANSLATION_FILTER;
    }

    /**
     * Default implementation of <code>ThrowableAnalyzer</code> which is capable of also unwrapping
     * <code>ServletException</code>s.
     */
    private static final class DefaultThrowableAnalyzer extends ThrowableAnalyzer {
        /**
         * @see org.springframework.security.util.ThrowableAnalyzer#initExtractorMap()
         */
        protected void initExtractorMap() {
            super.initExtractorMap();

            registerExtractor(ServletException.class, new ThrowableCauseExtractor() {
                public Throwable extractCause(Throwable throwable) {
                    ThrowableAnalyzer.verifyThrowableHierarchy(throwable, ServletException.class);
                    return ((ServletException) throwable).getRootCause();
                }
            });
        }

    }

}