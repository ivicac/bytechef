/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.aihub.tool;

import com.bytechef.automation.knowledgebase.facade.WorkspaceKnowledgeBaseFacade;
import com.bytechef.ee.platform.aihub.task.AiHubTaskArtifactKind;
import com.bytechef.ee.platform.aihub.task.AiHubTaskArtifactService;
import com.bytechef.ee.platform.aihub.tool.AiHubToolInvocationContext;
import com.bytechef.ee.platform.aihub.util.ToolErrors;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.knowledgebase.exception.KnowledgeBaseDocumentNotFoundException;
import com.bytechef.platform.knowledgebase.facade.KnowledgeBaseDocumentFacade;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring AI {@link ToolCallback} that deletes a document from a knowledge base by id. The mutation is executed
 * immediately — every server-side mutation lands in real time and is recorded as a task artifact for audit purposes.
 *
 * <p>
 * This callback is registered on {@code aiHubBuildSpringAIAgent} only — the ASK variant is read-only.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class DeleteKnowledgeBaseDocumentToolCallback implements ToolCallback {

    private static final long DEFAULT_ENVIRONMENT_ORDINAL = 0L;
    private static final String TOOL_NAME = "deleteKnowledgeBaseDocument";

    private static final String DESCRIPTION = """
        Delete a document from a knowledge base by its id. Supply the knowledgeBaseId (from
        listKnowledgeBases) and the documentId to delete. The document is deleted immediately.
        The knowledgeBaseId must belong to the current workspace.""";

    private static final String INPUT_SCHEMA =
        """
            {
                "type": "object",
                "properties": {
                    "knowledgeBaseId": {"type": "string", "description": "Knowledge base id obtained from listKnowledgeBases"},
                    "documentId": {"type": "string", "description": "The document id to delete"}
                },
                "required": ["knowledgeBaseId", "documentId"]
            }""";

    private final KnowledgeBaseDocumentFacade knowledgeBaseDocumentFacade;
    private final KnowledgeBaseDocumentService knowledgeBaseDocumentService;
    private final WorkspaceKnowledgeBaseFacade workspaceKnowledgeBaseFacade;
    private final AiHubTaskArtifactService taskArtifactService;
    private final JsonMapper jsonMapper = new JsonMapper();

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public DeleteKnowledgeBaseDocumentToolCallback(
        KnowledgeBaseDocumentFacade knowledgeBaseDocumentFacade,
        KnowledgeBaseDocumentService knowledgeBaseDocumentService,
        WorkspaceKnowledgeBaseFacade workspaceKnowledgeBaseFacade,
        AiHubTaskArtifactService taskArtifactService) {

        this.knowledgeBaseDocumentFacade = knowledgeBaseDocumentFacade;
        this.knowledgeBaseDocumentService = knowledgeBaseDocumentService;
        this.workspaceKnowledgeBaseFacade = workspaceKnowledgeBaseFacade;
        this.taskArtifactService = taskArtifactService;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(INPUT_SCHEMA)
            .build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        try {
            DeleteKnowledgeBaseDocumentInput input =
                jsonMapper.readValue(toolInput, DeleteKnowledgeBaseDocumentInput.class);

            if (input.knowledgeBaseId() == null || input.knowledgeBaseId()
                .isBlank()) {
                return toolError("knowledgeBaseId is required");
            }

            if (input.documentId() == null || input.documentId()
                .isBlank()) {
                return toolError("documentId is required");
            }

            AiHubToolInvocationContext invocationContext =
                AiHubToolInvocationContext.fromToolContext(toolContext);

            Long workspaceId = invocationContext == null ? null : invocationContext.workspaceId();

            if (workspaceId == null) {
                return toolError(
                    "Workspace context unavailable - open this chat from the AI Hub of a workspace.");
            }

            long knowledgeBaseId;

            try {
                knowledgeBaseId = Long.parseLong(input.knowledgeBaseId());
            } catch (NumberFormatException exception) {
                return toolError(
                    "Invalid knowledgeBaseId - must be a numeric id obtained from listKnowledgeBases");
            }

            long documentId;

            try {
                documentId = Long.parseLong(input.documentId());
            } catch (NumberFormatException exception) {
                return toolError("Invalid documentId - must be a numeric id");
            }

            long environmentId = resolveEnvironmentId(invocationContext);

            KnowledgeBase knowledgeBase = resolveKnowledgeBaseInWorkspace(knowledgeBaseId, workspaceId, environmentId);

            if (knowledgeBase == null) {
                return toolError(
                    "Knowledge base " + input.knowledgeBaseId() + " not found in the current workspace.");
            }

            if (!isDocumentInKnowledgeBase(documentId, knowledgeBaseId)) {
                return jsonMapper.writeValueAsString(new DeleteKnowledgeBaseDocumentOutput(false, input.documentId()));
            }

            knowledgeBaseDocumentFacade.deleteKnowledgeBaseDocument(documentId);

            recordArtifact(invocationContext, input.documentId(), knowledgeBaseId);

            return jsonMapper.writeValueAsString(new DeleteKnowledgeBaseDocumentOutput(true, input.documentId()));
        } catch (JacksonException exception) {
            return toolError("Invalid tool input: " + exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolErrors.runtimeFailure(
                jsonMapper, DeleteKnowledgeBaseDocumentToolCallback.class, TOOL_NAME, exception);
        }
    }

    private void
        recordArtifact(AiHubToolInvocationContext invocationContext, String documentId, long knowledgeBaseId) {
        String threadId = invocationContext.threadId();
        Long userId = invocationContext.userId();

        if (threadId == null || userId == null) {
            return;
        }

        java.util.Map<String, Object> metadata = new java.util.HashMap<>();

        metadata.put("knowledgeBaseId", knowledgeBaseId);

        taskArtifactService.record(
            threadId, userId, AiHubTaskArtifactKind.KB_DOCUMENT_DELETED,
            documentId, "Document " + documentId, metadata);
    }

    private boolean isDocumentInKnowledgeBase(long documentId, long knowledgeBaseId) {
        try {
            return knowledgeBaseDocumentService.getKnowledgeBaseDocument(documentId)
                .getKnowledgeBaseId()
                .equals(knowledgeBaseId);
        } catch (KnowledgeBaseDocumentNotFoundException exception) {
            return false;
        }
    }

    private KnowledgeBase resolveKnowledgeBaseInWorkspace(long knowledgeBaseId, long workspaceId, long environmentId) {
        List<KnowledgeBase> workspaceKnowledgeBases =
            workspaceKnowledgeBaseFacade.getWorkspaceKnowledgeBases(workspaceId, environmentId);

        return workspaceKnowledgeBases.stream()
            .filter(knowledgeBase -> knowledgeBase.getId() != null && knowledgeBase.getId() == knowledgeBaseId)
            .findFirst()
            .orElse(null);
    }

    private long resolveEnvironmentId(AiHubToolInvocationContext invocationContext) {
        Long environmentId = invocationContext.environmentId();

        return environmentId != null ? environmentId : DEFAULT_ENVIRONMENT_ORDINAL;
    }

    private String toolError(String message) {
        return ToolErrors.toolError(jsonMapper, message);
    }

    public record DeleteKnowledgeBaseDocumentInput(String knowledgeBaseId, String documentId) {
    }

    public record DeleteKnowledgeBaseDocumentOutput(boolean deleted, String documentId) {
    }
}
