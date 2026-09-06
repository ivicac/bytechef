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

package com.bytechef.automation.assetfile.service;

import com.bytechef.automation.assetfile.domain.AssetFile;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * Asset-file operations for callers that have no authenticated principal: the {@code asset-file} workflow component
 * actions, the anonymous public-link download, and the AG-UI agent-turn threads, which run on a
 * {@code ForkJoinPool.commonPool()} worker where only the tenant is bound.
 *
 * <p>
 * No method here is membership-checked, because there is no user whose membership could be tested. What replaces the
 * membership check differs by method shape:
 * </p>
 *
 * <ul>
 * <li>Every method that takes both an asset file id and a {@code workspaceId} is ownership-checked: it verifies that
 * the file belongs to the workspace passed in.</li>
 * <li>{@link #createFromUpload} and {@link #findAllByWorkspaceIdAndEnvironment} take a workspace but name no existing
 * file, so there is nothing to check ownership of; the workspace itself is the whole authorization, and both refuse a
 * {@code null} one rather than degrading to a workspace-less operation.</li>
 * <li>{@link #fetchByPublicLinkToken} takes no workspace at all: the unguessable token is the authorization by design,
 * and the operator kill-switch is checked on every resolution.</li>
 * </ul>
 *
 * <p>
 * That makes the contract on every caller of this interface load-bearing:
 * </p>
 *
 * <blockquote>The workspace must be server-derived, and the caller's access to it must have been verified upstream on a
 * thread that had a principal.</blockquote>
 *
 * <p>
 * The set of classes permitted to call this interface is pinned by {@code AssetFileSystemFacadeCallerScanTest}. A new
 * caller must be added there deliberately, in the same commit. {@code enablePublicLink}, {@code disablePublicLink} and
 * {@code createSignedDownloadToken} are deliberately absent: they mint anonymous access to a file, which no
 * server-derived workspace alone should authorize.
 * </p>
 *
 * @author Ivica Cardic
 */
public interface AssetFileSystemFacade {

    AssetFile createFromUpload(
        Long workspaceId, int environment, String filename, String contentType, InputStream data);

    List<AssetFile> findAllByWorkspaceIdAndEnvironment(Long workspaceId, int environment, List<Long> tagIds);

    /**
     * Loads an asset file but only when it belongs to the supplied {@code workspaceId}.
     *
     * @throws IllegalArgumentException                                               when {@code workspaceId} is
     *                                                                                {@code null}
     * @throws com.bytechef.automation.assetfile.exception.AssetFileNotFoundException when the id is unknown or belongs
     *                                                                                to a different workspace
     */
    AssetFile findByIdInWorkspace(Long id, Long workspaceId);

    AssetFile renameInWorkspace(Long id, Long workspaceId, String newName);

    void deleteInWorkspace(Long id, Long workspaceId);

    InputStream downloadContentInWorkspace(Long id, Long workspaceId);

    AssetFile updateContentInWorkspace(Long id, Long workspaceId, String contentType, InputStream data);

    /**
     * Resolves a public-link token to its asset file. Returns empty when the token is unknown OR when the operator has
     * switched public sharing off — an existing link stops resolving the moment the kill-switch flips.
     */
    Optional<AssetFile> fetchByPublicLinkToken(String token);
}
