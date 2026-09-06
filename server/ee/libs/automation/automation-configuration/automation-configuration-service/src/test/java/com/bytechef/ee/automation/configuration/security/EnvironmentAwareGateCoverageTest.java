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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@code PermissionServiceImpl#hasResourceScope} only checks the caller's role IN a specific environment when a
 * {@link ResourceEnvironmentResolver} is registered for the resource type AND can resolve one for the given id.
 * Everywhere else -- no resolver, or a resolver that returns empty -- it falls back to the environment-<em>unaware</em>
 * overload, which unions the scopes the caller holds across every environment in the workspace. A method whose own
 * signature carries an environment (a plain {@code environmentId}/{@code Environment} argument, or an argument object
 * that carries one, such as {@link com.bytechef.platform.security.domain.ApiKey}) but whose {@code @PreAuthorize} does
 * not route through that environment is exactly the shape of bug this whole plan closes: a member who holds a scope in
 * one environment names a different one as an ordinary method argument, and the body acts on it unchecked.
 *
 * <p>
 * This test scans production source <strong>files</strong> under {@code server/}, not loaded classes, for exactly that
 * shape. {@code GuardrailSurfaceCoverageTest}'s own javadoc records why: a class-based scan only sees what its own
 * module depends on, so a negative-control run from a different module can report green while a real violation sits in
 * a module the running test never touches. Walking the file tree from the repository root sees every module regardless
 * of which one happens to declare this test as a dependency, at the cost of needing its own lightweight
 * multi-line-aware parser rather than reflection.
 *
 * <h2>What counts as "takes an environment"</h2>
 *
 * <ul>
 * <li>a parameter named {@code environmentId} (any type -- {@code long}, {@code Long}, {@code int});</li>
 * <li>a parameter typed {@link com.bytechef.platform.configuration.domain.Environment}; or</li>
 * <li>a parameter typed one of {@link #ENVIRONMENT_CARRYING_PARAMETER_TYPES} -- object arguments known to carry an
 * environment field. There is no general way to detect "this type has an environment field" from a type name alone, so
 * this list is named types only, seeded with {@code ApiKey} (see {@code WorkspaceApiKeyFacadeImpl#create}, site 20, and
 * {@code ApiKeyFacadeImpl#update} below): a future type that packs an environment inside an object argument needs
 * adding here explicitly or this scan cannot see it.</li>
 * </ul>
 *
 * <h2>What counts as "environment-aware"</h2>
 *
 * <p>
 * Every environment-aware expression form in this codebase -- {@code hasWorkspaceScopeInEnvironment(...)},
 * {@code hasWorkspaceScopeInEnvironmentId(...)}, {@code hasWorkflowScopeInEnvironment(...)},
 * {@code hasWorkspaceScopeInEveryEnvironment(...)} -- shares the substring {@link #ENVIRONMENT_AWARE_MARKER}. A
 * {@code hasPermission(id, 'ResourceType', scope)} expression is also treated as environment-aware when
 * {@code ResourceType} is one of the three types a {@link ResourceEnvironmentResolver} is actually registered for
 * ({@link #RESOLVED_RESOURCE_TYPES}) -- {@code hasResourceScope} resolves the environment itself in that case, even
 * though the annotation text carries no {@code InEnvironment} marker. Getting this wrong in the narrow direction
 * (treating a resolved type as unaware) would have produced false positives on already-correct call sites such as
 * {@code ProjectDeploymentFacadeImpl#updateProjectDeployment(ProjectDeploymentDTO)}, which is exactly how this
 * exception was discovered while building the test.
 *
 * <h2>Which classes are scanned, and why the net is not thrown over the whole repository</h2>
 *
 * <p>
 * Scanning is restricted to concrete classes whose simple name ends in {@code FacadeImpl} or {@code Handler}, and only
 * within files that use {@code @PreAuthorize} at least once. Both restrictions are load-bearing, not convenience:
 *
 * <ul>
 * <li><strong>Public methods only.</strong> Spring's method-security proxy can only ever intercept a call that crosses
 * the proxy boundary, which for a JDK/CGLIB proxy means a public method invoked from outside the bean. A
 * {@code private} helper such as {@code A2aServerPromotionHandler#loadSource(long, Environment)} can carry no
 * enforceable annotation no matter what its signature looks like, so flagging it would be noise with no fix available.
 * </li>
 * <li><strong>{@code FacadeImpl}/{@code Handler} suffix.</strong> This codebase's own convention (stated in
 * {@code CLAUDE.md}'s "API facade vs shared facade" section, and true of every site this plan touched) is that
 * {@code @PreAuthorize} enforcement lives on facade implementations and, for the two promotion handlers, on the handler
 * class directly -- controllers are thin delegators and internal services carry no method security at all. Scanning
 * every class that merely contains the substring {@code @PreAuthorize} anywhere in the file (an earlier version of this
 * test did exactly that) pulled in the framework's own {@code PermissionServiceImpl} and dozens of unrelated
 * {@code *ServiceImpl} classes whose environment-typed parameters are implementation plumbing, not entry points -- a
 * coverage test that cannot tell a security boundary from its own machinery is not useful as one.
 * <li><strong>File must use {@code @PreAuthorize} at least once, and the failing method's own class must carry a
 * {@code hasPermission(...)} or {@code isResourceOwner(...)} expression somewhere.</strong> Those are the only two SpEL
 * heads that route through {@code PermissionServiceImpl} (via {@code hasResourceScope}/{@code isResourceOwner}
 * respectively) rather than through an unrelated, deliberately environment-independent model --
 * {@code isAuthenticated()}, {@code isTenantAdmin()}, {@code hasAuthority(...)}, {@code hasRole(...)}. A tenant admin
 * is documented elsewhere in this codebase as deliberately not subject to per-environment roles, so an
 * {@code isTenantAdmin()}-gated method that happens to take an {@code environmentId} (such as
 * {@code ApiKeyFacadeImpl#getAdminApiKeys}) is not an instance of this bug; requiring the class to exhibit the
 * resource-scoped pattern at least once is what keeps such methods out of the result without hand-listing them.</li>
 * </ul>
 *
 * <p>
 * This scope is a deliberate boundary, not a claim that nothing outside it could ever have the same defect -- exactly
 * the caveat {@code GuardrailSurfaceCoverageTest} states about its own module-visibility limit.
 *
 * <h2>{@link #KNOWN_EXEMPT}</h2>
 *
 * <p>
 * Every site below is real: the scan finds each one when the exemption is removed (see the negative control in
 * {@code task-8-report.md}). Five come from the plan's own amendment; the rest were found by writing and running this
 * scan and are recorded here rather than fixed, per this task's brief.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class EnvironmentAwareGateCoverageTest {

    private static final String SETTINGS_FILE_NAME = "settings.gradle.kts";

    private static final Pattern COMMENT_PATTERN = Pattern.compile("/\\*.*?\\*/|//[^\\r\\n]*", Pattern.DOTALL);

    private static final String PRE_AUTHORIZE_HINT = "@PreAuthorize";

    /**
     * Matches a concrete public method's modifier+returnType+name+"(" prefix, anchored at the start of a (possibly
     * indented) line so it cannot match a call expression buried inside a method body -- a local variable can never
     * carry an access modifier, so this cannot mistake a body statement for a declaration.
     */
    private static final Pattern METHOD_SIGNATURE_START_PATTERN = Pattern.compile(
        "(?m)^[ \\t]*public\\s+(?:static\\s+)?(?:final\\s+)?"
            + "(?:[A-Za-z_$][\\w$.]*(?:<[^;{}]*>)?(?:\\[\\])?\\s+)+([A-Za-z_]\\w*)\\s*\\(");

    private static final String ENVIRONMENT_AWARE_MARKER = "InEnvironment";

    private static final List<String> RESOURCE_SCOPED_PREAUTHORIZE_HEADS =
        List.of("hasPermission(", "isResourceOwner(");

    /**
     * The three resource types {@code hasResourceScope} can resolve an environment for without an "InEnvironment"
     * marker in the annotation text -- see {@code ResourceEnvironmentResolverCoverageTest} for how this set is itself
     * kept honest.
     */
    private static final List<String> RESOLVED_RESOURCE_TYPES = List.of("Connection", "ProjectDeployment", "McpServer");

    private static final Pattern RESOLVED_TYPE_HAS_PERMISSION_PATTERN = Pattern.compile(
        "hasPermission\\([^,]*,\\s*'(" + String.join("|", RESOLVED_RESOURCE_TYPES) + ")'");

    /**
     * Types with no repo-wide way to detect "carries an environment field" other than naming them explicitly -- see the
     * class javadoc.
     */
    private static final List<String> ENVIRONMENT_CARRYING_PARAMETER_TYPES = List.of("ApiKey");

    private static final Pattern ENVIRONMENT_ID_PARAMETER_PATTERN = Pattern.compile("\\benvironmentId\\b");
    private static final Pattern ENVIRONMENT_TYPE_PARAMETER_PATTERN = Pattern.compile("\\bEnvironment\\s+\\w+");

    /**
     * Every open site this scan finds, keyed {@code ClassName#methodName} (overloads are not disambiguated -- the scan
     * does not need per-overload precision to be useful) or {@code ClassName#*} for a whole family, with a reason per
     * entry. Do not fix any of these here; recording them is this task's job, not closing them.
     */
    private static final Map<String, String> KNOWN_EXEMPT = buildKnownExempt();

    private static Map<String, String> buildKnownExempt() {
        Map<String, String> exempt = new java.util.LinkedHashMap<>();

        exempt.put(
            "ProjectWorkflowExecutionFacadeImpl#getWorkflowExecutions",
            "Site 17, deferred by Task 6. execution-app carries automation-workflow-execution-service (which hosts "
                + "this method) but not automation-configuration-service, the only module with a production "
                + "@EnableMethodSecurity -- so @PreAuthorize is never evaluated on that deployment at all, and "
                + "re-pointing the annotation would change nothing at runtime there. Deployment-topology decision, "
                + "maintainer's call.");

        String projectPromotionReason =
            "Confirmed vulnerable: 'Project' has no ResourceEnvironmentResolver (pinned deliberately by "
                + "ResourceEnvironmentResolverProjectGuardTest), so hasPermission(...,'Project',...) falls to the "
                + "environment-unaware union while the method body writes into #targetEnvironment. Out of this "
                + "plan's approved scope; own ticket.";

        exempt.put("ProjectDeploymentPromotionHandler#preview", projectPromotionReason);
        exempt.put("ProjectDeploymentPromotionHandler#promote", projectPromotionReason);
        exempt.put("ApiCollectionPromotionHandler#preview", projectPromotionReason);
        exempt.put("ApiCollectionPromotionHandler#promote", projectPromotionReason);

        String dataTableReason =
            "The DataTable by-id family: confirmed unmitigated. 'DataTable' carries a real environment (each table "
                + "lives in one) but has no registered ResourceEnvironmentResolver, so every by-id "
                + "hasPermission(#dataTableId,'DataTable',...) gate here unions across environments while the method "
                + "acts on the caller-supplied environmentId. Own ticket.";

        for (String method : List.of(
            "addColumn", "dropTable", "duplicateTable", "removeColumn", "renameColumn", "renameTable", "listRows",
            "insertRow", "updateRow", "deleteRow", "exportCsv", "importCsv", "listWebhooks", "getTable", "getRow",
            "fetchRowByExternalId", "upsertRow", "deleteRowByExternalId", "insertRows", "deleteRows", "clearRows")) {

            exempt.put("WorkspaceDataTableFacadeImpl#" + method, dataTableReason);
        }

        String workflowFamilyReason =
            "The Workflow family: mitigated only for api-key principals, since PrincipalEnvironment."
                + "resolveEffectiveEnvironmentId returns empty for an ordinary session member. 'Workflow' carries a "
                + "real environment but has no registered ResourceEnvironmentResolver, so "
                + "hasPermission(#workflowId,'Workflow',...) unions across environments for everyone else. Own "
                + "ticket.";

        for (String facadeClass : List.of(
            "WorkflowNodeDescriptionFacadeImpl", "WorkflowNodeOutputFacadeImpl", "WorkflowNodeScriptFacadeImpl",
            "WorkflowNodeDynamicPropertiesFacadeImpl", "WorkflowNodeOptionFacadeImpl",
            "WorkflowNodeParameterFacadeImpl", "WorkflowTestConfigurationFacadeImpl",
            "WorkflowNodeTestOutputFacadeImpl")) {

            exempt.put(facadeClass + "#*", workflowFamilyReason);
        }

        exempt.put(
            "WebhookTriggerTestApiFacadeImpl#enableTrigger",
            "Found by this scan, not named in the plan's amendment: a ninth/tenth Workflow-family site outside the "
                + "eight platform-configuration facades. Same root cause and same reason as workflowFamilyReason "
                + "above -- the method's own comment already says so ('hasPermission(#workflowId, 'Workflow', ...) "
                + "above is environment-agnostic'). Own ticket, same one as the Workflow family.");
        exempt.put(
            "WebhookTriggerTestApiFacadeImpl#disableTrigger",
            "Same as enableTrigger above -- identical annotation, identical comment, identical cause.");

        exempt.put(
            "ApiKeyFacadeImpl#update",
            "WorkspaceApiKeyGraphQlController#updateWorkspaceApiKey reaches this shared method, which carries "
                + "@PreAuthorize(\"isResourceOwner(#apiKey.id, 'ApiKey')\") -- a gate exists, and it is "
                + "ownership-only and environment-unaware. The open question is whether ownership-only is the "
                + "intended model for a workspace-scoped credential, not whether a gate is missing.");

        exempt.put(
            "WorkspaceApiKeyFacadeImpl#create",
            "Found by this scan, not named in the plan's amendment. This is site 20's OWN facade method: Task 2 "
                + "moved the environment-aware check to WorkspaceApiKeyGraphQlController#createWorkspaceApiKey (its "
                + "only caller, verified), then a later commit (352d3d2) deliberately RESTORED "
                + "hasPermission(#workspaceId,'Workspace','API_KEY_CREATE') here as defense-in-depth for any future "
                + "caller that reaches this method without going through that controller. Passing the "
                + "environment-specific check at the controller always implies passing this union check, so the "
                + "restored gate cannot produce a new denial for callers going through the controller -- but taken "
                + "on its own, this method's own annotation is genuinely environment-unaware. Documented, "
                + "intentional design, not a live gap for today's only caller.");

        exempt.put(
            "ApiCollectionFacadeImpl#getApiCollections",
            "Found by this scan, not named in the plan's amendment, and a different shape of finding than the rest "
                + "of this list: this method (workspaceId, environmentId, projectId, tagId) carries no @PreAuthorize "
                + "at all -- not merely environment-unaware, but apparently entirely unguarded. Its only production "
                + "caller, ApiCollectionApiController#getWorkspaceApiCollections (REST, path prefix "
                + "'/api-platform/internal'), also carries no annotation. More severe than this plan's scope and not "
                + "confirmed exploitable end to end (an internal-path network boundary may cover it); flagged here "
                + "for a maintainer decision and its own ticket rather than fixed in this task.");

        exempt.put(
            "ProjectDeploymentFacadeImpl#enableProjectDeploymentWorkflow",
            "Pre-existing, already-documented exemption (see the method's own javadoc and "
                + "ProjectDeploymentFacadeAuthorizationTest.testEmbeddedEnableWorkflowOverloadIsNotGated): its only "
                + "production caller is the EE embedded ConnectedUserProjectFacade, which authorizes by api key and "
                + "connected user at its own REST boundary rather than by workspace RBAC, and the call is an "
                + "in-bean self-invocation that never crosses the security proxy to the sibling three-argument "
                + "overload's hasPermission(#projectDeploymentId,'ProjectDeployment','DEPLOYMENT_EDIT'). Recorded "
                + "here too because this scan's criteria differ from VisibilityBearingSurfaceAuditTest's (this one "
                + "keys on the Environment parameter, that one on visibility-bearing return/parameter types).");

        exempt.put(
            "ProjectDeploymentFacadeImpl#updateProjectDeployment",
            "Pre-existing, already-documented exemption (see VisibilityBearingSurfaceAuditTest.EXEMPTIONS, entry "
                + "'ProjectDeploymentFacadeImpl#updateProjectDeployment(long,int,String,List,Long)'): its only "
                + "production caller is the embedded ConnectedUserProjectFacadeImpl.publishProjectWorkflow, on no "
                + "automation HTTP or GraphQL surface, for the same reason as enableProjectDeploymentWorkflow above.");

        return Map.copyOf(exempt);
    }

    private record MethodSite(String filePath, String className, String methodName, String parameterListText,
        @Nullable String preAuthorizeExpression) {
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void testEveryEnvironmentTakingMethodIsEnvironmentAware() throws IOException {
        Path serverRoot = findRepositoryRoot().resolve("server");

        assertTrue(Files.isDirectory(serverRoot), "server/ directory not found under " + serverRoot);

        List<Path> candidateFiles = findCandidateFiles(serverRoot);

        assertFalse(
            candidateFiles.isEmpty(),
            "Found no *FacadeImpl.java/*Handler.java files under " + serverRoot + " using @PreAuthorize -- the file "
                + "walk itself is broken, this is not a claim that server/ has no such classes.");

        List<String> unexpected = new ArrayList<>();

        for (Path file : candidateFiles) {
            String source = Files.readString(file);
            String text = COMMENT_PATTERN.matcher(source)
                .replaceAll("");

            // A class with no hasPermission(...)/isResourceOwner(...) anywhere carries no resource-scoped gating at
            // all -- an environment-typed parameter on one of its methods is functional plumbing, not an escaped
            // entry point, whether or not the method carries its own unrelated annotation (isTenantAdmin(),
            // hasAuthority(...)) or none. Without this, AiProviderFacadeImpl (every method hasAuthority(ADMIN)-gated)
            // and ConnectedUserProjectAdminFacadeImpl (class-level isTenantAdmin()) both false-positive: their
            // environment-typed methods are already as protected as the rest of the class, just not through the
            // resource-scope mechanism this test is about.
            if (!classHasResourceScopedAnnotation(text)) {
                continue;
            }

            for (MethodSite site : findMethodSites(file, text)) {
                if (!takesEnvironment(site.parameterListText())) {
                    continue;
                }

                if (isEnvironmentAware(site.preAuthorizeExpression())) {
                    continue;
                }

                String preAuthorizeExpression = site.preAuthorizeExpression();

                if (preAuthorizeExpression != null
                    && !isResourceScopedExpression(preAuthorizeExpression)) {

                    continue;
                }

                String siteKey = site.className() + "#" + site.methodName();
                String wildcardKey = site.className() + "#*";

                if (KNOWN_EXEMPT.containsKey(siteKey) || KNOWN_EXEMPT.containsKey(wildcardKey)) {
                    continue;
                }

                unexpected.add(
                    siteKey + " (" + site.filePath() + ") preAuthorize=" + preAuthorizeExpression);
            }
        }

        if (!unexpected.isEmpty()) {
            fail(
                "Found " + unexpected.size() + " method(s) that take an environment (an environmentId/Environment "
                    + "parameter, or an argument object known to carry one) whose @PreAuthorize is not "
                    + "environment-aware and is not in KNOWN_EXEMPT. Either re-point the annotation to an "
                    + "InEnvironment-aware expression, or add the site to KNOWN_EXEMPT with the reason it stays "
                    + "open:\n" + String.join("\n", unexpected));
        }
    }

    /**
     * Sanity check on {@link #KNOWN_EXEMPT} mirroring the pattern in {@code GuardrailSurfaceCoverageTest} and
     * {@code VisibilityBearingSurfaceAuditTest}: an empty map here would make the main test vacuous in the other
     * direction, since every entry exists because the scan found it live.
     */
    @Test
    void testKnownExemptIsNotEmpty() {
        assertFalse(KNOWN_EXEMPT.isEmpty(), "KNOWN_EXEMPT should never be empty while any open site is recorded.");
    }

    private static boolean classHasResourceScopedAnnotation(String text) {
        int searchFromIndex = 0;

        while (true) {
            int preAuthorizeIndex = text.indexOf(PRE_AUTHORIZE_HINT, searchFromIndex);

            if (preAuthorizeIndex < 0) {
                return false;
            }

            int openParenIndex = text.indexOf('(', preAuthorizeIndex);

            if (openParenIndex < 0) {
                return false;
            }

            String expression = extractParenthesizedContent(text, openParenIndex);

            if (expression != null && isResourceScopedExpression(normalizeExpression(expression))) {
                return true;
            }

            searchFromIndex = openParenIndex + 1;
        }
    }

    private static boolean isResourceScopedExpression(String expression) {
        for (String head : RESOURCE_SCOPED_PREAUTHORIZE_HEADS) {
            if (expression.startsWith(head)) {
                return true;
            }
        }

        return false;
    }

    private static boolean takesEnvironment(String parameterListText) {
        if (ENVIRONMENT_ID_PARAMETER_PATTERN.matcher(parameterListText)
            .find()) {

            return true;
        }

        if (ENVIRONMENT_TYPE_PARAMETER_PATTERN.matcher(parameterListText)
            .find()) {

            return true;
        }

        for (String carryingType : ENVIRONMENT_CARRYING_PARAMETER_TYPES) {
            if (Pattern.compile("\\b" + carryingType + "\\s+\\w+")
                .matcher(parameterListText)
                .find()) {

                return true;
            }
        }

        return false;
    }

    private static boolean isEnvironmentAware(@Nullable String preAuthorizeExpression) {
        if (preAuthorizeExpression == null) {
            return false;
        }

        if (preAuthorizeExpression.contains(ENVIRONMENT_AWARE_MARKER)) {
            return true;
        }

        return RESOLVED_TYPE_HAS_PERMISSION_PATTERN.matcher(preAuthorizeExpression)
            .find();
    }

    private static List<MethodSite> findMethodSites(Path file, String text) {
        List<MethodSite> sites = new ArrayList<>();
        List<Integer> boundaries = computeMemberBoundaries(text);
        String className = simpleClassName(file);
        Matcher methodMatcher = METHOD_SIGNATURE_START_PATTERN.matcher(text);

        while (methodMatcher.find()) {
            int methodStart = methodMatcher.start();
            String methodName = methodMatcher.group(1);
            int parametersOpenParenIndex = methodMatcher.end() - 1;

            String parameterListText = extractParenthesizedContent(text, parametersOpenParenIndex);

            if (parameterListText == null) {
                continue;
            }

            int afterParametersIndex = parametersOpenParenIndex + parameterListText.length() + 2;
            int nextBraceOrSemicolon = findNextBraceOrSemicolon(text, afterParametersIndex);

            if (nextBraceOrSemicolon < 0 || text.charAt(nextBraceOrSemicolon) != '{') {
                // No body -- an abstract or interface declaration, not a real enforcement point.
                continue;
            }

            int boundary = floorBoundary(boundaries, methodStart);
            String headerText = text.substring(boundary, methodStart);
            String preAuthorizeExpression = findPreAuthorizeExpression(headerText);

            sites
                .add(new MethodSite(file.toString(), className, methodName, parameterListText, preAuthorizeExpression));
        }

        return sites;
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

    private static int findNextBraceOrSemicolon(String text, int fromIndex) {
        for (int index = fromIndex; index < text.length(); index++) {
            char character = text.charAt(index);

            if (character == '{' || character == ';') {
                return index;
            }
        }

        return -1;
    }

    /**
     * Tracks brace depth across the whole file and records, at each point the depth returns from a member body (depth
     * 2) back to class-body level (depth 1), or a field/import statement ends at class-body level, the position right
     * after it. A method's own preceding annotation block can contain unrelated braces (an array literal inside
     * {@code @WorkflowCacheEvict(cacheNames = {...})}, for instance) without this being mistaken for a member boundary,
     * because those braces never bring the depth back down to 1.
     */
    private static List<Integer> computeMemberBoundaries(String text) {
        List<Integer> boundaries = new ArrayList<>();

        boundaries.add(0);

        int depth = 0;
        boolean classOpened = false;

        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);

            if (character == '{') {
                depth++;

                if (depth == 1 && !classOpened) {
                    classOpened = true;

                    boundaries.add(index + 1);
                }
            } else if (character == '}') {
                if (depth == 2) {
                    boundaries.add(index + 1);
                }

                depth = Math.max(0, depth - 1);
            } else if (character == ';' && depth == 1) {
                boundaries.add(index + 1);
            }
        }

        return boundaries;
    }

    private static int floorBoundary(List<Integer> boundaries, int position) {
        int result = 0;

        for (int boundary : boundaries) {
            if (boundary <= position) {
                result = boundary;
            } else {
                break;
            }
        }

        return result;
    }

    @Nullable
    private static String findPreAuthorizeExpression(String headerText) {
        int preAuthorizeIndex = headerText.indexOf(PRE_AUTHORIZE_HINT);

        if (preAuthorizeIndex < 0) {
            return null;
        }

        int openParenIndex = headerText.indexOf('(', preAuthorizeIndex);

        if (openParenIndex < 0) {
            return null;
        }

        String expression = extractParenthesizedContent(headerText, openParenIndex);

        return expression == null ? null : normalizeExpression(expression);
    }

    /**
     * The raw parenthesized content of {@code @PreAuthorize(...)} is a Java string literal, so it carries its
     * surrounding double quotes (and, for a wrapped annotation such as Task 7's
     * {@code @PreAuthorize("hasWorkspaceScopeInEnvironment(...)," + "...")}, a leading quote before a trailing
     * concatenation). {@code startsWith("hasPermission(")} on the unstripped text would silently never match anything
     * -- exactly the bug this comment exists to prevent regressing, found while writing the negative control for this
     * test: {@link #isResourceScopedExpression} and {@link #classHasResourceScopedAnnotation} both compare against a
     * literal head, so every real annotation failed that comparison and the whole "no @PreAuthorize at all" and "has
     * one but is not environment-aware" branches were vacuously inert. {@code contains}/{@code find} checks elsewhere
     * in this class do not need this, since they are not anchored to the start of the string.
     */
    private static String normalizeExpression(String expression) {
        String trimmed = expression.trim();

        return trimmed.startsWith("\"") ? trimmed.substring(1) : trimmed;
    }

    private static String simpleClassName(Path sourceFile) {
        String fileName = String.valueOf(sourceFile.getFileName());

        return fileName.substring(0, fileName.length() - ".java".length());
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

    private static List<Path> findCandidateFiles(Path serverRoot) throws IOException {
        try (Stream<Path> paths = Files.walk(serverRoot)) {
            return paths.filter(Files::isRegularFile)
                .filter(path -> path.toString()
                    .endsWith(".java"))
                .filter(EnvironmentAwareGateCoverageTest::isMainSource)
                .filter(EnvironmentAwareGateCoverageTest::isCandidateClassName)
                .filter(EnvironmentAwareGateCoverageTest::containsPreAuthorize)
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        }
    }

    private static boolean isMainSource(Path path) {
        String normalizedPath = path.toString()
            .replace(File.separatorChar, '/');

        return normalizedPath.contains("/src/main/java/") && !normalizedPath.contains("/build/");
    }

    private static boolean isCandidateClassName(Path path) {
        String className = simpleClassName(path);

        return className.endsWith("FacadeImpl") || className.endsWith("Handler");
    }

    private static boolean containsPreAuthorize(Path path) {
        try {
            return Files.readString(path)
                .contains(PRE_AUTHORIZE_HINT);
        } catch (IOException ioException) {
            throw new UncheckedIOException(ioException);
        }
    }
}
