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

package com.bytechef.platform.ai.guardrails;

import com.bytechef.platform.ai.sensitivedata.SensitiveSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The advisor-context channel through which the workspace floor tells the per-node check what it detected in the
 * caller's USER messages, BEFORE it tokenized, redacted, allowed or blocked them.
 *
 * <p>
 * This is what makes the node's input verdict independent of chain position: the floor runs first and transforms the
 * text, so a node check that only looked at the text it receives would see tokens and never fire. Reading the floor's
 * own detection instead lets a node say "block PII" and have it hold even when the workspace chose to let PII through
 * tokenized. The published spans carry kind, category, offsets and confidence — never the matched value.
 * </p>
 *
 * <p>
 * {@code ChatClientRequest#context()} is an advisor-only map: {@code DefaultChatClientUtils#toChatClientRequest} keeps
 * it separate from the {@code ToolContext} that tool callbacks see, so nothing published here reaches a tool.
 * </p>
 *
 * <p>
 * The floor guards each USER message separately and concatenates the resulting spans into one list, so a span's
 * {@code start}/{@code end} are offsets into THAT span's own message, not into any global coordinate space — the list
 * published here can freely mix spans that belong to different messages. A consumer may compare kind, category and
 * count across the whole list, but must never use a span's offsets to slice a single piece of text: two spans next to
 * each other in the list can point into two different messages entirely.
 * </p>
 *
 * @author Ivica Cardic
 */
public final class PublishedInputSpans {

    public static final String CONTEXT_KEY = "bytechef.guardrails.publishedInputSpans";

    private PublishedInputSpans() {
    }

    /**
     * Returns the spans published under {@link #CONTEXT_KEY}, or an empty list when nothing was published or the value
     * is not a list of spans — a guardrail fails towards "nothing published", never towards a cast failure on the
     * request thread.
     *
     * @param context the advisor context map
     * @return the published spans, never {@code null}
     */
    public static List<SensitiveSpan> from(Map<String, ?> context) {
        Object value = context.get(CONTEXT_KEY);

        if (!(value instanceof List<?> list)) {
            return List.of();
        }

        List<SensitiveSpan> spans = new ArrayList<>(list.size());

        for (Object element : list) {
            if (!(element instanceof SensitiveSpan span)) {
                return List.of();
            }

            spans.add(span);
        }

        return List.copyOf(spans);
    }
}
