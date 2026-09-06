/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.configuration.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@code PermissionServiceImpl#hasResourceScope} looks up a {@link ResourceEnvironmentResolver} keyed by resource type
 * and, only when one is registered and can answer, checks the caller's role in that specific environment rather than
 * unioning every environment the caller can reach. A resource type reachable through {@code hasResourceScope} (anything
 * named as the middle argument of a {@code hasPermission(id, 'ResourceType', scope)} SpEL expression) with no
 * registered resolver is therefore always checked the environment-unaware way, no matter how the annotation itself is
 * written -- that is exactly {@link EnvironmentAwareGateCoverageTest}'s subject from the annotation side. This test is
 * the resolver-registry side: which resource types have opted in, and is that the complete set this codebase actually
 * needs.
 *
 * <h2>Why this scans files, not classes</h2>
 *
 * <p>
 * {@code ResourceEnvironmentResolverProjectGuardTest}, in this same package, already does a classpath-reflection scan
 * for {@link ResourceEnvironmentResolver} implementations, and its own javadoc states the limit precisely: it sees only
 * the CE {@code automation-configuration-service} module (a dependency here) and this EE module itself, and would NOT
 * see a resolver contributed from a module this one does not depend on -- naming {@code automation-ai-mcp-service},
 * which is exactly where {@code McpServerEnvironmentResolver} lives, as its example of a blind spot. That module is on
 * this module's compile classpath only transitively through EE wiring, and even where it is on the classpath, a
 * differently-assembled test run (a different Gradle module invoking the same class) is not guaranteed to load it.
 * Scanning source files by path under {@code server/} sees every resolver regardless of which module declares it or
 * which module runs this test, at the cost of extracting {@code resourceType()}'s return value textually instead of by
 * invoking the method.
 *
 * <h2>What "reachable through hasResourceScope" means here</h2>
 *
 * <p>
 * Only the three-argument {@code hasPermission(id, 'ResourceType', scope)} SpEL form dispatches to
 * {@code hasResourceScope} -- confirmed by reading {@code AutomationPermissionEvaluator}, whose 3-argument
 * {@code hasPermission(Authentication, Serializable, String, Object)} override is the only one that calls
 * {@code permissionService.hasResourceScope(...)}. The 2-argument {@code hasPermission(target, permission)} form routes
 * on the object's Java type instead and does not consult a resource-type string at all;
 * {@code isResourceOwner(id, 'Type')} calls a different {@code PermissionService} method entirely, with no
 * environment-resolution step of its own. This scan therefore only counts the exact
 * {@code hasPermission(<expr>, '<ResourceType>', <expr>)} shape, extracted with a balanced-argument split rather than a
 * first-comma regex, since a first argument such as {@code @promotionAuthorizer.workspaceIdOfMcpServer(#sourceId)} is
 * itself a call and must not be mistaken for having ended at its own internal comma (it has none today, but a regex
 * that assumed otherwise would break silently the day one gained a second argument).
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class ResourceEnvironmentResolverCoverageTest {

    private static final String SETTINGS_FILE_NAME = "settings.gradle.kts";

    private static final Pattern COMMENT_PATTERN = Pattern.compile("/\\*.*?\\*/|//[^\\r\\n]*", Pattern.DOTALL);

    private static final Pattern IMPLEMENTS_RESOLVER_PATTERN =
        Pattern.compile("implements\\s+[^{;]*\\bResourceEnvironmentResolver\\b");

    private static final Pattern RESOURCE_TYPE_METHOD_PATTERN =
        Pattern.compile("resourceType\\s*\\(\\s*\\)\\s*\\{\\s*return\\s*\"([^\"]+)\"");

    private static final Pattern HAS_PERMISSION_START_PATTERN = Pattern.compile("hasPermission\\s*\\(");

    /**
     * Resource types with no {@link ResourceEnvironmentResolver} and a reason each stays that way. Two are the given
     * ones from this task's brief; the rest were found by running this scan and are recorded rather than fixed here.
     */
    private static final Map<String, String> KNOWN_NO_ENVIRONMENT = buildKnownNoEnvironment();

    private static Map<String, String> buildKnownNoEnvironment() {
        Map<String, String> reasons = new java.util.LinkedHashMap<>();

        reasons.put(
            "Workspace",
            "A workspace has no environment of its own -- the environment is an argument to the gate, not a "
                + "property of the workspace row, so no resolver could supply one. The argument-supplied "
                + "hasWorkspaceScopeInEnvironmentId(...)/hasWorkspaceScopeInEnvironment(...) family is what covers "
                + "'Workspace'-keyed gates instead; see EnvironmentAwareGateCoverageTest.");

        reasons.put(
            "Project",
            "Deliberately absent, and read ResourceEnvironmentResolverProjectGuardTest before changing this: "
                + "environments belong to a project's DEPLOYMENTS, not to the project itself, and that test fails on "
                + "purpose the moment any resolver claims 'Project'. For 'Project'-keyed gates the GATE must carry "
                + "the environment explicitly (as ProjectDeploymentPromotionHandler and ApiCollectionPromotionHandler "
                + "should but currently do not -- see EnvironmentAwareGateCoverageTest's KNOWN_EXEMPT), exactly as "
                + "for 'Workspace'. Do not \"fix\" this absence by adding the resolver the guard test forbids.");

        String noEnvironmentFieldReason =
            "No environment field on the underlying domain type -- it is a sub-element or projection scoped "
                + "entirely by its already-resolved parent (an AiAgent, a Job's own execution record, an "
                + "already-environment-scoped McpServer/McpProject, or a Project/ProjectWorkflow, which carries no "
                + "environment for the same reason 'Project' above does not). Nothing for a resolver to answer with.";

        for (String noEnvironmentType : List.of(
            "AiAgent", "AiAgentChannel", "AiAgentElement", "Job", "McpComponent", "McpProject", "McpProjectWorkflow",
            "McpTool", "ProjectWorkflow")) {

            reasons.put(noEnvironmentType, noEnvironmentFieldReason);
        }

        reasons.put(
            "DataTable",
            "Found by this scan, not given in the brief: unlike 'Workspace'/'Project', a data table DOES carry a "
                + "real environment (WorkspaceDataTableFacadeImpl's by-id family acts on a caller-supplied "
                + "environmentId). No resolver is registered for it, so every hasPermission(id,'DataTable',...) "
                + "by-id gate unions across environments -- confirmed unmitigated, the same finding "
                + "EnvironmentAwareGateCoverageTest records for the same class. Adding a resolver here would be a "
                + "real fix, not merely a acknowledged limit; out of this plan's approved scope, own ticket.");

        reasons.put(
            "Workflow",
            "Found by this scan, not given in the brief: a workflow DOES carry a real environment. No resolver is "
                + "registered, so hasPermission(id,'Workflow',...) unions across environments except for api-key "
                + "principals (PrincipalEnvironment.resolveEffectiveEnvironmentId) -- the same Workflow-family "
                + "finding EnvironmentAwareGateCoverageTest records. Own ticket.");

        reasons.put(
            "ApiKey",
            "Found by this scan, not given in the brief: an api key DOES carry a real environment. No resolver is "
                + "registered, so WorkspaceApiKeyFacadeImpl#delete's "
                + "hasPermission(#apiKeyId,'ApiKey','API_KEY_DELETE') unions across environments for a plain by-id "
                + "delete that names no environment argument at all -- a different shape from every other finding in "
                + "this list, since EnvironmentAwareGateCoverageTest's parameter-based scan cannot see it (delete "
                + "takes only an id, no environment parameter to flag). Own ticket.");

        reasons.put(
            "KnowledgeBase",
            "Found by this scan, not given in the brief: a knowledge base DOES carry a real environment. No "
                + "resolver is registered, so WorkspaceKnowledgeBaseFacadeImpl's by-id family "
                + "(hasPermission(#knowledgeBaseId,'KnowledgeBase',...), none of whose methods take an environment "
                + "parameter of their own) unions across environments the same way the ApiKey delete case does. Own "
                + "ticket.");

        return Map.copyOf(reasons);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void testEveryReachableResourceTypeHasAResolverOrAReason() throws IOException {
        Path serverRoot = findRepositoryRoot().resolve("server");

        assertTrue(Files.isDirectory(serverRoot), "server/ directory not found under " + serverRoot);

        List<Path> mainSourceFiles = findMainSourceFiles(serverRoot);

        assertFalse(
            mainSourceFiles.isEmpty(),
            "Found no *.java files under any src/main/java beneath " + serverRoot + " - the file walk itself is "
                + "broken, this is not a claim that server/ has no production sources.");

        Set<String> resolvedResourceTypes = new LinkedHashSet<>();
        Set<String> reachableResourceTypes = new TreeSet<>();

        for (Path sourceFile : mainSourceFiles) {
            String source = Files.readString(sourceFile);
            String text = COMMENT_PATTERN.matcher(source)
                .replaceAll("");

            if (IMPLEMENTS_RESOLVER_PATTERN.matcher(text)
                .find()) {

                String resourceType = extractResourceType(text);

                if (resourceType != null) {
                    resolvedResourceTypes.add(resourceType);
                }
            }

            reachableResourceTypes.addAll(extractHasPermissionResourceTypes(text));
        }

        assertTrue(
            resolvedResourceTypes.containsAll(List.of("Connection", "ProjectDeployment", "McpServer")),
            "Expected to discover the three known ResourceEnvironmentResolver implementations (Connection, "
                + "ProjectDeployment, McpServer) by scanning source files; found " + resolvedResourceTypes
                + " instead. If this list shrank, the file-based discovery mechanism itself is broken, which would "
                + "make the assertion below pass vacuously for every type.");

        List<String> unaccountedFor = new ArrayList<>();

        for (String resourceType : reachableResourceTypes) {
            if (resolvedResourceTypes.contains(resourceType)) {
                continue;
            }

            if (KNOWN_NO_ENVIRONMENT.containsKey(resourceType)) {
                continue;
            }

            unaccountedFor.add(resourceType);
        }

        if (!unaccountedFor.isEmpty()) {
            fail(
                "Found " + unaccountedFor.size() + " resource type(s) reachable through hasPermission(id,'Type',"
                    + "scope) with no registered ResourceEnvironmentResolver and no KNOWN_NO_ENVIRONMENT reason: "
                    + unaccountedFor + ". Either register a resolver, or add the type to KNOWN_NO_ENVIRONMENT "
                    + "with the reason it has none.");
        }
    }

    @Test
    void testKnownNoEnvironmentIsNotEmpty() {
        assertFalse(
            KNOWN_NO_ENVIRONMENT.isEmpty(), "KNOWN_NO_ENVIRONMENT should never be empty while 'Workspace' and "
                + "'Project' are permanent, deliberate absences.");
    }

    @Nullable
    private static String extractResourceType(String text) {
        Matcher matcher = RESOURCE_TYPE_METHOD_PATTERN.matcher(text);

        return matcher.find() ? matcher.group(1) : null;
    }

    private static List<String> extractHasPermissionResourceTypes(String text) {
        List<String> resourceTypes = new ArrayList<>();
        Matcher matcher = HAS_PERMISSION_START_PATTERN.matcher(text);

        while (matcher.find()) {
            int openParenIndex = matcher.end() - 1;
            String argumentsText = extractParenthesizedContent(text, openParenIndex);

            if (argumentsText == null) {
                continue;
            }

            List<String> arguments = splitTopLevelArguments(argumentsText);

            if (arguments.size() != 3) {
                continue;
            }

            String resourceTypeArgument = arguments.get(1)
                .trim();

            if (resourceTypeArgument.length() >= 2 && resourceTypeArgument.startsWith("'")
                && resourceTypeArgument.endsWith("'")) {

                resourceTypes.add(resourceTypeArgument.substring(1, resourceTypeArgument.length() - 1));
            }
        }

        return resourceTypes;
    }

    /**
     * Splits an argument list on commas that are not nested inside parentheses, so that a first argument which is
     * itself a call (such as {@code @promotionAuthorizer.workspaceIdOfMcpServer(#sourceId)}) is not mistaken for ending
     * at a comma inside its own parentheses.
     */
    private static List<String> splitTopLevelArguments(String argumentsText) {
        List<String> arguments = new ArrayList<>();
        int depth = 0;
        int segmentStart = 0;

        for (int index = 0; index < argumentsText.length(); index++) {
            char character = argumentsText.charAt(index);

            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
            } else if (character == ',' && depth == 0) {
                arguments.add(argumentsText.substring(segmentStart, index));

                segmentStart = index + 1;
            }
        }

        arguments.add(argumentsText.substring(segmentStart));

        return arguments;
    }

    @Nullable
    private static String extractParenthesizedContent(String text, int openParenIndex) {
        int depth = 0;

        for (int index = openParenIndex; index < text.length(); index++) {
            char character = text.charAt(index);

            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;

                if (depth == 0) {
                    return text.substring(openParenIndex + 1, index);
                }
            }
        }

        return null;
    }

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
