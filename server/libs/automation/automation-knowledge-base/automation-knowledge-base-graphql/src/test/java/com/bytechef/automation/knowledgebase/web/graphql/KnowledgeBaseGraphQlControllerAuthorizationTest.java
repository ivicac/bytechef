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

package com.bytechef.automation.knowledgebase.web.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Pins the {@code @PreAuthorize} expressions that workspace-scope knowledge-base operations (T21). Reads require
 * VIEWER, writes require EDITOR; per-id ops resolve the owning workspace via the {@code KnowledgeBase:ResourceRole}
 * token, while list/create take a {@code workspaceId} argument directly.
 *
 * @author Ivica Cardic
 */
class KnowledgeBaseGraphQlControllerAuthorizationTest {

    @Test
    void testKnowledgeBasesRequiresWorkspaceViewer() {
        assertExpression("knowledgeBases", "hasPermission(#workspaceId, 'WorkspaceRole', 'VIEWER')");
    }

    @Test
    void testKnowledgeBaseRequiresResourceViewer() {
        assertExpression("knowledgeBase", "hasPermission(#id, 'KnowledgeBase:ResourceRole', 'VIEWER')");
    }

    @Test
    void testSearchKnowledgeBaseRequiresResourceViewer() {
        assertExpression("searchKnowledgeBase", "hasPermission(#id, 'KnowledgeBase:ResourceRole', 'VIEWER')");
    }

    @Test
    void testCreateKnowledgeBaseRequiresWorkspaceEditor() {
        assertExpression("createKnowledgeBase", "hasPermission(#workspaceId, 'WorkspaceRole', 'EDITOR')");
    }

    @Test
    void testUpdateKnowledgeBaseRequiresResourceEditor() {
        assertExpression("updateKnowledgeBase", "hasPermission(#id, 'KnowledgeBase:ResourceRole', 'EDITOR')");
    }

    @Test
    void testDeleteKnowledgeBaseRequiresResourceEditor() {
        assertExpression("deleteKnowledgeBase", "hasPermission(#id, 'KnowledgeBase:ResourceRole', 'EDITOR')");
    }

    private static void assertExpression(String methodName, String expression) {
        Method method = null;

        for (Method candidate : KnowledgeBaseGraphQlController.class.getDeclaredMethods()) {
            if (candidate.getName()
                .equals(methodName)) {
                method = candidate;

                break;
            }
        }

        assertThat(method)
            .as("method %s", methodName)
            .isNotNull();

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize)
            .as("@PreAuthorize on %s", methodName)
            .isNotNull();
        assertThat(preAuthorize.value()).isEqualTo(expression);
    }
}
