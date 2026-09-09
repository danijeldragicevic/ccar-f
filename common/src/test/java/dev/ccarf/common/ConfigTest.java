package dev.ccarf.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConfigTest {

    @Test
    void modelMainReturnsConfiguredValue() {
        assertEquals("claude-opus-5", Config.modelMain());
    }

    @Test
    void modelWorkerReturnsConfiguredValue() {
        assertEquals("claude-sonnet-5", Config.modelWorker());
    }

    @Test
    void maxTokensReturnsConfiguredValue() {
        assertEquals(8000L, Config.maxTokens());
    }

    @Test
    void maxTokensIsPositive() {
        assertTrue(Config.maxTokens() > 0);
    }
}
