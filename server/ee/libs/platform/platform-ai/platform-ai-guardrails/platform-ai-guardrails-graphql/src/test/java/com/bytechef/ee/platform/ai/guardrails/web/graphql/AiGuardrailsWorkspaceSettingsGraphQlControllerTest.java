/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.web.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsSettingsScope;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsWorkspaceSettings;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsWorkspaceSettings.BlockingMode;
import com.bytechef.ee.platform.ai.guardrails.service.AiGuardrailsWorkspaceSettingsService;
import com.bytechef.ee.platform.ai.guardrails.web.graphql.AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput;
import com.bytechef.graphql.error.GraphQlBadRequestException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * @version ee
 */
class AiGuardrailsWorkspaceSettingsGraphQlControllerTest {

    private static final long WORKSPACE_ID = 7L;
    private static final long OTHER_WORKSPACE_ID = 99L;

    private AiGuardrailsWorkspaceSettingsGraphQlController aiGuardrailsWorkspaceSettingsGraphQlController;
    private AiGuardrailsWorkspaceSettingsService aiGuardrailsWorkspaceSettingsService;

    @BeforeEach
    void beforeEach() {
        aiGuardrailsWorkspaceSettingsService = mock(AiGuardrailsWorkspaceSettingsService.class);
        aiGuardrailsWorkspaceSettingsGraphQlController =
            new AiGuardrailsWorkspaceSettingsGraphQlController(aiGuardrailsWorkspaceSettingsService);
    }

    @Test
    void testAiGuardrailsWorkspaceSettingsQueryRequiresWorkspaceMembershipOrAdmin() throws NoSuchMethodException {
        Method method = AiGuardrailsWorkspaceSettingsGraphQlController.class.getDeclaredMethod(
            "aiGuardrailsWorkspaceSettings", Long.class, AiGuardrailsSettingsScope.class);

        assertThat(method.getAnnotation(QueryMapping.class)).isNotNull();

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value())
            .as("the non-admin branch must exclude the EMBEDDED scope: this gate keys on workspaceId while the "
                + "method body dispatches on scope, so without that condition a member of any workspace could pass "
                + "their own id alongside scope: EMBEDDED and be handed the tenant-wide row, which only ROLE_ADMIN "
                + "may write")
            .isEqualTo(
                "hasAuthority('ROLE_ADMIN') or (#scope != "
                    + "T(com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsSettingsScope).EMBEDDED "
                    + "&& #workspaceId != null && hasPermission(#workspaceId, 'Workspace', 'AI_GATEWAY_VIEW'))");
    }

    @Test
    void testEveryArgumentTheQueryBodyBranchesOnAlsoAppearsInItsGate() throws NoSuchMethodException {
        Method method = AiGuardrailsWorkspaceSettingsGraphQlController.class.getDeclaredMethod(
            "aiGuardrailsWorkspaceSettings", Long.class, AiGuardrailsSettingsScope.class);

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value())
            .as("a gate that ignores an argument the body switches on authorizes a different request than the one "
                + "that runs - the defect this query shipped with")
            .contains("#workspaceId")
            .contains("#scope");
    }

    @Test
    void testUpdateAiGuardrailsWorkspaceSettingsMutationRequiresAdminOrWorkspaceEditScope()
        throws NoSuchMethodException {
        Method method = AiGuardrailsWorkspaceSettingsGraphQlController.class.getDeclaredMethod(
            "updateAiGuardrailsWorkspaceSettings", AiGuardrailsWorkspaceSettingsInput.class);

        assertThat(method.getAnnotation(MutationMapping.class)).isNotNull();

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value())
            .as("the non-admin branch must NAME the scopes it allows (null or explicit WORKSPACE) rather than deny "
                + "one it forbids: this gate keys on workspaceId and scope while the method body dispatches on "
                + "scopeOf(input). A deny-list of EMBEDDED alone would also authorize scope: PLATFORM, which is "
                + "refused only by validateScopeWorkspaceIdPairing in the body -- authorization backstopped by "
                + "body-side validation instead of enforced at the gate")
            .isEqualTo(
                "hasAuthority('ROLE_ADMIN') or ((#input.scope == null || #input.scope == "
                    + "T(com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsSettingsScope).WORKSPACE) "
                    + "&& #input.workspaceId != null && hasPermission(#input.workspaceId, 'Workspace', "
                    + "'AI_GATEWAY_EDIT'))");
    }

    @Test
    void testEveryArgumentTheMutationBodyBranchesOnAlsoAppearsInItsGate() throws NoSuchMethodException {
        Method method = AiGuardrailsWorkspaceSettingsGraphQlController.class.getDeclaredMethod(
            "updateAiGuardrailsWorkspaceSettings", AiGuardrailsWorkspaceSettingsInput.class);

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value())
            .as("scopeOf(input) branches on both input.scope and input.workspaceId, so a gate that names only one of "
                + "them authorizes a different request than the one that runs")
            .contains("#input.workspaceId")
            .contains("#input.scope");
    }

    /**
     * Evaluates the mutation's real {@code @PreAuthorize} expression via {@link AiGuardrailGateTestSupport}, the same
     * technique {@code AiGuardrailCustomRuleGraphQlControllerTest} proved discriminates, against three principals
     * sharing the same non-tenant-admin authentication, distinguished only by what a stub {@link PermissionEvaluator}
     * grants them. The third case is the one that actually proves the gate is per-workspace: a {@code hasPermission}
     * call keyed on a constant, or on the wrong argument, would still evaluate true here even though the caller was
     * never granted anything on {@link #WORKSPACE_ID}.
     */
    @Test
    void testUpdateMutationAllowsWorkspaceAdminButRefusesViewerAndOtherWorkspaceAdmin() {
        AiGuardrailsWorkspaceSettingsInput input = workspaceInput(AiGuardrailsSettingsScope.WORKSPACE, WORKSPACE_ID);

        Authentication nonTenantAdmin = new UsernamePasswordAuthenticationToken(
            "workspace-admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));

        PermissionEvaluator grantsTargetWorkspace = mock(PermissionEvaluator.class);

        when(grantsTargetWorkspace.hasPermission(nonTenantAdmin, WORKSPACE_ID, "Workspace", "AI_GATEWAY_EDIT"))
            .thenReturn(true);

        assertThat(
            AiGuardrailGateTestSupport.isAuthorized(
                AiGuardrailsWorkspaceSettingsGraphQlController.class, aiGuardrailsWorkspaceSettingsGraphQlController,
                "updateAiGuardrailsWorkspaceSettings", new Class<?>[] {
                    AiGuardrailsWorkspaceSettingsInput.class
                }, new Object[] {
                    input
                }, nonTenantAdmin, grantsTargetWorkspace))
                    .as("a workspace admin of the target workspace must be allowed")
                    .isTrue();

        PermissionEvaluator grantsNoWorkspace = mock(PermissionEvaluator.class);

        assertThat(
            AiGuardrailGateTestSupport.isAuthorized(
                AiGuardrailsWorkspaceSettingsGraphQlController.class, aiGuardrailsWorkspaceSettingsGraphQlController,
                "updateAiGuardrailsWorkspaceSettings", new Class<?>[] {
                    AiGuardrailsWorkspaceSettingsInput.class
                }, new Object[] {
                    input
                }, nonTenantAdmin, grantsNoWorkspace))
                    .as("a workspace member (viewer) of the target workspace must be refused")
                    .isFalse();

        PermissionEvaluator grantsOnlyAnotherWorkspace = mock(PermissionEvaluator.class);

        when(
            grantsOnlyAnotherWorkspace.hasPermission(
                nonTenantAdmin, OTHER_WORKSPACE_ID, "Workspace", "AI_GATEWAY_EDIT"))
                    .thenReturn(true);

        assertThat(
            AiGuardrailGateTestSupport.isAuthorized(
                AiGuardrailsWorkspaceSettingsGraphQlController.class, aiGuardrailsWorkspaceSettingsGraphQlController,
                "updateAiGuardrailsWorkspaceSettings", new Class<?>[] {
                    AiGuardrailsWorkspaceSettingsInput.class
                }, new Object[] {
                    input
                }, nonTenantAdmin, grantsOnlyAnotherWorkspace))
                    .as("a workspace admin of a different workspace must be refused")
                    .isFalse();
    }

    /**
     * The branch's central safety claim is that widening access to workspace admins never narrows what a tenant admin
     * could already do. A string assertion on the gate's text (asserting it contains
     * {@code hasAuthority('ROLE_ADMIN') or}) cannot catch a disjunct that is present but unreachable at evaluation time
     * -- e.g. a future edit that wraps the whole expression so the admin branch stops being evaluated, or an authority
     * name that drifted from what the deployment actually grants. This evaluates the real gate instead.
     *
     * <p>
     * It uses the input shape a workspace admin is refused on -- {@code scope: EMBEDDED, workspaceId: 7} -- with a
     * {@link PermissionEvaluator} stub left completely unstubbed, so it returns false for every call. That is the
     * strongest form of the assertion: it proves the admin disjunct alone carries the decision, with every other
     * conjunct (the scope check, the workspaceId null check, and the permission check) failing.
     */
    @Test
    void testUpdateMutationAllowsTenantAdminWithNoWorkspacePermissionEvenOnTheEmbeddedScopeInputAWorkspaceAdminIsRefusedOn() {
        AiGuardrailsWorkspaceSettingsInput input = workspaceInput(AiGuardrailsSettingsScope.EMBEDDED, WORKSPACE_ID);

        Authentication tenantAdmin = new UsernamePasswordAuthenticationToken(
            "tenant-admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        PermissionEvaluator grantsNothingAtAll = mock(PermissionEvaluator.class);

        assertThat(
            AiGuardrailGateTestSupport.isAuthorized(
                AiGuardrailsWorkspaceSettingsGraphQlController.class, aiGuardrailsWorkspaceSettingsGraphQlController,
                "updateAiGuardrailsWorkspaceSettings", new Class<?>[] {
                    AiGuardrailsWorkspaceSettingsInput.class
                }, new Object[] {
                    input
                }, tenantAdmin, grantsNothingAtAll))
                    .as("a tenant admin must be allowed even with no workspace permission granted, on the input "
                        + "shape (scope: EMBEDDED, workspaceId: 7) a workspace admin is refused on")
                    .isTrue();
    }

    /**
     * Mirrors {@code AiGuardrailCustomRuleGraphQlControllerTest}'s sweep: enumerate every {@code @MutationMapping} on
     * this controller and assert each one's gate string exactly, rather than reflecting on the single mutation by name.
     * A second mutation added to this controller without this sweep would ship with no gate assertion at all.
     */
    @Test
    void testEveryMutationHasExactlyOneAndItsGateIsExact() {
        List<Method> mutations =
            List.of(AiGuardrailsWorkspaceSettingsGraphQlController.class.getDeclaredMethods())
                .stream()
                .filter(method -> method.isAnnotationPresent(MutationMapping.class))
                .toList();

        assertThat(mutations)
            .as("updateAiGuardrailsWorkspaceSettings")
            .hasSize(1);

        for (Method mutation : mutations) {
            PreAuthorize preAuthorize = mutation.getAnnotation(PreAuthorize.class);

            assertThat(preAuthorize)
                .as("%s must be gated", mutation.getName())
                .isNotNull();

            assertThat(preAuthorize.value())
                .as(
                    "%s must permit ROLE_ADMIN and the workspace-scoped, WORKSPACE-only AI_GATEWAY_EDIT permission",
                    mutation.getName())
                .isEqualTo(
                    "hasAuthority('ROLE_ADMIN') or ((#input.scope == null || #input.scope == "
                        + "T(com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsSettingsScope).WORKSPACE) "
                        + "&& #input.workspaceId != null && hasPermission(#input.workspaceId, 'Workspace', "
                        + "'AI_GATEWAY_EDIT'))");
        }
    }

    /**
     * The escalation the whole task exists to close: a workspace admin of {@link #WORKSPACE_ID} sends
     * {@code workspaceId: 7} <em>and</em> {@code scope: EMBEDDED}. {@code scopeOf(input)} would resolve this to
     * {@code EMBEDDED} and hand back the tenant-wide row -- a row only {@code ROLE_ADMIN} may write -- so the gate must
     * refuse it even though the caller genuinely holds {@code AI_GATEWAY_EDIT} on workspace 7.
     *
     * <p>
     * This asserts on the authorization outcome directly via {@link AiGuardrailGateTestSupport#isAuthorized}, which
     * evaluates the {@code @PreAuthorize} SpEL expression through reflection without ever invoking the method body --
     * so this is independent of {@code validateScopeWorkspaceIdPairing} rejecting the same input for a different
     * reason. A test that merely asserted "this call throws" would pass whether or not this gate's own
     * {@code scope != EMBEDDED} condition existed at all.
     */
    @Test
    void testUpdateMutationRefusesWorkspaceAdminEscalatingToEmbeddedScopeViaTheirOwnWorkspaceId() {
        AiGuardrailsWorkspaceSettingsInput input = workspaceInput(AiGuardrailsSettingsScope.EMBEDDED, WORKSPACE_ID);

        Authentication workspaceAdmin = new UsernamePasswordAuthenticationToken(
            "workspace-admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));

        PermissionEvaluator grantsTargetWorkspaceEdit = mock(PermissionEvaluator.class);

        when(grantsTargetWorkspaceEdit.hasPermission(workspaceAdmin, WORKSPACE_ID, "Workspace", "AI_GATEWAY_EDIT"))
            .thenReturn(true);

        assertThat(
            AiGuardrailGateTestSupport.isAuthorized(
                AiGuardrailsWorkspaceSettingsGraphQlController.class, aiGuardrailsWorkspaceSettingsGraphQlController,
                "updateAiGuardrailsWorkspaceSettings", new Class<?>[] {
                    AiGuardrailsWorkspaceSettingsInput.class
                }, new Object[] {
                    input
                }, workspaceAdmin, grantsTargetWorkspaceEdit))
                    .as("workspaceId: 7 + scope: EMBEDDED must be refused by the gate, even though the caller "
                        + "holds AI_GATEWAY_EDIT on workspace 7")
                    .isFalse();
    }

    /**
     * The escalation an allow-list closes that a deny-list (checking only {@code scope != EMBEDDED}) would not: a
     * workspace admin of {@link #WORKSPACE_ID} sends {@code workspaceId: 7} <em>and</em> {@code scope: PLATFORM}.
     * {@code PLATFORM != EMBEDDED} is true, so a deny-list gate would let this through to the body, which happens to
     * refuse it only because {@code validateScopeWorkspaceIdPairing} rejects a non-null {@code workspaceId} paired with
     * {@code PLATFORM} -- authorization backstopped by body-side validation rather than enforced at the gate. The gate
     * itself must refuse it, independent of whatever the body would have done.
     */
    @Test
    void testUpdateMutationRefusesWorkspaceAdminEscalatingToPlatformScopeViaTheirOwnWorkspaceId() {
        AiGuardrailsWorkspaceSettingsInput input = workspaceInput(AiGuardrailsSettingsScope.PLATFORM, WORKSPACE_ID);

        Authentication workspaceAdmin = new UsernamePasswordAuthenticationToken(
            "workspace-admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));

        PermissionEvaluator grantsTargetWorkspaceEdit = mock(PermissionEvaluator.class);

        when(grantsTargetWorkspaceEdit.hasPermission(workspaceAdmin, WORKSPACE_ID, "Workspace", "AI_GATEWAY_EDIT"))
            .thenReturn(true);

        assertThat(
            AiGuardrailGateTestSupport.isAuthorized(
                AiGuardrailsWorkspaceSettingsGraphQlController.class, aiGuardrailsWorkspaceSettingsGraphQlController,
                "updateAiGuardrailsWorkspaceSettings", new Class<?>[] {
                    AiGuardrailsWorkspaceSettingsInput.class
                }, new Object[] {
                    input
                }, workspaceAdmin, grantsTargetWorkspaceEdit))
                    .as("workspaceId: 7 + scope: PLATFORM must be refused by the gate, even though the caller "
                        + "holds AI_GATEWAY_EDIT on workspace 7")
                    .isFalse();
    }

    /**
     * The shape the automation guardrails settings page actually sends: the GraphQL {@code scope} field is nullable
     * with no default, so the page omits it entirely and sends only {@code workspaceId}. {@code scopeOf(input)}
     * resolves a null scope with a non-null workspaceId to {@code WORKSPACE}, so a workspace admin holding
     * {@code AI_GATEWAY_EDIT} on their own workspace must be allowed through.
     */
    @Test
    void testUpdateMutationAllowsWorkspaceAdminWithNullScope() {
        AiGuardrailsWorkspaceSettingsInput input = workspaceInput(null, WORKSPACE_ID);

        Authentication workspaceAdmin = new UsernamePasswordAuthenticationToken(
            "workspace-admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));

        PermissionEvaluator grantsTargetWorkspaceEdit = mock(PermissionEvaluator.class);

        when(grantsTargetWorkspaceEdit.hasPermission(workspaceAdmin, WORKSPACE_ID, "Workspace", "AI_GATEWAY_EDIT"))
            .thenReturn(true);

        assertThat(
            AiGuardrailGateTestSupport.isAuthorized(
                AiGuardrailsWorkspaceSettingsGraphQlController.class, aiGuardrailsWorkspaceSettingsGraphQlController,
                "updateAiGuardrailsWorkspaceSettings", new Class<?>[] {
                    AiGuardrailsWorkspaceSettingsInput.class
                }, new Object[] {
                    input
                }, workspaceAdmin, grantsTargetWorkspaceEdit))
                    .as("workspaceId: 7 + scope: null (the shape the product actually sends) must be allowed for a "
                        + "workspace admin holding AI_GATEWAY_EDIT on workspace 7")
                    .isTrue();
    }

    @Test
    void testAiGuardrailsWorkspaceSettingsWithNullWorkspaceIdFetchesTenantDefault() {
        AiGuardrailsWorkspaceSettings tenantDefault = new AiGuardrailsWorkspaceSettings(
            AiGuardrailsSettingsScope.PLATFORM, null, true, true, "secret", false, true, false,
            BlockingMode.REDACT_AND_CONTINUE, null, null,
            null);

        when(aiGuardrailsWorkspaceSettingsService.fetchSettings(isNull())).thenReturn(Optional.of(tenantDefault));

        AiGuardrailsWorkspaceSettings result =
            aiGuardrailsWorkspaceSettingsGraphQlController.aiGuardrailsWorkspaceSettings(null, null);

        assertThat(result).isEqualTo(tenantDefault);

        verify(aiGuardrailsWorkspaceSettingsService).fetchSettings(isNull());
    }

    @Test
    void testAiGuardrailsWorkspaceSettingsReturnsNullWhenNoSettingsRowExists() {
        when(aiGuardrailsWorkspaceSettingsService.fetchSettings(eq(1L))).thenReturn(Optional.empty());

        AiGuardrailsWorkspaceSettings result =
            aiGuardrailsWorkspaceSettingsGraphQlController.aiGuardrailsWorkspaceSettings(1L, null);

        assertThat(result).isNull();
    }

    @Test
    void testAiGuardrailsWorkspaceSettingsWithEmbeddedScopeFetchesEmbeddedSettings() {
        AiGuardrailsWorkspaceSettings embeddedSettings = new AiGuardrailsWorkspaceSettings(
            AiGuardrailsSettingsScope.EMBEDDED, null, true, false, null, false, false, false,
            BlockingMode.BLOCK, null, true,
            null);

        when(aiGuardrailsWorkspaceSettingsService.fetchEmbeddedSettings()).thenReturn(
            Optional.of(embeddedSettings));

        AiGuardrailsWorkspaceSettings result = aiGuardrailsWorkspaceSettingsGraphQlController
            .aiGuardrailsWorkspaceSettings(null, AiGuardrailsSettingsScope.EMBEDDED);

        assertThat(result).isEqualTo(embeddedSettings);

        verify(aiGuardrailsWorkspaceSettingsService).fetchEmbeddedSettings();
    }

    @Test
    void testUpdateAiGuardrailsWorkspaceSettingsRoundTripsThroughService() {
        AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput input =
            new AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput(
                null, 1L, true, false, "foo,bar", true, false, true, BlockingMode.BLOCK, 0.75, null, null);

        AiGuardrailsWorkspaceSettings saved = new AiGuardrailsWorkspaceSettings(
            AiGuardrailsSettingsScope.WORKSPACE, 1L, true, false, "foo,bar", true, false, true, BlockingMode.BLOCK,
            0.75, null,
            null);

        when(aiGuardrailsWorkspaceSettingsService.saveSettings(eq(saved))).thenReturn(saved);

        AiGuardrailsWorkspaceSettings result =
            aiGuardrailsWorkspaceSettingsGraphQlController.updateAiGuardrailsWorkspaceSettings(input);

        assertThat(result).isEqualTo(saved);

        verify(aiGuardrailsWorkspaceSettingsService).saveSettings(eq(saved));
    }

    @Test
    void testUpdateAiGuardrailsWorkspaceSettingsPassesRedactMcpResultsThrough() {
        AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput input =
            new AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput(
                null, 1L, null, null, null, null, null, null, null, null, true, null);

        aiGuardrailsWorkspaceSettingsGraphQlController.updateAiGuardrailsWorkspaceSettings(input);

        ArgumentCaptor<AiGuardrailsWorkspaceSettings> captor =
            ArgumentCaptor.forClass(AiGuardrailsWorkspaceSettings.class);

        verify(aiGuardrailsWorkspaceSettingsService).saveSettings(captor.capture());

        AiGuardrailsWorkspaceSettings saved = captor.getValue();

        assertThat(saved.redactMcpResults()).isTrue();
    }

    @Test
    void testUpdateAiGuardrailsWorkspaceSettingsPassesRestoreIntoWorkflowOutputThrough() {
        AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput input =
            new AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput(
                null, 1L, null, null, null, null, null, null, null, null, null, false);

        aiGuardrailsWorkspaceSettingsGraphQlController.updateAiGuardrailsWorkspaceSettings(input);

        ArgumentCaptor<AiGuardrailsWorkspaceSettings> captor =
            ArgumentCaptor.forClass(AiGuardrailsWorkspaceSettings.class);

        verify(aiGuardrailsWorkspaceSettingsService).saveSettings(captor.capture());

        AiGuardrailsWorkspaceSettings saved = captor.getValue();

        assertThat(saved.restoreIntoWorkflowOutput()).isFalse();
    }

    @Test
    void testUpdateAiGuardrailsWorkspaceSettingsPassesTheEmbeddedScopeThrough() {
        aiGuardrailsWorkspaceSettingsGraphQlController.updateAiGuardrailsWorkspaceSettings(embeddedInput());

        ArgumentCaptor<AiGuardrailsWorkspaceSettings> captor =
            ArgumentCaptor.forClass(AiGuardrailsWorkspaceSettings.class);

        verify(aiGuardrailsWorkspaceSettingsService).saveSettings(captor.capture());

        AiGuardrailsWorkspaceSettings saved = captor.getValue();

        assertThat(saved.scope()).isEqualTo(AiGuardrailsSettingsScope.EMBEDDED);
        assertThat(saved.workspaceId()).isNull();
    }

    @Test
    void testUpdateAiGuardrailsWorkspaceSettingsRejectsEmbeddedScopeWithNonNullWorkspaceId() {
        AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput input =
            new AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput(
                AiGuardrailsSettingsScope.EMBEDDED, 1L, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(
            () -> aiGuardrailsWorkspaceSettingsGraphQlController.updateAiGuardrailsWorkspaceSettings(input))
                .isInstanceOf(GraphQlBadRequestException.class)
                .hasMessageContaining("scope=EMBEDDED")
                .hasMessageContaining("workspaceId=1");

        verify(aiGuardrailsWorkspaceSettingsService, never()).saveSettings(any());
    }

    @Test
    void testUpdateAiGuardrailsWorkspaceSettingsRejectsWorkspaceScopeWithNullWorkspaceId() {
        AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput input =
            new AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput(
                AiGuardrailsSettingsScope.WORKSPACE, null, null, null, null, null, null, null, null, null, null,
                null);

        assertThatThrownBy(
            () -> aiGuardrailsWorkspaceSettingsGraphQlController.updateAiGuardrailsWorkspaceSettings(input))
                .isInstanceOf(GraphQlBadRequestException.class)
                .hasMessageContaining("scope=WORKSPACE")
                .hasMessageContaining("workspaceId=null");

        verify(aiGuardrailsWorkspaceSettingsService, never()).saveSettings(any());
    }

    private AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput embeddedInput() {
        return new AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput(
            AiGuardrailsSettingsScope.EMBEDDED, null, true, false, null, false, false, false, BlockingMode.BLOCK,
            null, true, null);
    }

    private AiGuardrailsWorkspaceSettingsInput workspaceInput(
        @Nullable AiGuardrailsSettingsScope scope, @Nullable Long workspaceId) {

        return new AiGuardrailsWorkspaceSettingsInput(
            scope, workspaceId, true, false, null, false, false, false, BlockingMode.BLOCK, null, null, null);
    }
}
