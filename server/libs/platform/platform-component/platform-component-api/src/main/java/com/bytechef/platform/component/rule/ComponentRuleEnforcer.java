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

package com.bytechef.platform.component.rule;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Extension point for administrative, conditional governance of a single action call. CE ships no implementation, so
 * every call is a no-op and behaviour is unchanged; EE ships a persistence-backed implementation that evaluates
 * admin-authored conditions against the call's actual input (and, after the fact, its output).
 *
 * <p>
 * This is the conditional sibling of {@link com.bytechef.platform.component.visibility.ComponentVisibilityProvider}:
 * visibility answers "may this action ever run", a rule answers "may <em>this</em> call run". They are separate
 * interfaces because visibility is also consulted on listing paths, where there is no call to inspect.
 * </p>
 *
 * <p>
 * <b>Suspend/resume:</b> the chokepoint that calls this SPI is also the entry point a resumed suspended action
 * re-enters. A suspending action calls {@link #checkBeforePerform} once per attempt to invoke {@code perform} — once
 * for the initial call and once more for each resume — because each attempt is a genuine, independent invocation that a
 * newly added BLOCK rule should be able to stop. {@link #recordAfterPerform}, by contrast, fires exactly once per
 * action instance, on whichever attempt actually produces real output; it is skipped on every attempt that only
 * produces a suspend marker. An implementation that counts invocations should expect an imbalance between the two
 * methods for any action that suspends at least once.
 * </p>
 *
 * @author Ivica Cardic
 */
public interface ComponentRuleEnforcer {

    /**
     * One action invocation, reduced to the facts a rule can be written against.
     *
     * @param componentName   the component the action belongs to
     * @param actionName      the action being invoked
     * @param inputParameters the action's resolved input parameters for this call
     * @param connectionId    the id of the first connection wired to this call, or {@code null} when the action takes
     *                        no connection. Note that {@code com.bytechef.platform.component.ComponentConnection} — the
     *                        record this chokepoint uses — carries a primitive {@code long connectionId}; the boxed,
     *                        nullable type here encodes "no connection", not "connection without an id".
     * @param jobId           the id of the job this call is running under, or {@code null} when there is none (for
     *                        example, the polyglot/code-workflow seam does not run under a job)
     * @param taskExecutionId the id of the task execution this call belongs to, or {@code null} for the same reason
     */
    @SuppressFBWarnings("EI")
    record ActionCall(
        String componentName, String actionName, Map<String, ?> inputParameters, @Nullable Long connectionId,
        @Nullable Long jobId, @Nullable Long taskExecutionId) {
    }

    /**
     * Runs before the action's {@code perform}.
     *
     * @return {@code null} to allow the call, or a human-readable reason to refuse it. The caller turns a non-null
     *         reason into a {@code ConfigurationException} carrying {@code ActionDefinitionErrorType.RULE_BLOCKED}; the
     *         implementation does not throw, because the error type lives in the CE service module and an
     *         implementation should not have to depend on it. An implementation that only tags records the match and
     *         returns {@code null}.
     */
    @Nullable
    String checkBeforePerform(ActionCall actionCall);

    /**
     * Runs after {@code perform} returns, with its output. Must never throw: the action has already run and its side
     * effects already happened, so failing here would turn an observability feature into a spurious execution failure.
     */
    void recordAfterPerform(ActionCall actionCall, @Nullable Object output);
}
