/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.tool.knowledgebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.bytechef.automation.knowledgebase.facade.WorkspaceKnowledgeBaseFacade;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class DeleteKnowledgeBaseToolCallbackTest {

    private final WorkspaceKnowledgeBaseFacade facade = Mockito.mock(WorkspaceKnowledgeBaseFacade.class);
    private final DeleteKnowledgeBaseToolCallback toolCallback = new DeleteKnowledgeBaseToolCallback(facade);

    @Test
    void deletesKnowledgeBaseById() {
        String result = toolCallback.call("{\"id\": 42}");

        verify(facade).deleteWorkspaceKnowledgeBase(42L);
        assertThat(result).contains("\"deleted\":true");
    }

    @Test
    void rejectsMissingId() {
        String result = toolCallback.call("{}");

        assertThat(result).contains("id is required");
    }

    @Test
    void surfacesFacadeIllegalArgumentExceptionAsToolError() {
        doThrow(new IllegalArgumentException("Knowledge base not found"))
            .when(facade)
            .deleteWorkspaceKnowledgeBase(42L);

        String result = toolCallback.call("{\"id\": 42}");

        assertThat(result).contains("Knowledge base not found");
    }
}
