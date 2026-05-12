/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.aihub.tool;

/**
 * Minimal abstraction that allows asset-file tool callbacks to record task artifacts without coupling to the
 * {@code automation-ai-hub-api} module. Implementations are provided by the service layer and injected into the
 * callbacks at configuration time.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface AiHubTaskArtifactRecorder {

    /**
     * Records that a file was created during the current task turn.
     *
     * @param threadId     the AG-UI thread id that identifies the active task
     * @param userId       the id of the user who owns the task (may be {@code null} — will be treated as 0)
     * @param artifactKind the kind string (matches {@code AiHubTaskArtifactKind.name()})
     * @param artifactId   string representation of the created entity id
     * @param artifactName display name snapshot at creation time
     */
    void record(String threadId, Long userId, String artifactKind, String artifactId, String artifactName);
}
