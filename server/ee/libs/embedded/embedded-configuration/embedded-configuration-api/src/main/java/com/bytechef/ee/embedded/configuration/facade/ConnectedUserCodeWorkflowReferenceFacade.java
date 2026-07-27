/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.facade;

import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProjectWorkflow;
import com.bytechef.ee.embedded.configuration.exception.MissingConnectionException;
import com.bytechef.platform.configuration.domain.Environment;
import java.util.Set;

/**
 * Owns the lifecycle of a connected user's reference to a shared catalog code workflow: provisioning on first use,
 * per-user connection auto-wiring, enable/disable, deletion, and flagging references whose catalog workflow was removed
 * on redeploy ("dangling").
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface ConnectedUserCodeWorkflowReferenceFacade {

    void deleteReference(String externalUserId, String catalogWorkflowUuid, Environment environment);

    void enableReference(String externalUserId, String catalogWorkflowUuid, boolean enable, Environment environment);

    /**
     * @throws MissingConnectionException if the reference cannot be auto-wired because a component it uses has no
     *                                    matching connection for the connected user. The reference is still created,
     *                                    left disabled.
     */
    ConnectedUserProjectWorkflow getOrCreateReference(
        String externalUserId, String catalogWorkflowUuid, Environment environment);

    void markDanglingReferences(long catalogProjectId, Set<String> currentCatalogWorkflowUuids);
}
