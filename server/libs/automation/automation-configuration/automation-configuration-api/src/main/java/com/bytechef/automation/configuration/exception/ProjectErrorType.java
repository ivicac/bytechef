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

package com.bytechef.automation.configuration.exception;

import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.exception.AbstractErrorType;

/**
 * @author Ivica Cardic
 */
public class ProjectErrorType extends AbstractErrorType {

    public static final ProjectErrorType CREATE_PROJECT = new ProjectErrorType(100);
    public static final ProjectErrorType DELETE_PROJECT = new ProjectErrorType(101);
    public static final ProjectErrorType GET_PROJECT = new ProjectErrorType(102);
    public static final ProjectErrorType GET_PROJECT_STATUS = new ProjectErrorType(103);
    public static final ProjectErrorType LIST_PROJECTS = new ProjectErrorType(104);
    public static final ProjectErrorType PUBLISH_PROJECT = new ProjectErrorType(105);
    public static final ProjectErrorType SEARCH_PROJECTS = new ProjectErrorType(106);
    public static final ProjectErrorType UPDATE_PROJECT = new ProjectErrorType(107);

    /** Unknown project, project outside the caller's workspace, or grantee outside the project's workspace. */
    public static final ProjectErrorType INVALID_PROJECT = new ProjectErrorType(Project.class, 100);

    public static final ProjectErrorType UNSUPPORTED_VISIBILITY = new ProjectErrorType(Project.class, 101);

    private ProjectErrorType(int errorKey) {
        super(ProjectErrorType.class, errorKey);
    }

    private ProjectErrorType(Class<?> errorClass, int errorKey) {
        super(errorClass, errorKey);
    }
}
