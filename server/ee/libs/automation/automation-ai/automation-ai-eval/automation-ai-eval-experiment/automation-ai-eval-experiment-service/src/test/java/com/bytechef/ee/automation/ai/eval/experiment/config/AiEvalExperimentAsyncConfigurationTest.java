/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.eval.experiment.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Pins the rejection-handler contract on the experiment task executor. Spring's default rejection handler is
 * {@code AbortPolicy} (throws {@link java.util.concurrent.RejectedExecutionException}); the gateway deliberately
 * substitutes {@link ThreadPoolExecutor.CallerRunsPolicy} so back-pressure manifests as a slow
 * {@code POST /experiments} rather than silent task loss. A regression that swaps the handler back to AbortPolicy (or
 * omits it entirely) would silently drop experiment submissions under sustained load.
 *
 * @author Ivica Cardic
 * @version ee
 */
class AiEvalExperimentAsyncConfigurationTest {

    @Test
    void testRejectsExcessiveTasksByRunningOnCallerThread() throws InterruptedException {
        AiEvalExperimentAsyncConfiguration configuration = new AiEvalExperimentAsyncConfiguration();
        ThreadPoolTaskExecutor executor = configuration.aiEvalExperimentTaskExecutor();

        try {
            // ThreadPoolExecutor's growth algorithm: spawn core threads first (2), then queue up to capacity
            // (100), then spawn additional threads up to max (8), then reject. So total in-flight capacity is
            // 8 + 100 = 108. Submit 108 blocking tasks to fill capacity, then one more to trigger
            // CallerRunsPolicy. The 109th task must execute synchronously on the calling thread.
            CountDownLatch holdLatch = new CountDownLatch(1);

            Runnable blockingTask = () -> {
                try {
                    holdLatch.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread()
                        .interrupt();
                }
            };

            for (int taskIndex = 0; taskIndex < 108; taskIndex++) {
                executor.submit(blockingTask);
            }

            // Wait for the pool to reach saturation: 8 active workers + 100 queued. Polling avoids relying on a
            // synchronization primitive in the queued tasks (those never run until the latch releases).
            long deadline = System.currentTimeMillis() + 5_000;

            while (System.currentTimeMillis() < deadline) {
                ThreadPoolExecutor underlying = executor.getThreadPoolExecutor();

                if (underlying.getActiveCount() == 8 && underlying.getQueue()
                    .size() == 100) {
                    break;
                }

                Thread.sleep(50);
            }

            ThreadPoolExecutor underlying = executor.getThreadPoolExecutor();

            assertThat(underlying.getActiveCount()).as("All 8 worker threads should be active")
                .isEqualTo(8);
            assertThat(underlying.getQueue()
                .size()).as("Queue should be at capacity (100)")
                    .isEqualTo(100);

            AtomicReference<Thread> runningThread = new AtomicReference<>();
            Thread submittingThread = Thread.currentThread();

            executor.submit(() -> runningThread.set(Thread.currentThread()));

            assertThat(runningThread.get())
                .as("CallerRunsPolicy must execute the rejected task on the submitting thread")
                .isSameAs(submittingThread);

            holdLatch.countDown();
        } finally {
            executor.getThreadPoolExecutor()
                .shutdownNow();
        }
    }
}
