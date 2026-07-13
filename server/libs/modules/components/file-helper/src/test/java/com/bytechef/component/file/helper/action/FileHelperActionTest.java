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

package com.bytechef.component.file.helper.action;

import static com.bytechef.component.file.helper.constant.FileHelperConstants.FILE;
import static com.bytechef.component.file.helper.constant.FileHelperConstants.FILENAME;
import static com.bytechef.component.file.helper.constant.FileHelperConstants.TEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.Context.ContextFunction;
import com.bytechef.component.definition.Context.File;
import com.bytechef.component.definition.FileEntry;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.test.definition.MockParametersFactory;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class FileHelperActionTest {

    private final ActionContext mockedContext = mock(ActionContext.class);
    private final File mockedFile = mock(File.class);
    private final FileEntry mockedFileEntry = mock(FileEntry.class);

    @BeforeEach
    void beforeEach() throws Exception {
        when(mockedContext.file(any()))
            .thenAnswer(invocation -> {
                ContextFunction<File, ?> contextFunction = invocation.getArgument(0);

                return contextFunction.apply(mockedFile);
            });
    }

    @Test
    void testPerformGetFileInfo() throws Exception {
        Parameters parameters = mock(Parameters.class);

        when(parameters.getRequiredFileEntry(FILE)).thenReturn(mockedFileEntry);
        when(mockedFileEntry.getName()).thenReturn("file.txt");
        when(mockedFileEntry.getExtension()).thenReturn("txt");
        when(mockedFileEntry.getMimeType()).thenReturn("text/plain");
        when(mockedFile.getContentLength(mockedFileEntry)).thenReturn(13L);

        Map<String, Object> result = FileHelperGetFileInfoAction.perform(parameters, parameters, mockedContext);

        assertEquals("file.txt", result.get("name"));
        assertEquals("txt", result.get("extension"));
        assertEquals("text/plain", result.get("mimeType"));
        assertEquals(13L, result.get("size"));
    }

    @Test
    void testPerformReadAsText() {
        Parameters parameters = mock(Parameters.class);

        when(parameters.getRequiredFileEntry(FILE)).thenReturn(mockedFileEntry);
        when(mockedFile.readToString(mockedFileEntry)).thenReturn("Hello, World!");

        assertEquals("Hello, World!", FileHelperReadAsTextAction.perform(parameters, parameters, mockedContext));
    }

    @Test
    void testPerformToBase64() throws Exception {
        Parameters parameters = mock(Parameters.class);

        when(parameters.getRequiredFileEntry(FILE)).thenReturn(mockedFileEntry);
        when(mockedFile.readAllBytes(mockedFileEntry)).thenReturn("Hello, World!".getBytes(StandardCharsets.UTF_8));

        Base64.Encoder encoder = Base64.getEncoder();

        assertEquals(
            encoder.encodeToString("Hello, World!".getBytes(StandardCharsets.UTF_8)),
            FileHelperToBase64Action.perform(parameters, parameters, mockedContext));
    }

    @Test
    void testPerformWriteText() throws Exception {
        Parameters parameters = MockParametersFactory.create(Map.of(TEXT, "Hello, World!", FILENAME, "hello.txt"));

        when(mockedFile.storeContent("hello.txt", "Hello, World!")).thenReturn(mockedFileEntry);

        assertEquals(mockedFileEntry, FileHelperWriteTextAction.perform(parameters, parameters, mockedContext));
    }
}
