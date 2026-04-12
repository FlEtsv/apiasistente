package com.example.apiasistente.auth.controller;

import com.example.apiasistente.auth.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
/**
 * Pruebas para Auth Controller.
 */
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @Test
    void registerPageRedirectsToAngularRoute() throws Exception {
        mockMvc.perform(get("/register")
                        .requestAttr("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "test-token")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/register"));
    }

    @Test
    void loginPageRedirectsToAngularRoute() throws Exception {
        mockMvc.perform(get("/login")
                        .requestAttr("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "test-token")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/login"));
    }

    @Test
    void registerSuccessRedirectsToLogin() throws Exception {
        doNothing().when(authService).register(eq("ana"), eq("secret123"), eq("CODE-1"));

        mockMvc.perform(post("/register")
                .param("username", "ana")
                .param("password", "secret123")
                .param("code", "CODE-1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/login?registered=1"));
    }

    @Test
    void registerFailureRedirectsBackToAngularRegister() throws Exception {
        doThrow(new IllegalArgumentException("Codigo invalido"))
                .when(authService).register(eq("ana"), eq("secret123"), eq("BAD"));

        mockMvc.perform(post("/register")
                        .requestAttr("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "test-token"))
                        .param("username", "ana")
                        .param("password", "secret123")
                        .param("code", "BAD"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/register?error=1&message=Codigo%20invalido"));
    }
}
