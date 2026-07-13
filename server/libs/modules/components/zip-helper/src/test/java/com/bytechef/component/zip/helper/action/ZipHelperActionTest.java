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

package com.bytechef.component.zip.helper.action;

import static com.bytechef.component.zip.helper.constant.ZipHelperConstants.FILE;
import static com.bytechef.component.zip.helper.constant.ZipHelperConstants.FILENAME;
import static com.bytechef.component.zip.helper.constant.ZipHelperConstants.FILES;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.Context.ContextFunction;
import com.bytechef.component.definition.FileEntry;
import com.bytechef.component.definition.Parameters;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class ZipHelperActionTest {

    private final ActionContext mockedContext = mock(ActionContext.class);
    private final com.bytechef.component.definition.Context.File mockedFile =
        mock(com.bytechef.component.definition.Context.File.class);
    private final Map<FileEntry, byte[]> fileContents = new HashMap<>();
    private final List<String> storedFilenames = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void beforeEach() throws Exception {
        when(mockedContext.file(any()))
            .thenAnswer(invocation -> {
                ContextFunction<com.bytechef.component.definition.Context.File, ?> contextFunction =
                    invocation.getArgument(0);

                return contextFunction.apply(mockedFile);
            });
        when(mockedFile.readAllBytes(any(FileEntry.class)))
            .thenAnswer(invocation -> fileContents.get(invocation.getArgument(0, FileEntry.class)));
        when(mockedFile.storeContent(anyString(), any(InputStream.class)))
            .thenAnswer(invocation -> {
                String filename = invocation.getArgument(0, String.class);

                storedFilenames.add(filename);

                InputStream inputStream = invocation.getArgument(1, InputStream.class);

                FileEntry fileEntry = mock(FileEntry.class);

                when(fileEntry.getName()).thenReturn(filename);

                fileContents.put(fileEntry, inputStream.readAllBytes());

                return fileEntry;
            });
    }

    @Test
    void testPerformCompressAndExtract() throws IOException {
        FileEntry textFileEntry = mock(FileEntry.class);

        when(textFileEntry.getName()).thenReturn("hello.txt");

        fileContents.put(textFileEntry, "Hello, World!".getBytes(StandardCharsets.UTF_8));

        Parameters compressParameters = mock(Parameters.class);

        when(compressParameters.getFileEntries(FILES, List.of())).thenReturn(List.of(textFileEntry));
        when(compressParameters.getString(FILENAME, "file.zip")).thenReturn("archive.zip");

        FileEntry zipFileEntry = ZipHelperCompressAction.perform(
            compressParameters, compressParameters, mockedContext);

        assertEquals("archive.zip", zipFileEntry.getName());

        Parameters extractParameters = mock(Parameters.class);

        when(extractParameters.getRequiredFileEntry(FILE)).thenReturn(zipFileEntry);

        List<FileEntry> extractedFileEntries = ZipHelperExtractAction.perform(
            extractParameters, extractParameters, mockedContext);

        assertEquals(1, extractedFileEntries.size());
        assertEquals("hello.txt", extractedFileEntries.getFirst()
            .getName());
        assertArrayEquals("Hello, World!".getBytes(StandardCharsets.UTF_8),
            fileContents.get(extractedFileEntries.getFirst()));
    }
}
