package com.example.apiasistente.apikey.service;

import com.example.apiasistente.apikey.dto.ApiKeyCreateResponse;
import com.example.apiasistente.apikey.entity.ApiKey;
import com.example.apiasistente.auth.entity.AppUser;
import com.example.apiasistente.apikey.repository.ApiKeyRepository;
import com.example.apiasistente.auth.repository.AppUserRepository;
import com.example.apiasistente.chat.service.ChatService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas para Api Key Service.
 */
class ApiKeyServiceTest {

    @Test
    void createForUserCreatesIsolatedSession() {
        ApiKeyRepository apiKeyRepo = mock(ApiKeyRepository.class);
        AppUserRepository userRepo = mock(AppUserRepository.class);
        ChatService chatService = mock(ChatService.class);

        ApiKeyService service = new ApiKeyService(apiKeyRepo, userRepo, chatService);

        AppUser user = new AppUser();
        user.setId(42L);
        user.setUsername("ana");

        when(userRepo.findByUsername("ana")).thenReturn(Optional.of(user));
        when(chatService.newSession("ana")).thenReturn("session-123");
        when(apiKeyRepo.save(any(ApiKey.class))).thenAnswer(invocation -> {
            ApiKey saved = invocation.getArgument(0);
            saved.setId(7L);
            return saved;
        });

        ApiKeyCreateResponse response = service.createForUser("ana", "Integracion CRM", true);

        assertNotNull(response.apiKey());
        assertNotNull(response.keyPrefix());
        assertEquals("Integracion CRM", response.label());
        assertEquals(true, response.specialModeEnabled());
        assertEquals("session-123", response.sessionId());
        verify(chatService).newSession("ana");
    }
}


