/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.aihub.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.bytechef.automation.assetfile.domain.AssetFile;
import com.bytechef.automation.assetfile.service.AssetFileFacade;
import com.bytechef.ee.platform.aihub.tool.AiHubToolInvocationContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class ListAssetFilesToolCallbackTest {

    @Mock
    private AssetFileFacade facade;

    @Test
    void testCallHappyPath() {
        ToolContext toolContext = new ToolContext(Map.of(
            AiHubToolInvocationContext.TOOL_CONTEXT_WORKSPACE_ID_KEY, 11L));

        AssetFile file = new AssetFile();

        file.setId(101L);
        file.setName("notes.md");
        file.setMimeType("text/markdown");
        file.setSizeBytes(42L);

        when(facade.findAllByWorkspaceIdAndEnvironment(eq(11L), anyInt(), isNull())).thenReturn(List.of(file));

        ListAssetFilesToolCallback callback = new ListAssetFilesToolCallback(facade);

        String result = callback.call("{}", toolContext);

        assertThat(result).contains("\"id\":101");
        assertThat(result).contains("\"name\":\"notes.md\"");
        assertThat(result).contains("\"mimeType\":\"text/markdown\"");
    }

    @Test
    void testCallPrefersToolContextOverProvider() {
        AssetFile file = new AssetFile();

        file.setId(202L);
        file.setName("spec.md");
        file.setMimeType("text/markdown");
        file.setSizeBytes(50L);

        when(facade.findAllByWorkspaceIdAndEnvironment(eq(42L), anyInt(), isNull())).thenReturn(List.of(file));

        ListAssetFilesToolCallback callback = new ListAssetFilesToolCallback(facade);

        ToolContext toolContext = new ToolContext(
            Map.of(AiHubToolInvocationContext.TOOL_CONTEXT_WORKSPACE_ID_KEY, 42L));

        String result = callback.call("{}", toolContext);

        assertThat(result).contains("\"id\":202");

    }

    @Test
    void testCallReturnsErrorWhenWorkspaceContextMissing() {

        ListAssetFilesToolCallback callback = new ListAssetFilesToolCallback(facade);

        String result = callback.call("{}");

        assertThat(result).contains("error");
        assertThat(result).containsIgnoringCase("workspace context unavailable");
    }
}
