package com.example.apiasistente.auth.service;

import com.example.apiasistente.auth.config.AdminBootstrapProperties;
import com.example.apiasistente.auth.entity.AppUser;
import com.example.apiasistente.auth.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapRunnerTest {

    @Mock
    private AppUserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void skipWhenDisabled() {
        AdminBootstrapProperties properties = new AdminBootstrapProperties();
        properties.setEnabled(false);

        AdminBootstrapRunner runner = new AdminBootstrapRunner(properties, userRepository, passwordEncoder);
        runner.bootstrapIfNeeded();

        verify(userRepository, never()).count();
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any(AppUser.class));
    }

    @Test
    void skipWhenUsersAlreadyExist() {
        AdminBootstrapProperties properties = new AdminBootstrapProperties();
        properties.setOutputFile("");
        when(userRepository.count()).thenReturn(2L);

        AdminBootstrapRunner runner = new AdminBootstrapRunner(properties, userRepository, passwordEncoder);
        runner.bootstrapIfNeeded();

        verify(userRepository).count();
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any(AppUser.class));
    }

    @Test
    void createAdminWithProvidedPassword() {
        AdminBootstrapProperties properties = new AdminBootstrapProperties();
        properties.setUsername("installer");
        properties.setPassword("StrongPassword123!");
        properties.setOutputFile("");
        when(userRepository.count()).thenReturn(0L);
        when(passwordEncoder.encode("StrongPassword123!")).thenReturn("ENCODED");

        AdminBootstrapRunner runner = new AdminBootstrapRunner(properties, userRepository, passwordEncoder);
        runner.bootstrapIfNeeded();

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(captor.capture());
        AppUser saved = captor.getValue();
        assertEquals("installer", saved.getUsername());
        assertEquals("ENCODED", saved.getPasswordHash());
        assertTrue(saved.getGrantedPermissions() != null && !saved.getGrantedPermissions().isBlank());
    }

    @Test
    void generatePasswordWhenMissing() {
        AdminBootstrapProperties properties = new AdminBootstrapProperties();
        properties.setUsername("admin");
        properties.setPassword("");
        properties.setGenerateRandomPasswordIfEmpty(true);
        properties.setOutputFile("");
        when(userRepository.count()).thenReturn(0L);
        when(passwordEncoder.encode(anyString())).thenReturn("ENCODED");

        AdminBootstrapRunner runner = new AdminBootstrapRunner(properties, userRepository, passwordEncoder);
        runner.bootstrapIfNeeded();

        ArgumentCaptor<String> rawPassword = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).encode(rawPassword.capture());
        String generated = rawPassword.getValue();
        assertTrue(generated != null && generated.length() >= 12);
    }
}
