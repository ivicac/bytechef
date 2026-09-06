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

package com.bytechef.automation.ai.tool;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Every production writer of the {@code bytechef.agentTool.workspaceId} / {@code bytechef.assetFile.workspaceId}
 * tool-context map slots — {@link AgentToolInvocationContext#TOOL_CONTEXT_WORKSPACE_ID_KEY} and
 * {@link AutomationToolInvocationContext#TOOL_CONTEXT_WORKSPACE_ID_KEY} are two of the named constants that carry those
 * literal values — must source its workspace id from a controller-verified state key, never from a raw client-supplied
 * one.
 *
 * <p>
 * This exists because it once did not hold: a writer of the agent tool context took its workspace id from
 * {@code CopilotConstants.STATE_WORKSPACE_ID} — an unverified, client-suppliable request field — instead of
 * {@code CopilotConstants.STATE_VERIFIED_WORKSPACE_ID}, the key the controller rewrites only after checking workspace
 * membership. Any tool reading the resulting tool context (asset files, data tables, project deployments, …) then
 * operated against a workspace the caller merely claimed, not one it belonged to. That writer,
 * {@code CopilotToolContextUtils}, was fixed to read the verified key. This test does not re-prove that fix; it pins
 * the shape of the bug so the *next* writer that reaches for the unverified key fails loudly instead of shipping
 * silently.
 * </p>
 *
 * <p>
 * Two rules, because the first one alone was not enough. The <b>negative</b> rule flags a file that also names a
 * known-unverified constant; it is blind to one that reaches for an unverified value under no name at all. The
 * <b>positive</b> rule closes that: a file naming the key and reading the run {@code State} must also name a verified
 * key. Classes that take the workspace id as an argument rather than reading state — the {@code *ToolInvocationContext}
 * value classes — read no state and are not subject to it.
 * </p>
 *
 * <p>
 * Both rules were added after {@code WorkflowExecutionSpringAIAgent} shipped exactly this defect and this scan passed
 * it clean. That agent read {@code state.get("parameters")} and wrote the caller's own {@code workspaceId} from it,
 * overwriting the verified value {@code CopilotToolContextUtils} had just written to the same map slot, so a member of
 * one workspace could read and enumerate another workspace's runs. It was invisible here for two independent reasons,
 * both fixed above: it named the key through a third constant, {@code WorkflowExecutionToolContextKeys.WORKSPACE_ID},
 * absent from the hand-maintained name list (aliases are now derived from the literals), and it wrote through a local
 * {@code putLong(} helper, absent from the {@code put(}/{@code putIfNotNull(} call allowlist (call shape is no longer
 * part of the test). A scan whose recognition rules are hand-maintained lists fails silently and looks green doing it.
 * </p>
 *
 * <p>
 * A source scan rather than a context test: reproducing the vulnerable request shape (an authenticated caller with an
 * out-of-workspace id in a raw request field) would need a running controller and a real membership check per calling
 * surface — Copilot, AI Hub, and the management MCP server each wire that differently, and only one of them lives in
 * this module. Reading the same invariant off the source is surface-agnostic and needs no application context.
 * </p>
 *
 * <p>
 * Three write shapes are recognised. A statement is a write when it calls {@code put(...)}/{@code putIfNotNull(...)}
 * and either (a) references the bare token {@code TOOL_CONTEXT_WORKSPACE_ID_KEY} — qualified
 * ({@code AgentToolInvocationContext.TOOL_CONTEXT_WORKSPACE_ID_KEY}) or unqualified, the shape a context record's own
 * {@code toToolContext()} uses to write its own field — or (b) references one of the key's literal string values
 * directly ({@code "bytechef.agentTool.workspaceId"}, {@code "bytechef.assetFile.workspaceId"}). The second form exists
 * because {@code AiHubToolInvocationContext} (EE {@code ai-hub-api}) declares its own, differently-named constant
 * carrying the exact same literal value as {@code AutomationToolInvocationContext}'s — the two are read interchangeably
 * by downstream tool callbacks, since {@link java.util.Map} keys are strings, not constant identities — so a scan keyed
 * only on constant names can never see that class's write. Separately, (c) an
 * {@code AgentToolInvocationContext.builder()}/{@code AutomationToolInvocationContext.builder()} chain that calls
 * {@code .workspaceId(...)} before {@code .build()} is also a write, the shape {@code AiHubSpringAIAgent#toolContext}
 * uses for the {@code AgentToolInvocationContext} key family, where neither the constant nor the literal value appears
 * in the writing file at all — both are written inside the context record's own {@code toToolContext()}. All three are
 * recognised at statement granularity (split on {@code ;}, comments stripped), the same coarse granularity
 * {@code McpOutboundGuardrailsCoverageTest} uses for its guard-call scan.
 * </p>
 *
 * <p>
 * The unverified-key check is deliberately the fully qualified constant reference ({@code CopilotConstants.
 * STATE_WORKSPACE_ID}, {@code AiHubStateKeys.WORKSPACE_ID}), not the bare field name: {@code WORKSPACE_ID} is a literal
 * suffix of both {@code VERIFIED_WORKSPACE_ID} and {@code TOOL_CONTEXT_WORKSPACE_ID_KEY}, so a bare substring search
 * would flag the verified key and the tool-context key themselves as violations. Matching the qualified name with
 * {@code \b} word boundaries on both ends avoids that: {@code \bAiHubStateKeys\.WORKSPACE_ID\b} does not match inside
 * {@code AiHubStateKeys.VERIFIED_WORKSPACE_ID}, because the character immediately before the candidate match (the
 * {@code _} in {@code VERIFIED_}) is itself a word character, so no boundary exists there. The bare-token and
 * literal-value write patterns above do not create a false-positive risk here: a constant's own declaration line
 * ({@code = "bytechef.agentTool.workspaceId";}) never contains {@code put(} or {@code putIfNotNull(}, so declaring a
 * key never by itself marks a file a writer, and none of the three declaration classes reference either unverified key
 * anywhere else in their file.
 * </p>
 *
 * <p>
 * Gradle's up-to-date check and remote build-cache key are blind to this scan for any file that is not already on this
 * module's own compile/runtime classpath: {@code CopilotToolContextUtils} and {@code SliceSpringAIAgent} live in
 * {@code ai-copilot-service}, {@code AiHubSpringAIAgent} and {@code AiHubToolInvocationContext} in
 * {@code ai-hub-service}/{@code ai-hub-api} — none of which this module depends on. {@link Files#readString} is
 * invisible to the task-input graph, so a regression in any of those would leave a normal test task's fingerprint
 * unchanged and report {@code UP-TO-DATE} / green instead of executing this test at all.
 * </p>
 *
 * <p>
 * This module's {@code build.gradle.kts} therefore runs this class from its own never-up-to-date
 * {@code toolContextWorkspaceScan} task, wired into {@code check} and excluded from {@code test}. Declaring the
 * {@code server/**} tree as an input of {@code test} was tried first and had to be reverted: it made every spotless
 * task a producer of that task's declared inputs, so {@code check} failed validation before running anything. Run this
 * scan with {@code :automation-ai-tool:toolContextWorkspaceScan}; {@code test --tests
 * '*ToolContextWorkspaceVerificationTest*'} no longer selects it.
 * </p>
 *
 * @author Ivica Cardic
 */
class ToolContextWorkspaceVerificationTest {

    private static final String SETTINGS_FILE_NAME = "settings.gradle.kts";

    private static final Pattern COMMENT_PATTERN = Pattern.compile("/\\*.*?\\*/|//[^\\r\\n]*", Pattern.DOTALL);

    private static final String AGENT_CONTEXT_KEY_LITERAL_VALUE = "\"bytechef.agentTool.workspaceId\"";

    private static final String AUTOMATION_CONTEXT_KEY_LITERAL_VALUE = "\"bytechef.assetFile.workspaceId\"";

    private static final Pattern KEY_CONSTANT_DECLARATION_PATTERN = Pattern.compile(
        "String\\s+(\\w+)\\s*=\\s*\"bytechef\\.(?:agentTool|assetFile)\\.workspaceId\"");

    private static final String AGENT_CONTEXT_BUILDER_CALL = "AgentToolInvocationContext.builder()";

    private static final String AUTOMATION_CONTEXT_BUILDER_CALL = "AutomationToolInvocationContext.builder()";

    private static final String WORKSPACE_ID_SETTER_CALL = ".workspaceId(";

    private static final Pattern UNVERIFIED_COPILOT_STATE_KEY_PATTERN =
        Pattern.compile("\\bCopilotConstants\\.STATE_WORKSPACE_ID\\b");

    private static final Pattern UNVERIFIED_AI_HUB_STATE_KEY_PATTERN =
        Pattern.compile("\\bAiHubStateKeys\\.WORKSPACE_ID\\b");

    private static final String VERIFIED_KEY_BARE_TOKEN = "VERIFIED_WORKSPACE_ID";

    private static final String STATE_READ_CALL = "state.get(";

    private static final String STATE_ACCESSOR_CALL = "input.state()";

    /**
     * The complete set of production classes allowed to name the tool-context workspace-id key.
     *
     * <p>
     * An allowlist rather than another heuristic, because the other two rules are file-granular and a file that names a
     * verified key anywhere passes them even if it also writes an unverified value somewhere else in the same file - a
     * reviewer demonstrated exactly that by adding a raw write to {@code SliceSpringAIAgent}, which already names
     * {@code VERIFIED_WORKSPACE_ID}. Distinguishing a read of this key from a write of it needs dataflow analysis a
     * source scan does not have, so the honest gate is the membership of the set itself: the key is one shared,
     * security-critical map slot, the set of files touching it is small, and it should change only deliberately.
     * </p>
     *
     * <p>
     * {@code WorkspaceScopedFlatToolCallback} and {@code WorkspaceScopedSubAgentToolCallback} are the two entries that
     * most need the gate. Both once took the workspace id straight from MCP tool-call arguments with no membership
     * check; they now resolve it through {@code AccessibleWorkspaceResolver}, which is why neither names a verified
     * state key and yet neither is a violation - they read no run {@code State} at all, so the positive rule below is
     * blind to them and this set is the only thing watching them.
     * </p>
     *
     * <p>
     * {@code AiHubToolApprovalFacadeImpl} was added when the approval-continuation path started building a tool context
     * of its own. Both of its writes take {@code chat.getWorkspaceId()}, and the chat is unreachable until either
     * {@code getById(chatId, workspaceId, currentUserId)} or {@code resolve}'s own equality-and-membership check has
     * passed, so the value is a server-verified row field rather than anything a caller supplied. It is in the set
     * because it writes the slot, not because the write is suspect.
     * </p>
     */
    private static final Set<String> EXPECTED_KEY_TOUCHING_CLASSES = Set.of(
        "AgentToolInvocationContext",
        "AiHubSpringAIAgent",
        "AiHubToolApprovalFacadeImpl",
        "AiHubToolInvocationContext",
        "AutomationToolInvocationContext",
        "CopilotToolContextUtils",
        "DeferredGuardrailsAdvisor",
        "SliceSpringAIAgent",
        "WorkflowExecutionTools",
        "WorkspaceScopedFlatToolCallback",
        "WorkspaceScopedSubAgentToolCallback");

    /**
     * The inherited 30-second per-method default comes from {@code test-support} and is a unit-test budget. This is a
     * walk of every production source under {@code server/} - tens of thousands of files, growing with the repository -
     * so it does not belong under that budget. It ran at roughly 8.5s when this limit was set; the limit is
     * deliberately far above that so ordinary repository growth cannot turn this into an intermittent red build, which
     * is what it did once already.
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void testEveryToolContextWorkspaceIdWriterSourcesFromAVerifiedStateKey() throws IOException {
        Path repositoryRoot = findRepositoryRoot();

        Path serverRoot = repositoryRoot.resolve("server");

        assertTrue(
            Files.isDirectory(serverRoot), "server/ directory not found under repository root: " + repositoryRoot);

        List<Path> mainSourceFiles = findMainSourceFiles(serverRoot);

        assertFalse(
            mainSourceFiles.isEmpty(),
            "Found no *.java files under any src/main/java directory beneath " + serverRoot
                + " - the file walk itself is broken, this is not a claim that server/ has no production sources.");

        List<KeyConstantAlias> keyConstantAliases = findKeyConstantAliases(mainSourceFiles);

        assertFalse(
            keyConstantAliases.isEmpty(),
            "Found no constant anywhere under " + serverRoot + " declared equal to one of the tool-context "
                + "workspace-id key literals - the alias discovery itself is broken, this is not a claim that the "
                + "key has no named constant.");

        List<Path> referencingFiles = new ArrayList<>();
        List<String> unexpectedFiles = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        List<String> unnamedSourceViolations = new ArrayList<>();

        for (Path sourceFile : mainSourceFiles) {
            String source = Files.readString(sourceFile);

            if (!mayReferenceKey(source, keyConstantAliases)) {
                continue;
            }

            String sourceWithoutComments = stripComments(source);

            if (!referencesToolContextWorkspaceIdKey(sourceWithoutComments, sourceFile, keyConstantAliases)) {
                continue;
            }

            referencingFiles.add(sourceFile);

            if (!EXPECTED_KEY_TOUCHING_CLASSES.contains(simpleClassName(sourceFile))) {
                unexpectedFiles.add(sourceFile.toString());
            }

            if (UNVERIFIED_COPILOT_STATE_KEY_PATTERN.matcher(sourceWithoutComments)
                .find()
                || UNVERIFIED_AI_HUB_STATE_KEY_PATTERN.matcher(sourceWithoutComments)
                    .find()) {

                violations.add(sourceFile.toString());
            }

            boolean readsRunState = sourceWithoutComments.contains(STATE_READ_CALL)
                || sourceWithoutComments.contains(STATE_ACCESSOR_CALL);

            if (readsRunState && !sourceWithoutComments.contains(VERIFIED_KEY_BARE_TOKEN)) {
                unnamedSourceViolations.add(sourceFile.toString());
            }
        }

        assertFalse(
            referencingFiles.isEmpty(),
            "Found no production reference to the tool-context workspace-id key under " + serverRoot + " (expected at "
                + "least CopilotToolContextUtils, SliceSpringAIAgent, AiHubSpringAIAgent#toolContext, "
                + "AiHubToolInvocationContext, and the management MCP WorkspaceScoped*ToolCallback classes) - the "
                + "scan pattern itself is broken, this is not a claim that no such reference exists.");

        if (!unexpectedFiles.isEmpty()) {
            fail(
                "Found " + unexpectedFiles.size() + " production file(s) naming the tool-context workspace-id key "
                    + "that are not in EXPECTED_KEY_TOUCHING_CLASSES. That key is one shared map slot reached through "
                    + "several aliased constants, so writing it is never additive - it overwrites whatever a caller "
                    + "already put there, including a server-verified workspace id. If this file legitimately needs "
                    + "the key, add its simple class name to that set in the same commit and make sure any value it "
                    + "writes comes from CopilotConstants.STATE_VERIFIED_WORKSPACE_ID / "
                    + "AiHubStateKeys.VERIFIED_WORKSPACE_ID:\n"
                    + String.join("\n", unexpectedFiles));
        }

        if (!violations.isEmpty()) {
            fail(
                "Found " + violations.size() + " of " + referencingFiles.size() + " tool-context workspace-id writer "
                    + "file(s) that also reference a known-unverified, client-suppliable workspace-id state key in "
                    + "the same file - source the workspace id from the verified key instead "
                    + "(CopilotConstants.STATE_VERIFIED_WORKSPACE_ID / AiHubStateKeys.VERIFIED_WORKSPACE_ID), which "
                    + "is populated only after the calling controller checks workspace membership:\n"
                    + String.join("\n", violations));
        }

        if (!unnamedSourceViolations.isEmpty()) {
            fail(
                "Found " + unnamedSourceViolations.size() + " of " + referencingFiles.size()
                    + " tool-context workspace-id "
                    + "writer file(s) that read the run State but never name a verified workspace-id key - the "
                    + "workspace id they write is sourced from something this scan cannot vouch for, which is exactly "
                    + "how WorkflowExecutionSpringAIAgent shipped a cross-workspace read. Source it from "
                    + "CopilotConstants.STATE_VERIFIED_WORKSPACE_ID / AiHubStateKeys.VERIFIED_WORKSPACE_ID, or stop "
                    + "writing the key and let CopilotToolContextUtils' already-verified value stand:\n"
                    + String.join("\n", unnamedSourceViolations));
        }
    }

    /**
     * Cheap raw-text pre-filter run before the comment strip. The strip is a {@code DOTALL} regex over a whole file and
     * is by far the most expensive thing here; running it on all ~7,300 production sources twice (once for alias
     * discovery, once for the reference scan) overran this module's inherited 30-second per-method timeout. Only files
     * whose raw text could possibly name the key are stripped, which is a few dozen.
     */
    private static boolean mayReferenceKey(String source, List<KeyConstantAlias> keyConstantAliases) {
        if (source.contains(AGENT_CONTEXT_KEY_LITERAL_VALUE) || source.contains(AUTOMATION_CONTEXT_KEY_LITERAL_VALUE)) {
            return true;
        }

        if (source.contains(AGENT_CONTEXT_BUILDER_CALL) || source.contains(AUTOMATION_CONTEXT_BUILDER_CALL)) {
            return true;
        }

        for (KeyConstantAlias alias : keyConstantAliases) {
            if (source.contains(alias.constantName())) {
                return true;
            }
        }

        return false;
    }

    private static String stripComments(String source) {
        return COMMENT_PATTERN.matcher(source)
            .replaceAll("");
    }

    /**
     * Maps each constant simple name declared equal to one of the two key literals to the simple name of the class
     * declaring it, e.g. {@code TOOL_CONTEXT_WORKSPACE_ID_KEY -> AutomationToolInvocationContext} and
     * {@code WORKSPACE_ID -> WorkflowExecutionToolContextKeys}.
     *
     * <p>
     * Derived rather than hardcoded because the aliasing is the whole hazard: the key is one map slot reached through
     * several differently-named constants in different modules, and a hand-maintained list of the names known when the
     * scan was written is precisely what let {@code WorkflowExecutionToolContextKeys.WORKSPACE_ID} through.
     * </p>
     */
    private static List<KeyConstantAlias> findKeyConstantAliases(List<Path> mainSourceFiles) throws IOException {
        List<KeyConstantAlias> aliases = new ArrayList<>();

        for (Path sourceFile : mainSourceFiles) {
            String source = Files.readString(sourceFile);

            if (!source.contains(AGENT_CONTEXT_KEY_LITERAL_VALUE)
                && !source.contains(AUTOMATION_CONTEXT_KEY_LITERAL_VALUE)) {

                continue;
            }

            Matcher matcher = KEY_CONSTANT_DECLARATION_PATTERN.matcher(stripComments(source));

            while (matcher.find()) {
                aliases.add(new KeyConstantAlias(matcher.group(1), simpleClassName(sourceFile)));
            }
        }

        return aliases;
    }

    /**
     * True when any statement names the key: through a qualified alias reference
     * ({@code AutomationToolInvocationContext.TOOL_CONTEXT_WORKSPACE_ID_KEY}), through a bare alias token inside the
     * very class that declares it, through one of the literal values, or through an
     * {@code AgentToolInvocationContext}/{@code AutomationToolInvocationContext} {@code .builder()...workspaceId(...)}
     * chain.
     *
     * <p>
     * Deliberately not restricted to a fixed set of writer call names. An earlier revision matched only
     * {@code put(}/{@code putIfNotNull(}, and {@code WorkflowExecutionSpringAIAgent} wrote the key through a local
     * {@code putLong(} helper, so the file was never even classified as touching the key. Naming the key at all is the
     * signal; what the surrounding call is called is not.
     * </p>
     *
     * <p>
     * A bare alias token counts only inside its own declaring class because the aliases are not all distinctive -
     * {@code WORKSPACE_ID} is also an unrelated constant in the Monday, ClickUp and Retable components, which matched
     * 25 extra files when the bare token was accepted everywhere.
     * </p>
     */
    private static boolean referencesToolContextWorkspaceIdKey(
        String sourceWithoutComments, Path sourceFile, List<KeyConstantAlias> keyConstantAliases) {

        String className = simpleClassName(sourceFile);

        for (String statement : sourceWithoutComments.split(";")) {
            if (KEY_CONSTANT_DECLARATION_PATTERN.matcher(statement)
                .find()) {

                continue;
            }

            if (statement.contains(AGENT_CONTEXT_KEY_LITERAL_VALUE)
                || statement.contains(AUTOMATION_CONTEXT_KEY_LITERAL_VALUE)) {

                return true;
            }

            for (KeyConstantAlias alias : keyConstantAliases) {
                String declaringClassName = alias.declaringClassName();
                String constantName = alias.constantName();

                if (statement.contains(declaringClassName + "." + constantName)) {
                    return true;
                }

                if (declaringClassName.equals(className)
                    && Pattern.compile("\\b" + Pattern.quote(constantName) + "\\b")
                        .matcher(statement)
                        .find()) {

                    return true;
                }
            }

            boolean isBuilderCall =
                statement.contains(AGENT_CONTEXT_BUILDER_CALL) || statement.contains(AUTOMATION_CONTEXT_BUILDER_CALL);

            if (isBuilderCall && statement.contains(WORKSPACE_ID_SETTER_CALL)) {
                return true;
            }
        }

        return false;
    }

    /**
     * One constant declared equal to a key literal, paired with the class declaring it.
     *
     * <p>
     * A list of pairs rather than a {@code Map} keyed by constant name, because the SAME name is declared in several
     * classes -- {@code TOOL_CONTEXT_WORKSPACE_ID_KEY} exists in {@code AutomationToolInvocationContext},
     * {@code AgentToolInvocationContext} and {@code AiHubToolInvocationContext}. A map silently kept whichever was
     * scanned last, so the qualified references and in-class writes of the other two were invisible to this scan -- the
     * very aliasing hazard it exists to police, reintroduced inside the police.
     * </p>
     */
    private record KeyConstantAlias(String constantName, String declaringClassName) {
    }

    private static String simpleClassName(Path sourceFile) {
        String fileName = String.valueOf(sourceFile.getFileName());

        return fileName.substring(0, fileName.length() - ".java".length());
    }

    /**
     * Walks up from the current working directory until a directory containing {@value #SETTINGS_FILE_NAME} is found. A
     * module-relative {@code src/main/java} cannot see other modules, and this scan must see every module under
     * {@code server/}, not just this one.
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

        throw new IllegalStateException(
            "Could not locate " + SETTINGS_FILE_NAME + " by walking up from "
                + Path.of("")
                    .toAbsolutePath()
                + " - the working directory is unexpected.");
    }

    /**
     * Recursively lists every {@code *.java} file under a {@code src/main/java} directory beneath {@code serverRoot},
     * excluding anything under a {@code build/} directory — a stale generated or copied-resource tree under such a path
     * would otherwise be misclassified as production source.
     */
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
