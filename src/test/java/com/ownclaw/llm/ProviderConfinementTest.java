package com.ownclaw.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The gateway is the only door, pinned in the source tree.
 * <p>
 * Every other guarantee of Phase 3 rests on there being exactly one code path to a cloud
 * provider. This test is what catches the fourth call site next month: it walks
 * {@code src/main/java}, strips comments, and asserts that the two providers are constructed
 * and named nowhere but in themselves and the gateway, that neither is a bean, and that the two
 * API hosts appear nowhere else.
 */
class ProviderConfinementTest {

    private static Path sourceRoot() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++) {
            if (Files.isDirectory(p.resolve("src/main/java"))) return p.resolve("src/main/java");
            p = p.getParent();
        }
        throw new IllegalStateException("src/main/java not found above " + Path.of("").toAbsolutePath());
    }

    /**
     * Source with comments removed, so a javadoc mention does not count — and string literals
     * kept, which the first version destroyed: stripping from {@code //} to end of line also
     * erased {@code "https://api.anthropic.com/v1/messages"}, so the host assertion passed on
     * every file including the providers' own. A one-pass scanner that knows it is inside a
     * string is the only honest way to do this.
     */
    private static String code(Path file) throws IOException {
        String s = Files.readString(file);
        var out = new StringBuilder(s.length());
        boolean inString = false, inChar = false, inLine = false, inBlock = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char next = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
            if (inLine) { if (c == '\n') { inLine = false; out.append(c); } continue; }
            if (inBlock) { if (c == '*' && next == '/') { inBlock = false; i++; } continue; }
            if (inString) {
                out.append(c);
                if (c == '\\') { if (i + 1 < s.length()) out.append(s.charAt(++i)); }
                else if (c == '"') inString = false;
                continue;
            }
            if (inChar) {
                if (c == '\\') i++;
                else if (c == '\'') inChar = false;
                continue;
            }
            if (c == '/' && next == '/') { inLine = true; i++; continue; }
            if (c == '/' && next == '*') { inBlock = true; i++; continue; }
            if (c == '"') { inString = true; out.append(c); continue; }
            if (c == '\'') { inChar = true; continue; }
            out.append(c);
        }
        return out.toString();
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> w = Files.walk(sourceRoot())) {
            return w.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    @Test
    @DisplayName("the API hosts occur only in their own provider")
    void hostsAreConfined() throws IOException {
        for (Path f : javaFiles()) {
            String name = f.getFileName().toString();
            String c = code(f);
            if (c.contains("api.anthropic.com")) assertEquals("AnthropicProvider.java", name, f.toString());
            if (c.contains("api.openai.com")) assertEquals("OpenAiProvider.java", name, f.toString());
        }
    }

    @Test
    @DisplayName("the provider classes are named only by themselves and the gateway")
    void providersAreConstructedOnlyByTheGateway() throws IOException {
        for (Path f : javaFiles()) {
            String name = f.getFileName().toString();
            if (name.equals("AnthropicProvider.java") || name.equals("OpenAiProvider.java")
                    || name.equals("CloudGateway.java")) continue;
            String c = code(f);
            assertFalse(c.contains("AnthropicProvider"), f + " names AnthropicProvider");
            assertFalse(c.contains("OpenAiProvider"), f + " names OpenAiProvider");
        }
    }

    @Test
    @DisplayName("neither provider is a bean, public, or publicly constructible")
    void providersAreNotBeans() throws IOException {
        for (String name : List.of("AnthropicProvider", "OpenAiProvider")) {
            Path f = sourceRoot().resolve("com/ownclaw/llm/" + name + ".java");
            String c = code(f);
            assertFalse(c.contains("@Component"), name + " is a bean");
            assertFalse(c.contains("public class " + name), name + " is public");
            assertFalse(c.contains("public " + name + "("), name + " has a public constructor");
        }
    }
}
