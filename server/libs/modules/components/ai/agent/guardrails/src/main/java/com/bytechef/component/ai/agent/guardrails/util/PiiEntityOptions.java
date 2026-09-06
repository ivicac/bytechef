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

package com.bytechef.component.ai.agent.guardrails.util;

import static com.bytechef.component.ai.agent.guardrails.constant.GuardrailsConstants.ENTITIES;
import static com.bytechef.component.ai.agent.guardrails.constant.GuardrailsConstants.TYPE;
import static com.bytechef.component.ai.agent.guardrails.constant.GuardrailsConstants.TYPE_ALL;
import static com.bytechef.component.ai.agent.guardrails.constant.GuardrailsConstants.TYPE_SELECTED;

import com.bytechef.component.definition.ComponentDsl;
import com.bytechef.component.definition.Option;
import com.bytechef.component.definition.Parameters;
import com.bytechef.platform.ai.sensitivedata.PiiPatternCatalog;
import com.bytechef.platform.ai.sensitivedata.PiiPatternLabels;
import java.util.ArrayList;
import java.util.List;

/**
 * The per-node PII entity picker: the option list the {@code pii} and {@code llm-pii} components offer, and the
 * resolution of a node's selection to the shared catalog patterns detection should run.
 *
 * <p>
 * Detection itself lives in the platform engine ({@code SensitiveDataRedactor} over {@code RegexPiiDetector}); this
 * class only decides WHICH catalog patterns that engine is given.
 * </p>
 *
 * @author Ivica Cardic
 */
public final class PiiEntityOptions {

    private PiiEntityOptions() {
    }

    /**
     * Returns the per-node PII detection picker options: one option per entry in {@link PiiPatternCatalog#ALL}, pairing
     * each pattern's type with a human-readable label.
     *
     * <p>
     * The set of option values here must always equal the set of {@link PiiPatternCatalog.PiiPattern#type()} values in
     * {@link PiiPatternCatalog#ALL} — an entry added to the catalog with no matching option here would be silently
     * unselectable in the picker, and a stale option here would silently offer a type that detects nothing. The type
     * list is hand-maintained (not derived from the catalog) because its order also fixes the generated definition JSON
     * for the {@code pii} and {@code llm-pii} components, so it cannot simply follow the catalog's grouping order.
     * {@code PiiEntityOptionsTest} pins the invariant instead, in {@code testPickerOptionsMatchCatalogTypesExactly}.
     * The labels themselves come from {@link PiiPatternLabels}, which the AI Text Mask picker shares.
     * </p>
     *
     * @return the picker options for PII detection type selection
     */
    public static List<Option<String>> getPiiDetectionOptions() {
        List<String> types = List.of(
            "EMAIL_ADDRESS",
            "PHONE_NUMBER",
            "CREDIT_CARD",
            "IP_ADDRESS",
            "IBAN_CODE",
            "CRYPTO",
            "DATE_TIME",
            "LOCATION",
            "MEDICAL_LICENSE",
            "US_SSN",
            "US_BANK_NUMBER",
            "US_DRIVER_LICENSE",
            "US_ITIN",
            "US_PASSPORT",
            "UK_NHS",
            "UK_NINO",
            "ES_NIF",
            "ES_NIE",
            "IT_FISCAL_CODE",
            "IT_VAT_CODE",
            "IT_DRIVER_LICENSE",
            "IT_PASSPORT",
            "IT_IDENTITY_CARD",
            "PL_PESEL",
            "SG_NRIC_FIN",
            "SG_UEN",
            "AU_ABN",
            "AU_ACN",
            "AU_TFN",
            "AU_MEDICARE",
            "IN_AADHAAR",
            "IN_PAN",
            "IN_PASSPORT",
            "IN_VEHICLE_REGISTRATION",
            "IN_VOTER",
            "FI_PERSONAL_IDENTITY_CODE");

        List<Option<String>> options = new ArrayList<>(types.size());

        for (String type : types) {
            options.add(ComponentDsl.option(PiiPatternLabels.labelOf(type), type));
        }

        return options;
    }

    /**
     * Resolves the node's {@code type}/{@code entities} parameters to the catalog patterns to run. {@code ALL} is the
     * whole catalog, contextual types included — the picker is a menu, and the curated default's exclusions are the
     * platform's, not the node author's (spec §5a).
     *
     * @param parameters the node's input parameters
     * @return the catalog patterns detection should run for this node
     */
    public static List<PiiPatternCatalog.PiiPattern> selectedPatterns(Parameters parameters) {
        String type = parameters.getString(TYPE, TYPE_ALL);

        if (!TYPE_SELECTED.equals(type)) {
            return PiiPatternCatalog.ALL;
        }

        List<PiiPatternCatalog.PiiPattern> selected =
            PiiPatternCatalog.filterByTypes(parameters.getList(ENTITIES, String.class));

        if (selected.isEmpty()) {
            throw new IllegalArgumentException(
                "PII guardrail TYPE='SELECTED' requires at least one entity in 'Entities'.");
        }

        return selected;
    }
}
