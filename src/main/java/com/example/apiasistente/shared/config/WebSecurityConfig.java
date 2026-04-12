package com.example.apiasistente.shared.config;

import com.example.apiasistente.apikey.security.ApiKeyAuthFilter;
import com.example.apiasistente.apikey.service.ApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.access.RequestMatcherDelegatingAccessDeniedHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.LinkedHashMap;
import java.util.function.Supplier;

/**
 * Configuracion de Web Security.
 */
@Configuration
public class WebSecurityConfig {

    @Bean
    CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        return repository;
    }

    @Bean
    AccessDeniedHandler apiAccessDeniedHandler(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return (request, response, accessDeniedException) ->
                resolver.resolveException(request, response, null, accessDeniedException);
    }

    @Bean
    AccessDeniedHandler webAccessDeniedHandler(@Qualifier("apiAccessDeniedHandler") AccessDeniedHandler apiAccessDeniedHandler) {
        AccessDeniedHandlerImpl pageHandler = new AccessDeniedHandlerImpl();
        pageHandler.setErrorPage("/access-denied");

        LinkedHashMap<RequestMatcher, AccessDeniedHandler> handlers = new LinkedHashMap<>();
        handlers.put(new AntPathRequestMatcher("/api/**"), apiAccessDeniedHandler);
        return new RequestMatcherDelegatingAccessDeniedHandler(handlers, pageHandler);
    }

    @Bean
    @Order(1)
    SecurityFilterChain extChain(HttpSecurity http, ApiKeyService apiKeyService) throws Exception {

        http.securityMatcher("/api/ext/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ApiKeyAuthFilter(apiKeyService), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .cors(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain integrationChain(HttpSecurity http, ApiKeyService apiKeyService) throws Exception {

        http.securityMatcher("/api/integration/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ApiKeyAuthFilter(apiKeyService), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .cors(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain webChain(HttpSecurity http,
                                 CookieCsrfTokenRepository csrfTokenRepository,
                                 @Qualifier("webAccessDeniedHandler") AccessDeniedHandler webAccessDeniedHandler) throws Exception {

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .authorizeHttpRequests(auth -> auth
                        // paginas publicas
                        .requestMatchers("/login", "/register", "/access-denied", "/error").permitAll()

                        // estaticos tipicos
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()

                        // Angular SPA static bundle
                        .requestMatchers("/app/**").permitAll()

                        // estaticos sueltos
                        .requestMatchers("/chat.js", "/api-keys.js", "/rag-admin.js", "/rag-maintenance.js", "/rag-ops.js").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/api/auth/status", "/api/auth/login", "/api/auth/register").permitAll()

                        // El chat requiere login
                        .requestMatchers("/chat", "/chat/**", "/api/chat/**").hasAuthority("PERM_CHAT")
                        .requestMatchers("/rag-admin", "/api/rag/**").hasAuthority("PERM_RAG")
                        .requestMatchers("/monitor", "/api/monitor/**", "/ops/**").hasAuthority("PERM_MONITOR")
                        .requestMatchers("/api/api-keys/**", "/api/registration-codes/**", "/api/system-prompts/**").hasAuthority("PERM_API_KEYS")
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .permitAll()
                )
                .logout(l -> l
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .permitAll()
                )
                .exceptionHandling(ex -> ex
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                new AntPathRequestMatcher("/api/**"))
                        .accessDeniedHandler(webAccessDeniedHandler))
                .cors(Customizer.withDefaults());

        return http.build();
    }

    /**
     * Accepts both the plain cookie token used by the SPA and the masked token
     * used by server-rendered pages.
     */
    private static final class SpaCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

        private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
        private final XorCsrfTokenRequestAttributeHandler xor = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(HttpServletRequest request,
                           HttpServletResponse response,
                           Supplier<CsrfToken> csrfToken) {
            this.xor.handle(request, response, csrfToken);
            csrfToken.get();
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            String headerValue = request.getHeader(csrfToken.getHeaderName());
            if (StringUtils.hasText(headerValue)) {
                if (headerValue.equals(csrfToken.getToken())) {
                    return this.plain.resolveCsrfTokenValue(request, csrfToken);
                }
                String resolved = this.xor.resolveCsrfTokenValue(request, csrfToken);
                if (StringUtils.hasText(resolved)) {
                    return resolved;
                }
            }
            return this.xor.resolveCsrfTokenValue(request, csrfToken);
        }
    }
}
