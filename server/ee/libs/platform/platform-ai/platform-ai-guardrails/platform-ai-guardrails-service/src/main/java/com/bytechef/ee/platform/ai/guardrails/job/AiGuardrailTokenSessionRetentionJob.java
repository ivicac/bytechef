/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.job;

import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailTokenSession;
import com.bytechef.ee.platform.ai.guardrails.repository.AiGuardrailTokenSessionRepository;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes conversation-scoped PII token sessions ({@code ai_guardrail_token_session}) that have not moved since
 * {@code bytechef.ai.guardrails.session.retention-days} ago.
 *
 * <p>
 * A row here holds a map whose VALUES are the caller's real PII, encrypted at rest only, not access-controlled by
 * conversation lifetime. {@link com.bytechef.platform.ai.sensitivedata.PiiTokenSessionStore#evict} removes a
 * conversation's row immediately when its chat is deleted, but a conversation that is simply abandoned — never deleted,
 * never revisited — would otherwise keep its tokens forever. This sweep is that backstop, keyed off
 * {@code last_modified_date} rather than {@code created_date} because a session is a single row that a save()
 * overwrites turn after turn: the column that matters is when it was last touched, not when it was first created.
 * </p>
 *
 * <p>
 * The default is 30 days, matching {@code AiGuardrailViolationRetentionJob} rather than the 365 {@code
 * AuditEventRetentionJob} uses — the shorter window is deliberate here too, since these rows exist purely to make a
 * conversation coherent across turns and have no audit value once the conversation is gone.
 * </p>
 *
 * <p>
 * Runs at 03:00, half an hour after the violation sweep at 02:30 and an hour after the audit sweep at 02:00, so the
 * three do not contend for the same window.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class AiGuardrailTokenSessionRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AiGuardrailTokenSessionRetentionJob.class);

    private final AiGuardrailTokenSessionRepository aiGuardrailTokenSessionRepository;
    private final long retentionDays;

    @SuppressFBWarnings("EI")
    public AiGuardrailTokenSessionRetentionJob(
        AiGuardrailTokenSessionRepository aiGuardrailTokenSessionRepository,
        @Value("${bytechef.ai.guardrails.session.retention-days:30}") long retentionDays) {

        this.aiGuardrailTokenSessionRepository = aiGuardrailTokenSessionRepository;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${bytechef.ai.guardrails.session.retention-cron:0 0 3 * * *}")
    public void purgeExpiredTokenSessions() {
        Instant cutoff = Instant.now()
            .minus(Duration.ofDays(retentionDays));

        List<AiGuardrailTokenSession> expired = aiGuardrailTokenSessionRepository.findByLastModifiedDateBefore(cutoff);

        if (expired.isEmpty()) {
            log.debug("Guardrail token session retention: no sessions older than {}", cutoff);

            return;
        }

        aiGuardrailTokenSessionRepository.deleteAll(expired);

        log.info("Guardrail token session retention: deleted {} session(s) older than {}", expired.size(), cutoff);
    }
}
