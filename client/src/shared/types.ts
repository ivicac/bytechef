import {UpdateWorkflowRequestI} from '@/shared//mutations/platform/workflows.mutations';
import {
    ArrayPropertyModel,
    BooleanPropertyModel,
    ComponentDefinitionBasicModel,
    ComponentDefinitionModel,
    ControlTypeModel,
    DatePropertyModel,
    DateTimePropertyModel,
    DynamicPropertiesPropertyModel,
    FileEntryPropertyModel,
    IntegerPropertyModel,
    NullPropertyModel,
    NumberPropertyModel,
    ObjectPropertyModel,
    PropertyModel,
    StringPropertyModel,
    TaskDispatcherDefinitionModel,
    TaskPropertyModel,
    TimePropertyModel,
    ValuePropertyModel,
    WorkflowModel,
} from '@/shared/middleware/platform/configuration';
import {UseMutationResult} from '@tanstack/react-query';
import {ReactNode} from 'react';
import {Node} from 'reactflow';

export type DataPillType = {
    componentName?: string;
    componentDefinition?: ComponentDefinitionModel | string;
    componentIcon?: string;
    id: string;
    nodeName?: string;
    value: string;
};

export type ComponentOperationType = {
    componentName: string;
    operationName: string;
    workflowNodeName?: string;
};

export type ComponentPropertiesType =
    | {
          componentDefinition: ComponentDefinitionBasicModel;
          properties?: Array<PropertyModel>;
      }
    | undefined;

export type TaskDispatcherType = {
    componentName: string;
    icon: ReactNode;
    label: string;
    name: string;
};

export type ComponentType = {
    componentName: string;
    displayConditions?: {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        [key: string]: boolean;
    };
    metadata?: {
        ui?: {
            dynamicPropertyTypes?: {[key: string]: string};
        };
    };
    notes?: string;
    operationName: string;
    parameters?: {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        [key: string]: any;
    };
    title?: string;
    type?: string;
    workflowNodeName: string;
};

export type ClickedItemType = {
    componentName?: string;
    trigger?: boolean;
    taskDispatcher?: boolean;
} & (ComponentDefinitionBasicModel | TaskDispatcherDefinitionModel);

export type NodeType = {
    componentName?: string;
    connections?: Array<WorkflowConnectionType>;
    connectionId?: number;
    displayConditions?: {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        [key: string]: boolean;
    };
    metadata?: {
        ui?: {
            dynamicPropertyTypes?: {[key: string]: string};
        };
    };
    icon?: ReactNode;
    id: string;
    label?: string;
    name: string;
    operationName?: string;
    parameters?: {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        [key: string]: any;
    };
    taskDispatcher?: boolean;
    trigger?: boolean;
    type: string;
    version: number;
};

export type NodeWithMetadataType = Node & {
    metadata?: {
        conditionChild?: boolean;
    };
};

export type SubPropertyType = PropertyType & {custom: boolean};

export type WorkflowDefinitionType = {
    description?: string;
    label?: string;
    inputs?: Array<WorkflowInputType>;
    outputs?: Array<WorkflowOutputType>;
    tasks?: Array<WorkflowTaskType>;
    triggers?: Array<WorkflowTriggerType>;
};

export type WorkflowInputType = {
    label?: string;
    name: string;
    required?: boolean;
    type?: string;
};

export type WorkflowOutputType = {
    name: string;
    value: object;
};

export type WorkflowTaskType = {
    connections: [
        {
            componentName: string;
            componentVersion: number;
            key: string;
            required: boolean;
            workflowNodeName: string;
        },
    ] & {
        [key: string]: WorkflowConnectionType;
    };
    finalize?: Array<WorkflowTaskType>;
    label?: string;
    name: string;
    node?: string;
    parameters?: {[key: string]: object};
    post?: Array<WorkflowTaskType>;
    pre?: Array<WorkflowTaskType>;
    timeout?: string;
    type: string;
};

export type WorkflowTriggerType = {
    label?: string;
    name: string;
    parameters?: {[key: string]: object};
    timeout?: string;
    type: string;
};

export type WorkflowConnectionType = {
    componentName: string;
    componentVersion: number;
};

export type ArrayPropertyType = PropertyModel & {
    additionalProperties?: Array<PropertyModel>;
    controlType?: ControlTypeModel;
    custom?: boolean;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    defaultValue?: any;
    key?: string;
    label?: string;
    properties?: Array<PropertyType>;
};

type PropertyTypeAllType = ArrayPropertyModel &
    BooleanPropertyModel &
    DatePropertyModel &
    DateTimePropertyModel &
    DynamicPropertiesPropertyModel &
    FileEntryPropertyModel &
    IntegerPropertyModel &
    NumberPropertyModel &
    NullPropertyModel &
    ObjectPropertyModel &
    PropertyModel &
    StringPropertyModel &
    TaskPropertyModel &
    TimePropertyModel &
    ValuePropertyModel;

export type PropertyType = Omit<PropertyTypeAllType, 'controlType'> & {
    additionalProperties?: Array<PropertyModel>;
    controlType?: ControlTypeModel;
    custom?: boolean;
    expressionEnabled?: boolean;
};

export type UpdateWorkflowMutationType = UseMutationResult<WorkflowModel, Error, UpdateWorkflowRequestI, unknown>;
