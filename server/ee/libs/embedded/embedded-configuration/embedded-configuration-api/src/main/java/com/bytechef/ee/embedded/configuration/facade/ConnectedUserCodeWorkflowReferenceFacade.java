/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.facade;

import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProjectWorkflow;
import com.bytechef.platform.configuration.domain.Environment;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns the lifecycle of a connected user's reference to a shared catalog code workflow: provisioning on first use,
 * per-user connection auto-wiring, enable/disable, deletion, and flagging references whose catalog workflow was removed
 * on redeploy ("dangling"). Also serves as the read seam for callers that need every
 * {@link ConnectedUserProjectWorkflow} row (both reference-mode and copy-mode) belonging to a connected user, so those
 * callers never need to depend on the repository directly.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface ConnectedUserCodeWorkflowReferenceFacade {

    void deleteReference(String externalUserId, String catalogWorkflowUuid, Environment environment);

    /**
     * @throws com.bytechef.ee.embedded.configuration.exception.MissingConnectionException if enabling and a required
     *                                                                                     connection is missing; the
     *                                                                                     reference is left disabled
     * @throws com.bytechef.ee.embedded.configuration.exception.MissingInputException      if enabling and a required
     *                                                                                     input has no value; the
     *                                                                                     reference is left disabled
     */
    void enableReference(String externalUserId, String catalogWorkflowUuid, boolean enable, Environment environment);

    /**
     * Returns every {@link ConnectedUserProjectWorkflow} row belonging to the connected user, across all of their
     * {@code ConnectedUserProject}s, regardless of whether the row is reference-mode
     * ({@code catalogWorkflowUuid != null}) or copy-mode ({@code projectWorkflowId != null}).
     */
    List<ConnectedUserProjectWorkflow> getConnectedUserWorkflows(long connectedUserId);

    /**
     * {@link #getOrCreateReference(String, String, Environment, Map)} without requested connections. Deliberately not a
     * {@code default} method: a default the implementation does not override carries no transaction attribute, and its
     * call to the four-argument overload bypasses the transactional proxy, so provisioning would not be atomic.
     */
    ConnectedUserProjectWorkflow getOrCreateReference(
        String externalUserId, String catalogWorkflowUuid, Environment environment);

    /**
     * Creates the reference on first use, writing the template's row into the connected user's deployment of the
     * catalog project; on an existing reference, a non-empty {@code requestedConnectionIds} re-resolves its
     * connections. A required input without a value leaves the reference disabled without failing.
     *
     * @param requestedConnectionIds connection ids by component name, chosen by the caller over the automatic choice
     * @throws com.bytechef.ee.embedded.configuration.exception.MissingConnectionException if a component the workflow
     *                                                                                     uses has no connection for
     *                                                                                     the connected user. The
     *                                                                                     reference is still created,
     *                                                                                     left disabled.
     */
    ConnectedUserProjectWorkflow getOrCreateReference(
        String externalUserId, String catalogWorkflowUuid, Environment environment,
        Map<String, Long> requestedConnectionIds);

    /**
     * A reference dangles iff its {@code catalog_workflow_uuid} was served by this catalog project in the previous
     * deploy and is no longer served by the current one. Both sets are scoped to a SINGLE catalog project (uuids the
     * project served before vs. now), so a redeploy of one catalog project can never dangle a reference to a different
     * catalog project -- there is deliberately no repository-wide "not in the current set" scan.
     */
    void markDanglingReferences(
        long catalogProjectId, Set<String> previousCatalogWorkflowUuids, Set<String> currentCatalogWorkflowUuids);

    /**
     * Replaces the inputs of the reference's deployment row, serialized with the connected user's other reference
     * writes.
     *
     * @throws com.bytechef.ee.embedded.configuration.exception.MissingInputException if the reference is enabled and a
     *                                                                                required input has no value;
     *                                                                                nothing is written
     */
    void updateReferenceInputs(
        String externalUserId, String catalogWorkflowUuid, Map<String, ?> inputs, Environment environment);
}
