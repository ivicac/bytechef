/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.copilot.advisor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The three {@code GuardrailsAdvisorCoverageTest}s that already existed - in {@code ai-copilot-service} (CE),
 * {@code automation-ai-copilot} and {@code embedded-ai-copilot} - all scan {@code *Configuration.java} files for
 * builder idioms ({@code *SpringAIAgent.builder()}, {@code ChatClient.builder(}). This module had no coverage test at
 * all, and its two model callers are neither: {@code PropertyCopilotGeneratorImpl} and
 * {@code WorkflowDescriptionCopilotGeneratorImpl} are {@code *Impl.java} {@code @Service} classes that called
 * {@code chatModel.call(new Prompt(...))} directly. They were invisible to those scans twice over - wrong file-name
 * filter, wrong idiom - and so shipped as a fifth, unguarded way for Copilot to reach a model provider, after four
 * earlier rounds of fixing exactly this class of gap.
 *
 * <p>
 * A direct {@link org.springframework.ai.chat.model.ChatModel} call cannot be guarded at all:
 * {@code AiGuardrailsAdvisor} is a {@link org.springframework.ai.chat.client.ChatClient} advisor, so there is no hook
 * at the {@code ChatModel} layer for it to attach to. That is what makes this a structural invariant worth pinning in
 * source rather than a bug worth fixing once - the property generator's prompt embeds {@code WorkflowNodeOutputFacade}
 * sample output, which is data captured from real test runs against real connections.
 * </p>
 *
 * <p>
 * This test therefore scans every {@code .java} file under this module's {@code src/main/java} - not only
 * {@code *Configuration.java} - and pins two invariants:
 * </p>
 * <ol>
 * <li>No source file invokes a {@code ChatModel} directly. Resolver classes that <em>return</em> a {@code ChatModel} or
 * {@code ChatClient} for a caller to guard ({@code CatalogSubAgentChatModelResolver},
 * {@code CopilotChatClientResolver}) are unaffected: they hand the value out, they never call it.</li>
 * <li>Every {@code ChatClient} invocation statement - one carrying both {@code .prompt(} and {@code .call()} - also
 * carries {@code guardedChatClient(}, so the client being invoked came from {@code CopilotGuardrailsAdvisorFactory}.
 * This covers the catalog-override branch too, where the client arrives already built from
 * {@code CatalogChatClientResolver} rather than from a builder this module controls.</li>
 * </ol>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class GuardrailsAdvisorCoverageTest {

    private static final Path MAIN_SOURCE_ROOT = Path.of("src/main/java");

    private static final Pattern COMMENT_PATTERN = Pattern.compile("/\\*.*?\\*/|//[^\\r\\n]*", Pattern.DOTALL);

    /**
     * Matches a direct model invocation on any identifier whose name ends in {@code ChatModel} or is exactly
     * {@code chatModel} - the naming convention every {@code ChatModel} field and local in this module follows.
     */
    private static final Pattern DIRECT_CHAT_MODEL_CALL_PATTERN =
        Pattern.compile("\\b\\w*[cC]hatModel\\.(?:call|stream)\\(");

    private static final String PROMPT_CALL = ".prompt(";

    private static final String TERMINAL_CALL = ".call()";

    private static final String GUARDED_CHAT_CLIENT_CALL = "guardedChatClient(";

    @Test
    void testNoSourceFileInvokesAChatModelDirectly() throws IOException {
        List<Path> sourceFiles = findMainSourceFiles();

        List<String> violations = new ArrayList<>();

        for (Path sourceFile : sourceFiles) {
            String sourceWithoutComments = withoutComments(sourceFile);

            Matcher matcher = DIRECT_CHAT_MODEL_CALL_PATTERN.matcher(sourceWithoutComments);

            while (matcher.find()) {
                violations.add(
                    sourceFile + ": invokes a ChatModel directly (" + matcher.group() + "...). AiGuardrailsAdvisor is "
                        + "a ChatClient advisor, so nothing can guard a ChatModel call - route it through "
                        + "copilotGuardrailsAdvisorFactory.guardedChatClient(chatModel).prompt(...).call() instead.");
            }
        }

        if (!violations.isEmpty()) {
            fail(
                "Found " + violations.size() + " direct ChatModel invocation(s) in this module:\n"
                    + String.join("\n", violations));
        }
    }

    @Test
    void testEveryChatClientInvocationUsesAGuardedClient() throws IOException {
        List<Path> sourceFiles = findMainSourceFiles();

        assertFalse(
            sourceFiles.isEmpty(),
            "Found no .java files under " + MAIN_SOURCE_ROOT + " - the scan itself is broken, this is not a claim "
                + "that the module has no sources.");

        List<String> violations = new ArrayList<>();

        int totalInvocationCount = 0;

        for (Path sourceFile : sourceFiles) {
            String sourceWithoutComments = withoutComments(sourceFile);

            for (String statement : sourceWithoutComments.split(";")) {
                if (!statement.contains(PROMPT_CALL) || !statement.contains(TERMINAL_CALL)) {
                    continue;
                }

                totalInvocationCount++;

                if (!statement.contains(GUARDED_CHAT_CLIENT_CALL)) {
                    violations.add(
                        sourceFile + ": a ChatClient invocation statement (one carrying both " + PROMPT_CALL + " and "
                            + TERMINAL_CALL + ") does not carry " + GUARDED_CHAT_CLIENT_CALL + " - wrap the client in "
                            + "copilotGuardrailsAdvisorFactory.guardedChatClient(...) so the guardrails and PII "
                            + "tool-boundary advisors are attached before the prompt leaves the process.");
                }
            }
        }

        assertTrue(
            totalInvocationCount > 0,
            "Found no ChatClient invocation statements under " + MAIN_SOURCE_ROOT + " - the statement-splitting scan "
                + "is broken, this is not a claim that the module never calls a model.");

        if (!violations.isEmpty()) {
            fail(
                "Found " + violations.size() + " of " + totalInvocationCount + " ChatClient invocation(s) using an "
                    + "unguarded client:\n" + String.join("\n", violations));
        }
    }

    private static List<Path> findMainSourceFiles() throws IOException {
        assertTrue(
            Files.isDirectory(MAIN_SOURCE_ROOT),
            "Source root not found, working directory is wrong: " + MAIN_SOURCE_ROOT);

        try (Stream<Path> paths = Files.walk(MAIN_SOURCE_ROOT)) {
            return paths.filter(Files::isRegularFile)
                .filter(path -> path.toString()
                    .endsWith(".java"))
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        }
    }

    private static String withoutComments(Path sourceFile) throws IOException {
        return COMMENT_PATTERN.matcher(Files.readString(sourceFile))
            .replaceAll("");
    }
}
