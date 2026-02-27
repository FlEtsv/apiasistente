package com.example.apiasistente.config;

import com.example.apiasistente.security.ApiKeyAuthFilter;
import com.example.apiasistente.service.ApiKeyService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.http.HttpStatus;

@Configuration
public class WebSecurityConfig {

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
    SecurityFilterChain webChain(HttpSecurity http) throws Exception {

        http
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                .authorizeHttpRequests(auth -> auth
                        // páginas públicas
                        .requestMatchers("/login", "/register", "/error").permitAll()

                        // estáticos típicos
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()

                        // estáticos sueltos (TU caso real)
                        .requestMatchers("/chat.js", "/api-keys.js", "/rag-admin.js").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()

                        // NO permitas "/" si ahí está el chat. Debe pedir login
                        .requestMatchers("/chat", "/api/chat/**").hasAuthority("PERM_CHAT")
                        .requestMatchers("/rag-admin", "/api/rag/**").hasAuthority("PERM_RAG")
                        .requestMatchers("/monitor", "/api/monitor/**", "/ops/**").hasAuthority("PERM_MONITOR")
                        .requestMatchers("/api/api-keys/**", "/api/registration-codes/**").hasAuthority("PERM_API_KEYS")
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
                .exceptionHandling(ex -> ex.accessDeniedPage("/access-denied"))
                .cors(Customizer.withDefaults());

        return http.build();
    }
}
