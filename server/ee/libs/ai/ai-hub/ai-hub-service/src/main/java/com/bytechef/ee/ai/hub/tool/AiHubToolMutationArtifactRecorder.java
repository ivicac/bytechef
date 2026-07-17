/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.tool;

import com.bytechef.ee.ai.hub.task.AiHubTaskArtifactKind;
import com.bytechef.ee.ai.hub.task.AiHubTaskArtifactService;
import com.bytechef.ee.automation.ai.tool.ToolMutationArtifactRecorder;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * AI Hub's {@link ToolMutationArtifactRecorder} implementation, delegating to {@link AiHubTaskArtifactService} so the
 * knowledge-base tool callbacks moved into the shared {@code automation-ai-tool} lib keep recording task artifacts
 * for the audit trail when invoked from AI Hub. The {@code kind} string is mapped back to the concrete
 * {@link AiHubTaskArtifactKind} enum constant via {@link AiHubTaskArtifactKind#valueOf(String)}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class AiHubToolMutationArtifactRecorder implements ToolMutationArtifactRecorder {

    private final AiHubTaskArtifactService taskArtifactService;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public AiHubToolMutationArtifactRecorder(AiHubTaskArtifactService taskArtifactService) {
        this.taskArtifactService = taskArtifactService;
    }

    @Override
    public void record(
        String conversationId, long userId, String kind,
        String artifactId, String artifactName, @Nullable Map<String, Object> metadata) {

        taskArtifactService.record(
            conversationId, userId, AiHubTaskArtifactKind.valueOf(kind), artifactId, artifactName, metadata);
    }
}
