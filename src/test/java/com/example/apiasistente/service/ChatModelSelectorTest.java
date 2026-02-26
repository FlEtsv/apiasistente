package com.example.apiasistente.service;

import com.example.apiasistente.config.OllamaProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatModelSelectorTest {

    @Test
    void resolveChatModelAppliesAutoRoutingPolicy() {
        OllamaProperties props = new OllamaProperties();
        props.setChatModel("qwen2.5:32b");
        props.setFastChatModel("qwen3.0:14b");
        props.setVisualModel("qwen-vl:latest");
        props.setResponseGuardModel("qwen2.5:3b");

        ChatModelSelector selector = new ChatModelSelector(props);

        assertEquals("qwen3.0:14b", selector.resolveChatModel(null));
        assertEquals("qwen3.0:14b", selector.resolveChatModel("default"));
        assertEquals("qwen3.0:14b", selector.resolveChatModel("auto"));
        assertEquals("qwen2.5:32b", selector.resolveChatModel("auto", true, false, false));
        assertEquals("qwen2.5:32b", selector.resolveChatModel("auto", false, true, false));
        assertEquals("qwen2.5:32b", selector.resolveChatModel("auto", false, false, true));
    }

    @Test
    void resolveChatModelSupportsExplicitAliasesAndExactMatches() {
        OllamaProperties props = new OllamaProperties();
        props.setChatModel("qwen2.5:32b");
        props.setFastChatModel("qwen3.0:14b");
        props.setVisualModel("qwen-vl:latest");
        props.setResponseGuardModel("qwen2.5:3b");

        ChatModelSelector selector = new ChatModelSelector(props);

        assertEquals("qwen2.5:32b", selector.resolveChatModel("chat"));
        assertEquals("qwen3.0:14b", selector.resolveChatModel("fast"));
        assertEquals("qwen2.5:32b", selector.resolveChatModel("visual"));
        assertEquals("qwen2.5:32b", selector.resolveChatModel("qwen-vl:latest"));
        assertEquals("qwen3.0:14b", selector.resolveChatModel("qwen3.0:14b"));
        assertEquals("qwen2.5:32b", selector.resolveChatModel("qwen2.5:32b"));
        assertEquals("qwen-vl:latest", selector.resolveVisualModel("visual"));
        assertEquals("qwen-vl:latest", selector.resolveVisualModel("default"));
        assertEquals("qwen2.5:3b", selector.resolveResponseGuardModel());
        assertThrows(IllegalArgumentException.class, () -> selector.resolveChatModel("otro-modelo"));
    }
}
