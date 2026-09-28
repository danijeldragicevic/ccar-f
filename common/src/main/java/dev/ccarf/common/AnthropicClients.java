package dev.ccarf.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;

/**
 * Builds the Anthropic client every exercise uses, with the API key read from
 * the .env file at the repository root.
 *
 * <p>The key deliberately lives in that gitignored file rather than in an
 * exported ANTHROPIC_API_KEY variable: an exported variable is visible to
 * every process started from that shell - including AI coding assistants,
 * which may pick it up and bill against it. Read here, the key only ever
 * exists inside the exercise's own JVM.</p>
 *
 * <p>Never log, print, or return the key's value - error messages below name
 * the file and the missing entry, never its contents.</p>
 */
public final class AnthropicClients {

    static final String ENV_FILE = ".env";
    static final String API_KEY = "ANTHROPIC_API_KEY";

    private AnthropicClients() {}

    // Builds a client using the ANTHROPIC_API_KEY from the .env file found from
    // the current working directory (see findEnvFile).
    public static AnthropicClient fromDotEnv() {
        return fromDotEnv(Path.of(System.getProperty("user.dir")));
    }

    // Same as fromDotEnv(), but searching for the .env file from the given directory.
    static AnthropicClient fromDotEnv(Path startDirectory) {
        Path envFile = findEnvFile(startDirectory);
        String apiKey = readValue(envFile, API_KEY);
        return AnthropicOkHttpClient.builder()
                .fromEnv()
                .apiKey(apiKey)
                .build();
    }

    // Helper methods

    // Looks for the .env file in startDirectory, then in each parent directory
    // that is still part of this Maven project (i.e. contains a pom.xml). That
    // way it works whether the working directory is the repository root (IDE
    // "run" button) or a module directory (mvn exec:java), without ever
    // picking up an unrelated .env from outside the project.
    static Path findEnvFile(Path startDirectory) {
        Path directory = startDirectory.toAbsolutePath().normalize();
        while (directory != null) {
            Path candidate = directory.resolve(ENV_FILE);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            Path parent = directory.getParent();
            if (parent == null || !Files.isRegularFile(parent.resolve("pom.xml"))) {
                break;
            }
            directory = parent;
        }
        throw new IllegalStateException("No " + ENV_FILE + " file found in " + startDirectory
                + " or its parent project directories. Create one at the repository root"
                + " containing a line " + API_KEY + "=<your key> (see README -> Prerequisites).");
    }

    // Returns the value for key from a .env file. Supports KEY=value lines,
    // optional surrounding quotes, an optional "export " prefix, blank lines
    // and # comments.
    static String readValue(Path envFile, String key) {
        List<String> lines;
        try {
            lines = Files.readAllLines(envFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + envFile, e);
        }

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).trim();
            }
            int equals = line.indexOf('=');
            if (equals <= 0 || !line.substring(0, equals).trim().equals(key)) {
                continue;
            }
            String value = stripQuotes(line.substring(equals + 1).trim());
            if (!value.isEmpty()) {
                return value;
            }
        }
        throw new IllegalStateException(envFile + " has no non-empty " + key + " entry."
                + " Add a line " + key + "=<your key> (see README -> Prerequisites).");
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
