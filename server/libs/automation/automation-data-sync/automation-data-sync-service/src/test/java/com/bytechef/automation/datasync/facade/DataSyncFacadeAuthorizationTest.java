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

package com.bytechef.automation.datasync.facade;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.automation.datasync.domain.DataSync.TriggerType;
import com.bytechef.automation.datasync.domain.DataSyncElement.Kind;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Pins every method of {@link DataSyncFacadeImpl} to its {@code @PreAuthorize} expression, the way
 * {@code AiAgentFacadeAuthorizationTest} does for the sibling agent facade. The expression is compared as a string
 * rather than exercised through a live evaluator: the identity of the gate is the thing under test, not whether a given
 * caller happens to satisfy it.
 *
 * @author Ivica Cardic
 */
class DataSyncFacadeAuthorizationTest {

    private static final String CREATE_BY_WORKSPACE = "hasPermission(#workspaceId, 'Workspace', 'DATA_SYNC_CREATE')";
    private static final String VIEW_BY_WORKSPACE = "hasPermission(#workspaceId, 'Workspace', 'DATA_SYNC_VIEW')";
    private static final String VIEW_BY_ID = "hasPermission(#id, 'DataSync', 'DATA_SYNC_VIEW')";
    private static final String EDIT_BY_ID = "hasPermission(#id, 'DataSync', 'DATA_SYNC_EDIT')";
    private static final String DELETE_BY_ID = "hasPermission(#id, 'DataSync', 'DATA_SYNC_DELETE')";
    private static final String PUBLISH_BY_ID = "hasPermission(#id, 'DataSync', 'DATA_SYNC_PUBLISH')";
    private static final String ELEMENT_EDIT = "hasPermission(#elementId, 'DataSyncElement', 'DATA_SYNC_EDIT')";

    /**
     * The broad backstop: every public method carries a {@code hasPermission(...)} {@code @PreAuthorize}, with no named
     * exemption. Unlike {@code AiAgentFacadeAuthorizationTest}, {@link DataSyncFacadeImpl} has no method that
     * legitimately stays at {@code isAuthenticated()} or ungated — so an empty exemption set is itself the assertion. A
     * method carrying {@code @PreAuthorize("isAuthenticated()")} or {@code "permitAll()"} counts as ungated here: an
     * expression that is present but does not actually check a resource is not a gate.
     */
    @Test
    void testEveryFacadeMethodIsGated() {
        List<String> ungated = Arrays.stream(DataSyncFacadeImpl.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .filter(method -> !method.isSynthetic())
            .filter(method -> {
                PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

                return preAuthorize == null || !preAuthorize.value()
                    .startsWith("hasPermission(");
            })
            .map(Method::getName)
            .toList();

        assertThat(ungated).isEmpty();
    }

    /**
     * Catches what the expression test above cannot: a seventeenth facade method added later. That test only names
     * today's sixteen methods, so a new method with its own well-formed {@code hasPermission(...)} expression would
     * pass both {@link #testEveryFacadeMethodIsGated()} and {@link #testExpressions()} without ever being argued for
     * here. Pinning the count means a new method must be added to {@link #testExpressions()} in this file before the
     * build goes green.
     */
    @Test
    void testFacadeMethodCountIsPinned() {
        assertThat(DataSyncFacade.class.getDeclaredMethods()).hasSize(16);
    }

    @Test
    void testExpressions() throws NoSuchMethodException {
        assertExpression(CREATE_BY_WORKSPACE, "createDataSync", String.class, String.class, long.class);
        assertExpression(EDIT_BY_ID, "updateDataSync", long.class, String.class, String.class);
        assertExpression(DELETE_BY_ID, "deleteDataSync", long.class);
        assertExpression(VIEW_BY_ID, "getDataSync", long.class);
        assertExpression(VIEW_BY_WORKSPACE, "getDataSyncs", long.class);
        assertExpression(EDIT_BY_ID, "updateDataSyncTrigger", long.class, TriggerType.class, Map.class);
        assertExpression(
            EDIT_BY_ID, "setDataSyncElement", long.class, Kind.class, String.class, int.class, String.class,
            Map.class, Long.class);
        assertExpression(ELEMENT_EDIT, "updateDataSyncElement", long.class, Map.class, Long.class);
        assertExpression(PUBLISH_BY_ID, "publishDataSync", long.class, String.class);
        assertExpression(VIEW_BY_ID, "getDataSyncVersions", long.class);
        assertExpression(VIEW_BY_WORKSPACE, "getDataSyncDeployments", long.class);
        assertExpression(EDIT_BY_ID, "runDataSyncDeployment", long.class, long.class);
        assertExpression(VIEW_BY_WORKSPACE, "getDataSyncTags", long.class);
        assertExpression(EDIT_BY_ID, "updateDataSyncTags", long.class, List.class);
        assertExpression(VIEW_BY_WORKSPACE, "getDataSyncDeploymentTags", long.class);
        assertExpression(EDIT_BY_ID, "updateDataSyncDeploymentTags", long.class, long.class, List.class);
    }

    private static void assertExpression(String expected, String methodName, Class<?>... parameterTypes)
        throws NoSuchMethodException {

        Method method = DataSyncFacadeImpl.class.getMethod(methodName, parameterTypes);

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).as(methodName)
            .isNotNull();
        assertThat(preAuthorize.value()).as(methodName)
            .isEqualTo(expected);
    }
}
