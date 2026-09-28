package dev.ccarf.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Every test works on fake .env files in a temporary directory - never on the
// real .env at the repository root.
class AnthropicClientsTest {

    private static final String FAKE_KEY = "fake-test-key-123";

    @TempDir
    Path tempDir;

    @Test
    void findsEnvFileInTheStartDirectory() throws IOException {
        Path envFile = Files.writeString(tempDir.resolve(".env"), "ANTHROPIC_API_KEY=" + FAKE_KEY);

        assertEquals(envFile, AnthropicClients.findEnvFile(tempDir));
    }

    @Test
    void findsEnvFileAtTheProjectRootWhenStartingInAModuleDirectory() throws IOException {
        Path projectRoot = Files.createDirectory(tempDir.resolve("project"));
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        Path envFile = Files.writeString(projectRoot.resolve(".env"), "ANTHROPIC_API_KEY=" + FAKE_KEY);
        Path module = Files.createDirectory(projectRoot.resolve("module"));
        Files.writeString(module.resolve("pom.xml"), "<project/>");

        assertEquals(envFile, AnthropicClients.findEnvFile(module));
    }

    @Test
    void neverSearchesAboveTheProjectRoot() throws IOException {
        // A .env outside the project (no pom.xml in its directory) must be ignored.
        Files.writeString(tempDir.resolve(".env"), "ANTHROPIC_API_KEY=" + FAKE_KEY);
        Path projectRoot = Files.createDirectory(tempDir.resolve("project"));
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");

        assertThrows(IllegalStateException.class, () -> AnthropicClients.findEnvFile(projectRoot));
    }

    @Test
    void throwsWhenNoEnvFileExists() {
        assertThrows(IllegalStateException.class, () -> AnthropicClients.findEnvFile(tempDir));
    }

    @Test
    void readsAPlainKeyValueLine() throws IOException {
        Path envFile = Files.writeString(tempDir.resolve(".env"), "ANTHROPIC_API_KEY=" + FAKE_KEY);

        assertEquals(FAKE_KEY, AnthropicClients.readValue(envFile, "ANTHROPIC_API_KEY"));
    }

    @Test
    void readsQuotedAndExportedValuesAndSkipsCommentsAndOtherKeys() throws IOException {
        Path envFile = Files.writeString(tempDir.resolve(".env"), """
                # secrets for ccar-f
                OTHER_KEY=something-else

                export ANTHROPIC_API_KEY="%s"
                """.formatted(FAKE_KEY));

        assertEquals(FAKE_KEY, AnthropicClients.readValue(envFile, "ANTHROPIC_API_KEY"));
    }

    @Test
    void readsSingleQuotedValues() throws IOException {
        Path envFile = Files.writeString(tempDir.resolve(".env"), "ANTHROPIC_API_KEY='" + FAKE_KEY + "'");

        assertEquals(FAKE_KEY, AnthropicClients.readValue(envFile, "ANTHROPIC_API_KEY"));
    }

    @Test
    void throwsWithoutRevealingFileContentsWhenTheKeyIsMissing() throws IOException {
        Path envFile = Files.writeString(tempDir.resolve(".env"), "OTHER_SECRET=do-not-leak-me");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> AnthropicClients.readValue(envFile, "ANTHROPIC_API_KEY"));
        assertFalse(exception.getMessage().contains("do-not-leak-me"));
    }

    @Test
    void throwsWhenTheKeyIsEmpty() throws IOException {
        Path envFile = Files.writeString(tempDir.resolve(".env"), "ANTHROPIC_API_KEY=");

        assertThrows(IllegalStateException.class,
                () -> AnthropicClients.readValue(envFile, "ANTHROPIC_API_KEY"));
    }

    @Test
    void buildsAClientFromAnEnvFileWithoutCallingTheApi() throws IOException {
        Files.writeString(tempDir.resolve(".env"), "ANTHROPIC_API_KEY=" + FAKE_KEY);

        assertNotNull(AnthropicClients.fromDotEnv(tempDir));
    }
}
