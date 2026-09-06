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

package com.bytechef.platform.ai.sensitivedata;

import java.util.Map;

/**
 * The human-readable label for each {@link PiiPatternCatalog} entity type: one table, shared by every front-end that
 * offers a PII type picker.
 *
 * <p>
 * The labels started life inside the per-node picker in the {@code guardrails} component. A second picker — the AI Text
 * Mask action — would have meant a second copy, and two copies of the same 36 strings drift the moment one catalog
 * entry is renamed. The label lives here, beside the catalog it names; each picker keeps its own ORDER, which is the
 * part that is genuinely per-front-end because it fixes that component's generated definition JSON.
 * </p>
 *
 * @author Ivica Cardic
 */
public final class PiiPatternLabels {

    private static final Map<String, String> LABELS = Map.ofEntries(
        Map.entry("EMAIL_ADDRESS", "Email address"),
        Map.entry("PHONE_NUMBER", "Phone number"),
        Map.entry("CREDIT_CARD", "Credit card number"),
        Map.entry("IP_ADDRESS", "IP address (IPv4)"),
        Map.entry("IBAN_CODE", "IBAN code"),
        Map.entry("CRYPTO", "Bitcoin/crypto address"),
        Map.entry("DATE_TIME", "Date or date-time (ISO-8601)"),
        Map.entry("LOCATION", "Street address / location"),
        Map.entry("MEDICAL_LICENSE", "Medical license"),
        Map.entry("US_SSN", "US Social Security Number"),
        Map.entry("US_BANK_NUMBER", "US bank account number"),
        Map.entry("US_DRIVER_LICENSE", "US driver license"),
        Map.entry("US_ITIN", "US Individual Taxpayer Identification Number (ITIN)"),
        Map.entry("US_PASSPORT", "US passport number"),
        Map.entry("UK_NHS", "UK NHS number"),
        Map.entry("UK_NINO", "UK National Insurance Number"),
        Map.entry("ES_NIF", "Spanish NIF"),
        Map.entry("ES_NIE", "Spanish NIE"),
        Map.entry("IT_FISCAL_CODE", "Italian fiscal code (Codice Fiscale)"),
        Map.entry("IT_VAT_CODE", "Italian VAT code (Partita IVA)"),
        Map.entry("IT_DRIVER_LICENSE", "Italian driver license"),
        Map.entry("IT_PASSPORT", "Italian passport"),
        Map.entry("IT_IDENTITY_CARD", "Italian identity card"),
        Map.entry("PL_PESEL", "Polish PESEL"),
        Map.entry("SG_NRIC_FIN", "Singapore NRIC/FIN"),
        Map.entry("SG_UEN", "Singapore UEN (Unique Entity Number)"),
        Map.entry("AU_ABN", "Australian Business Number (ABN)"),
        Map.entry("AU_ACN", "Australian Company Number (ACN)"),
        Map.entry("AU_TFN", "Australian Tax File Number (TFN)"),
        Map.entry("AU_MEDICARE", "Australian Medicare card number"),
        Map.entry("IN_AADHAAR", "Indian Aadhaar number"),
        Map.entry("IN_PAN", "Indian Permanent Account Number (PAN)"),
        Map.entry("IN_PASSPORT", "Indian passport number"),
        Map.entry("IN_VEHICLE_REGISTRATION", "Indian vehicle registration"),
        Map.entry("IN_VOTER", "Indian voter ID (EPIC)"),
        Map.entry("FI_PERSONAL_IDENTITY_CODE", "Finnish Personal Identity Code (HETU)"));

    private PiiPatternLabels() {
    }

    /**
     * Returns the label for {@code type}, falling back to the type itself when there is none. A type with no label is a
     * usable picker entry rather than a missing one, which is the right failure for a catalog entry added ahead of its
     * label.
     *
     * @param type the catalog entity type
     * @return the human-readable label, or {@code type} when none is registered
     */
    public static String labelOf(String type) {
        return LABELS.getOrDefault(type, type);
    }
}
