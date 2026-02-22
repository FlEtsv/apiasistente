package com.example.apiasistente.service;

import com.example.apiasistente.model.dto.ApiKeyCreateResponse;
import com.example.apiasistente.model.entity.ApiKey;
import com.example.apiasistente.model.entity.AppUser;
import com.example.apiasistente.repository.ApiKeyRepository;
import com.example.apiasistente.repository.AppUserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
