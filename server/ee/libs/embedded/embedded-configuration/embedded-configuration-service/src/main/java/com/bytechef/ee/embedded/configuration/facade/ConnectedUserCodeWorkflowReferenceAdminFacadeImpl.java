/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.facade;

import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProject;
import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProjectWorkflow;
import com.bytechef.ee.embedded.configuration.dto.ConnectedUserCodeWorkflowReferenceDTO;
import com.bytechef.ee.embedded.configuration.repository.ConnectedUserProjectWorkflowRepository;
import com.bytechef.ee.embedded.configuration.service.ConnectedUserProjectService;
import com.bytechef.ee.embedded.connected.user.domain.ConnectedUser;
import com.bytechef.ee.embedded.connected.user.service.ConnectedUserService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Admin-only read seam over automation-bridge references, joined back to the connected user each reference belongs to
 * -- the direction {@link com.bytechef.ee.embedded.configuration.facade.ConnectedUserCodeWorkflowReferenceFacade}
 * deliberately does not serve (it reads per connected user, not per catalog workflow). Gated the same way
 * {@code AutomationWorkflowProjectAdminFacade} already is: a plain {@code isTenantAdmin()} guard rather than a role
 * literal, since the embedded admin console has no {@code ROLE_ADMIN} authority of its own.
 *
 * <p>
 * The join back to {@link ConnectedUser} goes through {@link ConnectedUserProjectService} rather than a new method on
 * {@link ConnectedUserService} that would accept a {@link ConnectedUserProjectWorkflow}:
 * {@code embedded-configuration-api} already depends on {@code embedded-connected-user-api} (for
 * {@link ConnectedUserProject}), so the reverse dependency that a {@link ConnectedUserProjectWorkflow}-typed parameter
 * on {@link ConnectedUserService} would require is a circular module dependency. Composing the two existing lookups --
 * {@code ConnectedUserProjectService#getConnectedUserProject} then {@code ConnectedUserService#getConnectedUser} --
 * reaches the same row without changing any module's dependency graph.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Service
@ConditionalOnEEVersion
public class ConnectedUserCodeWorkflowReferenceAdminFacadeImpl
    implements ConnectedUserCodeWorkflowReferenceAdminFacade {

    private final ConnectedUserProjectService connectedUserProjectService;
    private final ConnectedUserProjectWorkflowRepository connectedUserProjectWorkflowRepository;
    private final ConnectedUserService connectedUserService;

    @SuppressFBWarnings("EI")
    public ConnectedUserCodeWorkflowReferenceAdminFacadeImpl(
        ConnectedUserProjectService connectedUserProjectService,
        ConnectedUserProjectWorkflowRepository connectedUserProjectWorkflowRepository,
        ConnectedUserService connectedUserService) {

        this.connectedUserProjectService = connectedUserProjectService;
        this.connectedUserProjectWorkflowRepository = connectedUserProjectWorkflowRepository;
        this.connectedUserService = connectedUserService;
    }

    @Override
    @PreAuthorize("isTenantAdmin()")
    public List<ConnectedUserCodeWorkflowReferenceDTO> getReferences(Set<String> catalogWorkflowUuids) {
        return connectedUserProjectWorkflowRepository.findAllByCatalogWorkflowUuidIn(catalogWorkflowUuids)
            .stream()
            .map(this::toDTO)
            .toList();
    }

    private ConnectedUserCodeWorkflowReferenceDTO toDTO(ConnectedUserProjectWorkflow reference) {
        ConnectedUserProject connectedUserProject = connectedUserProjectService.getConnectedUserProject(
            reference.getConnectedUserProjectId());

        ConnectedUser connectedUser = connectedUserService.getConnectedUser(
            connectedUserProject.getConnectedUserId());

        return new ConnectedUserCodeWorkflowReferenceDTO(
            reference.getCatalogWorkflowUuid(), connectedUser.getExternalId(), reference.isEnabled(),
            reference.isDangling(), reference.getDanglingReason());
    }
}
