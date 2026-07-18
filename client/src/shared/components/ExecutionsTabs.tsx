import {useLocation, useNavigate} from 'react-router-dom';
import {twMerge} from 'tailwind-merge';

interface ExecutionsTabI {
    label: string;
    path: string;
}

const EXECUTIONS_TABS: Array<ExecutionsTabI> = [
    {label: 'Workflow Executions', path: '/automation/executions'},
    {label: 'Tool Invocations', path: '/automation/executions/tool-invocations'},
];

const ExecutionsTabs = () => {
    const navigate = useNavigate();

    const location = useLocation();

    return (
        <div className="flex items-center gap-1">
            {EXECUTIONS_TABS.map((executionsTab) => {
                const active = location.pathname === executionsTab.path;

                return (
                    <button
                        className={twMerge(
                            'rounded-md px-3 py-1.5 text-sm font-medium',
                            active
                                ? 'bg-surface-neutral-secondary text-content-neutral-primary'
                                : 'text-content-neutral-secondary hover:text-content-neutral-primary'
                        )}
                        key={executionsTab.path}
                        onClick={() => navigate(executionsTab.path)}
                        type="button"
                    >
                        {executionsTab.label}
                    </button>
                );
            })}
        </div>
    );
};

export default ExecutionsTabs;
