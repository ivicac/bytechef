/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.ai.copilot.config;

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
 * The weakness of an opt-in guardrails advisor is that a new {@code LocalAgent}-producing bean can simply skip
 * attaching it, and nothing complains - that is exactly how {@code AiGuardrailsAdvisor} ended up wired into zero of
 * Copilot's chat surfaces despite its own javadoc claiming otherwise, then, after a first fix covered only
 * AI-Hub-delegation {@code ChatClient} beans, into zero of {@code CopilotConfiguration}'s 17 {@code *SpringAIAgent}
 * beans (the interactive chat UI, the surface real users actually type into) and, after a second fix covered only that
 * one file, into 16 more {@code *SpringAIAgent} beans spread across the other eight {@code *Configuration} classes in
 * this module (verified against the current source: {@code CopilotConfiguration.java} carries 17 sites, the other eight
 * files two each). Each fix measured coverage of the file it touched, never of the module - a predecessor of this test,
 * {@code CopilotConfigurationGuardrailsAdvisorTest}, pinned literal call-site counts inside
 * {@code CopilotConfiguration.java} alone and so could not see, and did not catch, the third gap.
 *
 * <p>
 * This test scans every {@code *Configuration.java} source file under this module's {@code src/main/java}, not one
 * named file, so a tenth configuration class - or an eleventh - is covered automatically instead of needing this test
 * updated first. It pins two invariants:
 * </p>
 * <ol>
 * <li>Every {@code [A-Za-z]+SpringAIAgent.builder()} call site's fluent chain (extracted up to that call's own
 * {@code .build()}) calls {@code guardrailsAdvisors(}. Asserting the weaker {@code .advisors(} would be close to
 * vacuous: most sites attach some other advisor - chat memory, a vector store - and would satisfy it while carrying no
 * guardrails at all. every {@code CopilotSpringAIAgent} subclass implements the vendored spring-ai-ag-ui
 * {@code LocalAgent}, and {@code CopilotChatFacadeImpl.localAgentMap} collects every {@code LocalAgent} bean in the
 * context regardless of which configuration declared it - that collection, not any one file, is the real coverage
 * boundary.</li>
 * <li>Every {@code ChatClient.builder(} call site in the module sits inside {@link CopilotConfiguration}'s own private
 * {@code chatClientBuilder(ChatModel)} wrapper method, and that wrapper's own body calls {@code guardrailsAdvisors(}.
 * Verified against the current source: this module has exactly one such wrapper, in
 * {@link CopilotConfiguration#chatClientBuilder}, and today's only {@code ChatClient.builder(} call site in the whole
 * module is the one inside that wrapper itself - so this invariant currently holds with zero slack. Nothing before this
 * second check stopped a new bean method, in {@code CopilotConfiguration} or any of the other eight
 * {@code *Configuration} classes, from calling {@code ChatClient.builder(chatModel)} directly and bypassing the wrapper
 * - undetected, since the first invariant only looks at {@code *SpringAIAgent.builder()} call sites. This mirrors the
 * second check the EE counterparts of this test already carry ({@code GuardrailsAdvisorCoverageTest} in
 * {@code automation-ai-copilot} and {@code embedded-ai-copilot}), added there after exactly that bypass left 4 subagent
 * {@code ChatClient} sites unguarded in those modules; this module has not yet had that incident, so this check is
 * preventive here rather than a fix for a discovered gap.</li>
 * </ol>
 *
 * <p>
 * A context-level test - registering a real {@code ApplicationContext} and asserting every {@code LocalAgent} bean
 * carries the advisor - would be stronger, since it observes runtime state instead of source text. It is not used here:
 * the vendored {@code SpringAIAgent} (see {@code spring-ai/spring-ag-ui/integrations/spring-ai/src/main/java/
 * com/agui/spring/ai/SpringAIAgent.java}) builds its wrapped {@link org.springframework.ai.chat.client.ChatClient} in
 * its constructor from the {@code ChatModel} alone and stores the resolved {@code advisors} list in a private field
 * with no getter - reachable only via reflection - and applies it per-request rather than at construction. Worse, this
 * CE module has no {@code AiGuardrailsAdvisorProvider} bean on its test classpath, so
 * {@code CopilotGuardrailsAdvisorFactory#guardrailsAdvisors()} always resolves to an empty list here regardless of
 * whether {@code guardrailsAdvisors()} was called at all - an empty-list observation could not distinguish "attached,
 * but nothing to attach" from "never attached". Making that distinction observable would mean standing up a full Spring
 * context for all nine {@code *Configuration} classes (each with its own long list of collaborator beans - facades,
 * services, tool factories, prompt resources) purely to mock a fake guardrails provider bean and reflect into a private
 * field - substantially more machinery, and more fragile to unrelated refactors, than reading the source. A source scan
 * directly encodes the actual rule ("every such builder chain calls {@code guardrailsAdvisors()}"), costs the module no
 * new test dependency or Spring context, and needs no change when a bean gains or loses unrelated constructor
 * parameters.
 * </p>
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
                            + "CopilotGuardrailsAdvisorFactory as a bean-method parameter) additively, alongside any "
                            + "other advisor the bean already attaches, never replacing it.");
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
                            + "own body - every ChatClient bean built through this wrapper routes through it for "
                            + "guardrails coverage, so a broken wrapper leaves every one of them unguarded.");
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
                        + "chatClientBuilder(chatModel), bypassing guardrails.");
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
     * class added in a new package - not just a new file next to the nine known today - is picked up without this test
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
