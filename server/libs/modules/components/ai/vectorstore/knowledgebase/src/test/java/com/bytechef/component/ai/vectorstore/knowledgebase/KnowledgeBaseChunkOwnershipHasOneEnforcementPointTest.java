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

import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_OWNER_ID;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_SHARED;
import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.component.ai.vectorstore.knowledgebase.util.KnowledgeBaseVectorStoreWrapper;
import com.bytechef.platform.owner.Owner;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Chunk ownership has exactly one enforcement point, and this is the test that keeps it that way.
 *
 * <p>
 * The behavioural tests beside it prove the wrapper filters correctly; none of them can notice a SECOND place that
 * decides ownership, and a second place is how this kind of separation is actually lost -- not by the chokepoint being
 * wrong but by a step growing its own owner parameter and reaching past it. The equivalent for data tables is the rule
 * that both owners reach SQL only from the ref.
 *
 * <p>
 * A source scan for the file set and reflection for the signatures, matching {@code
 * KnowledgeBaseComponentScopesByIdReadsTest}: no new module dependency, and what it pins is what a reviewer reads.
 *
 * @author Ivica Cardic
 */
class KnowledgeBaseChunkOwnershipHasOneEnforcementPointTest {

    private static final String COMPONENT_SOURCE_ROOT =
        "src/main/java/com/bytechef/component/ai/vectorstore/knowledgebase";

    private static final String ENFORCEMENT_POINT = "KnowledgeBaseVectorStoreWrapper.java";

    /**
     * The metadata keys are the vocabulary of the axis: a file that names either one is stamping ownership or filtering
     * on it, and only the chokepoint may.
     */
    @Test
    void testNoFileBesidesTheWrapperNamesTheOwnershipMetadataKeys() throws IOException {
        Path root = Path.of(COMPONENT_SOURCE_ROOT);

        assertThat(Files.isDirectory(root))
            .withFailMessage("Source root not found, working directory is wrong: %s", root)
            .isTrue();

        try (Stream<Path> paths = Files.walk(root)) {
            List<String> offenders = paths.filter(path -> String.valueOf(path)
                .endsWith(".java"))
                .filter(path -> !ENFORCEMENT_POINT.equals(String.valueOf(path.getFileName())))
                .filter(KnowledgeBaseChunkOwnershipHasOneEnforcementPointTest::namesAnOwnershipKey)
                .map(path -> String.valueOf(path.getFileName()))
                .toList();

            assertThat(offenders)
                .withFailMessage("Files applying chunk ownership outside the wrapper: %s", offenders)
                .isEmpty();
        }
    }

    /**
     * One constructor, and it demands the owner. An overload that defaulted it would let a construction site omit the
     * account it acts for, and the omission would read as vendor -- writing that account's chunks as shared and handing
     * them to everyone.
     */
    @Test
    void testTheWrapperHasASingleConstructorAndItDemandsTheOwner() {
        Constructor<?>[] constructors = KnowledgeBaseVectorStoreWrapper.class.getConstructors();

        assertThat(constructors).hasSize(1);

        Constructor<?> constructor = constructors[0];

        assertThat(constructor.getParameterTypes()).contains(Optional.class);
    }

    /**
     * The owner arrives at construction and nowhere else. A store operation taking one would be a second source of the
     * answer, able to disagree with the ref-equivalent the wrapper is.
     */
    @Test
    void testNoWrapperOperationTakesAnOwnerOfItsOwn() {
        Method[] methods = KnowledgeBaseVectorStoreWrapper.class.getDeclaredMethods();

        List<String> offenders = Arrays.stream(methods)
            .filter(method -> Arrays.asList(method.getParameterTypes())
                .contains(Owner.class))
            .map(Method::getName)
            .toList();

        assertThat(offenders)
            .withFailMessage("Wrapper operations taking an owner parameter: %s", offenders)
            .isEmpty();
    }

    private static boolean namesAnOwnershipKey(Path path) {
        String source = readSource(path);

        return source.contains("METADATA_OWNER_ID") || source.contains("METADATA_SHARED")
            || source.contains("\"" + METADATA_OWNER_ID + "\"") || source.contains("\"" + METADATA_SHARED + "\"");
    }

    private static String readSource(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
    }
}
