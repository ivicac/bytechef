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

package com.bytechef.platform.scheduler.db;

import com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors;
import com.bytechef.platform.scheduler.db.task.OAuth2TokenRefreshData;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.SchedulableInstance;
import java.time.Duration;
import java.time.Instant;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class DbConnectionRefreshSchedulerTest {

    private final SchedulerClient schedulerClient = Mockito.mock(SchedulerClient.class);
    private final DbConnectionRefreshScheduler connectionRefreshScheduler =
        new DbConnectionRefreshScheduler(schedulerClient);

    @Test
    void testScheduleConnectionRefreshFiresFiveMinutesBeforeExpiryUnderTenantPrefixedId() {
        Mockito.when(schedulerClient.reschedule(Mockito.any(SchedulableInstance.class)))
            .thenReturn(false);
        Instant expiry = Instant.parse("2031-01-01T00:00:00Z");

        connectionRefreshScheduler.scheduleConnectionRefresh(77L, expiry, "000001");

        ArgumentCaptor<SchedulableInstance<?>> captor = ArgumentCaptor.forClass(SchedulableInstance.class);

        Mockito.verify(schedulerClient)
            .scheduleIfNotExists(captor.capture());

        SchedulableInstance<?> instance = captor.getValue();

        Assertions.assertThat(instance.getTaskName())
            .isEqualTo(DbSchedulerTaskDescriptors.OAUTH2_TOKEN_REFRESH_NAME);
        Assertions.assertThat(instance.getId())
            .isEqualTo("00000177");
        Assertions.assertThat((OAuth2TokenRefreshData) instance.getTaskInstance()
            .getData())
            .isEqualTo(new OAuth2TokenRefreshData(77L, "000001"));
        Assertions.assertThat(instance.getNextExecutionTime(Instant.now()))
            .isEqualTo(expiry.minus(Duration.ofMinutes(5)));
    }

    @Test
    void testCancelConnectionRefreshCancelsTenantPrefixedId() {
        connectionRefreshScheduler.cancelConnectionRefresh(77L, "000001");

        Mockito.verify(schedulerClient)
            .cancel(DbSchedulerTaskDescriptors.OAUTH2_TOKEN_REFRESH.instanceId("00000177"));
    }
}
