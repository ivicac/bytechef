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
import com.bytechef.workflow.definition.ConnectionRequirement;
import com.bytechef.workflow.definition.Input;
import com.bytechef.workflow.definition.Output;
import com.bytechef.workflow.definition.Parameter;
import com.bytechef.workflow.definition.TaskContext;
import com.bytechef.workflow.definition.TaskDefinition;
import com.bytechef.workflow.definition.TriggerDefinition;
import com.bytechef.workflow.definition.WorkflowDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.TypeLiteral;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class ProjectHandlerPolyglotEngine {

    // A dedicated engine for the strict-sandbox perform contexts. GraalVM caches host-access interop info
    // per engine, so building a strict `HostAccess.NONE` context and the permissive definition-loading
    // context on the SAME engine corrupts that cache; keeping them on separate engines avoids the clash
    // while leaving definition loading (`getContext()`/`engine`) untouched.
    private static Engine performEngine;

    static ProjectHandler load(String languageId, String script) {
        if (engine == null) {
            engine = Engine.create();
        }

        try (Context polyglotContext = getContext()) {
            Value value = polyglotContext.eval(languageId, script);

            String name = Objects.requireNonNull(getMember(value, "name"));
            String description = getMember(value, "description");
            String version = getMember(value, "version");

            List<WorkflowDefinition> workflows = getWorkflows(
                value, new TypeLiteral<List<Map<String, Object>>>() {})
                    .stream()
                    .map(workflow -> (WorkflowDefinition) new PolyglotWorkflowDefinition(
                        (String) workflow.get("name"), (String) workflow.get("label"),
                        (String) workflow.get("description"),
                        toTaskDefinitions(
                            (String) workflow.get("name"), (List<?>) workflow.get("tasks"), languageId, script)))
                    .toList();

            return () -> new PolyglotProjectDefinition(name, description, version, workflows);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object executePerform(
        String workflowName, String taskName, String languageId, String script, TaskContext taskContext) {

        if (performEngine == null) {
            performEngine = Engine.create();
        }

        try (Context polyglotContext = PolyglotSandbox.newContext(performEngine, languageId)) {
            Value value = polyglotContext.eval(languageId, script);

            List<Map<String, Object>> workflows = getWorkflows(value, new TypeLiteral<>() {});

            List<Map<String, Object>> tasks = (List<Map<String, Object>>) workflows.stream()
                .filter(workflow -> workflowName.equals(workflow.get("name")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Workflow name=%s not found".formatted(workflowName)))
                .get("tasks");

            for (Map<String, Object> task : tasks) {
                if (taskName.equals(task.get("name"))) {
                    Function<Object[], Object> perform = (Function<Object[], Object>) task.get("perform");

                    Object result = perform.apply(new Object[] {
                        toGuestContext(taskContext, languageId)
                    });

                    // The guest function's return value may be a live view backed by the polyglot context (e.g. a
                    // JS object mapped to a PolyglotMap), which becomes unusable once the context closes below.
                    // Copy it into plain Java collections while the context is still open.
                    return PolyglotValues.copyFromPolyglotContext(result);
                }
            }

            throw new IllegalArgumentException("Task name=%s not found".formatted(taskName));
        }
    }

    /**
     * Builds the guest-facing {@code context} argument handed to a code workflow task's {@code perform} function:
     * {@code component} exposes the shared component proxy chain
     * ({@code context.component.<componentName>.<actionName>(input, connectionName)}), {@code connection(name)} returns
     * a wired connection's parameters, and {@code log} delegates to {@link TaskContext#log}. Both dispatch through the
     * {@link TaskContext} the engine received at perform time; a {@code null} context (a legacy zero-argument
     * invocation) fails only when the guest actually uses one of them.
     */
    private static ProxyObject toGuestContext(TaskContext taskContext, String languageId) {
        ComponentActionInvoker componentActionInvoker = (componentName, actionName, input, connectionName) -> {
            if (taskContext == null) {
                throw new IllegalStateException("A TaskContext is not available");
            }

            return taskContext.component(componentName, actionName, input, connectionName);
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

                    taskContext.log(arguments[0].asString(), arguments[1].asString());

                    return null;
                }));
    }

    private static Context getContext() {
        return Context.newBuilder()
            .engine(engine)
            .build();
    }

    private static String getMember(Value value, String name) {
        value = value.getMember(name);

        return value == null ? null : value.as(String.class);
    }

    private static <T> T getWorkflows(Value value, TypeLiteral<T> typeLiteral) {
        return value.getMember("workflows")
            .as(typeLiteral);
    }

    private static List<TaskDefinition> toTaskDefinitions(
        String workflowName, List<?> tasks, String languageId, String script) {

        if (tasks == null) {
            return List.of();
        }

        return tasks.stream()
            .map(task -> (Map<?, ?>) task)
            .map(task -> (TaskDefinition) new PolyglotTaskDefinition(
                workflowName, (String) task.get("name"), (String) task.get("label"), (String) task.get("description"),
                toConnectionRequirements(task.get("connections")), languageId, script))
            .toList();
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

    private record PolyglotTaskDefinition(
        String workflowName, String name, String label, String description,
        List<ConnectionRequirement> connections, String languageId, String script)
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
        public Optional<List<? extends Parameter>> getParameters() {
            return Optional.empty();
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

    private record PolyglotWorkflowDefinition(
        String name, String label, String description, List<TaskDefinition> taskDefinitions)
        implements WorkflowDefinition {

        @Override
        public Optional<String> getDescription() {
            return Optional.ofNullable(description);
        }

        @Override
        public Optional<List<? extends Input>> getInputs() {
            return Optional.empty();
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
            return Optional.empty();
        }

        @Override
        public Optional<List<? extends TaskDefinition>> getTasks() {
            return Optional.ofNullable(taskDefinitions);
        }

        @Override
        public Optional<List<? extends TriggerDefinition>> getTriggers() {
            return Optional.empty();
        }
    }
}
