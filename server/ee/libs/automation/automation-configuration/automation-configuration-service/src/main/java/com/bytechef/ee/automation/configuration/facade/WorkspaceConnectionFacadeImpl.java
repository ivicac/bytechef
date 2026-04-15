/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.configuration.facade;

import static com.bytechef.platform.connection.audit.ConnectionAuditEvent.CONNECTION_DEMOTED;
import static com.bytechef.platform.connection.audit.ConnectionAuditEvent.CONNECTION_PROMOTED;

import com.bytechef.automation.configuration.domain.WorkspaceConnection;
import com.bytechef.automation.configuration.facade.WorkspaceFacade;
import com.bytechef.automation.configuration.service.ConnectionVisibilityResolver;
import com.bytechef.automation.configuration.service.ProjectDeploymentWorkflowService;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.WorkspaceConnectionService;
import com.bytechef.commons.util.CollectionUtils;
import com.bytechef.ee.automation.configuration.dto.BulkPromoteResultDTO;
import com.bytechef.ee.automation.configuration.dto.BulkPromoteResultDTO.BulkPromoteFailureDTO;
import com.bytechef.exception.ConfigurationException;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.configuration.service.WorkflowTestConfigurationService;
import com.bytechef.platform.connection.audit.AuditConnection;
import com.bytechef.platform.connection.audit.AuditConnection.AuditData;
import com.bytechef.platform.connection.domain.ConnectionVisibility;
import com.bytechef.platform.connection.exception.ConnectionErrorType;
import com.bytechef.platform.connection.facade.ConnectionFacade;
import com.bytechef.platform.connection.service.ConnectionService;
import com.bytechef.platform.security.constant.AuthorityConstants;
import com.bytechef.platform.security.util.SecurityUtils;
import com.bytechef.platform.user.service.UserService;
import com.bytechef.platform.workflow.execution.facade.ConnectionLifecycleFacade;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * EE implementation of the workspace connection visibility transitions. Extends the CE CRUD impl so the EE bean
 * satisfies both the CE base interface (REST/GraphQL CRUD consumers) and the EE sub-interface (visibility mutations).
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Service
@Transactional
@ConditionalOnEEVersion
@SuppressFBWarnings("NM")
public class WorkspaceConnectionFacadeImpl
    extends com.bytechef.automation.configuration.facade.WorkspaceConnectionFacadeImpl
    implements WorkspaceConnectionFacade {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceConnectionFacadeImpl.class);

    // Self-reference via the EE interface so AOP advice (audit, @Transactional propagation) fires on
    // internal re-entry (e.g. bulk-promote → promoteToWorkspace). @Lazy breaks the circular
    // dependency Spring would otherwise refuse. Tests inject via setSelf().
    @Lazy
    @Autowired
    private WorkspaceConnectionFacade self;

    @SuppressFBWarnings({
        "CT_CONSTRUCTOR_THROW", "EI", "EI2"
    })
    public WorkspaceConnectionFacadeImpl(
        ConnectionFacade connectionFacade, ConnectionLifecycleFacade connectionLifecycleFacade,
        ConnectionService connectionService, ConnectionVisibilityResolver connectionVisibilityResolver,
        ObjectProvider<MeterRegistry> meterRegistryProvider,
        ProjectDeploymentWorkflowService projectDeploymentWorkflowService, ProjectService projectService,
        UserService userService, WorkflowTestConfigurationService workflowTestConfigurationService,
        WorkspaceConnectionService workspaceConnectionService, WorkspaceFacade workspaceFacade) {

        super(
            connectionFacade, connectionLifecycleFacade, connectionService, connectionVisibilityResolver,
            meterRegistryProvider, projectDeploymentWorkflowService, projectService, userService,
            workflowTestConfigurationService, workspaceConnectionService, workspaceFacade);
    }

    /**
     * Returns the AOP-proxied self-reference. Falls back to {@code this} so unit tests that construct the impl directly
     * (without Spring) still exercise the bulk loop — note: aspect advice will NOT fire in that case, so any test that
     * needs proxy behavior must wire a real proxy via setSelf() or use Spring's test context.
     */
    private WorkspaceConnectionFacade self() {
        return self != null ? self : this;
    }

    /** Package-private for test wiring. */
    void setSelf(WorkspaceConnectionFacade self) {
        this.self = self;
    }

    @Override
    @AuditConnection(
        event = CONNECTION_DEMOTED, connectionId = "#connectionId",
        data = @AuditData(key = "toVisibility", value = "'PRIVATE'"))
    public void demoteToPrivate(long workspaceId, long connectionId) {
        // Authorize as early as possible without disclosing existence. An unauthenticated or
        // low-privilege caller must not be able to distinguish "does not exist" vs "exists but
        // forbidden" vs "exists and is in use" purely from which error message comes back, as that
        // would allow probing the (workspaceId, connectionId) namespace without being allowed to act
        // on it.
        //
        // Admins can short-circuit the creator check — their authority alone is sufficient. For
        // non-admins we must still load the row to know whether they are the creator, but we use
        // fetchConnection(...) (Optional) and conflate "not found" with "not authorized" via a
        // single INVALID_CONNECTION response so the two cases are indistinguishable from outside.
        boolean isAdmin = SecurityUtils.hasCurrentUserThisAuthority(AuthorityConstants.ADMIN);

        if (!isAdmin) {
            String currentUserLogin = SecurityUtils.getCurrentUserLogin();
            String creator;

            try {
                creator = connectionFacade.getConnection(connectionId)
                    .createdBy();
            } catch (RuntimeException loadFailure) {
                // Catches every RuntimeException (including DataAccessException, TransactionSystemException,
                // NotFoundException) — the name reflects that. Log server-side so infrastructure failures
                // surface to operators, but collapse the externally-visible error into INVALID_CONNECTION
                // so a non-admin cannot distinguish "this id does not exist" from "you are not the creator"
                // from "transient DB error" by comparing error responses.
                log.warn(
                    "demoteToPrivate: failed to load connection id={} for authorization check", connectionId,
                    loadFailure);

                throw new ConfigurationException(
                    "Only an administrator or the connection creator may demote this connection",
                    ConnectionErrorType.INVALID_CONNECTION);
            }

            boolean isCreator = currentUserLogin != null && currentUserLogin.equals(creator);

            if (!isCreator) {
                throw new ConfigurationException(
                    "Only an administrator or the connection creator may demote this connection",
                    ConnectionErrorType.INVALID_CONNECTION);
            }
        }

        validateConnectionBelongsToWorkspace(workspaceId, connectionId);
        validateConnectionNotUsedByDeployments(connectionId);

        connectionService.updateVisibility(connectionId, ConnectionVisibility.PRIVATE);
    }

    @Override
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @AuditConnection(
        event = CONNECTION_PROMOTED, connectionId = "#connectionId",
        data = {
            @AuditData(key = "fromVisibility", value = "#result.name()"),
            @AuditData(key = "toVisibility", value = "'WORKSPACE'")
        })
    public ConnectionVisibility promoteToWorkspace(long workspaceId, long connectionId) {
        validateConnectionBelongsToWorkspace(workspaceId, connectionId);

        ConnectionVisibility currentVisibility = connectionService.getConnection(connectionId)
            .getVisibility();

        if (currentVisibility.isAtLeast(ConnectionVisibility.WORKSPACE)) {
            throw new ConfigurationException(
                "Connection id=%s already has %s visibility and cannot be promoted to WORKSPACE".formatted(
                    connectionId, currentVisibility),
                ConnectionErrorType.CONNECTION_ALREADY_AT_TARGET_VISIBILITY);
        }

        connectionService.updateVisibility(connectionId, ConnectionVisibility.WORKSPACE);

        return currentVisibility;
    }

    @Override
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public BulkPromoteResultDTO promoteAllPrivateToWorkspace(long workspaceId) {
        // @PreAuthorize only confirms the caller is a global admin — it does NOT confirm they
        // administrate THIS workspace. Without this check a global admin could bulk-promote
        // connections in any workspace they are not a member of.
        validateCurrentUserIsWorkspaceMember(workspaceId);

        // Bulk migration helper: promote every PRIVATE connection in this workspace to WORKSPACE.
        // Single bulk fetch (avoids N+1) followed by per-row promotion. Each promotion goes through
        // promoteToWorkspace() so audit events still fire; failures are collected so the caller can
        // surface partial success instead of bailing on the first error.
        List<Long> connectionIds = CollectionUtils.map(
            workspaceConnectionService.getWorkspaceConnections(workspaceId), WorkspaceConnection::getConnectionId);

        if (connectionIds.isEmpty()) {
            return new BulkPromoteResultDTO(0, 0, 0, 0, List.of());
        }

        List<Long> privateIds = connectionService.getConnections(connectionIds)
            .stream()
            .filter(connection -> connection.getVisibility() == ConnectionVisibility.PRIVATE)
            .map(connection -> connection.getId())
            .toList();

        int promoted = 0;
        int skipped = 0;
        List<BulkPromoteFailureDTO> failures = new ArrayList<>();

        for (Long connectionId : privateIds) {
            try {
                self().promoteToWorkspace(workspaceId, connectionId);

                promoted++;
            } catch (ConfigurationException configurationException) {
                // Only CONNECTION_ALREADY_AT_TARGET_VISIBILITY is benign (the row was promoted by
                // another admin between our pre-filter read and our per-row call — a concurrent race).
                // Everything else, including INVALID_CONNECTION (cross-workspace mismatch) and
                // CONNECTION_IS_USED, is a real failure that the admin needs to see — classifying a
                // cross-workspace mismatch as "skipped" would hide an authorization bug and risks
                // enumerating connections across workspaces.
                if (configurationException.getErrorKey() == ConnectionErrorType.CONNECTION_ALREADY_AT_TARGET_VISIBILITY
                    .getErrorKey()) {

                    skipped++;

                    if (log.isDebugEnabled()) {
                        log.debug(
                            "Skipping promote for connection id={} — already at target visibility (race)",
                            connectionId);
                    }
                } else {
                    log.warn(
                        "Promote failed for connection id={} in workspace={} errorKey={}: {}",
                        connectionId, workspaceId, configurationException.getErrorKey(),
                        configurationException.getMessage());

                    failures.add(BulkPromoteFailureDTO.of(
                        connectionId,
                        Integer.toString(configurationException.getErrorKey()),
                        configurationExceptionMessage(configurationException)));
                }
            } catch (RuntimeException error) {
                log.warn(
                    "Unexpected failure promoting connection id={} in workspace={}",
                    connectionId, workspaceId, error);

                // Do NOT forward raw getMessage() for unknown exceptions — SQLException / DataAccessException
                // messages often contain JDBC URLs, bind parameters, or fully qualified table names that leak
                // schema detail into an admin toast. Sanitize to the simple class name.
                failures.add(BulkPromoteFailureDTO.of(
                    connectionId, BulkPromoteFailureDTO.UNEXPECTED_ERROR_CODE, sanitizedFailureMessage(error)));
            }
        }

        // The DTO's compact constructor enforces promoted + skipped + failed == attempted, so a loop
        // mis-account (double-counting, silently dropped row) fails loud here rather than reaching the
        // admin UI as a nonsense aggregate.
        return new BulkPromoteResultDTO(privateIds.size(), promoted, skipped, failures.size(), failures);
    }

    /**
     * Returns the {@link ConfigurationException} message as-is. These messages are authored by the facade layer itself,
     * so they are already safe to surface to admin UIs — no sanitization needed.
     */
    private static String configurationExceptionMessage(ConfigurationException exception) {
        String message = exception.getMessage();

        return message == null || message.isBlank() ? exception.getClass()
            .getSimpleName() : message;
    }

    /**
     * Sanitized message for unknown {@link RuntimeException}s. Does NOT forward {@code getMessage()} — for
     * {@link org.springframework.dao.DataAccessException},
     * {@link org.springframework.transaction.TransactionSystemException}, or JDBC-driver exceptions this string often
     * contains SQL state, bind parameters, schema names, or connection URLs that must not leak into an admin toast.
     * Returns the simple class name so the caller gets a stable, non-sensitive classifier; the full exception is logged
     * server-side at WARN where operators can still diagnose it.
     */
    private static String sanitizedFailureMessage(Throwable error) {
        return "Unexpected error: " + error.getClass()
            .getSimpleName();
    }

    private void validateConnectionBelongsToWorkspace(long workspaceId, long connectionId) {
        List<Long> workspaceConnectionIds = CollectionUtils.map(
            workspaceConnectionService.getWorkspaceConnections(workspaceId), WorkspaceConnection::getConnectionId);

        if (!workspaceConnectionIds.contains(connectionId)) {
            throw new ConfigurationException(
                "Connection id=%s does not belong to workspace id=%s".formatted(connectionId, workspaceId),
                ConnectionErrorType.INVALID_CONNECTION);
        }
    }

    private void validateConnectionNotUsedByDeployments(long connectionId) {
        if (projectDeploymentWorkflowService.isConnectionUsed(connectionId)) {
            throw new ConfigurationException(
                "Connection id=%s is used by active deployments".formatted(connectionId),
                ConnectionErrorType.CONNECTION_IS_USED);
        }
    }
}
