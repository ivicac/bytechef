/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.contextstore.web.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.automation.contextstore.dto.CreateContextStoreSourceInput;
import com.bytechef.ee.automation.contextstore.dto.UpdateContextStoreSourceInput;
import com.bytechef.ee.automation.contextstore.facade.WorkspaceContextStoreSourceFacade;
import com.bytechef.ee.automation.contextstore.service.WorkspaceContextStoreSourceService;
import com.bytechef.ee.automation.contextstore.web.graphql.config.ContextStoreGraphQlConfigurationSharedMocks;
import com.bytechef.ee.automation.contextstore.web.graphql.config.ContextStoreGraphQlTestConfiguration;
import com.bytechef.ee.platform.contextstore.domain.ContextStoreSource;
import com.bytechef.ee.platform.contextstore.domain.ContextStoreSourceStatus;
import com.bytechef.ee.platform.contextstore.service.ContextStoreSourceService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;

/**
 * Integration tests for {@link ContextStoreSourceGraphQlController}. Mocks the facade and verifies each query/mutation
 * routes to the right facade method with the right args, plus that admin-only mutations reject anonymous callers.
 *
 * @author Ivica Cardic
 * @version ee
 */
@ContextConfiguration(classes = {
    ContextStoreGraphQlTestConfiguration.class,
    ContextStoreSourceGraphQlController.class
})
@GraphQlTest(
    controllers = ContextStoreSourceGraphQlController.class,
    properties = {
        "bytechef.context-store.enabled=true",
        "bytechef.coordinator.enabled=true",
        "bytechef.edition=ee",
        "spring.graphql.schema.locations=classpath*:/graphql/"
    })
@ContextStoreGraphQlConfigurationSharedMocks
class ContextStoreSourceGraphQlControllerIntTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @Autowired
    private ContextStoreSourceService contextStoreSourceService;

    @Autowired
    private WorkspaceContextStoreSourceFacade workspaceContextStoreSourceFacade;

    @Autowired
    private WorkspaceContextStoreSourceService workspaceContextStoreSourceService;

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void testContextStoreSourceById() {
        ContextStoreSource source = createMockSource(1L, 10L, "demo");

        when(contextStoreSourceService.fetch(1L)).thenReturn(Optional.of(source));

        graphQlTester
            .document("""
                query {
                    contextStoreSource(id: "1") {
                        id
                        workspaceId
                        name
                        status
                        enabled
                    }
                }
                """)
            .execute()
            .path("contextStoreSource.id")
            .entity(String.class)
            .isEqualTo("1")
            .path("contextStoreSource.workspaceId")
            .entity(String.class)
            .isEqualTo("10")
            .path("contextStoreSource.name")
            .entity(String.class)
            .isEqualTo("demo")
            .path("contextStoreSource.status")
            .entity(String.class)
            .isEqualTo("READY")
            .path("contextStoreSource.enabled")
            .entity(Boolean.class)
            .isEqualTo(true);

        verify(contextStoreSourceService).fetch(1L);
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void testContextStoreSourceByIdNotFound() {
        when(contextStoreSourceService.fetch(99L)).thenReturn(Optional.empty());

        graphQlTester
            .document("""
                query {
                    contextStoreSource(id: "99") {
                        id
                    }
                }
                """)
            .execute()
            .path("contextStoreSource")
            .valueIsNull();

        verify(contextStoreSourceService).fetch(99L);
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void testContextStoreSourcesByWorkspaceWithoutFilter() {
        List<ContextStoreSource> sources = List.of(
            createMockSource(1L, 10L, "first"),
            createMockSource(2L, 10L, "second"));

        when(workspaceContextStoreSourceService.getAllSourcesByWorkspaceId(10L, 1L)).thenReturn(sources);

        graphQlTester
            .document("""
                query {
                    contextStoreSources(workspaceId: "10", environmentId: "1") {
                        id
                        name
                    }
                }
                """)
            .execute()
            .path("contextStoreSources")
            .entityList(Object.class)
            .hasSize(2);

        verify(workspaceContextStoreSourceService).getAllSourcesByWorkspaceId(10L, 1L);
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void testContextStoreSourcesByWorkspaceWithEnabledFilter() {
        List<ContextStoreSource> sources = List.of(createMockSource(1L, 10L, "first"));

        when(workspaceContextStoreSourceService.getAllEnabledSourcesByWorkspaceId(10L, 1L)).thenReturn(sources);

        graphQlTester
            .document("""
                query {
                    contextStoreSources(workspaceId: "10", environmentId: "1", filter: { enabled: true }) {
                        id
                    }
                }
                """)
            .execute()
            .path("contextStoreSources")
            .entityList(Object.class)
            .hasSize(1);

        verify(workspaceContextStoreSourceService).getAllEnabledSourcesByWorkspaceId(10L, 1L);
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void testCreateContextStoreSource() {
        ContextStoreSource source = createMockSource(1L, 10L, "new-source");

        source.setWorkflowId("wf-uuid-123");

        when(workspaceContextStoreSourceFacade.create(eq(10L), any(CreateContextStoreSourceInput.class)))
            .thenReturn(source);

        graphQlTester
            .document("""
                mutation {
                    createContextStoreSource(input: {
                        workspaceId: "10",
                        contextStoreId: "7",
                        name: "new-source",
                        sourceComponentName: "github",
                        sourceComponentVersion: 1,
                        sourceClusterElementName: "issuesReader",
                        cadence: "0 0 * * * *",
                        entityName: "issue",
                        idField: "id",
                        indexedFields: { title: "string" }
                    }) {
                        id
                        workflowId
                    }
                }
                """)
            .execute()
            .path("createContextStoreSource.id")
            .entity(String.class)
            .isEqualTo("1")
            .path("createContextStoreSource.workflowId")
            .entity(String.class)
            .isEqualTo("wf-uuid-123");

        ArgumentCaptor<CreateContextStoreSourceInput> captor =
            ArgumentCaptor.forClass(CreateContextStoreSourceInput.class);

        verify(workspaceContextStoreSourceFacade).create(eq(10L), captor.capture());

        CreateContextStoreSourceInput captured = captor.getValue();

        assertThat(captured.contextStoreId()).isEqualTo(7L);
        assertThat(captured.name()).isEqualTo("new-source");
        assertThat(captured.sourceComponentName()).isEqualTo("github");
        assertThat(captured.sourceComponentVersion()).isEqualTo(1);
        assertThat(captured.sourceClusterElementName()).isEqualTo("issuesReader");
        assertThat(captured.cadence()).isEqualTo("0 0 * * * *");
        assertThat(captured.entityName()).isEqualTo("issue");
        assertThat(captured.idField()).isEqualTo("id");
        assertThat(captured.indexedFields()
            .get("title")).isEqualTo("string");
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void testUpdateContextStoreSource() {
        ContextStoreSource source = createMockSource(1L, 10L, "renamed");

        when(workspaceContextStoreSourceFacade.update(eq(10L), eq(1L), any(UpdateContextStoreSourceInput.class)))
            .thenReturn(source);

        graphQlTester
            .document("""
                mutation {
                    updateContextStoreSource(id: "1", input: { name: "renamed", cadence: "0 */5 * * * *" }) {
                        id
                        name
                    }
                }
                """)
            .execute()
            .path("updateContextStoreSource.id")
            .entity(String.class)
            .isEqualTo("1")
            .path("updateContextStoreSource.name")
            .entity(String.class)
            .isEqualTo("renamed");

        ArgumentCaptor<UpdateContextStoreSourceInput> captor =
            ArgumentCaptor.forClass(UpdateContextStoreSourceInput.class);

        verify(workspaceContextStoreSourceFacade).update(eq(10L), eq(1L), captor.capture());

        UpdateContextStoreSourceInput captured = captor.getValue();

        assertThat(captured.name()).isEqualTo("renamed");
        assertThat(captured.cadence()).isEqualTo("0 */5 * * * *");
        assertThat(captured.enabled()).isNull();
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void testDeleteContextStoreSource() {
        // Wire workspace lookup so the controller can resolve the workspaceId off the source id.
        when(workspaceContextStoreSourceService.fetchWorkspaceIdByContextStoreSourceId(1L))
            .thenReturn(Optional.of(10L));

        graphQlTester
            .document("""
                mutation {
                    deleteContextStoreSource(id: "1")
                }
                """)
            .execute()
            .path("deleteContextStoreSource")
            .entity(Boolean.class)
            .isEqualTo(true);

        verify(workspaceContextStoreSourceFacade).delete(10L, 1L);
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void testRefreshContextStoreSourceReturnsJobExecutionId() {
        when(workspaceContextStoreSourceService.fetchWorkspaceIdByContextStoreSourceId(1L))
            .thenReturn(Optional.of(10L));
        when(workspaceContextStoreSourceFacade.refreshNow(10L, 1L)).thenReturn(42L);

        graphQlTester
            .document("""
                mutation {
                    refreshContextStoreSource(id: "1")
                }
                """)
            .execute()
            .path("refreshContextStoreSource")
            .entity(String.class)
            .isEqualTo("42");

        verify(workspaceContextStoreSourceFacade).refreshNow(10L, 1L);
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void testSetContextStoreSourceEnabled() {
        ContextStoreSource source = createMockSource(1L, 10L, "demo");

        source.setEnabled(false);

        when(contextStoreSourceService.get(1L)).thenReturn(source);

        graphQlTester
            .document("""
                mutation {
                    setContextStoreSourceEnabled(id: "1", enabled: false) {
                        id
                        enabled
                    }
                }
                """)
            .execute()
            .path("setContextStoreSourceEnabled.id")
            .entity(String.class)
            .isEqualTo("1")
            .path("setContextStoreSourceEnabled.enabled")
            .entity(Boolean.class)
            .isEqualTo(false);

        verify(workspaceContextStoreSourceFacade).setEnabled(10L, 1L, false);
        verify(contextStoreSourceService).get(1L);
    }

    @Test
    @WithAnonymousUser
    void testDeleteContextStoreSourceAsAnonymousIsDenied() {
        graphQlTester
            .document("""
                mutation {
                    deleteContextStoreSource(id: "1")
                }
                """)
            .execute()
            .errors()
            .expect(error -> true)
            .verify();

        verify(workspaceContextStoreSourceFacade, never()).delete(anyLong(), anyLong());
    }

    private ContextStoreSource createMockSource(Long id, Long workspaceId, String name) {
        ContextStoreSource source = new ContextStoreSource();

        source.setId(id);
        source.setName(name);

        when(workspaceContextStoreSourceService.fetchWorkspaceIdByContextStoreSourceId(id))
            .thenReturn(Optional.of(workspaceId));
        source.setSourceComponentName("github");
        source.setSourceComponentVersion(1);
        source.setSourceClusterElementName("issuesReader");
        source.setCadence("0 0 * * * *");
        source.setStatus(ContextStoreSourceStatus.READY);
        source.setEnabled(true);

        return source;
    }
}
