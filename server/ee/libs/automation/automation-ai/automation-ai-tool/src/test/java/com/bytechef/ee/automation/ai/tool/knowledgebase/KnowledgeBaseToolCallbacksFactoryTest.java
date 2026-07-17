/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.tool.knowledgebase;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.automation.knowledgebase.facade.WorkspaceKnowledgeBaseFacade;
import com.bytechef.ee.automation.ai.tool.ToolMutationArtifactRecorder;
import com.bytechef.platform.knowledgebase.facade.KnowledgeBaseDocumentFacade;
import com.bytechef.platform.knowledgebase.facade.KnowledgeBaseFacade;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseService;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.tool.ToolCallback;

/**
 * @author Ivica Cardic
 */
class KnowledgeBaseToolCallbacksFactoryTest {

    private final KnowledgeBaseToolCallbacksFactory factory = new KnowledgeBaseToolCallbacksFactory(
        Mockito.mock(WorkspaceKnowledgeBaseFacade.class),
        Mockito.mock(KnowledgeBaseFacade.class),
        Mockito.mock(KnowledgeBaseService.class),
        Mockito.mock(KnowledgeBaseDocumentFacade.class),
        Mockito.mock(KnowledgeBaseDocumentService.class),
        Mockito.mock(ToolMutationArtifactRecorder.class));

    @Test
    void readListExcludesMutations() {
        List<String> names = toolNames(factory.readToolCallbacks());

        assertThat(names).contains("listKnowledgeBases", "queryKnowledgeBase");
        assertThat(names).doesNotContain("deleteKnowledgeBase", "addKnowledgeBaseDocument");
    }

    @Test
    void writeListIncludesReadsAndMutations() {
        List<String> names = toolNames(factory.writeToolCallbacks());

        assertThat(names).contains(
            "listKnowledgeBases", "queryKnowledgeBase", "addKnowledgeBaseDocument", "deleteKnowledgeBaseDocument",
            "cloneKnowledgeBase", "deleteKnowledgeBase");
    }

    private static List<String> toolNames(List<ToolCallback> toolCallbacks) {
        return toolCallbacks.stream()
            .map(toolCallback -> toolCallback.getToolDefinition()
                .name())
            .collect(Collectors.toList());
    }
}
