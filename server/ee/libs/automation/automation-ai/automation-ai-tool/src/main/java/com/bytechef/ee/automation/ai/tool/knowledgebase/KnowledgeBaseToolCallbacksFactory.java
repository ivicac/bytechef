/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.tool.knowledgebase;

import com.bytechef.automation.ai.tool.ToolArtifactRecorder;
import com.bytechef.automation.knowledgebase.facade.WorkspaceKnowledgeBaseFacade;
import com.bytechef.platform.knowledgebase.facade.KnowledgeBaseDocumentFacade;
import com.bytechef.platform.knowledgebase.facade.KnowledgeBaseFacade;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;

/**
 * Builds the Knowledge Base tool-callback lists shared by the Copilot agents and the AI Hub
 * {@code knowledge_base_agent} subagent. Read list feeds ASK; write list feeds BUILD.
 *
 * @author Ivica Cardic
 * @version ee
 */
public class KnowledgeBaseToolCallbacksFactory {

    private final WorkspaceKnowledgeBaseFacade workspaceKnowledgeBaseFacade;
    private final KnowledgeBaseFacade knowledgeBaseFacade;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeBaseDocumentFacade knowledgeBaseDocumentFacade;
    private final KnowledgeBaseDocumentService knowledgeBaseDocumentService;
    private final @Nullable ToolArtifactRecorder artifactRecorder;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public KnowledgeBaseToolCallbacksFactory(
        WorkspaceKnowledgeBaseFacade workspaceKnowledgeBaseFacade,
        KnowledgeBaseFacade knowledgeBaseFacade,
        KnowledgeBaseService knowledgeBaseService,
        KnowledgeBaseDocumentFacade knowledgeBaseDocumentFacade,
        KnowledgeBaseDocumentService knowledgeBaseDocumentService,
        @Nullable ToolArtifactRecorder artifactRecorder) {

        this.workspaceKnowledgeBaseFacade = workspaceKnowledgeBaseFacade;
        this.knowledgeBaseFacade = knowledgeBaseFacade;
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeBaseDocumentFacade = knowledgeBaseDocumentFacade;
        this.knowledgeBaseDocumentService = knowledgeBaseDocumentService;
        this.artifactRecorder = artifactRecorder;
    }

    public List<ToolCallback> readToolCallbacks() {
        List<ToolCallback> toolCallbacks = new ArrayList<>();

        toolCallbacks.add(new ListKnowledgeBasesToolCallback(workspaceKnowledgeBaseFacade));
        toolCallbacks.add(new QueryKnowledgeBaseToolCallback(knowledgeBaseFacade, knowledgeBaseService));

        return toolCallbacks;
    }

    public List<ToolCallback> writeToolCallbacks() {
        List<ToolCallback> toolCallbacks = new ArrayList<>(readToolCallbacks());

        toolCallbacks.add(new CreateKnowledgeBaseToolCallback(workspaceKnowledgeBaseFacade));
        toolCallbacks.add(
            new AddKnowledgeBaseDocumentToolCallback(
                knowledgeBaseDocumentFacade, workspaceKnowledgeBaseFacade, artifactRecorder));
        toolCallbacks.add(
            new DeleteKnowledgeBaseDocumentToolCallback(
                knowledgeBaseDocumentFacade, knowledgeBaseDocumentService, workspaceKnowledgeBaseFacade,
                artifactRecorder));
        toolCallbacks.add(new CloneKnowledgeBaseToolCallback(workspaceKnowledgeBaseFacade));
        toolCallbacks.add(new DeleteKnowledgeBaseToolCallback(workspaceKnowledgeBaseFacade));

        return toolCallbacks;
    }
}
