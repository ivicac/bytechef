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
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseDocument;
import com.bytechef.platform.knowledgebase.facade.KnowledgeBaseDocumentFacade;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring AI {@link ToolCallback} that adds a text document to a knowledge base. The mutation is executed immediately —
 * every server-side mutation lands in real time and is recorded as a task artifact for audit purposes.
 *
 * <p>
 * This callback is registered on {@code aiHubBuildSpringAIAgent} only — the ASK variant is read-only.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class AddKnowledgeBaseDocumentToolCallback implements ToolCallback {

    static final Set<String> ALLOWED_MIME_TYPES =
        Set.of("text/markdown", "text/plain", "text/html", "application/json");

    private static final long DEFAULT_ENVIRONMENT_ORDINAL = 0L;
    private static final String TOOL_NAME = "addKnowledgeBaseDocument";

    private static final String DESCRIPTION = """
        Add a text document to a knowledge base. Supply the knowledgeBaseId (from listKnowledgeBases),
        a document name, the text content, and a mimeType. Permitted mimeType values: text/markdown,
        text/plain, text/html, application/json. The document is added immediately. The
        knowledgeBaseId must belong to the current workspace.""";

    private static final String INPUT_SCHEMA =
        """
            {
                "type": "object",
                "properties": {
                    "knowledgeBaseId": {"type": "string", "description": "Knowledge base id obtained from listKnowledgeBases"},
                    "name": {"type": "string", "description": "File name for the document (e.g. guide.md)"},
                    "content": {"type": "string", "description": "Text content of the document"},
                    "mimeType": {"type": "string", "description": "MIME type: text/markdown, text/plain, text/html, or application/json"}
                },
                "required": ["knowledgeBaseId", "name", "content", "mimeType"]
            }""";

    private final KnowledgeBaseDocumentFacade knowledgeBaseDocumentFacade;
    private final WorkspaceKnowledgeBaseFacade workspaceKnowledgeBaseFacade;
    private final AiHubTaskArtifactService taskArtifactService;
    private final JsonMapper jsonMapper = new JsonMapper();

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public AddKnowledgeBaseDocumentToolCallback(
        KnowledgeBaseDocumentFacade knowledgeBaseDocumentFacade,
        WorkspaceKnowledgeBaseFacade workspaceKnowledgeBaseFacade,
        AiHubTaskArtifactService taskArtifactService) {

        this.knowledgeBaseDocumentFacade = knowledgeBaseDocumentFacade;
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
            AddKnowledgeBaseDocumentInput input =
                jsonMapper.readValue(toolInput, AddKnowledgeBaseDocumentInput.class);

            if (input.knowledgeBaseId() == null || input.knowledgeBaseId()
                .isBlank()) {
                return toolError("knowledgeBaseId is required");
            }

            if (input.name() == null || input.name()
                .isBlank()) {
                return toolError("name is required");
            }

            if (input.content() == null || input.content()
                .isBlank()) {
                return toolError("content is required and must not be blank");
            }

            if (input.mimeType() == null || input.mimeType()
                .isBlank()) {
                return toolError("mimeType is required");
            }

            if (!ALLOWED_MIME_TYPES.contains(input.mimeType())) {
                return toolError(
                    "Unsupported mimeType '" + input.mimeType() +
                        "'. Allowed values: text/markdown, text/plain, text/html, application/json");
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

            long environmentId = resolveEnvironmentId(invocationContext);

            KnowledgeBase knowledgeBase = resolveKnowledgeBaseInWorkspace(knowledgeBaseId, workspaceId, environmentId);

            if (knowledgeBase == null) {
                return toolError(
                    "Knowledge base " + input.knowledgeBaseId() + " not found in the current workspace.");
            }

            byte[] contentBytes = input.content()
                .getBytes(StandardCharsets.UTF_8);

            KnowledgeBaseDocument document = knowledgeBaseDocumentFacade.createKnowledgeBaseDocument(
                knowledgeBaseId, input.name(), input.mimeType(), new ByteArrayInputStream(contentBytes));

            recordArtifact(invocationContext, document);

            return jsonMapper.writeValueAsString(
                new AddKnowledgeBaseDocumentOutput(true, document.getId()
                    .toString(), document.getName()));
        } catch (JacksonException exception) {
            return toolError("Invalid tool input: " + exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolErrors.runtimeFailure(
                jsonMapper, AddKnowledgeBaseDocumentToolCallback.class, TOOL_NAME, exception);
        }
    }

    private void recordArtifact(AiHubToolInvocationContext invocationContext, KnowledgeBaseDocument document) {
        String threadId = invocationContext.threadId();
        Long userId = invocationContext.userId();

        if (threadId != null && userId != null) {
            taskArtifactService.record(
                threadId, userId, AiHubTaskArtifactKind.KB_DOCUMENT_ADDED,
                document.getId()
                    .toString(),
                document.getName(), null);
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

    public record AddKnowledgeBaseDocumentInput(
        String knowledgeBaseId, String name, String content, String mimeType) {
    }

    public record AddKnowledgeBaseDocumentOutput(boolean added, String documentId, String name) {
    }
}
