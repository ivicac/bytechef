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

package com.bytechef.automation.assetfile.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link AssetFileSystemFacade} performs an ownership check (does this file belong to workspace X) rather than a
 * membership check (is this caller a member of workspace X), which is only sound when there is no principal to check
 * membership for and the workspace id itself is server-derived. A caller that has a principal must go through
 * {@code AssetFileFacade} instead — reaching for this interface turns the substitution into a bypass. This test pins
 * the exact set of classes allowed to reference it, and the exact set of operations it offers, so both kinds of
 * regression fail loudly here instead of shipping silently.
 *
 * <p>
 * This is the same shape as {@code ToolContextWorkspaceVerificationTest} ({@code automation-ai-tool}), copied
 * deliberately, including its build wiring — see that class's javadoc for the three failure modes an earlier revision
 * of that scan shipped and this one avoids from the start:
 * </p>
 *
 * <ol>
 * <li>Recognition must not be a hand-maintained list of names or call shapes. This scan does not try to distinguish a
 * genuine method call on a held reference from any other mention of the interface's simple name — it flags a source
 * file the moment the token appears outside a comment, then relies entirely on the allowlist below to say whether that
 * reference is expected. A method-call-shaped recognizer is exactly the kind of narrow heuristic that missed a live
 * vulnerability twice in the sibling scan.</li>
 * <li>The gate is the allowlist's membership, not a per-file or per-directory rule. Extending it is a deliberate,
 * reviewable, one-line change in the same commit as the new caller — there is no narrower unit a reviewer could bypass
 * by editing one call site.</li>
 * <li>A cheap raw {@link String#contains} pre-filter runs before the expensive {@code DOTALL} comment-strip regex, and
 * the whole scan carries its own generous {@link Timeout} rather than the 30-second per-method default this module
 * inherits from {@code test-support} — that default is a unit-test budget, and a whole-{@code server/} file walk does
 * not fit it.</li>
 * </ol>
 *
 * <p>
 * The allowlist below is larger than the caller set named in the task that introduced this test, deliberately: a
 * simple-name token match cannot distinguish a class that calls the facade from one that merely mentions its type, so
 * four more files legitimately match and are pinned here rather than filtered out with more clever (and more fragile)
 * recognition logic. {@link AssetFileSystemFacade} is the interface's own declaration — a definition trivially contains
 * its own name. {@code AssetFileSystemFacadeImpl} is its sole implementation — implementing the contract is not calling
 * it, and its {@code implements AssetFileSystemFacade} clause matches the same token.
 * {@code RemoteAssetFileSystemFacadeClient} (EE {@code automation-asset-file-remote-client}) is the distributed-EE stub
 * that satisfies the same contract with {@code UnsupportedOperationException} bodies, so it matches for the same reason
 * the implementation does. {@code AiHubConfiguration} (EE {@code ai-hub-service}) never calls a method on the facade at
 * all; it is a Spring {@code @Configuration} class that receives it as a constructor parameter purely to pass it
 * through, unmodified, to the {@code WebhookBridgeAgent} and {@code AiHubRoutingAgent} beans it constructs — both of
 * which are already genuine callers pinned below in their own right.
 * </p>
 *
 * @author Ivica Cardic
 */
class AssetFileSystemFacadeCallerScanTest {

    private static final String SETTINGS_FILE_NAME = "settings.gradle.kts";

    private static final String SYSTEM_FACADE_SIMPLE_NAME = "AssetFileSystemFacade";

    private static final Pattern COMMENT_PATTERN = Pattern.compile("/\\*.*?\\*/|//[^\\r\\n]*", Pattern.DOTALL);

    /**
     * The complete set of production classes allowed to reference {@link AssetFileSystemFacade}. See the class javadoc
     * for why this contains more than the interface's true callers.
     */
    private static final Set<String> EXPECTED_SYSTEM_FACADE_CALLERS = Set.of(
        "AssetFileComponentHandler",
        "AssetFileDeleteAction",
        "AssetFileDownloadAction",
        "AssetFileFacadeImpl",
        "AssetFileFindAction",
        "AssetFileGetAction",
        "AssetFilePublicDownloadController",
        "AssetFileRenameAction",
        "AssetFileUpdateContentAction",
        "AssetFileUploadAction",
        "AiHubRoutingAgent",
        "WebhookBridgeAgent",
        "AiHubConfiguration",
        "AssetFileSystemFacade",
        "AssetFileSystemFacadeImpl",
        "RemoteAssetFileSystemFacadeClient");

    /**
     * The inherited 30-second per-method default comes from {@code test-support} and is a unit-test budget. This is a
     * walk of every production source under {@code server/}, so it does not belong under that budget; see
     * {@code ToolContextWorkspaceVerificationTest} for the same reasoning and the incident that established it.
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void testOnlyExpectedClassesReferenceTheSystemFacade() throws IOException {
        List<Path> mainSourceFiles = findMainSourceFiles(repositoryRoot().resolve("server"));

        assertThat(mainSourceFiles)
            .as("Found no *.java files under any src/main/java directory beneath server/ - the file walk itself is "
                + "broken, this is not a claim that server/ has no production sources.")
            .isNotEmpty();

        List<String> unexpected = mainSourceFiles.stream()
            .filter(AssetFileSystemFacadeCallerScanTest::referencesSystemFacade)
            .map(AssetFileSystemFacadeCallerScanTest::simpleClassName)
            .filter(className -> !EXPECTED_SYSTEM_FACADE_CALLERS.contains(className))
            .sorted()
            .toList();

        assertThat(unexpected)
            .as("AssetFileSystemFacade performs an ownership check instead of a membership check, which is only "
                + "sound for a caller with no principal and a server-derived workspace. A caller that HAS a "
                + "principal must use AssetFileFacade; reaching for this one turns the substitution into a bypass. "
                + "If this caller is genuinely principal-less, add it above in the same commit.")
            .isEmpty();
    }

    /**
     * The scan governs who may call {@link AssetFileSystemFacade}; this governs what it may offer. Without it, a later
     * change can add a method such as {@code enablePublicLink} to the interface and every principal-less caller pinned
     * above silently gains the ability to mint anonymous access from nothing but a server-derived workspace id.
     * {@code enablePublicLink}, {@code disablePublicLink} and {@code createSignedDownloadToken} are excluded on
     * purpose, per the interface's own javadoc.
     */
    @Test
    void testTheSystemFacadeOffersExactlyTheEightPermittedOperations() {
        Set<String> methodNames = Arrays.stream(AssetFileSystemFacade.class.getDeclaredMethods())
            .map(Method::getName)
            .collect(Collectors.toSet());

        assertThat(methodNames)
            .as("adding a method here widens what a caller with no principal can do on nothing but a "
                + "server-derived workspace; the three link-minting operations are excluded deliberately")
            .containsExactlyInAnyOrder(
                "createFromUpload", "findAllByWorkspaceIdAndEnvironment", "findByIdInWorkspace",
                "renameInWorkspace", "deleteInWorkspace", "downloadContentInWorkspace",
                "updateContentInWorkspace", "fetchByPublicLinkToken");
    }

    /**
     * Cheap raw-text pre-filter before the {@code DOTALL} comment-strip regex, which is by far the most expensive part
     * of this scan. Running it against every production source under {@code server/} unconditionally is what pushed the
     * sibling scan over its timeout; here only the handful of files whose raw text could possibly name the facade are
     * stripped at all.
     */
    private static boolean referencesSystemFacade(Path sourceFile) {
        String source = readString(sourceFile);

        if (!source.contains(SYSTEM_FACADE_SIMPLE_NAME)) {
            return false;
        }

        String sourceWithoutComments = COMMENT_PATTERN.matcher(source)
            .replaceAll("");

        return sourceWithoutComments.contains(SYSTEM_FACADE_SIMPLE_NAME);
    }

    private static String readString(Path sourceFile) {
        try {
            return Files.readString(sourceFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String simpleClassName(Path sourceFile) {
        String fileName = String.valueOf(sourceFile.getFileName());

        return fileName.substring(0, fileName.length() - ".java".length());
    }

    /**
     * Walks up from the current working directory until a directory containing {@value #SETTINGS_FILE_NAME} is found. A
     * module-relative {@code src/main/java} cannot see other modules, and this scan must see every module under
     * {@code server/}, not just this one.
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("")
            .toAbsolutePath();

        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(SETTINGS_FILE_NAME))) {
                return candidate;
            }

            candidate = candidate.getParent();
        }

        throw new IllegalStateException(
            "Could not locate " + SETTINGS_FILE_NAME + " by walking up from "
                + Path.of("")
                    .toAbsolutePath()
                + " - the working directory is unexpected.");
    }

    /**
     * Recursively lists every {@code *.java} file under a {@code src/main/java} directory beneath {@code serverRoot},
     * excluding anything under a {@code build/} directory - a stale generated or copied-resource tree under such a path
     * would otherwise be misclassified as production source.
     */
    private static List<Path> findMainSourceFiles(Path serverRoot) throws IOException {
        try (Stream<Path> paths = Files.walk(serverRoot)) {
            return paths.filter(Files::isRegularFile)
                .filter(path -> path.toString()
                    .endsWith(".java"))
                .filter(path -> {
                    String normalizedPath = path.toString()
                        .replace(File.separatorChar, '/');

                    return normalizedPath.contains("/src/main/java/") && !normalizedPath.contains("/build/");
                })
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        }
    }
}
