package dev.ccarf.d1.subagentinvocationandcontextpassing;

import java.util.List;
import java.util.Map;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Tool;

import dev.ccarf.common.Config;

/**
 * Exercise 1.3.1
 * Create a coordinator agent with a "task" tool in its tool list - the
 * raw-SDK equivalent of an Agent SDK coordinator whose allowedTools includes
 * "Task" - so it has an explicit, callable mechanism for spawning subagents.
 *
 * <p>Note on translation: the exam material for this exercise describes the
 * Claude Agent SDK (TS/Python) - a {@code query()} call whose {@code options}
 * include {@code allowedTools} containing {@code "Agent"}/{@code "Task"} plus
 * subagent definitions under {@code options.agents}. There is no Java Agent
 * SDK (confirmed with Anthropic docs: it ships for Python and TypeScript
 * only), and the plain Anthropic Messages API this project calls has no
 * {@code query()}/{@code allowedTools}/{@code agents} equivalent - that
 * whole mechanism is Agent SDK-internal. So instead of reproducing that
 * literal shape, this class recreates the same *behavior* by hand: a "task"
 * {@link Tool} registered on the request stands in for the allowedTools
 * gate (arguably a stricter guarantee here - the Messages API can never
 * invoke a tool that isn't in the request's tool list, full stop), and the
 * subagent definitions ({@code options.agents}'s job) are what exercise
 * 1.3.2 adds next.</p>
 */
public class TaskToolCoordinatorAgent {

  static final String COORDINATOR_SYSTEM_PROMPT = """
      You are the coordinator agent in a hub-and-spoke multi-agent research \
      system. You are the central hub: you own task decomposition, subagent \
      selection, and result aggregation. You never perform research yourself \
      and you are not a subagent - your job is to orchestrate.

      You have access to a "task" tool that spawns a named subagent \
      (web_search or document_analysis) with a prompt you write yourself. Use \
      it whenever a subtask should be delegated rather than answered directly \
      - the subagent you spawn has no memory of this conversation, so every \
      prompt you send it must be complete and self-contained.""";

  public static void main(String[] args) {

    AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    Tool taskTool = getTaskTool();
    MessageCreateParams params = getParams(taskTool);

    System.out.println("Tools registered on the coordinator's request: "
        + params.tools().orElseThrow().size());
    System.out.println("- " + taskTool.name() + ": " + taskTool.description().orElse(""));
    System.out.println("Coordinator ready for model " + Config.modelMain()
        + " (" + client.getClass().getSimpleName() + "), no request sent yet.");
  }

  // Helper to build the coordinator's request carrying just the task tool.
  private static MessageCreateParams getParams(Tool taskTool) {
    return MessageCreateParams.builder()
        .model(Config.modelMain())
        .maxTokens(Config.maxTokens())
        .system(COORDINATOR_SYSTEM_PROMPT)
        .addTool(taskTool)
        .addUserMessage("What subagents can you spawn, and when would you use each one?")
        .build();
  }

  /**
   * The "task" tool definition - the mechanism by which the coordinator
   * explicitly decides to spawn a subagent, analogous to "Task" in an Agent
   * SDK's allowedTools. Its input schema names which subagent to spawn and
   * carries the complete, self-contained prompt the coordinator is handing
   * that subagent.
   *
   * @return the task tool definition
   */
  static Tool getTaskTool() {
    return Tool.builder()
        .name("task")
        .description("""
            Call this whenever a subtask should be delegated to a specialized \
            subagent rather than answered directly yourself - e.g. researching \
            a subtopic on the web, or analyzing a specific document - instead \
            of attempting that work yourself. Spawns the named subagent with the \
            prompt you provide and returns its findings. The subagent has no \
            memory of this conversation, so the prompt must be complete and \
            self-contained: state the overall goal, its specific assignment, \
            and any prior findings it needs.""")
        .inputSchema(Tool.InputSchema.builder()
            .properties(Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("subagent_type", JsonValue.from(Map.of(
                    "type", "string",
                    "enum", List.of("web_search", "document_analysis"),
                    "description", "Which subagent to spawn for this task.")))
                .putAdditionalProperty("prompt", JsonValue.from(Map.of(
                    "type", "string",
                    "description", "The complete, self-contained instructions for "
                        + "the subagent - the overall goal, its specific "
                        + "assignment, and any prior findings it needs.")))
                .build())
            .build())
        .build();
  }
}
