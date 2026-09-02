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

package com.bytechef.platform.component.owner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@code ActionDefinitionServiceImpl.executeProcessErrorResponse} builds an action context out of nothing: no job
 * principal, no platform type, and {@code editorEnvironment = false}. It is the only non-editor caller in the codebase
 * that does, and the reason the error mapper it invokes has never leaked across accounts is simply that no component
 * pairs the two -- no component that resolves an owner also declares a {@code processErrorResponse}.
 *
 * <p>
 * That is a coincidence, not a design. {@link OwnerResolution} now refuses to answer from such a context rather than
 * calling it the vendor, so the day the coincidence breaks the mapper fails loudly instead of reading every account's
 * data. This test is the other half: it fails at build time instead, while someone is still writing the component.
 *
 * <p>
 * Stated over the whole component tree rather than over the two components that scope owners today, so a third one
 * inherits the rule without anyone remembering to add it here.
 *
 * <p>
 * A source scan rather than a definition one: the owner-scoped components sit downstream of this module, so their
 * definitions cannot be loaded from here, and the rule is about what a reviewer reads anyway.
 *
 * @author Ivica Cardic
 */
class OwnerScopedProcessErrorResponseGuardTest {

    private static final String COMPONENTS_ROOT = "server/libs/modules/components";

    /**
     * Unqualified would match {@code GoogleUtils.processErrorResponse} itself, which is a method that HANDLES an error
     * response rather than a declaration that one is wired into a definition. The leading dot keeps it to the builder
     * call.
     */
    private static final String PROCESS_ERROR_RESPONSE = ".processErrorResponse(";

    private static final String OWNER_RESOLUTION = "OwnerResolution.resolve(";

    @Test
    void testNoOwnerScopedComponentDeclaresAProcessErrorResponse() throws IOException {
        List<ComponentModule> componentModules = scanComponentModules();

        List<String> offenders = componentModules.stream()
            .filter(ComponentModule::resolvesAnOwner)
            .filter(ComponentModule::declaresAProcessErrorResponse)
            .map(ComponentModule::name)
            .toList();

        assertTrue(
            offenders.isEmpty(),
            "Components that both resolve an owner and declare a processErrorResponse: " + offenders +
                ". Such a mapper runs on an action context with no job principal and no platform type, which cannot " +
                "say whose run it belongs to -- give it an owner-free code path, or stop it reaching an " +
                "owner-scoped resource.");
    }

    /**
     * Both halves of the rule above are string matches, and a string that matches nothing passes every scan silently.
     * This asserts each needle is live: some component resolves an owner, some component declares a
     * {@code processErrorResponse}, so the test above is answering a real question.
     */
    @Test
    void testTheScanItselfIsNotVacuous() throws IOException {
        List<ComponentModule> componentModules = scanComponentModules();

        assertFalse(componentModules.isEmpty(), "No component modules found, working directory is wrong");

        assertTrue(
            componentModules.stream()
                .anyMatch(ComponentModule::resolvesAnOwner),
            "No component resolves an owner, so the guard matches nothing: " + OWNER_RESOLUTION);

        assertTrue(
            componentModules.stream()
                .anyMatch(ComponentModule::declaresAProcessErrorResponse),
            "No component declares a process error response, so the guard matches nothing: " + PROCESS_ERROR_RESPONSE);
    }

    private static List<ComponentModule> scanComponentModules() throws IOException {
        Path componentsRoot = repositoryRoot().resolve(COMPONENTS_ROOT);

        assertTrue(Files.isDirectory(componentsRoot), "Components root not found: " + componentsRoot);

        List<ComponentModule> componentModules = new ArrayList<>();

        try (var paths = Files.walk(componentsRoot)) {
            List<Path> sourceRoots = paths.filter(Files::isDirectory)
                .filter(path -> path.endsWith(Path.of("src", "main", "java")))
                .toList();

            for (Path sourceRoot : sourceRoots) {
                componentModules.add(readComponentModule(componentsRoot, sourceRoot));
            }
        }

        return componentModules;
    }

    private static ComponentModule readComponentModule(Path componentsRoot, Path sourceRoot) throws IOException {
        boolean resolvesAnOwner = false;
        boolean declaresAProcessErrorResponse = false;

        try (var paths = Files.walk(sourceRoot)) {
            List<Path> sources = paths.filter(path -> {
                String name = String.valueOf(path.getFileName());

                return name.endsWith(".java");
            })
                .toList();

            for (Path source : sources) {
                String text = readSource(source);

                resolvesAnOwner = resolvesAnOwner || text.contains(OWNER_RESOLUTION);
                declaresAProcessErrorResponse = declaresAProcessErrorResponse || text.contains(PROCESS_ERROR_RESPONSE);
            }
        }

        Path moduleRoot = sourceRoot.resolve(Path.of("..", "..", ".."))
            .normalize();

        return new ComponentModule(
            String.valueOf(componentsRoot.relativize(moduleRoot)), resolvesAnOwner, declaresAProcessErrorResponse);
    }

    private static String readSource(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ioException) {
            throw new UncheckedIOException(ioException);
        }
    }

    /**
     * The test runs with this module as its working directory, and the component tree it has to read sits far outside
     * it. Walking up to the one file that only the repository root carries beats counting {@code ..} segments, which
     * would break the day this module moves.
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("")
            .toAbsolutePath();

        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
                return candidate;
            }

            candidate = candidate.getParent();
        }

        throw new IllegalStateException(
            "No settings.gradle.kts above " + Optional.of(Path.of("")
                .toAbsolutePath()));
    }

    private record ComponentModule(String name, boolean resolvesAnOwner, boolean declaresAProcessErrorResponse) {
    }
}
