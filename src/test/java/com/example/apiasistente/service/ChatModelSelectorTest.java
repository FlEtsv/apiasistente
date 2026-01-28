package com.example.apiasistente.service;

import com.example.apiasistente.config.OllamaProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatModelSelectorTest {

    @Test
    void resolveChatModelPrefersConfiguredAliases() {
        OllamaProperties props = new OllamaProperties();
        props.setChatModel("qwen2.5:32b");
        props.setFastChatModel("qwen3.0:14b");

        ChatModelSelector selector = new ChatModelSelector(props);

        assertEquals("qwen2.5:32b", selector.resolveChatModel(null));
        assertEquals("qwen2.5:32b", selector.resolveChatModel("default"));
        assertEquals("qwen3.0:14b", selector.resolveChatModel("fast"));
        assertEquals("qwen3.0:14b", selector.resolveChatModel("qwen3.0:14b"));
        assertEquals("qwen2.5:32b", selector.resolveChatModel("qwen2.5:32b"));
        assertThrows(IllegalArgumentException.class, () -> selector.resolveChatModel("otro-modelo"));
    }
}
