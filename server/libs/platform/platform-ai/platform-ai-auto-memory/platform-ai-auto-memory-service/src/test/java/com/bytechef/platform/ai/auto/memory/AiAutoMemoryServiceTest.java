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

package com.bytechef.platform.ai.auto.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.platform.ai.auto.memory.repository.AiAutoMemoryRepository;
import com.bytechef.platform.ai.auto.memory.repository.WorkspaceAiAutoMemoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link AiAutoMemoryServiceImpl}.
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class AiAutoMemoryServiceTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long USER_ID = 10L;
    private static final long OTHER_USER_ID = 99L;
    private static final int ENVIRONMENT = 0;

    @Mock
    private AiAutoMemoryRepository aiMemoryRepository;

    @Mock
    private WorkspaceAiAutoMemoryRepository workspaceAiMemoryRepository;

    private final Clock clock = Clock.fixed(Instant.parse("2026-04-24T10:00:00Z"), ZoneId.of("UTC"));

    @Test
    void testCreatePersistsWithTimestamps() {
        AiAutoMemoryServiceImpl realService = newService();

        when(aiMemoryRepository.findAllByWorkspaceIdAndUserIdAndEnvironmentAndName(
            WORKSPACE_ID, USER_ID, ENVIRONMENT, "user_profile"))
                .thenReturn(List.of());
        when(aiMemoryRepository.save(any(AiAutoMemory.class)))
            .thenAnswer(invocation -> {
                AiAutoMemory arg = invocation.getArgument(0);

                arg.setId(42L);

                return arg;
            });

        AiAutoMemory created = realService.create(
            WORKSPACE_ID, USER_ID, ENVIRONMENT, "user_profile", "User Profile", "User preferences",
            AiAutoMemoryType.USER, "Alice prefers concise replies");

        assertThat(created.getId()).isEqualTo(42L);
        assertThat(created.getName()).isEqualTo("user_profile");
        assertThat(created.getMemoryType()).isEqualTo(AiAutoMemoryType.USER);
        assertThat(created.getCreatedAt()).isEqualTo(LocalDateTime.now(clock));
        assertThat(created.getUpdatedAt()).isEqualTo(LocalDateTime.now(clock));

        verify(workspaceAiMemoryRepository).save(any(WorkspaceAiAutoMemory.class));
    }

    @Test
    void testCreateRejectsDuplicateName() {
        AiAutoMemoryServiceImpl realService = newService();

        AiAutoMemory existing = new AiAutoMemory();

        existing.setId(1L);
        existing.setName("dup");

        when(aiMemoryRepository.findAllByWorkspaceIdAndUserIdAndEnvironmentAndName(
            WORKSPACE_ID, USER_ID, ENVIRONMENT, "dup"))
                .thenReturn(List.of(existing));

        assertThatThrownBy(() -> realService.create(
            WORKSPACE_ID, USER_ID, ENVIRONMENT, "dup", "title", null, AiAutoMemoryType.USER, "content"))
                .isInstanceOf(DuplicateAiAutoMemoryNameException.class);

        verify(aiMemoryRepository, never()).save(any());
    }

    @Test
    void testCreateRequiresMemoryType() {
        AiAutoMemoryServiceImpl realService = newService();

        assertThatThrownBy(() -> realService.create(
            WORKSPACE_ID, USER_ID, ENVIRONMENT, "name", "title", null, null, "content"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memoryType");
    }

    @Test
    void testUpdateRequiresAtLeastOneField() {
        AiAutoMemoryServiceImpl realService = newService();

        assertThatThrownBy(() -> realService.update(
            WORKSPACE_ID, USER_ID, ENVIRONMENT, "name", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testUpdateAppliesPartialPatch() {
        AiAutoMemoryServiceImpl realService = newService();

        AiAutoMemory existing = buildMemory("user_profile", AiAutoMemoryType.USER);

        when(aiMemoryRepository.findAllByWorkspaceIdAndUserIdAndEnvironmentAndName(
            WORKSPACE_ID, USER_ID, ENVIRONMENT, "user_profile"))
                .thenReturn(List.of(existing));
        when(aiMemoryRepository.save(any(AiAutoMemory.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        AiAutoMemory updated = realService.update(
            WORKSPACE_ID, USER_ID, ENVIRONMENT, "user_profile", null, null, null, "new content");

        assertThat(updated.getContent()).isEqualTo("new content");
        assertThat(updated.getTitle()).isEqualTo("Title: user_profile");
        assertThat(updated.getUpdatedAt()).isEqualTo(LocalDateTime.now(clock));
    }

    @Test
    void testDeleteByIdReturnsNotFoundOnOwnershipMismatch() {
        AiAutoMemoryServiceImpl realService = newService();

        AiAutoMemory owned = buildMemory("name", AiAutoMemoryType.USER);

        owned.setId(5L);
        owned.setUserId(OTHER_USER_ID);

        when(aiMemoryRepository.findById(5L)).thenReturn(Optional.of(owned));

        assertThatThrownBy(() -> realService.deleteById(WORKSPACE_ID, USER_ID, 5L))
            .isInstanceOf(AiAutoMemoryNotFoundException.class)
            .hasMessageContaining("Memory not found");

        verify(aiMemoryRepository, never()).delete(any(AiAutoMemory.class));
    }

    @Test
    void testRenameRejectsExistingTarget() {
        AiAutoMemoryServiceImpl realService = newService();

        AiAutoMemory target = buildMemory("target", AiAutoMemoryType.USER);

        when(aiMemoryRepository.findAllByWorkspaceIdAndUserIdAndEnvironmentAndName(
            WORKSPACE_ID, USER_ID, ENVIRONMENT, "target"))
                .thenReturn(List.of(target));

        assertThatThrownBy(() -> realService.rename(WORKSPACE_ID, USER_ID, ENVIRONMENT, "source", "target"))
            .isInstanceOf(DuplicateAiAutoMemoryNameException.class);
    }

    @Test
    void testListFiltersByType() {
        AiAutoMemoryServiceImpl realService = newService();

        when(aiMemoryRepository.findByWorkspaceIdAndUserIdAndEnvironmentAndMemoryTypeOrderByUpdatedAtDesc(
            WORKSPACE_ID, USER_ID, ENVIRONMENT, AiAutoMemoryType.FEEDBACK.ordinal()))
                .thenReturn(List.of(buildMemory("a", AiAutoMemoryType.FEEDBACK)));

        List<AiAutoMemory> result = realService.list(
            WORKSPACE_ID, USER_ID, ENVIRONMENT, AiAutoMemoryType.FEEDBACK);

        assertThat(result).hasSize(1);
    }

    @Test
    void testFindByIdReturnsEmptyWhenOwnedByOtherUser() {
        AiAutoMemoryServiceImpl realService = newService();

        AiAutoMemory otherUsers = buildMemory("mine", AiAutoMemoryType.USER);

        otherUsers.setId(7L);
        otherUsers.setUserId(OTHER_USER_ID);

        when(aiMemoryRepository.findById(7L)).thenReturn(Optional.of(otherUsers));

        Optional<AiAutoMemory> result = realService.findById(WORKSPACE_ID, USER_ID, 7L);

        assertThat(result).isEmpty();
    }

    private AiAutoMemoryServiceImpl newService() {
        return new AiAutoMemoryServiceImpl(aiMemoryRepository, workspaceAiMemoryRepository, clock);
    }

    private AiAutoMemory buildMemory(String name, AiAutoMemoryType memoryType) {
        AiAutoMemory memory = new AiAutoMemory();

        memory.setUserId(USER_ID);
        memory.setName(name);
        memory.setTitle("Title: " + name);
        memory.setMemoryType(memoryType);
        memory.setContent("body: " + name);
        memory.setCreatedAt(LocalDateTime.now(clock));
        memory.setUpdatedAt(LocalDateTime.now(clock));

        return memory;
    }
}
