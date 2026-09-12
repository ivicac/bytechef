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

package com.bytechef.component.datatable;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Ownership has two levels -- a table belongs to one connected user or to nobody, and the rows inside a shared one
 * belong to whoever wrote them -- and BOTH are decided in the same place: the resolution that mints the
 * {@code DataTableRef}. The row service takes no per-row filter, so a step cannot choose either level for itself.
 *
 * <p>
 * What can still drift silently is that resolution step. A new call site can simply skip it, or a merge can resurrect
 * the per-row filter parameter that used to be threaded through every operation, and nothing complains. This turns both
 * from a convention into a build failure.
 *
 * <p>
 * A source scan rather than a bytecode one: the rule is about what a reviewer reads, the file set is small and fixed,
 * and it costs the module no new dependency.
 *
 * @author Ivica Cardic
 */
class DataTableComponentResolutionGuardTest {

    private static final String ACTION_ROOT = "src/main/java/com/bytechef/component/datatable/action";
    private static final String COMPONENT_ROOT = "src/main/java/com/bytechef/component/datatable";

    /**
     * Unqualified on purpose, so it matches both {@code DataTableUtils.effectiveOwner(} at the call sites and the bare
     * {@code effectiveOwner(} inside {@code DataTableUtils} itself, which cannot qualify a static call on its own
     * class.
     */
    private static final String EFFECTIVE_OWNER = "effectiveOwner(";
    private static final String OWNER_FROM_ID = "Owner.connectedUser(";
    private static final String OWNER_RESOLUTION = "OwnerResolution.resolve(";

    /**
     * The one file allowed to mint an owner from an id: {@code DataTableUtils} is where {@code effectiveOwner} is
     * defined, and minting is the second half of what it does. Trusted for that rule only -- its own
     * {@code OwnerResolution.resolve} calls are still required to pass through the gate, which is what
     * {@link #testEveryOwnerResolutionGoesThroughEffectiveOwner} checks about the options and dynamic-property paths
     * that live there.
     */
    private static final List<String> TRUSTED_OWNER_MINTERS = List.of("DataTableUtils.java");

    /**
     * Files with no account selector to reconcile, so an {@code effectiveOwner} call in them would assert nothing: a
     * trigger declares no {@link com.bytechef.component.datatable.util.DataTableUtils#accountProperty()}, and the table
     * dropdown it shares with the actions applies the rule in {@code DataTableUtils} regardless.
     *
     * <p>
     * Listed file by file rather than by package, so a new trigger is caught and has to make this decision
     * deliberately. Take a file off this list the moment its trigger gains the selector.
     */
    private static final List<String> FILES_WITHOUT_A_NAMED_ACCOUNT = List.of(
        "DataTableRecordCreatedTrigger.java", "DataTableRecordDeletedTrigger.java",
        "DataTableRecordUpdatedTrigger.java");

    /**
     * A {@code DataTableRef} is what every row-service call names its table with, and the owner inside it decides which
     * account's physical table the statement reaches. The component must only ever carry through the one that
     * {@code DataTableUtils.resolveDataTable} produced; minting a fresh one -- {@code DataTableRef.unowned(...)} or the
     * constructor -- would pick an owner locally and can silently address the vendor's table instead of the account's.
     * That is precisely the mistake that shipped once already, so it fails the build rather than review.
     */
    @Test
    void testNothingInTheComponentMintsItsOwnDataTableRef() throws IOException {
        List<String> offenders = new ArrayList<>();

        offenders.addAll(sourcesMentioning(COMPONENT_ROOT, "DataTableRef.unowned("));
        offenders.addAll(sourcesMentioning(COMPONENT_ROOT, "new DataTableRef("));

        assertTrue(offenders.isEmpty(), "DataTableRef built outside resolution: " + offenders);
    }

    /**
     * Every action needs to know which pool and which table it is reading or writing, and
     * {@code DataTableUtils.resolveDataTable} is the one place that applies both the cross-pool rejection and the
     * owned-wins-shared-fallback rule. Dropping the call would leave a step reading whatever table the base name
     * happened to hit first.
     *
     * <p>
     * All six actions genuinely need it -- including Clear Table and Delete Record(s), neither of which renders an
     * output schema and so neither needs a {@code DataTableInfo}'s columns: both still need the resolved
     * {@code PlatformType} to route their row-service calls to the right pool. There is no action to exclude here.
     */
    @Test
    void testEveryActionResolvesItsTableThroughTheHelper() throws IOException {
        List<String> offenders = sourcesNotMentioning(ACTION_ROOT, "DataTableUtils.resolveDataTable(");

        assertTrue(offenders.isEmpty(), "Actions that do not resolve their table through the helper: " + offenders);
    }

    /**
     * The account selector is a privilege boundary, and {@code DataTableUtils.effectiveOwner} is the only place it is
     * enforced: it refuses to let a run that already belongs to an account name a different one. An action that reached
     * for the parameter directly -- {@code Owner.connectedUser(inputParameters.getLong(ACCOUNT_ID))} -- would read
     * another account's table and break no existing test, because every other check only asks whether table resolution
     * happened at all.
     *
     * <p>
     * The whole component rather than {@code action/} alone. Scanning the actions was scanning where the rule was
     * WRITTEN rather than where it applies: the editor's own paths -- the table dropdown and the dynamic column
     * properties -- resolve an owner in {@code DataTableUtils}, outside {@code action/}, and skipped the rule entirely,
     * so a vendor step that named an account was offered the shared table and described with its columns while the run
     * itself acted on the account's.
     *
     * <p>
     * Structural rather than behavioural on purpose: the risk is the seventh action nobody has written yet, so what has
     * to fail the build is the shape of its code, not its output.
     */
    @Test
    void testEveryOwnerResolutionGoesThroughEffectiveOwner() throws IOException {
        Path componentRoot = Path.of(COMPONENT_ROOT);

        assertTrue(
            Files.isDirectory(componentRoot), "Component root not found, working directory is wrong: " + componentRoot);

        try (Stream<Path> paths = Files.walk(componentRoot)) {
            List<String> offenders = paths.filter(path -> path.toString()
                .endsWith(".java"))
                .filter(path -> !FILES_WITHOUT_A_NAMED_ACCOUNT.contains(fileNameOf(path)))
                .flatMap(path -> findUnguardedOwnerResolutions(path).stream())
                .toList();

            assertTrue(offenders.isEmpty(), "Owners resolved without DataTableUtils.effectiveOwner: " + offenders);
        }
    }

    private static List<String> findUnguardedOwnerResolutions(Path path) {
        String source = readSource(path);
        Path fileName = path.getFileName();

        List<String> offenders = new ArrayList<>();

        // An action mints an owner from an id only through effectiveOwner; anywhere else that is the escalation.
        if (!TRUSTED_OWNER_MINTERS.contains(fileNameOf(path))) {
            int mintIndex = source.indexOf(OWNER_FROM_ID);

            while (mintIndex >= 0) {
                offenders.add(fileName + " -> " + OWNER_FROM_ID);

                mintIndex = source.indexOf(OWNER_FROM_ID, mintIndex + 1);
            }
        }

        List<int[]> guardedSpans = new ArrayList<>();
        int guardIndex = source.indexOf(EFFECTIVE_OWNER);

        while (guardIndex >= 0) {
            int openIndex = guardIndex + EFFECTIVE_OWNER.length() - 1;

            guardedSpans.add(new int[] {
                openIndex, openIndex + readArguments(source, openIndex).length()
            });

            guardIndex = source.indexOf(EFFECTIVE_OWNER, guardIndex + 1);
        }

        int resolveIndex = source.indexOf(OWNER_RESOLUTION);

        while (resolveIndex >= 0) {
            int index = resolveIndex;

            boolean guarded = guardedSpans.stream()
                .anyMatch(span -> index > span[0] && index < span[1]);

            if (!guarded) {
                offenders.add(fileName + " -> " + OWNER_RESOLUTION);
            }

            resolveIndex = source.indexOf(OWNER_RESOLUTION, resolveIndex + 1);
        }

        return offenders;
    }

    private static List<String> sourcesMentioning(String root, String needle) throws IOException {
        Path sourceRoot = Path.of(root);

        assertTrue(Files.isDirectory(sourceRoot), "Source root not found, working directory is wrong: " + sourceRoot);

        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            return paths.filter(path -> path.toString()
                .endsWith(".java"))
                .filter(path -> readSource(path).contains(needle))
                .map(DataTableComponentResolutionGuardTest::fileNameOf)
                .toList();
        }
    }

    private static List<String> sourcesNotMentioning(String root, String needle) throws IOException {
        Path sourceRoot = Path.of(root);

        assertTrue(Files.isDirectory(sourceRoot), "Source root not found, working directory is wrong: " + sourceRoot);

        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            return paths.filter(path -> path.toString()
                .endsWith(".java"))
                .filter(path -> !readSource(path).contains(needle))
                .map(DataTableComponentResolutionGuardTest::fileNameOf)
                .toList();
        }
    }

    /**
     * The path's last element, falling back to the whole path. {@code getFileName} is nullable on a rootless path, and
     * a null here would swallow the offender this scan exists to name.
     */
    private static String fileNameOf(Path path) {
        return Objects.toString(path.getFileName(), path.toString());
    }

    /**
     * Returns the text between the opening parenthesis at {@code openIndex} and its match, counting nesting so that a
     * nested call does not end the argument list early.
     */
    private static String readArguments(String source, int openIndex) {
        int depth = 0;

        for (int index = openIndex; index < source.length(); index++) {
            char character = source.charAt(index);

            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;

                if (depth == 0) {
                    return source.substring(openIndex + 1, index);
                }
            }
        }

        throw new IllegalStateException("Unbalanced parentheses from index " + openIndex);
    }

    private static String readSource(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
    }
}
