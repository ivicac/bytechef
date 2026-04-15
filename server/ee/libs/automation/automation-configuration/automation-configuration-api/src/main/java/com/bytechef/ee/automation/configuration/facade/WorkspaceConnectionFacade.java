/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.configuration.facade;

import com.bytechef.ee.automation.configuration.dto.BulkPromoteResultDTO;
import com.bytechef.platform.connection.domain.ConnectionVisibility;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * EE-only workspace connection visibility transitions. Extends the CE base contract so the EE impl is the active bean
 * for both interfaces in EE and transparently satisfies CE consumers (CE REST/GraphQL). Transitions are coordinated
 * here rather than on {@code ConnectionFacade} so that workspace membership and audit/metric emission stay in one
 * place.
 *
 * <p>
 * <b>Authorization model:</b> most mutations require {@code ROLE_ADMIN} (enforced by {@code @PreAuthorize} on the
 * facade implementation). The exception is {@link #demoteToPrivate(long, long)} — an admin OR the connection creator
 * may call it, to support the "all admins lost role" orphan-recovery path. The creator-as-fallback check is enforced
 * inside the facade (no {@code @PreAuthorize} on that mutation).
 *
 * <p>
 * <b>Bulk-operation semantics:</b> {@link #promoteAllPrivateToWorkspace(long)} returns a {@link BulkPromoteResultDTO}
 * with distinct counts for <i>promoted</i> (this call), <i>skipped</i> (benign concurrent race — row already at the
 * target visibility, carried as {@code CONNECTION_ALREADY_AT_TARGET_VISIBILITY}), and <i>failed</i> (real errors, each
 * with an entry in {@code failures}). Callers surface partial success rather than bailing on the first error.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@SuppressFBWarnings("NM")
public interface WorkspaceConnectionFacade
    extends com.bytechef.automation.configuration.facade.WorkspaceConnectionFacade {

    /**
     * Demote any-visibility connection back to PRIVATE. Admin OR creator (orphan-recovery) — authorization is enforced
     * before any existence/usage validation so unauthorized callers cannot probe the namespace via error-message
     * differences. Blocks if the connection is still used by an active deployment.
     */
    void demoteToPrivate(long workspaceId, long connectionId);

    ConnectionVisibility promoteToWorkspace(long workspaceId, long connectionId);

    BulkPromoteResultDTO promoteAllPrivateToWorkspace(long workspaceId);
}
