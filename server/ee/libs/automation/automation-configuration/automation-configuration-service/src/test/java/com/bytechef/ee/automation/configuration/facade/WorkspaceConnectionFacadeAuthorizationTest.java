/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.configuration.facade;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Pins the {@link PreAuthorize} annotations on the EE {@link WorkspaceConnectionFacadeImpl} visibility mutations. The
 * guards were moved off {@code ConnectionVisibilityGraphQlController} onto the facade so they protect every caller of
 * the facade, not just the GraphQL entry point; this reflection test catches a refactor that silently drops one.
 *
 * <p>
 * Also pins the <em>absence</em> of {@code @PreAuthorize} on {@code demoteToPrivate} — that mutation's authorization is
 * done programmatically inside the facade (admin OR the connection's creator) so a WORKSPACE connection remains
 * recoverable when its workspace has no admins left (orphan-recovery path). An over-eager future refactor that added
 * {@code @PreAuthorize("hasAuthority(ADMIN)")} would break that recovery flow.
 *
 * <p>
 * Runtime enforcement of these expressions by Spring Security is proven generically by
 * {@code PreAuthorizeProxyEnforcementIntTest}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class WorkspaceConnectionFacadeAuthorizationTest {

    private static final String ADMIN_EXPRESSION = "hasAuthority(\"ROLE_ADMIN\")";

    @Test
    void testPromoteToWorkspaceRequiresAdmin() {
        assertAdminOnly("promoteToWorkspace");
    }

    @Test
    void testPromoteAllPrivateToWorkspaceRequiresAdmin() {
        assertAdminOnly("promoteAllPrivateToWorkspace");
    }

    @Test
    void testDemoteToPrivateIsNotAnnotatedSoFacadeCanDoAdminOrCreatorCheck() {
        assertThat(findMethod("demoteToPrivate").getAnnotation(PreAuthorize.class))
            .as(
                "demoteToPrivate must NOT have @PreAuthorize — the facade enforces admin-OR-creator so connections "
                    + "remain recoverable when a workspace has no admins. See CLAUDE.md.")
            .isNull();
    }

    private static void assertAdminOnly(String methodName) {
        PreAuthorize preAuthorize = findMethod(methodName).getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize)
            .as(
                "Method '%s' must have @PreAuthorize(hasAuthority(\"ROLE_ADMIN\")); dropping it would silently let "
                    + "every authenticated user perform an admin-only operation.",
                methodName)
            .isNotNull();

        assertThat(preAuthorize.value())
            .as("Method '%s' @PreAuthorize expression must require ROLE_ADMIN", methodName)
            .isEqualTo(ADMIN_EXPRESSION);
    }

    private static Method findMethod(String methodName) {
        List<Method> matches = Arrays.stream(WorkspaceConnectionFacadeImpl.class.getDeclaredMethods())
            .filter(method -> !method.isSynthetic())
            .filter(method -> method.getName()
                .equals(methodName))
            .toList();

        assertThat(matches)
            .as("Expected exactly one non-synthetic '%s' method on the EE WorkspaceConnectionFacadeImpl", methodName)
            .hasSize(1);

        return matches.get(0);
    }
}
