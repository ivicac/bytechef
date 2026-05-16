import {fireEvent, render, screen} from '@testing-library/react';
import {describe, expect, it, vi} from 'vitest';

import {MicButton} from './MicButton';

describe('MicButton', () => {
    it('calls onClick when clicked', () => {
        const onClick = vi.fn();

        render(<MicButton onClick={onClick} status="idle" />);

        fireEvent.click(screen.getByRole('button', {name: /record/i}));
        expect(onClick).toHaveBeenCalled();
    });

    it('renders a stop indicator when recording', () => {
        render(<MicButton onClick={() => undefined} status="recording" />);
        expect(screen.getByRole('button', {name: /stop/i})).toBeInTheDocument();
    });

    it('renders a spinner while transcribing', () => {
        render(<MicButton onClick={() => undefined} status="transcribing" />);
        expect(screen.getByTestId('mic-spinner')).toBeInTheDocument();
    });
});
