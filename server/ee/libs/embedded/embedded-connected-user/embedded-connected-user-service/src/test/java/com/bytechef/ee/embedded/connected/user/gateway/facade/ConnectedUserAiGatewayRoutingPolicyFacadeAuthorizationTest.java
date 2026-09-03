/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.connected.user.gateway.facade;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Pins the {@link PreAuthorize} annotations on {@link ConnectedUserAiGatewayRoutingPolicyFacadeImpl}. Both {@code
 * bind} and {@code unbind} require the {@code ROLE_ADMIN} authority, matching the routing-policy facade family's
 * existing guard style ({@code AiGatewayRoutingPolicyFacadeImpl}, {@code WorkspaceAiGatewayRoutingPolicyFacadeImpl}).
 *
 * <p>
 * Plain unit tests run without Spring's method-security AOP and {@link ConnectedUserAiGatewayRoutingPolicyFacadeTest}
 * constructs the impl directly, so neither exercises method security -- this reflection test catches a refactor that
 * silently drops or weakens a guard. {@link #testEveryFacadeMethodRequiresPreAuthorize()} sweeps every method declared
 * on {@link ConnectedUserAiGatewayRoutingPolicyFacade} so a newly added facade method with no annotation cannot pass
 * silently.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class ConnectedUserAiGatewayRoutingPolicyFacadeAuthorizationTest {

    private static final String ADMIN_EXPRESSION = "hasAuthority(\"ROLE_ADMIN\")";

    @Test
    void testBindRequiresAdmin() {
        assertExpression("bind", ADMIN_EXPRESSION);
    }

    @Test
    void testUnbindRequiresAdmin() {
        assertExpression("unbind", ADMIN_EXPRESSION);
    }

    @Test
    void testEveryFacadeMethodRequiresPreAuthorize() {
        List<Method> interfaceMethods = Arrays.asList(ConnectedUserAiGatewayRoutingPolicyFacade.class.getMethods());

        assertThat(interfaceMethods)
            .as(
                "Sanity check: ConnectedUserAiGatewayRoutingPolicyFacade must declare at least one method, or this "
                    + "sweep is vacuous")
            .isNotEmpty();

        for (Method interfaceMethod : interfaceMethods) {
            Method implMethod = resolveImplMethod(interfaceMethod);

            assertThat(implMethod.getAnnotation(PreAuthorize.class))
                .as(
                    "Method '%s' on ConnectedUserAiGatewayRoutingPolicyFacadeImpl must have @PreAuthorize -- a newly "
                        + "added facade method with no annotation would otherwise pass every other test in this class",
                    interfaceMethod.getName())
                .isNotNull();
        }
    }

    private static Method resolveImplMethod(Method interfaceMethod) {
        try {
            return ConnectedUserAiGatewayRoutingPolicyFacadeImpl.class.getDeclaredMethod(
                interfaceMethod.getName(), interfaceMethod.getParameterTypes());
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(
                "ConnectedUserAiGatewayRoutingPolicyFacadeImpl does not implement " + interfaceMethod, exception);
        }
    }

    private static void assertExpression(String methodName, String expression) {
        PreAuthorize preAuthorize = findMethod(methodName).getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize)
            .as("Method '%s' must have @PreAuthorize", methodName)
            .isNotNull();

        assertThat(preAuthorize.value())
            .as("Method '%s' @PreAuthorize expression", methodName)
            .isEqualTo(expression);
    }

    private static Method findMethod(String methodName) {
        List<Method> matches = Arrays.stream(ConnectedUserAiGatewayRoutingPolicyFacadeImpl.class.getDeclaredMethods())
            .filter(method -> !method.isSynthetic())
            .filter(method -> method.getName()
                .equals(methodName))
            .toList();

        assertThat(matches)
            .as(
                "Expected exactly one non-synthetic '%s' method on ConnectedUserAiGatewayRoutingPolicyFacadeImpl",
                methodName)
            .hasSize(1);

        return matches.get(0);
    }
}
