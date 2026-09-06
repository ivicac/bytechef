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

package com.bytechef.platform.ai.guardrails;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Guardrails attach as a Spring AI {@code ChatClient} advisor. A site that calls a model directly — {@code
 * chatModel.call(...)} or {@code .call(new Prompt(...))} — never passes through an advisor chain, so it is
 * <em>unguardable by construction</em>: no settings change, no policy and no future fix can bring it under guardrails
 * without first rewriting the call.
 *
 * <p>
 * That is not hypothetical. Two EE generators shipped exactly this shape and were found only by looking for it, not by
 * any test failing. This scan makes each such site a deliberate, named choice: a new one fails here until someone adds
 * it below with a reason, and writing that reason is the moment to ask whether it should be a {@code ChatClient}
 * instead.
 * </p>
 *
 * <p>
 * Three design choices, each paid for by a sibling scan on this branch that got them wrong:
 * </p>
 * <ul>
 * <li><b>An explicit allowlist, not a heuristic.</b> {@code ToolContextWorkspaceVerificationTest}'s rule was
 * file-granular and a reviewer bypassed it in a single edit. Membership of a set can only be extended deliberately, in
 * the commit that needs it.</li>
 * <li><b>No hand-maintained list of call shapes.</b> That scan matched only {@code put(}/{@code putIfNotNull(} and
 * missed a live vulnerability written through a local {@code putLong(} helper. Here the pattern matches the model call
 * itself, which cannot be renamed without changing Spring AI.</li>
 * <li><b>A cheap pre-filter and an explicit timeout.</b> That scan stripped every production source twice with a
 * {@code DOTALL} regex and overran the 30-second per-method default inherited from {@code test-support}, failing
 * intermittently — the worst failure mode for a tripwire.</li>
 * </ul>
 *
 * <p>
 * <b>One caveat, measured rather than assumed.</b> This test's real input is every {@code src/main/java} file under
 * {@code server/}, which Gradle has no way to know — its up-to-date check sees only this module. Writing an unguarded
 * caller in another module and running {@code ./gradlew test} therefore reports green: the task is skipped as
 * up-to-date. CI builds from a clean checkout, so the tripwire fires there; locally it fires only after
 * {@code --rerun-tasks} or a change in this module. Declaring the whole tree as an input would rebuild this module on
 * every server-side edit, which costs more than the staleness does.
 * </p>
 *
 * @author Ivica Cardic
 */
class GuardrailSurfaceCoverageTest {

    private static final String SETTINGS_FILE_NAME = "settings.gradle.kts";

    private static final Pattern COMMENT_PATTERN = Pattern.compile("/\\*.*?\\*/|//[^\\r\\n]*", Pattern.DOTALL);

    private static final Pattern DIRECT_MODEL_CALL_PATTERN =
        Pattern.compile("\\w*[Cc]hatModel\\s*\\.\\s*call\\s*\\(|\\.\\s*call\\s*\\(\\s*new\\s+Prompt\\s*\\(");

    private static final String DIRECT_MODEL_CALL_HINT = ".call(";

    /**
     * Classes that reach a model without an advisor chain, and why each is allowed to.
     *
     * <p>
     * The two classifiers are the load-bearing entries: they <em>are</em> the guardrails. Routing them through an
     * advisor chain would have the moderation check moderate itself, and the injection check screen its own screening
     * prompt — unbounded recursion, not merely wasteful.
     * </p>
     *
     * <p>
     * This list stood beside a second one for a while: three sites that reached a model with no guardrails at all,
     * tracked as debt so the scan could go green while they stayed countable. All three are now fixed and that list is
     * gone — {@code TitleGenerationService} on surface {@code ai_hub}, {@code ApiConnectorAiServiceImpl} on
     * {@code api_connector}, {@code AiEvalExecutor} on {@code ai_eval}. A future direct caller therefore has exactly
     * one place to go: here, with the reason it must bypass guardrails written next to it. There is no longer a list
     * that means "unguarded, and we know".
     * </p>
     */
    private static final Set<String> EXEMPT_DIRECT_MODEL_CALLERS = Set.of(
        "PromptBasedModerationClassifier",
        "PromptBasedInjectionClassifier",
        "AiGatewayFacadeImpl");

    /**
     * The inherited 30-second per-method default is a unit-test budget. This walks every production source under
     * {@code server/}, so it carries its own limit, deliberately far above the observed runtime, since ordinary
     * repository growth must not be able to turn a tripwire intermittently red.
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void testEveryDirectModelCallerIsAKnownExemption() throws IOException {
        Path serverRoot = findRepositoryRoot().resolve("server");

        assertTrue(Files.isDirectory(serverRoot), "server/ directory not found under " + serverRoot);

        List<Path> mainSourceFiles = findMainSourceFiles(serverRoot);

        assertFalse(
            mainSourceFiles.isEmpty(),
            "Found no *.java files under any src/main/java beneath " + serverRoot
                + " - the file walk itself is broken, this is not a claim that server/ has no production sources.");

        List<String> unexpected = new ArrayList<>();

        for (Path sourceFile : mainSourceFiles) {
            String source = Files.readString(sourceFile);

            if (!source.contains(DIRECT_MODEL_CALL_HINT)) {
                continue;
            }

            String sourceWithoutComments = COMMENT_PATTERN.matcher(source)
                .replaceAll("");

            if (!DIRECT_MODEL_CALL_PATTERN.matcher(sourceWithoutComments)
                .find()) {

                continue;
            }

            if (!EXEMPT_DIRECT_MODEL_CALLERS.contains(simpleClassName(sourceFile))) {
                unexpected.add(sourceFile.toString());
            }
        }

        if (!unexpected.isEmpty()) {
            fail(
                "Found " + unexpected.size() + " production class(es) calling a model directly rather than through a "
                    + "ChatClient. Guardrails attach as a ChatClient advisor, so such a call cannot be brought under "
                    + "guardrails by any settings change - it is unguardable until the call itself is rewritten. Use "
                    + "a ChatClient so the advisor chain applies, or add the class to "
                    + "EXEMPT_DIRECT_MODEL_CALLERS with the reason it must bypass guardrails:\n"
                    + String.join("\n", unexpected));
        }
    }

    @Test
    void testEverySurfaceNameIsRegistered() {
        assertTrue(
            GuardrailSurface.ALL.contains(GuardrailSurface.AI_AGENT)
                && GuardrailSurface.ALL.contains(GuardrailSurface.AI_EVAL)
                && GuardrailSurface.ALL.contains(GuardrailSurface.AI_HUB)
                && GuardrailSurface.ALL.contains(GuardrailSurface.API_CONNECTOR)
                && GuardrailSurface.ALL.contains(GuardrailSurface.COPILOT)
                && GuardrailSurface.ALL.contains(GuardrailSurface.MCP_AUTOMATION)
                && GuardrailSurface.ALL.contains(GuardrailSurface.MCP_EMBEDDED),
            "GuardrailSurface.ALL must list every declared surface; a surface missing from it resolves its own "
                + "policy and reports its own metrics under a name nothing else uses.");

        assertTrue(
            GuardrailSurface.ALL.size() == 7,
            "A surface was added or removed without updating this test. Adding one is a real change: it introduces a "
                + "place where workspace data reaches a model, so the addition should be reviewed rather than "
                + "absorbed. Found: " + GuardrailSurface.ALL);
    }

    private static String simpleClassName(Path sourceFile) {
        String fileName = String.valueOf(sourceFile.getFileName());

        return fileName.substring(0, fileName.length() - ".java".length());
    }

    /**
     * Walks up from the working directory to the directory holding {@value #SETTINGS_FILE_NAME}. A module-relative path
     * cannot see other modules, and this scan must see every module under {@code server/}.
     */
    private static Path findRepositoryRoot() {
        Path candidate = Path.of("")
            .toAbsolutePath();

        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(SETTINGS_FILE_NAME))) {
                return candidate;
            }

            candidate = candidate.getParent();
        }

        throw new IllegalStateException("Could not locate " + SETTINGS_FILE_NAME + " by walking up from the "
            + "working directory, which is unexpected.");
    }

    private static List<Path> findMainSourceFiles(Path serverRoot) throws IOException {
        try (Stream<Path> paths = Files.walk(serverRoot)) {
            return paths.filter(Files::isRegularFile)
                .filter(path -> path.toString()
                    .endsWith(".java"))
                .filter(path -> {
                    String normalizedPath = path.toString()
                        .replace(File.separatorChar, '/');

                    return normalizedPath.contains("/src/main/java/") && !normalizedPath.contains("/build/");
                })
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        }
    }
}
