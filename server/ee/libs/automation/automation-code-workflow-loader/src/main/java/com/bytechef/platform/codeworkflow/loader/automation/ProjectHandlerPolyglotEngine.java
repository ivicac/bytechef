/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.platform.codeworkflow.loader.automation;

import com.bytechef.automation.project.ProjectHandler;
import com.bytechef.automation.project.definition.ProjectDefinition;
import com.bytechef.platform.component.polyglot.ComponentActionInvoker;
import com.bytechef.platform.component.polyglot.ComponentCatalog;
import com.bytechef.platform.component.polyglot.ComponentProxyObject;
import com.bytechef.platform.component.polyglot.PolyglotSandbox;
import com.bytechef.platform.component.polyglot.PolyglotValues;
import com.bytechef.workflow.definition.CompositeTaskDefinition;
import com.bytechef.workflow.definition.ConnectionRequirement;
import com.bytechef.workflow.definition.Input;
import com.bytechef.workflow.definition.Output;
import com.bytechef.workflow.definition.TaskContext;
import com.bytechef.workflow.definition.TaskDefinition;
import com.bytechef.workflow.definition.TriggerDefinition;
import com.bytechef.workflow.definition.WorkflowDefinition;
import com.bytechef.workflow.definition.WorkflowTaskDefinition;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class ProjectHandlerPolyglotEngine {

    /**
     * Evaluates the code workflow's definition script. The script is user-supplied and its top level runs in full here,
     * so it is loaded through the same strict sandbox its task performs use rather than a permissive context of its own
     * - the ceilings apply to declaring a workflow, not only to running one.
     */
    static ProjectHandler load(String languageId, String script) {
        return PolyglotSandbox.call(languageId, polyglotContext -> {
            Value value = polyglotContext.eval(languageId, script);

            String name = Objects.requireNonNull(getMember(value, "name"));
            String description = getMember(value, "description");
            String version = getMember(value, "version");

            List<WorkflowDefinition> workflows = getWorkflows(value)
                .stream()
                .map(workflow -> (WorkflowDefinition) new PolyglotWorkflowDefinition(
                    (String) workflow.get("name"), (String) workflow.get("label"),
                    (String) workflow.get("description"),
                    toTaskDefinitions(
                        (String) workflow.get("name"), (List<?>) workflow.get("tasks"), languageId, script),
                    toInputs(workflow.get("inputs")), toOutputs(workflow.get("outputs")),
                    toTriggers(workflow.get("triggers"))))
                .toList();

            return () -> new PolyglotProjectDefinition(name, description, version, workflows);
        });
    }

    @SuppressWarnings("unchecked")
    private static Object executePerform(
        String workflowName, String taskName, String languageId, String script, TaskContext taskContext) {

        return PolyglotSandbox.call(languageId, polyglotContext -> {
            Value value = polyglotContext.eval(languageId, script);

            List<Map<String, Object>> workflows = getWorkflows(value);

            List<Map<String, Object>> tasks = (List<Map<String, Object>>) workflows.stream()
                .filter(workflow -> workflowName.equals(workflow.get("name")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Workflow name=%s not found".formatted(workflowName)))
                .get("tasks");

            Map<String, Object> task = findTask(tasks, taskName);

            if (task == null) {
                throw new IllegalArgumentException("Task name=%s not found".formatted(taskName));
            }

            Function<Object[], Object> perform = (Function<Object[], Object>) task.get("perform");

            Object result = perform.apply(new Object[] {
                toGuestContext(taskContext, languageId)
            });

            // The guest function's return value may be a live view backed by the polyglot context (e.g. a JS object
            // mapped to a PolyglotMap), which becomes unusable once the context closes below. Copy it into plain
            // Java collections while the context is still open.
            return PolyglotValues.copyFromPolyglotContext(result);
        });
    }

    /**
     * Finds a task's guest map by name, descending into groups. Only an entry carrying a {@code perform} matches — a
     * group shares the namespace but performs no work of its own.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> findTask(List<?> tasks, String taskName) {
        for (Object entry : tasks) {
            Map<String, Object> task = (Map<String, Object>) entry;

            if (taskName.equals(task.get("name")) && task.get("perform") != null) {
                return task;
            }

            if (task.get("tasks") instanceof List<?> nestedTasks) {
                Map<String, Object> nestedTask = findTask(nestedTasks, taskName);

                if (nestedTask != null) {
                    return nestedTask;
                }
            }

            if (task.get("branches") instanceof List<?> branches) {
                for (Object branch : branches) {
                    if (branch instanceof List<?> branchTasks) {
                        Map<String, Object> branchTask = findTask(branchTasks, taskName);

                        if (branchTask != null) {
                            return branchTask;
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Builds the guest-facing {@code context} argument handed to a code workflow task's {@code perform} function:
     * {@code component} exposes the shared component proxy chain
     * ({@code context.component.<componentName>.<actionName>(input, connectionName, clusterElements)}), {@code input()}
     * / {@code input(name)} return the workflow's inputs and prior task outputs, {@code connection(name)} returns a
     * wired connection's parameters, and {@code log} delegates to {@link TaskContext#log}. Both dispatch through the
     * {@link TaskContext} the engine received at perform time; a {@code null} context (a legacy zero-argument
     * invocation) fails only when the guest actually uses one of them.
     */
    private static ProxyObject toGuestContext(TaskContext taskContext, String languageId) {
        ComponentActionInvoker componentActionInvoker =
            (componentName, actionName, input, connectionName, clusterElements) -> {
                if (taskContext == null) {
                    throw new IllegalStateException("A TaskContext is not available");
                }

                return taskContext.component(componentName, actionName, input, connectionName, clusterElements);
            };

        // Component and action existence is validated host-side when an invocation dispatches through the
        // TaskContext, so the guest-facing catalog answers existence checks optimistically.
        ComponentCatalog componentCatalog = new ComponentCatalog() {

            @Override
            public boolean hasComponent(String name) {
                return true;
            }

            @Override
            public boolean hasAction(String componentName, String actionName) {
                return true;
            }
        };

        return ProxyObject.fromMap(
            Map.of(
                "component", new ComponentProxyObject(languageId, componentActionInvoker, componentCatalog),
                "input", (ProxyExecutable) arguments -> {
                    if (taskContext == null) {
                        throw new IllegalStateException("A TaskContext is not available");
                    }

                    // Zero arguments hands back the whole snapshot; one argument reads a single entry, failing on an
                    // unknown name rather than yielding an undefined the guest would only notice much later.
                    if (arguments.length == 0) {
                        return PolyglotValues.copyToGuestValue(taskContext.input(), languageId);
                    }

                    return PolyglotValues.copyToGuestValue(taskContext.input(arguments[0].asString()), languageId);
                },
                "parameters", (ProxyExecutable) arguments -> {
                    if (taskContext == null) {
                        throw new IllegalStateException("A TaskContext is not available");
                    }

                    return PolyglotValues.copyToGuestValue(taskContext.parameters(), languageId);
                },
                "connection", (ProxyExecutable) arguments -> {
                    if (taskContext == null) {
                        throw new IllegalStateException("A TaskContext is not available");
                    }

                    try {
                        return PolyglotValues.copyToGuestValue(
                            taskContext.connection(arguments[0].asString()), languageId);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                },
                "log", (ProxyExecutable) arguments -> {
                    if (taskContext == null) {
                        throw new IllegalStateException("A TaskContext is not available");
                    }

                    taskContext.log(TaskContext.LogLevel.of(arguments[0].asString()), arguments[1].asString());

                    return null;
                }));
    }

    private static String getMember(Value value, String name) {
        value = value.getMember(name);

        return value == null ? null : value.as(String.class);
    }

    /**
     * Walks the guest definition's {@code workflows} member into host-native collections.
     *
     * <p>
     * Walked rather than mapped with {@code Value.as(TypeLiteral)}: the perform contexts this runs in are built under a
     * {@link org.graalvm.polyglot.SandboxPolicy} that forbids host object mappings of mutable target types. The walk
     * keeps a task's {@code perform} member callable.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> getWorkflows(Value value) {
        return (List<Map<String, Object>>) PolyglotValues.copyToJavaValue(value.getMember("workflows"));
    }

    private static List<WorkflowTaskDefinition> toTaskDefinitions(
        String workflowName, List<?> tasks, String languageId, String script) {

        if (tasks == null) {
            return List.of();
        }

        List<WorkflowTaskDefinition> taskDefinitions = new ArrayList<>();
        Set<String> names = new HashSet<>();

        for (Object entry : tasks) {
            Map<?, ?> task = (Map<?, ?>) entry;

            Object type = task.get("type");

            if (type == null) {
                taskDefinitions.add(toLeafTaskDefinition(workflowName, task, languageId, script, names));
            } else {
                taskDefinitions.add(
                    toCompositeTaskDefinition(workflowName, task, String.valueOf(type), languageId, script, names));
            }
        }

        return taskDefinitions;
    }

    /**
     * Reads a group of tasks the engine runs concurrently: {@code type: "parallel"} with a {@code tasks} list, or
     * {@code type: "forkJoin"} with a {@code branches} list of task lists.
     *
     * <p>
     * A group carries no {@code perform} of its own, and a group inside a group is rejected — the dispatchers support
     * it, but the semantics of a branch fanning out again while its siblings run have not been worked through.
     */
    private static CompositeTaskDefinition toCompositeTaskDefinition(
        String workflowName, Map<?, ?> task, String type, String languageId, String script, Set<String> names) {

        String name = (String) task.get("name");

        addName(names, name);

        if (task.get("perform") != null) {
            throw new IllegalArgumentException(
                "Task %s groups other tasks, so it cannot declare a perform of its own".formatted(name));
        }

        CompositeTaskDefinition.Type compositeType = toCompositeType(name, type);

        List<TaskDefinition> tasks = new ArrayList<>();
        List<List<TaskDefinition>> branches = new ArrayList<>();

        if (compositeType == CompositeTaskDefinition.Type.PARALLEL) {
            Object nestedTasks = task.get("tasks");

            if (!(nestedTasks instanceof List<?> nestedTaskList) || nestedTaskList.isEmpty()) {
                throw new IllegalArgumentException("Parallel task %s must declare a non-empty tasks list"
                    .formatted(name));
            }

            for (Object nestedTask : nestedTaskList) {
                tasks.add(toNestedTaskDefinition(workflowName, nestedTask, languageId, script, names));
            }
        } else {
            Object declaredBranches = task.get("branches");

            if (!(declaredBranches instanceof List<?> branchList) || branchList.isEmpty()) {
                throw new IllegalArgumentException("Fork/join task %s must declare a non-empty branches list"
                    .formatted(name));
            }

            for (Object branch : branchList) {
                if (!(branch instanceof List<?> branchTasks) || branchTasks.isEmpty()) {
                    throw new IllegalArgumentException(
                        "Each branch of fork/join task %s must be a non-empty list of tasks".formatted(name));
                }

                List<TaskDefinition> branchTaskDefinitions = new ArrayList<>();

                for (Object branchTask : branchTasks) {
                    branchTaskDefinitions.add(
                        toNestedTaskDefinition(workflowName, branchTask, languageId, script, names));
                }

                branches.add(branchTaskDefinitions);
            }
        }

        return new PolyglotCompositeTaskDefinition(
            name, (String) task.get("label"), (String) task.get("description"), compositeType, tasks, branches);
    }

    private static CompositeTaskDefinition.Type toCompositeType(String name, String type) {
        String normalizedType = type.replace("-", "")
            .replace("_", "");

        if ("parallel".equalsIgnoreCase(normalizedType)) {
            return CompositeTaskDefinition.Type.PARALLEL;
        }

        if ("forkjoin".equalsIgnoreCase(normalizedType)) {
            return CompositeTaskDefinition.Type.FORK_JOIN;
        }

        throw new IllegalArgumentException(
            "Task %s declares type %s; a task's type may only be parallel or forkJoin".formatted(name, type));
    }

    private static TaskDefinition toNestedTaskDefinition(
        String workflowName, Object nestedTask, String languageId, String script, Set<String> names) {

        Map<?, ?> task = (Map<?, ?>) nestedTask;

        if (task.get("type") != null) {
            throw new IllegalArgumentException(
                "Task %s is nested inside a group, and a group inside a group is not supported"
                    .formatted(task.get("name")));
        }

        return toLeafTaskDefinition(workflowName, task, languageId, script, names);
    }

    private static TaskDefinition toLeafTaskDefinition(
        String workflowName, Map<?, ?> task, String languageId, String script, Set<String> names) {

        String name = (String) task.get("name");

        addName(names, name);

        Object parameters = task.get("parameters");

        return new PolyglotTaskDefinition(
            workflowName, name, (String) task.get("label"), (String) task.get("description"),
            toConnectionRequirements(task.get("connections")),
            parameters instanceof Map<?, ?> parameterMap
                ? (Map<String, ?>) PolyglotValues.copyFromPolyglotContext(parameterMap) : null,
            languageId, script);
    }

    /**
     * Task names share one namespace across the whole workflow, nesting included: a name is what the engine keys a
     * task's output by and what {@code context.input(name)} looks up, so a duplicate would make one task's output
     * unreachable.
     */
    private static void addName(Set<String> names, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Every task must declare a name");
        }

        if (!names.add(name)) {
            throw new IllegalArgumentException("Task name %s is declared more than once".formatted(name));
        }
    }

    /**
     * Reads a task's declared connections. Two shapes are accepted: a LIST of
     * {@code {componentName, componentVersion?, name}} entries, and a MAP keyed by connection name whose values are
     * {@code {componentName, componentVersion?}} — the latter mirrors the shape the generated workflow definition
     * carries, so sources written against it parse too.
     */
    private static List<ConnectionRequirement> toConnectionRequirements(Object connections) {
        if (connections == null) {
            return null;
        }

        if (connections instanceof List<?> connectionList) {
            return connectionList.stream()
                .map(connection -> (Map<?, ?>) connection)
                .map(
                    connection -> toConnectionRequirement(
                        (String) connection.get("name"), connection.get("componentName"),
                        connection.get("componentVersion")))
                .toList();
        }

        if (connections instanceof Map<?, ?> connectionMap) {
            List<ConnectionRequirement> connectionRequirements = new ArrayList<>();

            for (Map.Entry<?, ?> entry : connectionMap.entrySet()) {
                Map<?, ?> connection = (Map<?, ?>) entry.getValue();

                connectionRequirements.add(
                    toConnectionRequirement(
                        String.valueOf(entry.getKey()), connection.get("componentName"),
                        connection.get("componentVersion")));
            }

            return connectionRequirements;
        }

        throw new IllegalArgumentException(
            "A task's connections must be a list or a map keyed by connection name, got: " + connections);
    }

    private static ConnectionRequirement toConnectionRequirement(
        String name, Object componentName, Object componentVersion) {

        return new PolyglotConnectionRequirement(
            (String) componentName, componentVersion instanceof Number number ? number.intValue() : null, name);
    }

    private record PolyglotConnectionRequirement(String componentName, Integer componentVersion, String name)
        implements ConnectionRequirement {

        @Override
        public String getComponentName() {
            return componentName;
        }

        @Override
        public OptionalInt getComponentVersion() {
            return componentVersion == null ? OptionalInt.empty() : OptionalInt.of(componentVersion);
        }

        @Override
        public String getName() {
            return name;
        }
    }

    @SuppressFBWarnings("EI")
    private record PolyglotCompositeTaskDefinition(
        String name, String label, String description, Type type, List<TaskDefinition> tasks,
        List<List<TaskDefinition>> branches)
        implements CompositeTaskDefinition {

        @Override
        public List<? extends List<? extends TaskDefinition>> getBranches() {
            return branches;
        }

        @Override
        public Optional<String> getDescription() {
            return Optional.ofNullable(description);
        }

        @Override
        public Optional<String> getLabel() {
            return Optional.ofNullable(label);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public List<? extends TaskDefinition> getTasks() {
            return tasks;
        }

        @Override
        public Type getType() {
            return type;
        }
    }

    @SuppressFBWarnings("EI")
    private record PolyglotTaskDefinition(
        String workflowName, String name, String label, String description,
        List<ConnectionRequirement> connections, Map<String, ?> parameters, String languageId, String script)
        implements TaskDefinition {

        @Override
        public Optional<List<? extends ConnectionRequirement>> getConnections() {
            return Optional.ofNullable(connections);
        }

        @Override
        public Optional<String> getDescription() {
            return Optional.ofNullable(description);
        }

        @Override
        public Optional<String> getLabel() {
            return Optional.ofNullable(label);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Optional<Map<String, ?>> getParameters() {
            return Optional.ofNullable(parameters);
        }

        @Override
        public PerformFunction getPerform() {
            return new PerformFunction() {

                @Override
                public Object apply() {
                    return executePerform(workflowName, name, languageId, script, null);
                }

                @Override
                public Object apply(TaskContext taskContext) {
                    return executePerform(workflowName, name, languageId, script, taskContext);
                }
            };
        }
    }

    private record PolyglotProjectDefinition(
        String name, String description, String version, List<WorkflowDefinition> workflows)
        implements ProjectDefinition {

        @Override
        public Optional<String> getCategory() {
            return Optional.empty();
        }

        @Override
        public Optional<String> getDescription() {
            return Optional.ofNullable(description);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getVersion() {
            return version == null ? "0.0.1" : version;
        }

        @Override
        public List<WorkflowDefinition> getWorkflows() {
            return List.copyOf(workflows);
        }

        @Override
        public Optional<List<String>> getTags() {
            return Optional.empty();
        }
    }

    /**
     * Reads a workflow's declared inputs. Absent means the workflow declares none — the platform then has no contract
     * to prompt for or validate against, which is what every code workflow looked like before inputs could be declared
     * at all.
     */
    private static List<Input> toInputs(Object inputs) {
        if (!(inputs instanceof List<?> inputList)) {
            return null;
        }

        List<Input> inputDefinitions = new ArrayList<>();

        for (Object entry : inputList) {
            Map<?, ?> input = (Map<?, ?>) entry;

            Object required = input.get("required");

            inputDefinitions.add(
                new PolyglotInput(
                    (String) input.get("name"), (String) input.get("label"),
                    input.get("type") == null ? "STRING" : String.valueOf(input.get("type")),
                    Boolean.TRUE.equals(required)));
        }

        return inputDefinitions;
    }

    /**
     * Reads a workflow's declared outputs. An entry names either a {@code task} whose output is the value — the only
     * form that reaches a task name a {@code ${...}} expression cannot — or a literal/expression {@code value}.
     */
    private static List<Output> toOutputs(Object outputs) {
        if (!(outputs instanceof List<?> outputList)) {
            return null;
        }

        List<Output> outputDefinitions = new ArrayList<>();

        for (Object entry : outputList) {
            Map<?, ?> output = (Map<?, ?>) entry;

            outputDefinitions.add(
                new PolyglotOutput(
                    (String) output.get("name"), (String) output.get("task"),
                    PolyglotValues.copyFromPolyglotContext(output.get("value"))));
        }

        return outputDefinitions;
    }

    @SuppressFBWarnings("EI")
    private record PolyglotInput(String name, String label, String type, boolean required) implements Input {

        @Override
        public String getLabel() {
            return label;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public boolean isRequired() {
            return required;
        }
    }

    @SuppressFBWarnings("EI")
    private record PolyglotOutput(String name, String task, Object value) implements Output {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getTask() {
            return task;
        }

        @Override
        public Object getValue() {
            return value;
        }
    }

    /**
     * Reads a workflow's declared triggers. A trigger names a component trigger the platform already provides — it is
     * not guest code — so only its type and parameters cross from the source.
     */
    @SuppressWarnings("unchecked")
    private static List<TriggerDefinition> toTriggers(Object triggers) {
        if (!(triggers instanceof List<?> triggerList)) {
            return null;
        }

        List<TriggerDefinition> triggerDefinitions = new ArrayList<>();

        for (Object entry : triggerList) {
            Map<?, ?> trigger = (Map<?, ?>) entry;

            Object parameters = trigger.get("parameters");

            triggerDefinitions.add(
                new PolyglotTrigger(
                    (String) trigger.get("name"), (String) trigger.get("type"),
                    parameters instanceof Map<?, ?> parameterMap
                        // The guest map is a live view of the polyglot context, which closes before the definition is
                        // used, so copy it into plain Java collections now.
                        ? (Map<String, ?>) PolyglotValues.copyFromPolyglotContext(parameterMap)
                        : Map.of()));
        }

        return triggerDefinitions;
    }

    @SuppressFBWarnings("EI")
    private record PolyglotTrigger(String name, String type, Map<String, ?> parameters) implements TriggerDefinition {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Map<String, ?> getParameters() {
            return parameters;
        }

        @Override
        public String getType() {
            return type;
        }
    }

    private record PolyglotWorkflowDefinition(
        String name, String label, String description, List<WorkflowTaskDefinition> taskDefinitions,
        List<Input> inputs, List<Output> outputs, List<TriggerDefinition> triggers)
        implements WorkflowDefinition {

        @Override
        public Optional<String> getDescription() {
            return Optional.ofNullable(description);
        }

        @Override
        public Optional<List<? extends Input>> getInputs() {
            return Optional.ofNullable(inputs);
        }

        @Override
        public Optional<String> getLabel() {
            return Optional.ofNullable(label);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Optional<List<? extends Output>> getOutputs() {
            return Optional.ofNullable(outputs);
        }

        @Override
        public Optional<List<? extends WorkflowTaskDefinition>> getTasks() {
            return Optional.ofNullable(taskDefinitions);
        }

        @Override
        public Optional<List<? extends TriggerDefinition>> getTriggers() {
            return Optional.ofNullable(triggers);
        }
    }
}
