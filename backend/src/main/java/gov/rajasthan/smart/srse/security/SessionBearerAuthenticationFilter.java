package gov.rajasthan.smart.srse.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Validates bearer tokens for {@code mock} and {@code local} auth modes.
 */
@Component
@ConditionalOnBean(SessionTokenService.class)
@ConditionalOnExpression("'${srse.auth-mode:mock}' == 'mock' || '${srse.auth-mode:mock}' == 'local'")
public class SessionBearerAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionBearerAuthenticationFilter.class);

    private final SessionTokenService sessionTokenService;

    public SessionBearerAuthenticationFilter(SessionTokenService sessionTokenService) {
        this.sessionTokenService = sessionTokenService;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                SessionTokenService.ParsedSessionToken parsed = sessionTokenService.parse(header.substring(7));
                List<SimpleGrantedAuthority> granted = parsed.authorities().stream()
                        .map(SimpleGrantedAuthority::new)
                        .toList();
                var authentication = new UsernamePasswordAuthenticationToken(
                        parsed.username(), null, granted);
                authentication.setDetails(parsed);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (io.jsonwebtoken.JwtException | IllegalArgumentException ex) {
                // expired, malformed, or bad signature — stay anonymous
            } catch (RuntimeException ex) {
                log.warn("Unexpected failure parsing bearer token for {}: {}",
                        request.getRequestURI(), ex.toString());
            }
        }
        filterChain.doFilter(request, response);
    }
}
