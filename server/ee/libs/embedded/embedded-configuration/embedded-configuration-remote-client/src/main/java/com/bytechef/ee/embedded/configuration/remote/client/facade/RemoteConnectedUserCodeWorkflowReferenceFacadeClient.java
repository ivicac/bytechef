/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.remote.client.facade;

import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProjectWorkflow;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserCodeWorkflowReferenceFacade;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.configuration.domain.Environment;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class RemoteConnectedUserCodeWorkflowReferenceFacadeClient implements ConnectedUserCodeWorkflowReferenceFacade {

    @Override
    public void deleteReference(String externalUserId, String catalogWorkflowUuid, Environment environment) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void enableReference(
        String externalUserId, String catalogWorkflowUuid, boolean enable, Environment environment) {

        throw new UnsupportedOperationException();
    }

    @Override
    public List<ConnectedUserProjectWorkflow> getConnectedUserWorkflows(long connectedUserId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ConnectedUserProjectWorkflow getOrCreateReference(
        String externalUserId, String catalogWorkflowUuid, Environment environment) {

        throw new UnsupportedOperationException();
    }

    @Override
    public void markDanglingReferences(
        long catalogProjectId, Set<String> previousCatalogWorkflowUuids, Set<String> currentCatalogWorkflowUuids) {

        throw new UnsupportedOperationException();
    }
}
