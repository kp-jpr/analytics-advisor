package gov.rajasthan.smart.srse.config;

import java.util.Arrays;
import java.util.List;

import gov.rajasthan.smart.srse.security.AuthMode;
import gov.rajasthan.smart.srse.security.Authorities;
import gov.rajasthan.smart.srse.security.PasswordChangeRequiredFilter;
import gov.rajasthan.smart.srse.security.RajSewadwarAuthenticationFilter;
import gov.rajasthan.smart.srse.security.SessionBearerAuthenticationFilter;
import gov.rajasthan.smart.srse.security.SessionVersionValidationFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * spring-boot-starter-security auto-locks every endpoint behind a login form
 * when no SecurityFilterChain is defined. Officer vs admin is split at
 * <strong>method level</strong> (narrow matchers first). Enforced by
 * {@link SessionBearerAuthenticationFilter} (mock/local) or
 * {@link RajSewadwarAuthenticationFilter} (rajsewadwar).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ObjectProvider<SessionBearerAuthenticationFilter> sessionBearerFilter;
    private final ObjectProvider<PasswordChangeRequiredFilter> passwordChangeRequiredFilter;
    private final ObjectProvider<SessionVersionValidationFilter> sessionVersionValidationFilter;
    private final ObjectProvider<RajSewadwarAuthenticationFilter> rajSewadwarFilter;
    private final List<String> allowedOrigins;

    public SecurityConfig(@Value("${srse.auth-mode}") String authModeConfig,
                          @Value("${srse.frontend-origins}") String frontendOrigins,
                          ObjectProvider<SessionBearerAuthenticationFilter> sessionBearerFilter,
                          ObjectProvider<PasswordChangeRequiredFilter> passwordChangeRequiredFilter,
                          ObjectProvider<SessionVersionValidationFilter> sessionVersionValidationFilter,
                          ObjectProvider<RajSewadwarAuthenticationFilter> rajSewadwarFilter) {
        AuthMode.valueOf(authModeConfig.toUpperCase());
        this.allowedOrigins = Arrays.stream(frontendOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        this.sessionBearerFilter = sessionBearerFilter;
        this.passwordChangeRequiredFilter = passwordChangeRequiredFilter;
        this.sessionVersionValidationFilter = sessionVersionValidationFilter;
        this.rajSewadwarFilter = rajSewadwarFilter;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/health/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/mock-login").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/verify-otp").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/resend-otp").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/change-password").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/analysis/column-metadata").hasAuthority(Authorities.SRSE_ADMIN)
                .requestMatchers(HttpMethod.DELETE, "/api/analysis/column-metadata")
                    .hasAuthority(Authorities.SRSE_ADMIN)
                .requestMatchers(HttpMethod.GET, "/api/analysis/column-metadata").hasAuthority(Authorities.STATE_OFFICER)
                .requestMatchers("/api/admin/audit", "/api/admin/audit.csv").hasAuthority(Authorities.AUDIT_READ)
                .requestMatchers("/api/admin/lineage", "/api/admin/lineage/**").hasAuthority(Authorities.AUDIT_READ)
                .requestMatchers("/api/admin/**").hasAuthority(Authorities.SRSE_ADMIN)
                .requestMatchers("/api/analysis/**").hasAuthority(Authorities.STATE_OFFICER)
                .requestMatchers("/api/saved-queries/**").hasAuthority(Authorities.STATE_OFFICER)
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/error").permitAll()
                .anyRequest().authenticated()
            );

        SessionBearerAuthenticationFilter bearer = sessionBearerFilter.getIfAvailable();
        if (bearer != null) {
            http.addFilterBefore(bearer, UsernamePasswordAuthenticationFilter.class);
        }
        PasswordChangeRequiredFilter passwordChange = passwordChangeRequiredFilter.getIfAvailable();
        if (passwordChange != null) {
            http.addFilterAfter(passwordChange, SessionBearerAuthenticationFilter.class);
        }
        SessionVersionValidationFilter sessionVersion = sessionVersionValidationFilter.getIfAvailable();
        if (sessionVersion != null) {
            http.addFilterAfter(sessionVersion, SessionBearerAuthenticationFilter.class);
        }
        RajSewadwarAuthenticationFilter rajSewadwar = rajSewadwarFilter.getIfAvailable();
        if (rajSewadwar != null) {
            http.addFilterBefore(rajSewadwar, UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }
}
