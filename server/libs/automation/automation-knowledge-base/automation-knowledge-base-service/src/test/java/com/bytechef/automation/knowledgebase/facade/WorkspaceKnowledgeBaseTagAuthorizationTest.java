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

package com.bytechef.automation.knowledgebase.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bytechef.automation.knowledgebase.domain.WorkspaceKnowledgeBase;
import com.bytechef.automation.knowledgebase.service.WorkspaceKnowledgeBaseService;
import com.bytechef.platform.knowledgebase.facade.KnowledgeBaseDocumentFacade;
import com.bytechef.platform.knowledgebase.facade.KnowledgeBaseFacade;
import com.bytechef.platform.knowledgebase.facade.KnowledgeBaseTagFacade;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseStorageService;
import com.bytechef.platform.tag.domain.Tag;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Behavioural coverage for the gate on {@code getKnowledgeBaseTagsByKnowledgeBase}.
 *
 * <p>
 * The GraphQL query behind it, {@code knowledgeBaseTagsByKnowledgeBase}, took no argument identifying what it may see
 * and carried no authorization of any kind: it called {@code KnowledgeBaseTagFacade} directly over a {@code findAll()}
 * of {@code knowledge_base}. {@code /graphql} is only {@code .authenticated()}, and the embedded API-key configurer
 * routes a connected user's JWT there with no authorities at all, so the caller that reached it could be an embedded
 * end user reading every workspace's, both pools' and every account's knowledge bases at once.
 *
 * <p>
 * Both halves matter. The denial proves the gate is reached before any collaborator is touched -- a gate that runs
 * after the read has happened is not a gate. The permit proves it is not a gate that denies everybody, which would look
 * secure and be a broken tag filter.
 *
 * @author Ivica Cardic
 */
@SpringBootTest(classes = WorkspaceKnowledgeBaseTagAuthorizationTest.Config.class)
class WorkspaceKnowledgeBaseTagAuthorizationTest {

    private static final long WORKSPACE_ID = 5L;

    @Autowired
    private KnowledgeBaseTagFacade knowledgeBaseTagFacade;

    @Autowired
    private RecordingPermissionEvaluator permissionEvaluator;

    @Autowired
    private WorkspaceKnowledgeBaseFacade workspaceKnowledgeBaseFacade;

    @Autowired
    private WorkspaceKnowledgeBaseService workspaceKnowledgeBaseService;

    @BeforeEach
    void setUp() {
        reset(knowledgeBaseTagFacade, workspaceKnowledgeBaseService);

        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(
                "user@localhost.com", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testGetKnowledgeBaseTagsByKnowledgeBaseDeniesCallerWithoutWorkspaceViewScope() {
        permissionEvaluator.permit(false);

        assertThatThrownBy(() -> workspaceKnowledgeBaseFacade.getKnowledgeBaseTagsByKnowledgeBase(WORKSPACE_ID))
            .isInstanceOf(AccessDeniedException.class);

        assertThat(permissionEvaluator.getCallCount()).isEqualTo(1);
        assertThat(permissionEvaluator.getObservedTargetId()).isEqualTo(WORKSPACE_ID);
        assertThat(permissionEvaluator.getObservedTargetType()).isEqualTo("Workspace");
        assertThat(permissionEvaluator.getObservedPermission()).isEqualTo("KNOWLEDGE_BASE_VIEW");

        verifyNoInteractions(knowledgeBaseTagFacade, workspaceKnowledgeBaseService);
    }

    @Test
    void testGetKnowledgeBaseTagsByKnowledgeBasePermitsCallerWithWorkspaceViewScope() {
        permissionEvaluator.permit(true);

        when(workspaceKnowledgeBaseService.getWorkspaceKnowledgeBases(WORKSPACE_ID))
            .thenReturn(List.of(new WorkspaceKnowledgeBase(1L, WORKSPACE_ID)));
        when(knowledgeBaseTagFacade.getTagsByKnowledgeBaseIds(List.of(1L)))
            .thenReturn(Map.of(1L, List.of(new Tag("alpha"))));

        Map<Long, List<Tag>> tagsByKnowledgeBaseId =
            workspaceKnowledgeBaseFacade.getKnowledgeBaseTagsByKnowledgeBase(WORKSPACE_ID);

        assertThat(tagsByKnowledgeBaseId).containsOnlyKeys(1L);
        assertThat(permissionEvaluator.getCallCount()).isEqualTo(1);
        assertThat(permissionEvaluator.getObservedTargetId()).isEqualTo(WORKSPACE_ID);
        assertThat(permissionEvaluator.getObservedPermission()).isEqualTo("KNOWLEDGE_BASE_VIEW");
    }

    /**
     * The write beside the listing, and the sharper half: the mutation took a knowledge base id and never checked it,
     * so the same principal could rewrite the tags of any knowledge base in the tenant by naming its id.
     */
    @Test
    void testUpdateKnowledgeBaseTagsDeniesCallerWithoutKnowledgeBaseEditScope() {
        permissionEvaluator.permit(false);

        assertThatThrownBy(
            () -> workspaceKnowledgeBaseFacade.updateKnowledgeBaseTags(7L, List.of(new Tag("alpha"))))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(permissionEvaluator.getCallCount()).isEqualTo(1);
        assertThat(permissionEvaluator.getObservedTargetId()).isEqualTo(7L);
        assertThat(permissionEvaluator.getObservedTargetType()).isEqualTo("KnowledgeBase");
        assertThat(permissionEvaluator.getObservedPermission()).isEqualTo("KNOWLEDGE_BASE_EDIT");

        verifyNoInteractions(knowledgeBaseTagFacade);
    }

    @Test
    void testUpdateKnowledgeBaseTagsPermitsEditorOfThatKnowledgeBase() {
        permissionEvaluator.permit(true);

        List<Tag> tags = List.of(new Tag("alpha"));

        workspaceKnowledgeBaseFacade.updateKnowledgeBaseTags(7L, tags);

        verify(knowledgeBaseTagFacade).updateTags(7L, tags);

        assertThat(permissionEvaluator.getCallCount()).isEqualTo(1);
        assertThat(permissionEvaluator.getObservedTargetId()).isEqualTo(7L);
        assertThat(permissionEvaluator.getObservedPermission()).isEqualTo("KNOWLEDGE_BASE_EDIT");
    }

    @SpringBootConfiguration
    @EnableMethodSecurity(proxyTargetClass = true)
    static class Config {

        @Bean
        KnowledgeBaseDocumentFacade knowledgeBaseDocumentFacade() {
            return mock(KnowledgeBaseDocumentFacade.class);
        }

        @Bean
        KnowledgeBaseDocumentService knowledgeBaseDocumentService() {
            return mock(KnowledgeBaseDocumentService.class);
        }

        @Bean
        KnowledgeBaseFacade knowledgeBaseFacade() {
            return mock(KnowledgeBaseFacade.class);
        }

        @Bean
        KnowledgeBaseService knowledgeBaseService() {
            return mock(KnowledgeBaseService.class);
        }

        @Bean
        KnowledgeBaseStorageService knowledgeBaseStorageService() {
            return mock(KnowledgeBaseStorageService.class);
        }

        @Bean
        KnowledgeBaseTagFacade knowledgeBaseTagFacade() {
            return mock(KnowledgeBaseTagFacade.class);
        }

        @Bean
        MethodSecurityExpressionHandler methodSecurityExpressionHandler(
            RecordingPermissionEvaluator permissionEvaluator) {

            DefaultMethodSecurityExpressionHandler methodSecurityExpressionHandler =
                new DefaultMethodSecurityExpressionHandler();

            methodSecurityExpressionHandler.setPermissionEvaluator(permissionEvaluator);

            return methodSecurityExpressionHandler;
        }

        @Bean
        RecordingPermissionEvaluator permissionEvaluator() {
            return new RecordingPermissionEvaluator();
        }

        @Bean
        WorkspaceKnowledgeBaseFacade workspaceKnowledgeBaseFacade(
            KnowledgeBaseDocumentFacade knowledgeBaseDocumentFacade,
            KnowledgeBaseDocumentService knowledgeBaseDocumentService, KnowledgeBaseFacade knowledgeBaseFacade,
            KnowledgeBaseService knowledgeBaseService, KnowledgeBaseStorageService knowledgeBaseStorageService,
            KnowledgeBaseTagFacade knowledgeBaseTagFacade,
            WorkspaceKnowledgeBaseService workspaceKnowledgeBaseService) {

            return new WorkspaceKnowledgeBaseFacadeImpl(
                knowledgeBaseDocumentFacade, knowledgeBaseDocumentService, knowledgeBaseFacade, knowledgeBaseService,
                knowledgeBaseStorageService, knowledgeBaseTagFacade, workspaceKnowledgeBaseService);
        }

        @Bean
        WorkspaceKnowledgeBaseService workspaceKnowledgeBaseService() {
            return mock(WorkspaceKnowledgeBaseService.class);
        }
    }

    /**
     * Records what the SpEL expression handed the evaluator, so the tests can assert the gate keys on the caller's own
     * workspace id under the intended target type and scope rather than on some constant that would pass either way.
     */
    static final class RecordingPermissionEvaluator implements PermissionEvaluator {

        private int callCount;
        private Object observedTargetId;
        private String observedPermission;
        private String observedTargetType;
        private boolean permitted;

        @Override
        public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
            throw new UnsupportedOperationException("The expression under test is the target-id form");
        }

        @Override
        public boolean hasPermission(
            Authentication authentication, Serializable targetId, String targetType, Object permission) {

            callCount++;
            observedTargetId = targetId;
            observedTargetType = targetType;
            observedPermission = String.valueOf(permission);

            return permitted;
        }

        int getCallCount() {
            return callCount;
        }

        String getObservedPermission() {
            return observedPermission;
        }

        Object getObservedTargetId() {
            return observedTargetId;
        }

        String getObservedTargetType() {
            return observedTargetType;
        }

        void permit(boolean permitted) {
            this.permitted = permitted;
            this.callCount = 0;
            this.observedTargetId = null;
            this.observedTargetType = null;
            this.observedPermission = null;
        }
    }
}
