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

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Manages per-user, per-workspace long-term memories that outlive a single agent turn. All operations are scoped to
 * {@code (workspaceId, userId)} — there is no cross-user read or write path.
 *
 * <p>
 * Ownership is enforced in the service layer: callers supply the requesting user's id and the service throws
 * {@link AiAutoMemoryNotFoundException} when no row matches the (workspaceId, userId, ...) lookup. Cross-user reads
 * surface as the same not-found shape so a probe cannot enumerate ids across users.
 *
 * @author Ivica Cardic
 */
public interface AiAutoMemoryService {

    /**
     * Creates a new memory row. Throws {@link DuplicateAiAutoMemoryNameException} when another row already uses the
     * same {@code name} for this {@code (workspaceId, userId, environment)} triple. The unique constraint on the
     * underlying table includes environment so the same memory name can co-exist in DEVELOPMENT, STAGING, and
     * PRODUCTION without colliding.
     */
    AiAutoMemory create(
        long workspaceId, long userId, int environment, String name, String title, @Nullable String description,
        AiAutoMemoryType memoryType, String content);

    /**
     * Loads the memory with the given name for this {@code (workspaceId, userId, environment)} triple. Returns empty
     * when not found.
     */
    Optional<AiAutoMemory> read(long workspaceId, long userId, int environment, String name);

    /**
     * Partial update — only non-null fields are applied. At least one field must be non-null; throws
     * {@link IllegalArgumentException} when all are null. Throws {@link AiAutoMemoryNotFoundException} when no row
     * exists for this {@code (workspaceId, userId, environment, name)} or when the row belongs to another user (the
     * not-found shape is reused for cross-user lookups so a probe cannot enumerate ids).
     */
    AiAutoMemory update(
        long workspaceId, long userId, int environment, String name,
        @Nullable String title, @Nullable String description,
        @Nullable AiAutoMemoryType memoryType, @Nullable String content);

    /**
     * Updates the fields of the memory identified by its primary key, scoped to {@code (workspaceId, userId)}. Used by
     * the REST/GraphQL management endpoints. Partial update — only non-null fields are applied. Environment is not
     * threaded because the row's environment is immutable post-create and the primary key already pins the partition.
     */
    AiAutoMemory updateById(
        long workspaceId, long userId, long memoryId,
        @Nullable String title, @Nullable String description,
        @Nullable AiAutoMemoryType memoryType, @Nullable String content);

    /**
     * Deletes the memory row identified by {@code name}. Returns the deleted row so the tool callback layer can capture
     * a pre-image for artifact reversal. Throws {@link AiAutoMemoryNotFoundException} when the memory is missing.
     */
    AiAutoMemory delete(long workspaceId, long userId, int environment, String name);

    /**
     * Deletes the memory identified by its primary key, scoped to {@code (workspaceId, userId)}. Used by the
     * REST/GraphQL management endpoints. Environment is not threaded for the same reason as {@link #updateById}.
     */
    AiAutoMemory deleteById(long workspaceId, long userId, long memoryId);

    /**
     * Renames a memory. Throws {@link DuplicateAiAutoMemoryNameException} when the target name already exists and
     * {@link AiAutoMemoryNotFoundException} when the source does not exist. Both names are resolved within the supplied
     * environment.
     */
    AiAutoMemory rename(long workspaceId, long userId, int environment, String oldName, String newName);

    /**
     * Lists memories for the given {@code (workspaceId, userId, environment)}, optionally filtered by type. Ordered by
     * {@code updated_at DESC}.
     */
    List<AiAutoMemory> list(
        long workspaceId, long userId, int environment, @Nullable AiAutoMemoryType memoryType);

    /**
     * Loads a single memory by its primary key, verifying ownership against {@code (workspaceId, userId)}. Returns
     * empty when no row matches or the row belongs to another user — the REST/GraphQL layer surfaces this as 404.
     * Environment is not threaded because the primary-key lookup already targets a single row.
     */
    Optional<AiAutoMemory> findById(long workspaceId, long userId, long memoryId);

    /**
     * Returns the memories for the given {@code (workspaceId, userId, environment)}. Intended for the agent's
     * memory-index injection — equivalent to {@link #list(long, long, int, AiAutoMemoryType)} with {@code null} type
     * but named explicitly to make the intent at the call site obvious.
     */
    List<AiAutoMemory> listByUserAndWorkspace(long workspaceId, long userId, int environment);
}
