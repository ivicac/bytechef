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

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.scheduler.db.task.DbSchedulerTaskDescriptors;
import com.bytechef.platform.scheduler.db.task.DynamicWebhookRefreshData;
import com.bytechef.platform.scheduler.db.task.OneTimeResumeData;
import com.bytechef.platform.scheduler.db.task.PollingTriggerData;
import com.bytechef.platform.scheduler.db.task.ScheduleTriggerData;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceException;
import com.github.kagkarlsson.scheduler.task.SchedulableInstance;
import java.time.Instant;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

@ExtendWith(ObjectMapperSetupExtension.class)
class DbTriggerSchedulerTest {

    private final SchedulerClient schedulerClient = Mockito.mock(SchedulerClient.class);
    private final DbTriggerScheduler triggerScheduler = new DbTriggerScheduler(schedulerClient, 5);
    private final WorkflowExecutionId workflowExecutionId = WorkflowExecutionId.of(
        PlatformType.AUTOMATION, 1L, "workflow-uuid", "trigger_1");

    @Test
    void testScheduleScheduleTriggerStoresCronZoneAndOutputAccordingToData() {
        Mockito.when(schedulerClient.reschedule(Mockito.any(SchedulableInstance.class)))
            .thenReturn(false);

        triggerScheduler.scheduleScheduleTrigger(
            "0 0 9 * * ?", "Europe/Zagreb", Map.of("expression", "0 9 * * *"), workflowExecutionId);

        SchedulableInstance<?> instance = capturedScheduleIfNotExists();

        Assertions.assertThat(instance.getTaskName())
            .isEqualTo(DbSchedulerTaskDescriptors.SCHEDULE_TRIGGER_NAME);
        Assertions.assertThat(instance.getId())
            .isEqualTo(workflowExecutionId.toString());
        Assertions.assertThat((ScheduleTriggerData) instance.getTaskInstance()
            .getData())
            .isEqualTo(new ScheduleTriggerData("0 0 9 * * ?", "Europe/Zagreb", "{\"expression\":\"0 9 * * *\"}"));
    }

    @Test
    void testSchedulePollingTriggerIsDueNowWithConfiguredPeriod() {
        Mockito.when(schedulerClient.reschedule(Mockito.any(SchedulableInstance.class)))
            .thenReturn(false);
        Instant before = Instant.now();

        triggerScheduler.schedulePollingTrigger(workflowExecutionId);

        SchedulableInstance<?> instance = capturedScheduleIfNotExists();

        Assertions.assertThat(instance.getTaskName())
            .isEqualTo(DbSchedulerTaskDescriptors.POLLING_TRIGGER_NAME);
        Assertions.assertThat((PollingTriggerData) instance.getTaskInstance()
            .getData())
            .isEqualTo(new PollingTriggerData(5));
        Assertions.assertThat(instance.getNextExecutionTime(before))
            .isBetween(before, Instant.now());
    }

    @Test
    void testScheduleDynamicWebhookTriggerRefreshUsesExpiryAndConnectionId() {
        Mockito.when(schedulerClient.reschedule(Mockito.any(SchedulableInstance.class)))
            .thenReturn(false);
        Instant expiry = Instant.parse("2031-01-01T00:00:00Z");

        triggerScheduler.scheduleDynamicWebhookTriggerRefresh(expiry, "component", 1, workflowExecutionId, 77L);

        SchedulableInstance<?> instance = capturedScheduleIfNotExists();

        Assertions.assertThat(instance.getTaskName())
            .isEqualTo(DbSchedulerTaskDescriptors.DYNAMIC_WEBHOOK_REFRESH_NAME);
        Assertions.assertThat((DynamicWebhookRefreshData) instance.getTaskInstance()
            .getData())
            .isEqualTo(new DynamicWebhookRefreshData(77L));
        Assertions.assertThat(instance.getNextExecutionTime(Instant.now()))
            .isEqualTo(expiry);
    }

    @Test
    void testScheduleOneTimeTaskStoresContinueParametersOnlyWhenPresent() {
        Mockito.when(schedulerClient.reschedule(Mockito.any(SchedulableInstance.class)))
            .thenReturn(false);
        Instant executeAt = Instant.parse("2031-01-01T00:00:00Z");

        triggerScheduler.scheduleOneTimeTask(executeAt, Map.of("k", "v"), 42L);
        triggerScheduler.scheduleOneTimeTask(executeAt, Map.of(), 43L);

        ArgumentCaptor<SchedulableInstance<?>> captor = ArgumentCaptor.forClass(SchedulableInstance.class);

        Mockito.verify(schedulerClient, Mockito.times(2))
            .scheduleIfNotExists(captor.capture());

        Assertions.assertThat((OneTimeResumeData) captor.getAllValues()
            .get(0)
            .getTaskInstance()
            .getData())
            .isEqualTo(new OneTimeResumeData(42L, "{\"k\":\"v\"}"));
        Assertions.assertThat((OneTimeResumeData) captor.getAllValues()
            .get(1)
            .getTaskInstance()
            .getData())
            .isEqualTo(new OneTimeResumeData(43L, null));
    }

    @Test
    void testScheduleReplacesExistingInstanceViaReschedule() {
        Mockito.when(schedulerClient.reschedule(Mockito.any(SchedulableInstance.class)))
            .thenReturn(true);

        triggerScheduler.schedulePollingTrigger(workflowExecutionId);

        Mockito.verify(schedulerClient, Mockito.never())
            .scheduleIfNotExists(Mockito.any(SchedulableInstance.class));
    }

    @Test
    void testCancelPollingTriggerCancelsByTaskAndInstanceId() {
        triggerScheduler.cancelPollingTrigger(workflowExecutionId.toString());

        Mockito.verify(schedulerClient)
            .cancel(DbSchedulerTaskDescriptors.POLLING_TRIGGER.instanceId(workflowExecutionId.toString()));
    }

    @Test
    void testCancelDynamicWebhookTriggerRefreshTargetsItsOwnTask() {
        triggerScheduler.cancelDynamicWebhookTriggerRefresh(workflowExecutionId.toString());

        Mockito.verify(schedulerClient)
            .cancel(DbSchedulerTaskDescriptors.DYNAMIC_WEBHOOK_REFRESH.instanceId(workflowExecutionId.toString()));
    }

    @Test
    void testCancelOfMissingInstanceDoesNotThrow() {
        Mockito.doThrow(new TaskInstanceException("missing", "polling-trigger", "id"))
            .when(schedulerClient)
            .cancel(Mockito.any());

        Assertions.assertThatCode(() -> triggerScheduler.cancelScheduleTrigger(workflowExecutionId.toString()))
            .doesNotThrowAnyException();
    }

    private SchedulableInstance<?> capturedScheduleIfNotExists() {
        ArgumentCaptor<SchedulableInstance<?>> captor = ArgumentCaptor.forClass(SchedulableInstance.class);

        Mockito.verify(schedulerClient)
            .scheduleIfNotExists(captor.capture());

        return captor.getValue();
    }
}
