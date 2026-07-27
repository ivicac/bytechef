/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProject;
import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProjectWorkflow;
import com.bytechef.ee.embedded.configuration.dto.ConnectedUserCodeWorkflowReferenceDTO;
import com.bytechef.ee.embedded.configuration.repository.ConnectedUserProjectWorkflowRepository;
import com.bytechef.ee.embedded.configuration.service.ConnectedUserProjectService;
import com.bytechef.ee.embedded.connected.user.domain.ConnectedUser;
import com.bytechef.ee.embedded.connected.user.service.ConnectedUserService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class ConnectedUserCodeWorkflowReferenceAdminFacadeTest {

    @Mock
    private ConnectedUserProjectService connectedUserProjectService;

    @Mock
    private ConnectedUserProjectWorkflowRepository connectedUserProjectWorkflowRepository;

    @Mock
    private ConnectedUserService connectedUserService;

    @InjectMocks
    private ConnectedUserCodeWorkflowReferenceAdminFacadeImpl connectedUserCodeWorkflowReferenceAdminFacade;

    @Test
    void testGetReferencesJoinsBackToTheOwningConnectedUser() {
        ConnectedUserProjectWorkflow reference = new ConnectedUserProjectWorkflow();

        reference.setConnectedUserProjectId(1L);
        reference.setCatalogWorkflowUuid("uuid-1");
        reference.setEnabled(true);
        reference.setDangling(false);

        when(connectedUserProjectWorkflowRepository.findAllByCatalogWorkflowUuidIn(Set.of("uuid-1")))
            .thenReturn(List.of(reference));

        ConnectedUserProject connectedUserProject = new ConnectedUserProject();

        connectedUserProject.setId(1L);
        connectedUserProject.setConnectedUserId(2L);

        when(connectedUserProjectService.getConnectedUserProject(1L))
            .thenReturn(connectedUserProject);

        ConnectedUser connectedUser = new ConnectedUser();

        connectedUser.setExternalId("ext-1");

        when(connectedUserService.getConnectedUser(2L))
            .thenReturn(connectedUser);

        List<ConnectedUserCodeWorkflowReferenceDTO> result =
            connectedUserCodeWorkflowReferenceAdminFacade.getReferences(Set.of("uuid-1"));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst()
            .externalUserId()).isEqualTo("ext-1");
    }
}
