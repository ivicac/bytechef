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

package com.bytechef.platform.component.runner.external;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The languages an external task runner can execute, and every language id that denotes one of them.
 *
 * <p>
 * A language reaches this package under whichever id its caller already had. {@code script}'s JavaScript action passes
 * {@code js}, because that is the Truffle language id the in-JVM GraalVM runner resolves its polyglot context from and
 * the id it must keep passing; {@code commands}' Node action passes {@code javascript}, because it has no polyglot
 * context and named the language after the file it writes. Both mean Node.
 *
 * <p>
 * That the two are the same language is knowledge this enum holds and nothing else does. Answering it at each switch
 * instead - one in {@link TaskRunnerBootstrap#isSupported}, one in {@link TaskRunnerBootstrap#append}, one in
 * {@link TaskRunnerBootstrap#sourceFileName}, one in {@code ProcessTaskRunner}'s default-interpreter table - is how
 * selecting the process runner on a JavaScript script action came to fail 100% of the time while the identical Python
 * action worked: {@code python} happened to satisfy both vocabularies and {@code js} did not, and no single site was
 * wrong on its own.
 *
 * @author Ivica Cardic
 */
public enum ExternalLanguage {

    JAVASCRIPT("javascript", "js"),
    PYTHON("python"),
    SHELL("shell");

    private static final Map<String, ExternalLanguage> EXTERNAL_LANGUAGES_BY_ID = buildIndex();

    private final List<String> languageIds;

    ExternalLanguage(String... languageIds) {
        this.languageIds = List.of(languageIds);
    }

    /**
     * Returns the language the id denotes, failing when no external runner can execute it.
     */
    public static ExternalLanguage of(String languageId) {
        return find(languageId).orElseThrow(
            () -> new IllegalArgumentException(
                "Language '%s' cannot be executed by an external task runner".formatted(languageId)));
    }

    /**
     * Returns the language the id denotes, or empty when no external runner can execute it.
     */
    public static Optional<ExternalLanguage> find(String languageId) {
        return Optional.ofNullable(EXTERNAL_LANGUAGES_BY_ID.get(languageId));
    }

    /**
     * The id this package names the language by, whichever of its ids a caller arrived with.
     */
    public String getLanguageId() {
        return languageIds.getFirst();
    }

    private static Map<String, ExternalLanguage> buildIndex() {
        Map<String, ExternalLanguage> externalLanguagesById = new HashMap<>();

        for (ExternalLanguage externalLanguage : values()) {
            for (String languageId : externalLanguage.languageIds) {
                externalLanguagesById.put(languageId, externalLanguage);
            }
        }

        return Map.copyOf(externalLanguagesById);
    }
}
