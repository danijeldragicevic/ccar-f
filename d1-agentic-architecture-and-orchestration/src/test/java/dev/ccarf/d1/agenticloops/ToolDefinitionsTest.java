package dev.ccarf.d1.agenticloops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.models.messages.Tool;
import org.junit.jupiter.api.Test;

class ToolDefinitionsTest {

    @Test
    void calculatorDeclaresNameDescriptionAndExpressionProperty() {
        Tool calculator = ToolDefinitions.getCalculator();

        assertEquals("calculator", calculator.name());
        assertTrue(calculator.description().isPresent() && !calculator.description().get().isBlank());
        assertTrue(calculator.inputSchema().properties().isPresent());
        assertTrue(calculator.inputSchema().properties().get()
                ._additionalProperties().containsKey("expression"));
    }

    @Test
    void webSearchDeclaresNameDescriptionAndQueryProperty() {
        Tool webSearch = ToolDefinitions.getWebSearch();

        assertEquals("web_search", webSearch.name());
        assertTrue(webSearch.description().isPresent() && !webSearch.description().get().isBlank());
        assertTrue(webSearch.inputSchema().properties().isPresent());
        assertTrue(webSearch.inputSchema().properties().get()
                ._additionalProperties().containsKey("query"));
    }
}
