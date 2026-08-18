import LoadingDots from '@/components/LoadingDots';
import {Alert, AlertDescription, AlertTitle} from '@/components/ui/alert';
import {
    useCreateBlankAutomationMutation,
    useDeleteAutomationMutation,
    useDeprovisionReferenceMutation,
    useSetAutomationEnabledMutation,
} from '@/ee/pages/embedded/automation-hub/mutations/automationHub.mutations';
import {
    useGetAutomationsQuery,
    useGetTemplateProjectsQuery,
} from '@/ee/pages/embedded/automation-hub/queries/automationHub.queries';
import {useAutomationHubStore} from '@/ee/pages/embedded/automation-hub/stores/useAutomationHubStore';
import CatalogToolbar, {
    CATALOG_LAYOUT_STORAGE_KEY,
} from '@/ee/pages/embedded/automation-hub/views/components/CatalogToolbar';
import TemplateGridSection, {
    CatalogFilterType,
    CatalogLayoutType,
} from '@/ee/pages/embedded/automation-hub/views/components/TemplateGridSection';
import ActivationWizard from '@/ee/pages/embedded/automation-hub/wizard/ActivationWizard';
import {
    AutomationWorkflowProjectKindEnum,
    AutomationWorkflowProjectWorkflowTemplate,
    ConnectedUserProjectWorkflow,
} from '@/ee/shared/middleware/embedded/public';
import {useMemo, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {useShallow} from 'zustand/react/shallow';

/**
 * Read outside the component so a browser that throws on storage access (a private window, site
 * data blocked) still renders the default layout rather than failing the whole view.
 */
const readStoredLayout = (): string | undefined => {
    try {
        return localStorage.getItem(CATALOG_LAYOUT_STORAGE_KEY) ?? undefined;
    } catch {
        return undefined;
    }
};

interface ActiveTemplateI {
    kind: AutomationWorkflowProjectKindEnum;
    template: AutomationWorkflowProjectWorkflowTemplate;
}

interface AutomationsViewProps {
    onActivate?: (template: AutomationWorkflowProjectWorkflowTemplate, kind: AutomationWorkflowProjectKindEnum) => void;
}

/**
 * The hub's landing view: one flat catalog of every published template, each card carrying the
 * usage state of the connected user's matching automation, followed by the automations no template
 * accounts for — workflows built from scratch, copies of withdrawn templates, dangling references,
 * and any second copy of a template whose card is already taken. Every automation the user owns is
 * therefore reachable from this one grid.
 *
 * An automation is matched to its template by uuid: a REFERENCE through `catalogWorkflowUuid`, a
 * COPY through `copiedFromWorkflowUuid`. Exactly one automation can occupy a card, so nothing is
 * hidden: everything else falls to its own card below.
 *
 * Activation is handled by the caller — `onActivate` is a testing seam; the real page instead
 * opens the `ActivationWizard` via the `activeTemplate` state below.
 */
const AutomationsView = ({onActivate}: AutomationsViewProps) => {
    const [activeTemplate, setActiveTemplate] = useState<ActiveTemplateI>();
    const [storedLayout, setStoredLayout] = useState<CatalogLayoutType | undefined>(
        () => readStoredLayout() as CatalogLayoutType | undefined
    );

    const [filter, setFilter] = useState<CatalogFilterType>('all');
    const [search, setSearch] = useState('');

    const {defaultLayout, layoutSwitcherAllowed, newWorkflowEnabled} = useAutomationHubStore(
        useShallow((state) => ({
            defaultLayout: state.defaultLayout,
            layoutSwitcherAllowed: state.layoutSwitcherAllowed,
            newWorkflowEnabled: state.tabs.newWorkflow,
        }))
    );

    const navigate = useNavigate();

    const {data: projects, error: projectsError, isLoading: projectsLoading} = useGetTemplateProjectsQuery();
    const {data: automations, error: automationsError, isLoading: automationsLoading} = useGetAutomationsQuery();

    const {mutate: createBlankAutomation} = useCreateBlankAutomationMutation();
    // `mutateAsync`, not `mutate`: the cards keep their confirmation dialog open until the removal
    // settles, which needs a promise to await.
    const {mutateAsync: deleteAutomation} = useDeleteAutomationMutation();
    const {mutateAsync: deprovisionReference} = useDeprovisionReferenceMutation();
    const {mutate: setEnabled} = useSetAutomationEnabledMutation();

    const {automationsByTemplateId, unmatchedAutomations} = useMemo(() => {
        const publishedTemplateIds = new Set(
            (projects || []).flatMap((project) => project.workflowTemplates || []).map((template) => template.id)
        );

        const matchedAutomations = new Map<string, ConnectedUserProjectWorkflow>();
        const unmatched: ConnectedUserProjectWorkflow[] = [];

        for (const automation of automations || []) {
            const templateId =
                automation.kind === 'REFERENCE' ? automation.catalogWorkflowUuid : automation.copiedFromWorkflowUuid;

            // `dangling` is excluded explicitly rather than relied upon to fail the uuid check: a
            // dangling reference absorbed into a card would lose its "Needs attention" badge and
            // render as a healthy activation.
            if (!templateId || automation.dangling || !publishedTemplateIds.has(templateId)) {
                unmatched.push(automation);

                continue;
            }

            // A template can legitimately have more than one automation — one created through the
            // vendor's API or the sync bridge's implicit copy. The first takes the card; the extras
            // get their own, so every one of them stays removable.
            if (matchedAutomations.has(templateId)) {
                unmatched.push(automation);
            } else {
                matchedAutomations.set(templateId, automation);
            }
        }

        return {automationsByTemplateId: matchedAutomations, unmatchedAutomations: unmatched};
    }, [automations, projects]);

    // The vendor's `defaultLayout` is where a viewer starts; their own choice takes over from
    // there. With the switcher withdrawn there is no control left to change it back, so a choice
    // stored while it was offered must not outlive it.
    const layout = layoutSwitcherAllowed ? (storedLayout ?? defaultLayout) : defaultLayout;

    const handleLayoutChange = (nextLayout: CatalogLayoutType) => {
        setStoredLayout(nextLayout);

        try {
            localStorage.setItem(CATALOG_LAYOUT_STORAGE_KEY, nextLayout);
        } catch {
            // A viewer with site data blocked still gets the layout for this session.
        }
    };

    const handleCreateBlankAutomation = () => {
        createBlankAutomation(undefined, {
            onSuccess: (workflowUuid) => navigate(`/embedded/hub/builder/${workflowUuid}`),
        });
    };

    const handleUseTemplate = (
        template: AutomationWorkflowProjectWorkflowTemplate,
        kind: AutomationWorkflowProjectKindEnum
    ) => {
        if (onActivate) {
            onActivate(template, kind);

            return;
        }

        setActiveTemplate({kind, template});
    };

    if (projectsLoading || automationsLoading) {
        return (
            <div className="flex size-full items-center justify-center" data-testid="automations-view-loading">
                <LoadingDots />
            </div>
        );
    }

    return (
        <div className="flex size-full flex-col gap-6 overflow-y-auto">
            <CatalogToolbar
                filter={filter}
                layout={layout}
                layoutSwitcherAllowed={layoutSwitcherAllowed}
                newWorkflowEnabled={newWorkflowEnabled}
                onCreateBlankAutomation={handleCreateBlankAutomation}
                onFilterChange={setFilter}
                onLayoutChange={handleLayoutChange}
                onSearchChange={setSearch}
                search={search}
            />

            {automationsError && (
                <Alert variant="destructive">
                    <AlertTitle>Unable to load automations</AlertTitle>

                    <AlertDescription>{automationsError.message}</AlertDescription>
                </Alert>
            )}

            {projectsError ? (
                <Alert variant="destructive">
                    <AlertTitle>Unable to load templates</AlertTitle>

                    <AlertDescription>{projectsError.message}</AlertDescription>
                </Alert>
            ) : (
                <TemplateGridSection
                    activationDisabled={!!automationsError}
                    automationsByTemplateId={automationsByTemplateId}
                    filter={filter}
                    layout={layout}
                    newWorkflowEnabled={newWorkflowEnabled}
                    onDeleteAutomation={deleteAutomation}
                    onDeprovisionReference={deprovisionReference}
                    onSetEnabled={setEnabled}
                    onUseTemplate={handleUseTemplate}
                    projects={projects || []}
                    search={search}
                    unmatchedAutomations={unmatchedAutomations}
                />
            )}

            {activeTemplate && (
                <ActivationWizard
                    kind={activeTemplate.kind}
                    onClose={() => setActiveTemplate(undefined)}
                    template={activeTemplate.template}
                />
            )}
        </div>
    );
};

export default AutomationsView;
