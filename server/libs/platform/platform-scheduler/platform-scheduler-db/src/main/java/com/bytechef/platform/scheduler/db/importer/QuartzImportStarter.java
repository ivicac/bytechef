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

package com.bytechef.platform.scheduler.db.importer;

import static com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors.QUARTZ_IMPORT;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * Queues the one-time quartz-import task on every startup while db-scheduler is the active provider. In a cluster every
 * node calls {@code scheduleIfNotExists}; only the first row wins, so exactly one node runs the import.
 *
 * @author Ivica Cardic
 */
public class QuartzImportStarter {

    static final String STARTUP_INSTANCE_ID = "startup";

    private static final Logger log = LoggerFactory.getLogger(QuartzImportStarter.class);

    private final SchedulerClient schedulerClient;

    @SuppressFBWarnings("EI")
    public QuartzImportStarter(SchedulerClient schedulerClient) {
        this.schedulerClient = schedulerClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        boolean queued = schedulerClient.scheduleIfNotExists(
            QUARTZ_IMPORT.instance(STARTUP_INSTANCE_ID)
                .scheduledTo(Instant.now()));

        if (log.isDebugEnabled()) {
            log.debug("Quartz import task {}", queued ? "queued" : "already queued by another node");
        }
    }
}
