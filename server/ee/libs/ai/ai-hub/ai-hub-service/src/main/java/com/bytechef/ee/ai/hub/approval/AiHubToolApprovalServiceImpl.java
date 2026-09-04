/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.approval;

import com.bytechef.ee.ai.hub.approval.repository.AiHubToolApprovalRepository;
import com.bytechef.ee.ai.hub.exception.NotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Service
@ConditionalOnProperty(prefix = "bytechef.ai.hub", name = "enabled", havingValue = "true")
@Transactional
public class AiHubToolApprovalServiceImpl implements AiHubToolApprovalService {

    private static final Duration DEFAULT_EXPIRY = Duration.ofHours(24);

    private final AiHubToolApprovalRepository repository;
    private final Clock clock;

    /**
     * Marked {@code @Autowired} because this class has two constructors: Spring auto-selects a constructor only when
     * there is exactly one candidate, and would otherwise fall back to a no-arg constructor that does not exist,
     * failing context startup.
     */
    @Autowired
    public AiHubToolApprovalServiceImpl(AiHubToolApprovalRepository repository) {
        this(repository, Clock.systemUTC());
    }

    AiHubToolApprovalServiceImpl(AiHubToolApprovalRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public AiHubToolApproval createPending(AiHubToolApproval approval) {
        approval.setStatus(AiHubToolApproval.Status.PENDING);

        if (approval.getExpiresAt() == null) {
            approval.setExpiresAt(Instant.now(clock)
                .plus(DEFAULT_EXPIRY));
        }

        return repository.save(approval);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AiHubToolApproval> findPending(long chatId) {
        return repository.findFirstByChatIdAndStatus(chatId, AiHubToolApproval.Status.PENDING.ordinal());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiHubToolApproval> list(long chatId) {
        return repository.findAllByChatIdOrderByCreatedDateDesc(chatId);
    }

    @Override
    @Transactional(readOnly = true)
    public AiHubToolApproval get(long approvalId) {
        return repository.findById(approvalId)
            .orElseThrow(() -> new NotFoundException("AiHubToolApproval", approvalId));
    }

    @Override
    public AiHubToolApproval save(AiHubToolApproval approval) {
        return repository.save(approval);
    }

    @Override
    public int supersedePending(long chatId) {
        List<AiHubToolApproval> pendingApprovals =
            repository.findAllByChatIdAndStatus(chatId, AiHubToolApproval.Status.PENDING.ordinal());

        Instant now = Instant.now(clock);

        for (AiHubToolApproval pendingApproval : pendingApprovals) {
            pendingApproval.setStatus(AiHubToolApproval.Status.SUPERSEDED);
            pendingApproval.setDecidedAt(now);

            repository.save(pendingApproval);
        }

        return pendingApprovals.size();
    }

    @Override
    public void deleteByChat(long chatId) {
        repository.deleteAllByChatId(chatId);
    }
}
