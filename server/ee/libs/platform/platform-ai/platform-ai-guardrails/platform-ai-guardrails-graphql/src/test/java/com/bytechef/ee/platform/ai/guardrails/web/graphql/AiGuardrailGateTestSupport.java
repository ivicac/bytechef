/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.web.graphql;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.reflect.Method;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;

/**
 * Evaluates a controller method's actual {@code @PreAuthorize} SpEL expression -- not just its string -- the way
 * {@code EnableMethodSecurity} would, without spinning up a Spring context:
 * {@link DefaultMethodSecurityExpressionHandler} is a plain object that builds its own evaluation context from a mocked
 * {@link MethodInvocation} carrying the method (for parameter-name discovery -- this module compiles with
 * {@code -parameters}, so a reference like {@code #workspaceId} or {@code #input.workspaceId} resolves without any
 * {@code @P} annotation) and the actual call arguments.
 *
 * <p>
 * Extracted out of {@code AiGuardrailCustomRuleGraphQlControllerTest}, where this technique first proved it
 * discriminates (failing on a pre-widening gate, passing after), so
 * {@code AiGuardrailsWorkspaceSettingsGraphQlControllerTest} can reuse it instead of carrying a second copy.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
final class AiGuardrailGateTestSupport {

    private AiGuardrailGateTestSupport() {
    }

    /**
     * SPEL_INJECTION is suppressed because parsing the real annotation value is the entire point: a gate test that
     * evaluated a hand-copied expression string would still pass after someone edited the annotation, which is the
     * failure this helper exists to catch. The expression is a compile-time constant read off a class in this
     * repository, never caller-supplied text, so there is no injection channel to close.
     */
    @SuppressFBWarnings("SPEL_INJECTION")
    static boolean isAuthorized(
        Class<?> controllerType, Object controllerInstance, String methodName, Class<?>[] parameterTypes,
        Object[] arguments, Authentication authentication, PermissionEvaluator permissionEvaluator) {

        try {
            Method method = controllerType.getDeclaredMethod(methodName, parameterTypes);

            MethodInvocation methodInvocation = mock(MethodInvocation.class);

            when(methodInvocation.getMethod()).thenReturn(method);
            when(methodInvocation.getArguments()).thenReturn(arguments);
            when(methodInvocation.getThis()).thenReturn(controllerInstance);

            DefaultMethodSecurityExpressionHandler expressionHandler = new DefaultMethodSecurityExpressionHandler();

            expressionHandler.setPermissionEvaluator(permissionEvaluator);

            EvaluationContext evaluationContext =
                expressionHandler.createEvaluationContext(() -> authentication, methodInvocation);

            PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

            Expression expression = expressionHandler.getExpressionParser()
                .parseExpression(preAuthorize.value());

            return Boolean.TRUE.equals(expression.getValue(evaluationContext, Boolean.class));
        } catch (NoSuchMethodException noSuchMethodException) {
            throw new IllegalStateException(noSuchMethodException);
        }
    }
}
