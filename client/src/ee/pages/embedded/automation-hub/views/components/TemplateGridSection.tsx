import {SetAutomationEnabledRequestI} from '@/ee/pages/embedded/automation-hub/mutations/automationHub.mutations';
import AutomationCard from '@/ee/pages/embedded/automation-hub/views/components/AutomationCard';
import TemplateCard from '@/ee/pages/embedded/automation-hub/views/components/TemplateCard';
import {
    AutomationWorkflowProject,
    AutomationWorkflowProjectKindEnum,
    AutomationWorkflowProjectWorkflowTemplate,
    ConnectedUserProjectWorkflow,
} from '@/ee/shared/middleware/embedded/public';
import {useMemo} from 'react';
import {twMerge} from 'tailwind-merge';

export type CatalogLayoutType = 'grid' | 'list';

export type CatalogFilterType = 'active' | 'all' | 'enabled';

interface TemplateGridSectionProps {
    activationDisabled?: boolean;
    automationsByTemplateId: Map<string, ConnectedUserProjectWorkflow>;
    filter: CatalogFilterType;
    layout: CatalogLayoutType;
    onDeleteAutomation: (workflowUuid: string) => Promise<unknown>;
    onDeprovisionReference: (workflowUuid: string) => Promise<unknown>;
    onSetEnabled: (request: SetAutomationEnabledRequestI) => void;
    onUseTemplate: (
        template: AutomationWorkflowProjectWorkflowTemplate,
        kind: AutomationWorkflowProjectKindEnum
    ) => void;
    newWorkflowEnabled: boolean;
    projects: AutomationWorkflowProject[];
    search: string;
    unmatchedAutomations: ConnectedUserProjectWorkflow[];
}

/**
 * The catalog: every published template in one flat grid, each card carrying the usage state of the
 * connected user's matching automation, followed by the automations no template accounts for.
 * Projects only contribute their `kind`, which decides whether activation copies the template or
 * references it.
 *
 * The filter narrows in two steps. `active` keeps what the user has taken up — templates they have
 * activated, plus every automation they built themselves, which is active by definition since they
 * made it deliberately. `enabled` narrows again to what is actually RUNNING: an automation the user
 * owns but has switched off is active, not enabled, and only the second filter tells them apart.
 *
 * The "My Automations" group is shown only when the vendor allows the user to create their own
 * automations: with `newWorkflow` off, a section devoted to what the user built would be a section
 * they can never add to.
 */
const TemplateGridSection = ({
    activationDisabled,
    automationsByTemplateId,
    filter,
    layout,
    newWorkflowEnabled,
    onDeleteAutomation,
    onDeprovisionReference,
    onSetEnabled,
    onUseTemplate,
    projects,
    search,
    unmatchedAutomations,
}: TemplateGridSectionProps) => {
    const normalizedSearch = useMemo(() => search.trim().toLowerCase(), [search]);

    const templates = useMemo(
        () =>
            projects.flatMap((project) =>
                (project.workflowTemplates || [])
                    .filter((template) => (template.label || '').toLowerCase().includes(normalizedSearch))
                    .filter((template) => {
                        const automation = automationsByTemplateId.get(template.id!);

                        if (filter === 'enabled') {
                            return !!automation?.enabled;
                        }

                        return filter === 'all' || !!automation;
                    })
                    .map((template) => ({kind: project.kind!, template}))
            ),
        [automationsByTemplateId, filter, normalizedSearch, projects]
    );

    const automations = useMemo(
        () =>
            newWorkflowEnabled
                ? unmatchedAutomations
                      .filter((automation) => (automation.label || '').toLowerCase().includes(normalizedSearch))
                      .filter((automation) => filter !== 'enabled' || !!automation.enabled)
                : [],
        [filter, newWorkflowEnabled, normalizedSearch, unmatchedAutomations]
    );

    if (templates.length === 0 && automations.length === 0) {
        return (
            <div className="flex items-center justify-center py-10 text-center text-muted-foreground">
                No automations found.
            </div>
        );
    }

    return (
        <div
            className={twMerge('grid gap-4', layout === 'grid' && 'sm:grid-cols-2 lg:grid-cols-3')}
            data-layout={layout}
            data-testid="automations-catalog"
        >
            {templates.map(({kind, template}) => (
                <TemplateCard
                    activationDisabled={activationDisabled}
                    automation={automationsByTemplateId.get(template.id!)}
                    key={template.id}
                    onDeleteAutomation={onDeleteAutomation}
                    onDeprovisionReference={onDeprovisionReference}
                    onSetEnabled={onSetEnabled}
                    onUseTemplate={() => onUseTemplate(template, kind)}
                    template={template}
                />
            ))}

            {automations.length > 0 && (
                // Spanning every column is also what puts the group on its own row rather than
                // letting it fill the gap the template cards left. The extra top margin sits on
                // TOP of the grid's own row gap, so the break between the catalog and the user's
                // own automations reads as a section change rather than another row.
                <h2 className="col-span-full mt-4 text-lg font-semibold">My Automations</h2>
            )}

            {automations.map((automation) => (
                <AutomationCard
                    automation={automation}
                    key={automation.workflowUuid}
                    onDeleteAutomation={onDeleteAutomation}
                    onDeprovisionReference={onDeprovisionReference}
                    onSetEnabled={onSetEnabled}
                />
            ))}
        </div>
    );
};

export default TemplateGridSection;
