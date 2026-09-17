/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.listener;

import com.bytechef.ee.embedded.configuration.event.CatalogProjectPublishedEvent;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserReferenceRolloutService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Hands a catalog project publish to the reference rollout once the publishing transaction commits. Runs on
 * {@code workerExecutor}: re-registering triggers runs component code and may call external APIs.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class CatalogProjectPublishedEventListener {

    private final ConnectedUserReferenceRolloutService connectedUserReferenceRolloutService;

    @SuppressFBWarnings("EI")
    public CatalogProjectPublishedEventListener(
        ConnectedUserReferenceRolloutService connectedUserReferenceRolloutService) {

        this.connectedUserReferenceRolloutService = connectedUserReferenceRolloutService;
    }

    @Async("workerExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCatalogProjectPublished(CatalogProjectPublishedEvent catalogProjectPublishedEvent) {
        connectedUserReferenceRolloutService.rollOut(catalogProjectPublishedEvent.projectId());
    }
}
