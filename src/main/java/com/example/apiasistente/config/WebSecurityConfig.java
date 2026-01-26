package com.example.apiasistente.config;

import com.example.apiasistente.repository.AppUserRepository;
import com.example.apiasistente.security.ApiKeyAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
public class WebSecurityConfig {

    // ✅ Un solo PasswordEncoder para TODO el proyecto
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    // ---------------------------------------------------------------------
    // 1) API EXTERNA (stateless + API key) => /api/ext/**
    // ---------------------------------------------------------------------
    @Bean
    @Order(1)
    public SecurityFilterChain extChain(HttpSecurity http,
                                        AppUserRepository userRepo,
                                        PasswordEncoder encoder) throws Exception {

        http.securityMatcher("/api/ext/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ApiKeyAuthFilter(userRepo, encoder), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .cors(Customizer.withDefaults());

        return http.build();
    }

    // ---------------------------------------------------------------------
    // 2) WEB NORMAL (sesión + CSRF cookie para JS) => resto
    // ---------------------------------------------------------------------
    @Bean
    @Order(2)
    public SecurityFilterChain webChain(HttpSecurity http) throws Exception {

        http
                .csrf(csrf -> csrf
                        // cookie XSRF-TOKEN para que chat.js pueda leerla y mandarla
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/", "/login", "/register", "/error",
                                "/chat.js", "/chat.js/**",
                                "/css/**", "/js/**", "/images/**", "/favicon.ico"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/chat", true)
                        .failureUrl("/login?error")
                        .permitAll()
                )
                .logout(l -> l
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .permitAll()
                )
                .cors(Customizer.withDefaults());

        return http.build();
    }
}
