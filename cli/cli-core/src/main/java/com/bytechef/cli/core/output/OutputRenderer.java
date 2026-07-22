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

package com.bytechef.cli.core.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Renders command results either as pretty-printed JSON or as an aligned text table.
 *
 * @author Ivica Cardic
 */
public class OutputRenderer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(SerializationFeature.INDENT_OUTPUT);

    private final PrintStream printStream;

    public OutputRenderer(OutputStream outputStream) {
        this.printStream = new PrintStream(outputStream, true, StandardCharsets.UTF_8);
    }

    public void renderJson(Object value) {
        try {
            printStream.println(OBJECT_MAPPER.writeValueAsString(value));
        } catch (Exception e) {
            throw new RuntimeException("Failed to render JSON: " + e.getMessage(), e);
        }
    }

    public void renderTable(List<String> headers, List<List<String>> rows) {
        int[] widths = new int[headers.size()];

        for (int i = 0; i < headers.size(); i++) {
            widths[i] = headers.get(i)
                .length();
        }

        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                widths[i] = Math.max(
                    widths[i], row.get(i)
                        .length());
            }
        }

        printStream.println(formatRow(headers, widths));

        for (List<String> row : rows) {
            printStream.println(formatRow(row, widths));
        }
    }

    public void message(String text) {
        printStream.println(text);
    }

    private static String formatRow(List<String> cells, int[] widths) {
        StringBuilder stringBuilder = new StringBuilder();

        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                stringBuilder.append("  ");
            }

            stringBuilder.append(String.format("%-" + widths[i] + "s", cells.get(i)));
        }

        return stringBuilder.toString();
    }
}
