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

package com.bytechef.automation.configuration.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.automation.configuration.security.ProjectVisibilityFilter;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.ResourceVisibilityResolver;
import com.bytechef.platform.category.domain.Category;
import com.bytechef.platform.category.service.CategoryService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.LongPredicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ProjectCategoryFacadeImplTest {

    private static final long HIDDEN_PROJECT_ID = 2L;
    private static final long OTHER_WORKSPACE_ID = 6L;
    private static final long UNCATEGORIZED_PROJECT_ID = 3L;
    private static final long VISIBLE_PROJECT_ID = 1L;
    private static final long WORKSPACE_ID = 5L;

    private final CategoryService categoryService = mock(CategoryService.class);
    private final ProjectService projectService = mock(ProjectService.class);

    /**
     * The third project in the fixture is filed under no category at all, so this also pins that a null category id
     * never reaches the lookup.
     */
    @Test
    void testGetProjectCategoriesScopesToWorkspace() {
        when(categoryService.getCategories(List.of(10L, 11L)))
            .thenReturn(List.of(new Category(10, "a"), new Category(11, "b")));

        List<Category> categories = facadeWith(id -> true).getProjectCategories(WORKSPACE_ID);

        assertThat(categories).hasSize(2);

        verify(projectService).getWorkspaceProjectIds(WORKSPACE_ID);
        verify(categoryService).getCategories(List.of(10L, 11L));
    }

    /**
     * A category name aggregated off a project the caller cannot see is a name disclosed from a withheld project, and a
     * filter option that selects nothing in the listing this feeds.
     *
     * <p>
     * The load-bearing assertion is on the ids handed to {@code CategoryService}: the two projects here carry different
     * category ids, so a facade that stopped filtering would ask for both and fail. The returned list is asserted as
     * well, so that a facade returning null rather than what the collaborator handed back cannot pass.
     */
    @Test
    void testGetProjectCategoriesDropsTheCategoryOfAProjectTheResolverHides() {
        when(categoryService.getCategories(List.of(10L))).thenReturn(List.of(new Category(10, "a")));

        List<Category> categories = facadeWith(id -> id != HIDDEN_PROJECT_ID).getProjectCategories(WORKSPACE_ID);

        assertThat(categories).hasSize(1);

        verify(categoryService).getCategories(List.of(10L));
    }

    /**
     * The facade previously read every project in the instance, so a workspace holding no projects still offered
     * another workspace's categories. Both halves are asserted: that no category id is asked for, and that the caller
     * is handed an empty list rather than whatever the unstubbed collaborator happened to return.
     */
    @Test
    void testGetProjectCategoriesOfAWorkspaceWithoutProjectsIsEmpty() {
        when(projectService.getWorkspaceProjectIds(OTHER_WORKSPACE_ID)).thenReturn(List.of());
        when(projectService.getProjects(List.of())).thenReturn(List.of());
        when(categoryService.getCategories(List.of())).thenReturn(List.of());

        List<Category> categories = facadeWith(id -> true).getProjectCategories(OTHER_WORKSPACE_ID);

        assertThat(categories).isEmpty();

        verify(categoryService).getCategories(List.of());
    }

    private ProjectCategoryFacadeImpl facadeWith(LongPredicate visible) {
        when(projectService.getWorkspaceProjectIds(WORKSPACE_ID)).thenReturn(List.of(1L, 2L, 3L));
        when(projectService.getProjects(List.of(1L, 2L, 3L)))
            .thenReturn(
                List.of(
                    project(VISIBLE_PROJECT_ID, 10L), project(HIDDEN_PROJECT_ID, 11L),
                    project(UNCATEGORIZED_PROJECT_ID, null)));

        return new ProjectCategoryFacadeImpl(categoryService, projectService, projectVisibilityFilter(visible));
    }

    private static Project project(long id, Long categoryId) {
        Project project = new Project();

        project.setId(id);
        project.setCategoryId(categoryId);

        return project;
    }

    /**
     * The production {@link ProjectVisibilityFilter} over a stubbed resolver, not a mock of the filter — so the test
     * fails if this facade stops routing through the one component every project list surface shares.
     */
    @SuppressWarnings("unchecked")
    private static ProjectVisibilityFilter projectVisibilityFilter(LongPredicate visible) {
        ResourceVisibilityResolver resourceVisibilityResolver =
            (resourceType, workspaceId, candidates) -> candidates.stream()
                .map(ResourceVisibilityResolver.VisibilityRecord::id)
                .filter(visible::test)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        ObjectProvider<ResourceVisibilityResolver> objectProvider = mock(ObjectProvider.class);

        when(objectProvider.getIfAvailable()).thenReturn(resourceVisibilityResolver);

        return new ProjectVisibilityFilter(objectProvider);
    }
}
