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

package com.bytechef.atlas.configuration.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.bytechef.atlas.configuration.domain.Workflow.Format;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * @author Ivica Cardic
 */
@ExtendWith(ObjectMapperSetupExtension.class)
class WorkflowTest {

    @Test
    void testFieldMappingInputParsesObjectName() {
        String definition =
            """
                {
                    "inputs": [
                        {
                            "name": "contactMapping",
                            "label": "Contact Mapping",
                            "type": "field_mapping",
                            "objectName": "Contacts"
                        }
                    ]
                }
                """;

        Workflow workflow = new Workflow("1", definition, Format.JSON);

        Workflow.Input input = workflow.getInputs()
            .getFirst();

        assertEquals("Contacts", input.objectName());
        assertEquals("field_mapping", input.type());
    }

    @Test
    void testPlainInputHasNullObjectName() {
        String definition =
            """
                {
                    "inputs": [
                        {
                            "name": "x",
                            "type": "string"
                        }
                    ]
                }
                """;

        Workflow workflow = new Workflow("1", definition, Format.JSON);

        assertNull(workflow.getInputs()
            .getFirst()
            .objectName());
    }
}
