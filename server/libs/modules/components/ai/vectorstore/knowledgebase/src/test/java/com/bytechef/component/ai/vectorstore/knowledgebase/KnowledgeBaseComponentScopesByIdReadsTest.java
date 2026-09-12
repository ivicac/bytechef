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

package com.bytechef.component.ai.vectorstore.knowledgebase;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The rule: a file that turns a caller-supplied value into a knowledge base -- an id, a name, or an entity carrying one
 * -- must also pass it through an admission gate. Nothing in the component may reach a knowledge base on the id alone.
 *
 * <p>
 * This used to be pinned on ONE receiver, {@code knowledgeBaseService.getKnowledgeBase(}. That was a check on a route
 * rather than on the rule, and the route it pinned was not the one the original write hole took:
 * {@code KnowledgeBaseItemWriter} reached a knowledge base through {@code knowledgeBaseSourceService.fetch(sourceId)}
 * and {@code source.getKnowledgeBaseId()}, containing no such string anywhere. It was caught only because
 * {@code destination/} had by then joined the roots of the second test below -- luck, not design. So the receiver
 * became a LIST of id-yielding calls, and the rule became "if you use one of these, show a gate".
 *
 * <p>
 * There are two legitimate gates, for two different situations, and this guard treats them as equally admissible rather
 * than privileging one: {@code KnowledgeBaseOptionsUtils.resolveKnowledgeBase} where the run's owner is knowable (both
 * its by-id and its by-name overload), and {@code KnowledgeBaseService.fetchKnowledgeBase}, which since item 9 carries
 * the by-name resolution rule itself. The two must stay independently named rather than merged or renamed to share a
 * substring, or the guard would go green while a step resolved with no pool check at all.
 *
 * <p>
 * {@code KnowledgeBaseOptionsUtils.readUnscopedKnowledgeBase} -- the fallback for a frame that cannot learn the run's
 * owner at all, so cannot even ask the pool question -- is deliberately NOT one of these. A knowledge base is no longer
 * assigned to one account, so that call refuses nothing; it is a bare, unscoped read, and counting it as a gate would
 * let this guard go green over a step that admitted nothing at all. It is caught by the {@code .getKnowledgeBase(}
 * entry below like any other unscoped read, and is trusted only because it is made from inside
 * {@code KnowledgeBaseOptionsUtils.java} itself -- see {@link #TRUSTED_ID_YIELDING_CALLERS}.
 *
 * <p>
 * A source scan rather than a bytecode one, matching {@code DataTableComponentResolutionGuardTest}: the rule is about
 * what a reviewer reads, the file set is small and fixed, and it costs the module no new dependency.
 *
 * <p>
 * The file that legitimately names the service is allowlisted individually rather than left outside the scan; excluding
 * its package instead would have exempted every future file beside it.
 *
 * @author Ivica Cardic
 */
class KnowledgeBaseComponentScopesByIdReadsTest {

    private static final String SCOPED_CALL = "KnowledgeBaseOptionsUtils.resolveKnowledgeBase(";
    private static final String NAMED_CALL = ".fetchKnowledgeBase(";

    private static final List<String> ADMISSION_GATE_CALLS = List.of(SCOPED_CALL, NAMED_CALL);

    /**
     * Every way this component is known to turn a caller-supplied value into a knowledge base. Matched on the METHOD
     * rather than on the field it is called through, so renaming {@code knowledgeBaseService} to something else does
     * not slip a call past the scan.
     *
     * <p>
     * {@code .getKnowledgeBase(} covers both by-id overloads, the unscoped one and the pool-aware one -- a step may
     * reach neither directly. {@code .getKnowledgeBases(} covers the pool listings. {@code .fetchKnowledgeBase(} is the
     * by-name resolution, which is also a gate: the rule lives inside it, so calling it satisfies this requirement.
     * {@code .getKnowledgeBaseId()} is the entity dereference -- a source row or a document turned into the knowledge
     * base behind it -- and is what would have caught the original write hole whatever service had produced the entity.
     * {@code knowledgeBaseSourceService.fetch(} names that hole's own route as well, since a source row names a
     * knowledge base and constrains nothing else. The two mutating by-id calls are here because acting on an unadmitted
     * knowledge base is worse than reading one.
     *
     * <p>
     * File granularity is the limit of a source scan: a file that already shows a gate is not re-checked call by call,
     * so this cannot prove that the gate covers the particular id a second lookup in the same file uses. It stops a new
     * file from reaching a knowledge base with no gate at all, which is the failure this component has actually had.
     */
    private static final List<String> ID_YIELDING_CALLS = List.of(
        ".getKnowledgeBase(", ".getKnowledgeBases(", ".fetchKnowledgeBase(", ".getKnowledgeBaseId()",
        "knowledgeBaseSourceService.fetch(", ".deleteKnowledgeBase(", ".updateKnowledgeBase(");

    private static final String COMPONENT_SOURCE_ROOT =
        "src/main/java/com/bytechef/component/ai/vectorstore/knowledgebase";

    private static final List<String> STEP_ROOTS = List.of(
        COMPONENT_SOURCE_ROOT + "/action", COMPONENT_SOURCE_ROOT + "/cluster", COMPONENT_SOURCE_ROOT + "/util",
        COMPONENT_SOURCE_ROOT + "/destination");

    /**
     * Files under a {@link #STEP_ROOTS} root that are not themselves a step naming a knowledge base by id, so requiring
     * an admission-gate call from them would be requiring nothing meaningful.
     *
     * <p>
     * {@code KnowledgeBaseOptionsUtils} defines the gates; it does not call itself through its own qualified name.
     * {@code KnowledgeBaseVectorStoreWrapper} filters an already-open {@code VectorStore} by a {@code knowledgeBaseId}
     * its constructor receives pre-resolved -- it never looks an id up.
     */
    private static final List<String> FILES_WITHOUT_AN_ID_TO_ADMIT = List.of(
        "KnowledgeBaseOptionsUtils.java", "KnowledgeBaseVectorStoreWrapper.java");

    /**
     * The only file that may make an id-yielding call without showing a gate of its own, listed individually rather
     * than by package so that a new neighbour of it is still caught.
     *
     * <p>
     * {@code KnowledgeBaseOptionsUtils} is where both gates are defined -- it makes the id-yielding calls from INSIDE
     * them: once to resolve within a pool, once to resolve a name. It also makes one more, from
     * {@code readUnscopedKnowledgeBase}, that grants nothing at all -- a bare, unscoped read for the one frame that
     * cannot ask even the pool question. The scan matches on the method rather than on the argument list, so it cannot
     * tell any of these apart, which is why the whole file is trusted rather than particular call sites within it.
     */
    private static final List<String> TRUSTED_ID_YIELDING_CALLERS = List.of("KnowledgeBaseOptionsUtils.java");

    /**
     * Scans the whole component rather than the step packages alone. A guard that only looks where the bug was found is
     * decorative: the leak this pins reaches another pool from anywhere that turns an id into a knowledge base, so a
     * new file in {@code destination/}, in {@code util/}, or in a package nobody has created yet has to be caught by
     * the same net.
     */
    @Test
    void testNothingInTheComponentReachesAKnowledgeBaseWithoutAnAdmissionGate() throws IOException {
        Path root = Path.of(COMPONENT_SOURCE_ROOT);

        assertTrue(Files.isDirectory(root), "Source root not found, working directory is wrong: " + root);

        try (Stream<Path> paths = Files.walk(root)) {
            List<String> offenders = paths.filter(KnowledgeBaseComponentScopesByIdReadsTest::isJavaSource)
                .filter(path -> !TRUSTED_ID_YIELDING_CALLERS.contains(String.valueOf(path.getFileName())))
                .filter(KnowledgeBaseComponentScopesByIdReadsTest::reachesAKnowledgeBaseWithoutAGate)
                .map(path -> String.valueOf(path.getFileName()))
                .toList();

            assertTrue(offenders.isEmpty(), "Knowledge base reads bypassing an admission gate: " + offenders);
        }
    }

    /**
     * Every step that names a knowledge base must pass through an admission gate, not merely reach one by a route this
     * scan happens to list: a step that reached one some other way would pass the check above by making no listed call.
     *
     * <p>
     * Either gate satisfies this, since each is a way a step can admit a knowledge base rather than merely name one. A
     * file in {@link #FILES_WITHOUT_AN_ID_TO_ADMIT} is not a step and is exempt.
     */
    @Test
    void testEveryActionAndClusterToolResolvesAKnowledgeBaseAtAll() throws IOException {
        for (String scannedRoot : STEP_ROOTS) {
            Path root = Path.of(scannedRoot);

            try (Stream<Path> paths = Files.walk(root)) {
                List<String> offenders = paths.filter(KnowledgeBaseComponentScopesByIdReadsTest::isJavaSource)
                    .filter(path -> !FILES_WITHOUT_AN_ID_TO_ADMIT.contains(String.valueOf(path.getFileName())))
                    .filter(path -> !passesThroughAGate(readSource(path)))
                    .map(path -> String.valueOf(path.getFileName()))
                    .toList();

                assertTrue(offenders.isEmpty(), "Steps that pass through no admission gate: " + offenders);
            }
        }
    }

    private static boolean isJavaSource(Path path) {
        String pathName = path.toString();

        return pathName.endsWith(".java");
    }

    private static boolean reachesAKnowledgeBaseWithoutAGate(Path path) {
        String source = readSource(path);

        boolean reachesAKnowledgeBase = ID_YIELDING_CALLS.stream()
            .anyMatch(source::contains);

        return reachesAKnowledgeBase && !passesThroughAGate(source);
    }

    private static boolean passesThroughAGate(String source) {
        return ADMISSION_GATE_CALLS.stream()
            .anyMatch(source::contains);
    }

    private static String readSource(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
    }
}
