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

import com.bytechef.automation.assetfile.cleanup.AssetFileOrphanBlobRecorder;
import com.bytechef.automation.assetfile.config.AutomationAssetFileQuotaProperties;
import com.bytechef.automation.assetfile.domain.AssetFile;
import com.bytechef.automation.assetfile.exception.AssetFileQuotaExceededException;
import com.bytechef.automation.assetfile.file.storage.AssetFileFileStorage;
import com.bytechef.automation.assetfile.metric.AssetFileMetrics;
import com.bytechef.exception.QuotaLimitExceededException;
import com.bytechef.file.storage.domain.FileEntry;
import com.bytechef.platform.plan.provider.PlanLimitsProvider;
import com.bytechef.platform.ratelimit.PlanLimitRejectionCounter;
import com.bytechef.tenant.TenantContext;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Write-path bookkeeping shared by {@link AssetFileFacadeImpl} (for {@code createFromAi}, {@code createBinaryFromAi},
 * {@code cloneToEnvironment} and {@code restoreVersion}, which stayed on the membership-guarded facade) and
 * {@link AssetFileSystemFacadeImpl} (for {@code createFromUpload} and the {@code *InWorkspace} mutations, which moved
 * to the ownership-checked one): per-workspace and per-tenant quota enforcement, unique-name resolution, workspace-id
 * resolution, and blob cleanup after a failed or superseded write. Kept as one implementation, injected into both, so
 * the two facades cannot drift on quota semantics.
 *
 * @author Ivica Cardic
 */
@Service
@SuppressFBWarnings("EI2")
class AssetFileWriteSupport {

    private static final Logger log = LoggerFactory.getLogger(AssetFileWriteSupport.class);

    private final AssetFileService service;
    private final AssetFileFileStorage fileStorage;
    private final AssetFileMetrics metrics;
    private final AssetFileOrphanBlobRecorder orphanBlobRecorder;
    private final ObjectProvider<PlanLimitRejectionCounter> planLimitRejectionCounterObjectProvider;
    private final ObjectProvider<PlanLimitsProvider> planLimitsProviderObjectProvider;
    private final AutomationAssetFileQuotaProperties quota;

    @SuppressFBWarnings("EI")
    AssetFileWriteSupport(
        AssetFileService service,
        AssetFileFileStorage fileStorage,
        AssetFileMetrics metrics,
        AssetFileOrphanBlobRecorder orphanBlobRecorder,
        ObjectProvider<PlanLimitRejectionCounter> planLimitRejectionCounterObjectProvider,
        ObjectProvider<PlanLimitsProvider> planLimitsProviderObjectProvider,
        AutomationAssetFileQuotaProperties quota) {

        this.service = service;
        this.fileStorage = fileStorage;
        this.metrics = metrics;
        this.orphanBlobRecorder = orphanBlobRecorder;
        this.planLimitRejectionCounterObjectProvider = planLimitRejectionCounterObjectProvider;
        this.planLimitsProviderObjectProvider = planLimitsProviderObjectProvider;
        this.quota = quota;
    }

    String resolveUniqueName(Long workspaceId, int environment, String candidate) {
        Optional<AssetFile> existing = service.fetchByWorkspaceIdAndEnvironmentAndName(
            workspaceId, environment, candidate);

        if (existing.isEmpty()) {
            return candidate;
        }

        int suffix = 2;

        while (true) {
            String attempt = appendSuffix(candidate, suffix);

            if (service.fetchByWorkspaceIdAndEnvironmentAndName(workspaceId, environment, attempt)
                .isEmpty()) {
                return attempt;
            }

            suffix++;
        }
    }

    void enforceWorkspaceQuota(Long workspaceId, int environment, long additionalBytes) {
        enforcePlanStorageQuota(additionalBytes);

        long limit = quota.perWorkspaceTotalBytes();

        if (limit < 0) {
            return;
        }

        long current = service.sumSizeBytesByWorkspaceIdAndEnvironment(workspaceId, environment);

        if (current + additionalBytes > limit) {
            throw new AssetFileQuotaExceededException(
                "Workspace total %d would exceed limit %d".formatted(current + additionalBytes, limit),
                current + additionalBytes, limit);
        }
    }

    Long resolveWorkspaceIdForFile(AssetFile assetFile) {
        Long workspaceId = assetFile.getWorkspaceId();

        if (workspaceId == null) {
            throw new IllegalStateException(
                "No workspace id set on asset file %d".formatted(assetFile.getId()));
        }

        return workspaceId;
    }

    /**
     * Deletes a just-stored blob whose owning DB row failed to persist, attaching any cleanup failure as a suppressed
     * exception on the original cause. Without this, an S3/network failure inside {@code deleteFile} would replace the
     * real database exception with a misleading "blob delete failed" — operators would chase a storage red herring
     * instead of the row-level violation that actually triggered the rollback. The recorder commits in its own
     * transaction ({@code REQUIRES_NEW}), so the queue row survives the rollback of the caller's own transaction that
     * is already in flight here.
     */
    void safeDeleteAfterRollback(FileEntry stored, RuntimeException originalCause) {
        try {
            fileStorage.deleteFile(stored);
        } catch (RuntimeException cleanupException) {
            log.warn(
                "Failed to clean up orphaned blob after rollback (original cause: {})",
                originalCause.toString(), cleanupException);

            originalCause.addSuppressed(cleanupException);

            orphanBlobRecorder.record(stored);
        }
    }

    /**
     * Deletes {@code fileEntry} once the enclosing transaction commits, or immediately when called outside a
     * transaction. Deferring past commit means a rollback of the surrounding write never leaves a blob deleted out from
     * under a row that the rollback just restored.
     */
    void scheduleBlobDeleteAfterCommit(Long id, FileEntry fileEntry) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteBlobOrEnqueueOrphan(id, fileEntry);

            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

            @Override
            public void afterCommit() {
                deleteBlobOrEnqueueOrphan(id, fileEntry);
            }
        });
    }

    private String appendSuffix(String name, int suffix) {
        int dotIndex = name.lastIndexOf('.');

        if (dotIndex <= 0) {
            return name + "-" + suffix;
        }

        return name.substring(0, dotIndex) + "-" + suffix + name.substring(dotIndex);
    }

    /**
     * Rejects the write when the tenant-wide asset-file total plus the incoming bytes would exceed the plan's
     * {@code maxStorageBytes}. Runs alongside the operator-configured per-workspace quota — the tenant ceiling spans
     * all workspaces and environments. A null limit (or no {@link PlanLimitsProvider} bean) means unlimited.
     */
    private void enforcePlanStorageQuota(long additionalBytes) {
        PlanLimitsProvider planLimitsProvider = planLimitsProviderObjectProvider.getIfAvailable();

        if (planLimitsProvider == null) {
            return;
        }

        Long maxStorageBytes = planLimitsProvider.getPlanLimits(TenantContext.getCurrentTenantId())
            .maxStorageBytes();

        if (maxStorageBytes == null) {
            return;
        }

        long current = service.sumSizeBytes();

        if (current + additionalBytes > maxStorageBytes) {
            countQuotaRejection();

            throw new QuotaLimitExceededException(
                "Storage quota exceeded: the plan allows at most %d byte(s) of asset storage".formatted(
                    maxStorageBytes));
        }
    }

    private void countQuotaRejection() {
        PlanLimitRejectionCounter planLimitRejectionCounter = planLimitRejectionCounterObjectProvider.getIfAvailable();

        if (planLimitRejectionCounter != null) {
            planLimitRejectionCounter.increment("storage");
        }
    }

    private void deleteBlobOrEnqueueOrphan(Long id, FileEntry fileEntry) {
        try {
            fileStorage.deleteFile(fileEntry);
        } catch (RuntimeException exception) {
            log.warn("Failed to delete blob for workspace file {}", id, exception);
            metrics.recordBlobOrphan(exception.getClass()
                .getSimpleName());
            orphanBlobRecorder.record(fileEntry);
        }
    }
}
