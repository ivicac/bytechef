/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.automation.knowledgebase.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.bytechef.automation.knowledgebase.domain.WorkspaceKnowledgeBase;
import com.bytechef.automation.knowledgebase.service.WorkspaceKnowledgeBaseService;
import com.bytechef.platform.knowledgebase.facade.KnowledgeBaseTagFacade;
import com.bytechef.platform.tag.domain.Tag;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceKnowledgeBaseTagFacadeImplTest {

    @Mock
    private KnowledgeBaseTagFacade knowledgeBaseTagFacade;

    @Mock
    private WorkspaceKnowledgeBaseService workspaceKnowledgeBaseService;

    @InjectMocks
    private WorkspaceKnowledgeBaseFacadeImpl workspaceKnowledgeBaseFacade;

    @Test
    void testGetKnowledgeBaseTagsScopesToWorkspace() {
        when(workspaceKnowledgeBaseService.getWorkspaceKnowledgeBases(5L))
            .thenReturn(List.of(new WorkspaceKnowledgeBase(1L, 5L), new WorkspaceKnowledgeBase(2L, 5L)));
        when(knowledgeBaseTagFacade.getTags(List.of(1L, 2L))).thenReturn(List.of(new Tag("a"), new Tag("b")));

        List<Tag> tags = workspaceKnowledgeBaseFacade.getKnowledgeBaseTags(5L);

        assertThat(tags).hasSize(2);

        verify(knowledgeBaseTagFacade).getTags(List.of(1L, 2L));
    }

    /**
     * The scoping half of the fix, below the {@code @PreAuthorize} gate. Passing the authorization check for one
     * workspace must not widen the read: the ids handed down are the workspace's own, so a knowledge base belonging to
     * another workspace is never asked about and cannot come back.
     */
    @Test
    void testGetKnowledgeBaseTagsByKnowledgeBaseAsksOnlyForTheWorkspacesOwnKnowledgeBases() {
        when(workspaceKnowledgeBaseService.getWorkspaceKnowledgeBases(5L))
            .thenReturn(List.of(new WorkspaceKnowledgeBase(1L, 5L), new WorkspaceKnowledgeBase(2L, 5L)));
        when(knowledgeBaseTagFacade.getTagsByKnowledgeBaseIds(List.of(1L, 2L)))
            .thenReturn(Map.of(1L, List.of(new Tag("a")), 2L, List.of()));

        Map<Long, List<Tag>> tagsByKnowledgeBaseId =
            workspaceKnowledgeBaseFacade.getKnowledgeBaseTagsByKnowledgeBase(5L);

        assertThat(tagsByKnowledgeBaseId).containsOnlyKeys(1L, 2L);

        verify(knowledgeBaseTagFacade).getTagsByKnowledgeBaseIds(List.of(1L, 2L));
        verifyNoMoreInteractions(knowledgeBaseTagFacade);
    }
}
