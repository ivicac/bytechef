import AutomationWorkflowProjectDialog from '@/ee/pages/embedded/automation-workflows/components/automation-workflow-project-dialog/AutomationWorkflowProjectDialog';
import {fireEvent, render, screen, waitFor} from '@testing-library/react';
import {describe, expect, it, vi} from 'vitest';

describe('AutomationWorkflowProjectDialog', () => {
    it('shows new projects in the Automation Hub by default', async () => {
        const onSubmit = vi.fn();

        render(<AutomationWorkflowProjectDialog categories={[]} onClose={vi.fn()} onSubmit={onSubmit} tags={[]} />);

        fireEvent.change(screen.getByLabelText('Name'), {target: {value: 'New project'}});
        fireEvent.click(screen.getByRole('button', {name: 'Save'}));

        await waitFor(() =>
            expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({automationHubVisible: true}))
        );
    });

    it('submits a project hidden from the Automation Hub', async () => {
        const onSubmit = vi.fn();

        render(<AutomationWorkflowProjectDialog categories={[]} onClose={vi.fn()} onSubmit={onSubmit} tags={[]} />);

        fireEvent.change(screen.getByLabelText('Name'), {target: {value: 'API only'}});
        fireEvent.click(screen.getByLabelText('Show in Automation Hub'));
        fireEvent.click(screen.getByRole('button', {name: 'Save'}));

        await waitFor(() =>
            expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({automationHubVisible: false}))
        );
    });
});
