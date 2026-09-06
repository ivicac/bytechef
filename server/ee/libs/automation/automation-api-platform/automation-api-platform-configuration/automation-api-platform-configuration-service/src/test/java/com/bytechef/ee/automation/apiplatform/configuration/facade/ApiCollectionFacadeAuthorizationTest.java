/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.apiplatform.configuration.facade;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.ee.automation.apiplatform.configuration.dto.ApiCollectionDTO;
import com.bytechef.ee.automation.apiplatform.configuration.dto.ApiCollectionEndpointDTO;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Pins the authorization expressions on {@link ApiCollectionFacadeImpl}.
 * <p>
 * This facade carried gates on 2 of its 11 public methods, and its three REST controllers carried none at all across
 * ten endpoints. Every mutation was ungated — create, update, delete, endpoints, tags — as was every by-id read,
 * including {@code getOpenApiSpecification}, which returns the collection's full published API description. A by-id
 * method takes a bare {@code long} and resolves the row itself, so any authenticated user of any workspace could read
 * or modify any collection in the tenant: a cross-workspace IDOR across create/read/update/delete. The route resolves
 * to {@code /api/automation/api-platform/internal/...} and {@code SecurityConfiguration} matches {@code /api/**} with
 * {@code .authenticated()} alone, so nothing between an authenticated request and the query stood in the way.
 * <p>
 * <b>Why only two were gated.</b> Not a deliberate shared-facade exemption — CLAUDE.md's convention explains an
 * unguarded facade when a guarded API facade sits above it, and there is no {@code ApiCollectionApiFacade}. Those two
 * are simply the ones ticket 732's environment sweep happened to touch, because that sweep keyed on methods taking an
 * environment argument and only {@code getApiCollections} does. See
 * {@code docs/superpowers/specs/2026-09-06-api-collection-authorization-design.md}.
 * <p>
 * <b>The scopes are {@code API_PLATFORM_*}, not {@code WORKSPACE_VIEW}.</b> Publishing an API is at least as
 * consequential as creating an MCP server, which has its own family; and although every collection is backed by a
 * synthetic {@code __API_COLLECTION__} {@code ProjectDeployment}, that backing is an implementation detail the UI never
 * shows, so granting deployment rights must not silently confer API-publishing rights.
 * <p>
 * <b>The by-id gates are environment-aware, and that is not free.</b> They read {@code hasPermission(#id,
 * 'ApiCollection', ...)} with no environment argument, which is environment-aware only because
 * {@code ApiCollectionEnvironmentResolver} is registered — the fourth such resolver, reading the environment off the
 * backing deployment. Without it {@code hasResourceScope} would fall back to the union check and authorize a
 * Development-only member against a Production collection, which is the defect ticket 732 spent its sweep closing.
 * {@code EnvironmentAwareGateCoverageTest.RESOLVED_RESOURCE_TYPES} must list {@code ApiCollection} for the same reason.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class ApiCollectionFacadeAuthorizationTest {

    /**
     * Keyed on the project, since the DTO carries no {@code workspaceId}, and on the DTO's own {@code environment} —
     * the same value the body writes onto the backing deployment, so gate and body agree on which environment this call
     * acts in.
     * <p>
     * Pointing an expression at a client-supplied {@code Environment} is only safe because
     * {@code AutomationMethodSecurityExpressionRoot#hasResourceScopeInEnvironment} now treats a null as the
     * environment-unaware check. Before that guard it reached {@code environment.ordinal()} inside
     * {@code WorkspaceScopeCacheService} and a null turned a 403 into a 500, which is why this gate was originally left
     * environment-unaware.
     */
    private static final String CREATE_GATE =
        "hasResourceScopeInEnvironment(#apiCollectionDTO.projectId, 'Project', 'API_PLATFORM_CREATE', "
            + "#apiCollectionDTO.environment)";

    @Test
    void testCreateApiCollectionRequiresProjectScopedCreateInTheNamedEnvironment() throws NoSuchMethodException {
        assertPreAuthorize(method("createApiCollection", ApiCollectionDTO.class), CREATE_GATE);
    }

    @Test
    void testGetApiCollectionsRequiresApiPlatformViewInTheNamedEnvironment() throws NoSuchMethodException {
        assertPreAuthorize(
            method("getApiCollections", long.class, Long.class, Long.class, Long.class),
            "hasWorkspaceScopeInEnvironmentId(#workspaceId, 'API_PLATFORM_VIEW', #environmentId)");
    }

    @Test
    void testGetApiCollectionTagsRequiresApiPlatformView() throws NoSuchMethodException {
        assertPreAuthorize(
            method("getApiCollectionTags", long.class),
            "hasPermission(#workspaceId, 'Workspace', 'API_PLATFORM_VIEW')");
    }

    @Test
    void testByIdReadsRequireApiPlatformViewOnTheCollection() throws NoSuchMethodException {
        String expected = "hasPermission(#id, 'ApiCollection', 'API_PLATFORM_VIEW')";

        assertPreAuthorize(method("getApiCollection", long.class), expected);
        assertPreAuthorize(method("getOpenApiSpecification", long.class), expected);
    }

    @Test
    void testByIdMutationsRequireApiPlatformEditOnTheCollection() throws NoSuchMethodException {
        assertPreAuthorize(
            method("deleteApiCollection", long.class), "hasPermission(#id, 'ApiCollection', 'API_PLATFORM_EDIT')");
        assertPreAuthorize(
            method("updateApiCollectionTags", long.class, List.class),
            "hasPermission(#id, 'ApiCollection', 'API_PLATFORM_EDIT')");
        assertPreAuthorize(
            method("updateApiCollection", ApiCollectionDTO.class),
            "hasPermission(#apiCollectionDTO.id, 'ApiCollection', 'API_PLATFORM_EDIT')");
    }

    @Test
    void testEndpointMutationsAreKeyedOnTheParentCollection() throws NoSuchMethodException {
        String expected = "hasPermission(#apiCollectionEndpointDTO.apiCollectionId, 'ApiCollection', "
            + "'API_PLATFORM_EDIT')";

        assertPreAuthorize(method("createApiCollectionEndpoint", ApiCollectionEndpointDTO.class), expected);
        assertPreAuthorize(method("updateApiCollectionEndpoint", ApiCollectionEndpointDTO.class), expected);
    }

    /**
     * The assertion the individual pins above cannot make: that a method added later is gated at all. Each pin fails
     * only if an expression it names changes; none of them notices a twelfth method appearing with no annotation, which
     * is exactly how this facade reached 9 of 11 ungated. Keyed on "carries some gate", not on which one, so adding a
     * method does not require editing this test — only leaving it ungated does.
     */
    @Test
    void testEveryPublicMethodIsGated() {
        List<String> ungated = Arrays.stream(ApiCollectionFacadeImpl.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .filter(method -> !method.isSynthetic())
            .filter(method -> !method.isAnnotationPresent(PreAuthorize.class))
            .map(Method::getName)
            .distinct()
            .sorted()
            .toList();

        assertThat(ungated)
            .as(
                "every public method on ApiCollectionFacadeImpl must carry @PreAuthorize; these do not, and a by-id "
                    + "method resolves its own row, so an ungated one is reachable across workspaces")
            .isEmpty();
    }

    private static Method method(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        return ApiCollectionFacadeImpl.class.getMethod(name, parameterTypes);
    }

    private static void assertPreAuthorize(Method method, String expectedExpression) {
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize)
            .as("%s must carry a @PreAuthorize gate", method.getName())
            .isNotNull();

        assertThat(preAuthorize.value())
            .as("%s's authorization expression", method.getName())
            .isEqualTo(expectedExpression);
    }
}
