package com.cloudcode.wantok.keycloak;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Optional API filter for future REST/API webapps. Add to the API webapp web.xml, not necessarily the wankeycloak webapp.
 * For production, validate access tokens exactly like ID tokens, including issuer, audience and scopes.
 */
public class WanKeycloakApiFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            res.sendError(401, "Missing Bearer token");
            return;
        }
        // Scaffold: parse/validate token and attach tenant/user claims here using OidcTokenService and role mappings.
        chain.doFilter(request, response);
    }
}
