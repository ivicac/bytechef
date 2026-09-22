/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.facade;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.platform.configuration.domain.Environment;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Pins the {@code @PreAuthorize} that prevents an embedded caller authenticated for one end user from reading another
 * end user's connections by passing a different {@code externalUserId} in the path (T23, connection sub-path). The
 * authenticated principal's name is the connected user's externalId, so the path value must equal it.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class ConnectedUserConnectionFacadeAuthorizationTest {

    private static final String MATCHING_EXTERNAL_USER_ID = "#externalUserId == authentication.name";

    @Test
    void testGetConnectedUserConnectionsRequiresMatchingExternalUserId() {
        assertExpression(
            "getConnectedUserConnections", MATCHING_EXTERNAL_USER_ID, String.class, Environment.class, String.class,
            List.class);
    }

    @Test
    void testGetOwnedConnectionIdsRequiresMatchingExternalUserId() {
        assertExpression("getOwnedConnectionIds", MATCHING_EXTERNAL_USER_ID, String.class, Environment.class);
    }

    private static void assertExpression(String methodName, String expression, Class<?>... parameterTypes) {
        Method method;

        try {
            method = ConnectedUserConnectionFacadeImpl.class.getDeclaredMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError("method " + methodName + " not found", exception);
        }

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize)
            .as("@PreAuthorize on %s", methodName)
            .isNotNull();
        assertThat(preAuthorize.value()).isEqualTo(expression);
    }
}
