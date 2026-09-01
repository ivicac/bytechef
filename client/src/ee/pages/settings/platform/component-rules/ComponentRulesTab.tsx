import EmptyList from '@/components/EmptyList';
import PageLoader from '@/components/PageLoader';
import {Button} from '@/components/ui/button';
import ComponentRuleDialog from '@/ee/pages/settings/platform/component-rules/components/ComponentRuleDialog';
import ComponentRuleList, {
    type ComponentRuleItemType,
} from '@/ee/pages/settings/platform/component-rules/components/ComponentRuleList';
import {useComponentRulesQuery} from '@/shared/middleware/graphql';
import {ShieldCheckIcon} from 'lucide-react';
import {useState} from 'react';

/**
 * Tenant-wide conditional governance of individual action calls. A rule names a component (and optionally one of its
 * actions), a phase, an enforcement action, and a condition evaluated against the call's real input.
 */
const ComponentRulesTab = () => {
    const [dialogOpen, setDialogOpen] = useState(false);
    const [editedComponentRule, setEditedComponentRule] = useState<ComponentRuleItemType | undefined>(undefined);

    const {data, error, isLoading} = useComponentRulesQuery({componentName: undefined});

    const componentRules = data?.componentRules ?? [];

    const handleEdit = (componentRule: ComponentRuleItemType) => {
        setEditedComponentRule(componentRule);
        setDialogOpen(true);
    };

    const handleAdd = () => {
        setEditedComponentRule(undefined);
        setDialogOpen(true);
    };

    return (
        <PageLoader errors={[error]} loading={isLoading}>
            <div className="mt-4 flex flex-col gap-4">
                {componentRules.length > 0 ? (
                    <>
                        <div className="flex justify-end">
                            <Button onClick={handleAdd}>Add Rule</Button>
                        </div>

                        <ComponentRuleList componentRules={componentRules} onEdit={handleEdit} />
                    </>
                ) : (
                    <div className="flex flex-1 items-center justify-center py-12">
                        <EmptyList
                            button={<Button onClick={handleAdd}>Add Rule</Button>}
                            icon={<ShieldCheckIcon className="size-12 text-content-neutral-tertiary" />}
                            message="Rules conditionally block or tag individual action calls based on their input."
                            title="No Component Rules"
                        />
                    </div>
                )}
            </div>

            <ComponentRuleDialog componentRule={editedComponentRule} onOpenChange={setDialogOpen} open={dialogOpen} />
        </PageLoader>
    );
};

export default ComponentRulesTab;
