'use client';

import Header from '@/shared/layout/Header';

import ApprovalTaskDetail from './components/ApprovalTaskDetail';
import ApprovalTaskList from './components/ApprovalTaskList';
import PendingApprovalsList from './components/PendingApprovalsList';
import {useApprovalTasks} from './hooks/useApprovalTasks';
import {useApprovalTasksStore} from './stores/useApprovalTasksStore';

export default function ApprovalTasks() {
    useApprovalTasks();

    const selectedApprovalTask = useApprovalTasksStore((state) => state.getSelectedApprovalTask());

    return (
        <div className="flex size-full bg-background">
            <ApprovalTaskList />

            <div className="flex h-full flex-1 flex-col">
                <Header position="main" title={selectedApprovalTask?.title ?? ''} />

                <PendingApprovalsList />

                <div className="min-h-0 flex-1 overflow-hidden">
                    <ApprovalTaskDetail />
                </div>
            </div>
        </div>
    );
}
