package dev.ccarf.d1.subagentinvocationandcontextpassing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;

class TaskToolCoordinatorAgentTest {

  @Test
  void taskToolDeclaresNameAndNonBlankDescription() {
    Tool taskTool = TaskToolCoordinatorAgent.getTaskTool();

    assertEquals("task", taskTool.name());
    assertTrue(taskTool.description().isPresent() && !taskTool.description().get().isBlank());
  }

  @Test
  void taskToolDeclaresSubagentTypeAndPromptProperties() {
    Tool taskTool = TaskToolCoordinatorAgent.getTaskTool();

    assertTrue(taskTool.inputSchema().properties().isPresent());
    Map<String, JsonValue> properties =
        taskTool.inputSchema().properties().get()._additionalProperties();
    assertTrue(properties.containsKey("subagent_type"));
    assertTrue(properties.containsKey("prompt"));
  }

  @Test
  void subagentTypePropertyIsRestrictedToWebSearchAndDocumentAnalysis() {
    Tool taskTool = TaskToolCoordinatorAgent.getTaskTool();
    Map<String, JsonValue> properties =
        taskTool.inputSchema().properties().get()._additionalProperties();

    Map<String, Object> subagentType =
        properties.get("subagent_type").convert(new TypeReference<Map<String, Object>>() {});

    assertEquals(List.of("web_search", "document_analysis"), subagentType.get("enum"));
  }
}
