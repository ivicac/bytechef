/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.ai.copilot.config;

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
 * The EE-embedded counterpart of {@code GuardrailsAdvisorCoverageTest} in CE's {@code ai-copilot-service} module and in
 * the EE {@code automation-ai-copilot} module (see either class's own javadoc for the CE history of this gap: a first
 * fix covered only AI-Hub-delegation {@code ChatClient} beans, a second covered only {@code CopilotConfiguration}'s
 * {@code *SpringAIAgent} beans, and a third covered only the remaining eight CE {@code *Configuration} classes — each
 * fix measured coverage of the file it touched, never of the module). Neither CE test, nor the automation one, has any
 * visibility into this module's own {@link EmbeddedCopilotConfiguration}, so a fifth gap survived undetected here until
 * ticket 732: 6 {@code *SpringAIAgent.builder()} call sites plus 1 {@code ChatClient.builder(} one-shot subagent site —
 * 7 in total, not the 6 first assumed when this gap was scoped.
 *
 * <p>
 * This test scans every {@code *Configuration.java} source file under this module's {@code src/main/java}, not one
 * named file, so a new configuration class is covered automatically instead of needing this test updated first. It pins
 * two invariants:
 * </p>
 * <ol>
 * <li>Every {@code [A-Za-z]+SpringAIAgent.builder()} call site's fluent chain (extracted up to that call's own
 * {@code .build()}) calls {@code guardrailsAdvisors(} — mirrors CE's {@code GuardrailsAdvisorCoverageTest} exactly.
 * Asserting the weaker {@code .advisors(} would be close to vacuous: most sites attach some other advisor and would
 * satisfy it while carrying no guardrails at all.</li>
 * <li>Every {@code ChatClient.builder(} call site in the module sits inside that same file's own private
 * {@code chatClientBuilder(ChatModel)} wrapper method — the local equivalent of CE {@code CopilotConfiguration}'s
 * {@code chatClientBuilder} helper (see {@link EmbeddedCopilotConfiguration#chatClientBuilder}) — and that wrapper's
 * own body calls {@code guardrailsAdvisors(}. A bean method that calls {@code ChatClient.builder(} directly, bypassing
 * the wrapper, is exactly how this module's one subagent {@code ChatClient} site went unguarded in the first place.
 * CE's own coverage test does not check this invariant (it checks {@code *SpringAIAgent.builder()} chains only), so
 * this second check is this module's own addition, not a copy.</li>
 * </ol>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class GuardrailsAdvisorCoverageTest {

    private static final Path MAIN_SOURCE_ROOT = Path.of("src/main/java");

    private static final Pattern COMMENT_PATTERN = Pattern.compile("/\\*.*?\\*/|//[^\\r\\n]*", Pattern.DOTALL);

    private static final Pattern AGENT_BUILDER_CALL_PATTERN = Pattern.compile("[A-Za-z]+SpringAIAgent\\.builder\\(\\)");

    private static final Pattern CHAT_CLIENT_BUILDER_CALL_PATTERN = Pattern.compile("ChatClient\\.builder\\(");

    private static final Pattern CHAT_CLIENT_BUILDER_WRAPPER_PATTERN =
        Pattern.compile("ChatClient\\.Builder\\s+chatClientBuilder\\(ChatModel\\s+\\w+\\)\\s*\\{");

    private static final String BUILD_CALL = ".build()";

    private static final String GUARDRAILS_ADVISORS_CALL = "guardrailsAdvisors(";

    @Test
    void testEverySpringAIAgentBuilderChainInEveryConfigurationClassAttachesGuardrails() throws IOException {
        assertTrue(
            Files.isDirectory(MAIN_SOURCE_ROOT),
            "Source root not found, working directory is wrong: " + MAIN_SOURCE_ROOT);

        List<Path> configurationSourceFiles = findConfigurationSourceFiles();

        assertFalse(
            configurationSourceFiles.isEmpty(),
            "Found no *Configuration.java files under " + MAIN_SOURCE_ROOT + " - the scan itself is broken, "
                + "this is not a claim that the module has no configuration classes.");

        List<String> uncoveredSites = new ArrayList<>();

        int totalBuilderSiteCount = 0;

        for (Path sourceFile : configurationSourceFiles) {
            String sourceWithoutComments = withoutComments(sourceFile);

            Matcher matcher = AGENT_BUILDER_CALL_PATTERN.matcher(sourceWithoutComments);

            int siteOrdinal = 0;

            while (matcher.find()) {
                siteOrdinal++;
                totalBuilderSiteCount++;

                String chain = extractChain(sourceWithoutComments, matcher.start(), '(', ')', BUILD_CALL);

                if (chain == null) {
                    uncoveredSites.add(
                        sourceFile + ": call site #" + siteOrdinal + " (" + matcher.group()
                            + ") never reaches a top-level .build() - the chain-extraction scan itself may need "
                            + "updating for new syntax, or this is a genuinely unterminated builder chain.");
                } else if (!chain.contains(GUARDRAILS_ADVISORS_CALL)) {
                    uncoveredSites.add(
                        sourceFile + ": call site #" + siteOrdinal + " (" + matcher.group()
                            + ") builds a LocalAgent bean but its chain never calls guardrailsAdvisors() before "
                            + ".build() - "
                            + "add .advisors(copilotGuardrailsAdvisorFactory.guardrailsAdvisors()) (inject "
                            + "CopilotGuardrailsAdvisorFactory as a bean-method or constructor parameter) additively, "
                            + "alongside any other advisor the bean already attaches, never replacing it.");
                }
            }
        }

        assertTrue(
            totalBuilderSiteCount > 0,
            "Found no *SpringAIAgent.builder() call sites in any *Configuration.java file under " + MAIN_SOURCE_ROOT
                + " - the pattern itself is broken, this is not a claim that the module has no such beans.");

        if (!uncoveredSites.isEmpty()) {
            fail(
                "Found " + uncoveredSites.size() + " of " + totalBuilderSiteCount + " *SpringAIAgent.builder() call "
                    + "site(s) across the module without guardrails coverage:\n"
                    + String.join("\n", uncoveredSites));
        }
    }

    @Test
    void testEveryChatClientBuilderCallSiteRoutesThroughTheModulesGuardedWrapper() throws IOException {
        List<Path> configurationSourceFiles = findConfigurationSourceFiles();

        List<String> violations = new ArrayList<>();

        for (Path sourceFile : configurationSourceFiles) {
            String sourceWithoutComments = withoutComments(sourceFile);

            Matcher wrapperMatcher = CHAT_CLIENT_BUILDER_WRAPPER_PATTERN.matcher(sourceWithoutComments);

            boolean hasWrapper = wrapperMatcher.find();
            int allowedDirectCallCount = 0;

            if (hasWrapper) {
                String wrapperBody = extractChain(
                    sourceWithoutComments, wrapperMatcher.end() - 1, '{', '}', null);

                if (wrapperBody == null) {
                    violations.add(
                        sourceFile + ": chatClientBuilder(ChatModel) wrapper's body never reaches a closing brace - "
                            + "the brace-depth scan itself may need updating for new syntax.");
                } else if (!wrapperBody.contains(GUARDRAILS_ADVISORS_CALL)) {
                    violations.add(
                        sourceFile + ": chatClientBuilder(ChatModel) wrapper never calls guardrailsAdvisors() in its "
                            + "own body - every one-shot subagent ChatClient bean in this file routes through this "
                            + "wrapper for guardrails coverage, so a broken wrapper leaves every one of them "
                            + "unguarded.");
                }

                allowedDirectCallCount = 1;
            }

            Matcher directCallMatcher = CHAT_CLIENT_BUILDER_CALL_PATTERN.matcher(sourceWithoutComments);

            int directCallCount = 0;

            while (directCallMatcher.find()) {
                directCallCount++;
            }

            if (directCallCount > allowedDirectCallCount) {
                violations.add(
                    sourceFile + ": found " + directCallCount + " ChatClient.builder( call site(s) but only "
                        + allowedDirectCallCount + " explained by this file's own chatClientBuilder(ChatModel) "
                        + "wrapper - a bean method is calling ChatClient.builder(...) directly instead of "
                        + "chatClientBuilder(chatModel), bypassing guardrails the same way this module's original "
                        + "subagent ChatClient site went unguarded.");
            }
        }

        if (!violations.isEmpty()) {
            fail(
                "Found " + violations.size() + " ChatClient.builder( coverage violation(s) across the module:\n"
                    + String.join("\n", violations));
        }
    }

    /**
     * Recursively lists every {@code *Configuration.java} file under {@link #MAIN_SOURCE_ROOT}, so a configuration
     * class added in a new package - not just {@link EmbeddedCopilotConfiguration} - is picked up without this test
     * needing an update.
     */
    private static List<Path> findConfigurationSourceFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(MAIN_SOURCE_ROOT)) {
            return paths.filter(Files::isRegularFile)
                .filter(path -> path.toString()
                    .endsWith("Configuration.java"))
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        }
    }

    private static String withoutComments(Path sourceFile) throws IOException {
        return COMMENT_PATTERN.matcher(Files.readString(sourceFile))
            .replaceAll("");
    }

    /**
     * Starting at {@code chainStart}, scans forward tracking {@code openChar}/{@code closeChar} depth and returns the
     * substring up to and including the first point where depth returns to zero AND (when {@code terminator} is
     * non-null) the text from {@code chainStart} up to that point ends with {@code terminator} - e.g. a top-level
     * {@code .build()} for a fluent builder chain tracked with {@code '('}/{@code ')'}. When {@code terminator} is
     * {@code null}, the scan instead returns as soon as depth returns to zero (used to extract a method body tracked
     * with {@code '{'}/{@code '}'}, where the terminating {@code '}'} itself is the signal). Returns {@code null} if
     * depth never returns to zero (with the terminator condition satisfied, when one is given) before the source ends.
     */
    private static String extractChain(
        String source, int chainStart, char openChar, char closeChar, String terminator) {

        int depth = 0;
        int position = chainStart;

        while (position < source.length()) {
            char character = source.charAt(position);

            if (character == openChar) {
                depth++;
            } else if (character == closeChar) {
                depth--;

                if (depth == 0) {
                    int endExclusive = position + 1;

                    if (terminator == null) {
                        return source.substring(chainStart, endExclusive);
                    }

                    if (source.startsWith(terminator, endExclusive - terminator.length())) {
                        return source.substring(chainStart, endExclusive);
                    }
                }
            }

            position++;
        }

        return null;
    }
}
