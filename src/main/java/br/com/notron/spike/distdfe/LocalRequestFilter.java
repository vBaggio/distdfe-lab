package br.com.notron.spike.distdfe;

import java.io.IOException;
import java.net.URI;
import java.util.Set;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** The unauthenticated prototype is local-only; reject browser requests from other origins. */
@Component
public class LocalRequestFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        response.setHeader("Cache-Control","no-store");
        response.setHeader("X-Content-Type-Options","nosniff");
        response.setHeader("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        if(!Set.of("localhost","127.0.0.1","[::1]").contains(request.getServerName())) {
            response.sendError(403);return;
        }
        String origin=request.getHeader("Origin");
        if(origin!=null) {
            try {
                var uri=URI.create(origin); int port=uri.getPort()==-1?80:uri.getPort();
                if(!"http".equals(uri.getScheme())||!request.getServerName().equals(uri.getHost())||port!=request.getServerPort()) {
                    response.sendError(403); return;
                }
            } catch(IllegalArgumentException e) {response.sendError(403);return;}
        }
        chain.doFilter(request,response);
    }
}
