import {Input} from '@/components/ui/input';
import {Popover, PopoverContent, PopoverTrigger} from '@/components/ui/popover';
import {ComponentDefinitionBasicModel, TaskDispatcherDefinitionModel} from '@/shared/middleware/platform/configuration';
import {PropsWithChildren, useEffect, useState} from 'react';

import useWorkflowDataStore from '../stores/useWorkflowDataStore';
import WorkflowNodesPopoverMenuList from './WorkflowNodesPopoverMenuList';

interface WorkflowNodesPopoverMenuProps extends PropsWithChildren {
    condition?: boolean;
    id: string;
    edge?: boolean;
    hideActionComponents?: boolean;
    hideTriggerComponents?: boolean;
    hideTaskDispatchers?: boolean;
}

const WorkflowNodesPopoverMenu = ({
    children,
    condition = false,
    edge = false,
    hideActionComponents = false,
    hideTaskDispatchers = false,
    hideTriggerComponents = false,
    id,
}: WorkflowNodesPopoverMenuProps) => {
    const [filter, setFilter] = useState('');
    const [filteredActionComponentDefinitions, setFilteredActionComponentDefinitions] = useState<
        Array<ComponentDefinitionBasicModel>
    >([]);
    const [filteredTaskDispatcherDefinitions, setFilteredTaskDispatcherDefinitions] = useState<
        Array<TaskDispatcherDefinitionModel>
    >([]);
    const [filteredTriggerComponentDefinitions, setFilteredTriggerComponentDefinitions] = useState<
        Array<ComponentDefinitionBasicModel>
    >([]);

    const {componentDefinitions, taskDispatcherDefinitions} = useWorkflowDataStore();

    useEffect(() => {
        if (taskDispatcherDefinitions) {
            setFilteredTaskDispatcherDefinitions(
                taskDispatcherDefinitions.filter(
                    ({name, title}) =>
                        name?.toLowerCase().includes(filter.toLowerCase()) ||
                        title?.toLowerCase().includes(filter.toLowerCase())
                )
            );
        }
    }, [taskDispatcherDefinitions, filter, edge]);

    useEffect(() => {
        if (componentDefinitions) {
            setFilteredActionComponentDefinitions(
                componentDefinitions.filter(
                    ({actionsCount, name, title}) =>
                        actionsCount &&
                        (name?.toLowerCase().includes(filter.toLowerCase()) ||
                            title?.toLowerCase().includes(filter.toLowerCase()))
                )
            );

            setFilteredTriggerComponentDefinitions(
                componentDefinitions.filter(
                    ({name, title, triggersCount}) =>
                        triggersCount &&
                        (name?.toLowerCase().includes(filter.toLowerCase()) ||
                            title?.toLowerCase().includes(filter.toLowerCase()))
                )
            );
        }
    }, [componentDefinitions, filter, edge]);

    return (
        <Popover>
            <PopoverTrigger asChild>{children}</PopoverTrigger>

            <PopoverContent
                align="start"
                className="w-workflow-nodes-popover-menu-width rounded-lg p-0 will-change-auto"
                side="right"
                sideOffset={-34}
            >
                <div className="nowheel">
                    {typeof componentDefinitions === 'undefined' ||
                        (typeof taskDispatcherDefinitions === 'undefined' && (
                            <div className="px-3 py-2 text-xs text-gray-500">Something went wrong.</div>
                        ))}

                    <header className="p-3 text-center text-gray-600">
                        <Input
                            name="workflowNodeFilter"
                            onChange={(event) => setFilter(event.target.value)}
                            placeholder="Filter workflow nodes"
                            value={filter}
                        />
                    </header>

                    <WorkflowNodesPopoverMenuList
                        actionComponentDefinitions={filteredActionComponentDefinitions}
                        condition={condition}
                        edge={edge}
                        hideActionComponents={hideActionComponents}
                        hideTaskDispatchers={hideTaskDispatchers}
                        hideTriggerComponents={hideTriggerComponents}
                        id={id}
                        taskDispatcherDefinitions={filteredTaskDispatcherDefinitions}
                        triggerComponentDefinitions={filteredTriggerComponentDefinitions}
                    />
                </div>
            </PopoverContent>
        </Popover>
    );
};

export default WorkflowNodesPopoverMenu;
