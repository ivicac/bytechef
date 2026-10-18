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

package com.bytechef.platform.webhook.web.rest;

import com.bytechef.platform.workflow.execution.facade.ApprovalInputException;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * @author Ivica Cardic
 */
final class ResumeExceptions {

    private ResumeExceptions() {
    }

    /**
     * Returns the rejection of an approval that needs form inputs, also when a tenant-context call wrapped it.
     */
    static @Nullable ApprovalInputException findApprovalInputException(Throwable throwable) {
        return findCause(throwable, ApprovalInputException.class);
    }

    /**
     * Tells whether the failure is a lost race against a concurrent resume of the same job.
     */
    static boolean isOptimisticLockingFailure(Throwable throwable) {
        return findCause(throwable, OptimisticLockingFailureException.class) != null;
    }

    private static <T extends Throwable> @Nullable T findCause(Throwable throwable, Class<T> causeType) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (causeType.isInstance(cause)) {
                return causeType.cast(cause);
            }
        }

        return null;
    }
}
