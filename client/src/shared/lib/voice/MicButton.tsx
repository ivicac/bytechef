import Button from '@/components/Button/Button';
import {Loader2Icon, MicIcon, SquareIcon} from 'lucide-react';

import type {PushToTalkStatusType} from './usePushToTalk';

interface MicButtonPropsI {
    className?: string;
    disabled?: boolean;
    onClick: () => void;
    status: PushToTalkStatusType;
}

export function MicButton({className, disabled, onClick, status}: MicButtonPropsI) {
    const label = status === 'recording' ? 'Stop recording' : 'Record voice message';
    const isBusy = status === 'transcribing';

    const icon =
        status === 'recording' ? (
            <SquareIcon className="size-4 fill-red-500 text-red-500" />
        ) : status === 'transcribing' ? (
            <Loader2Icon className="size-4 animate-spin" data-testid="mic-spinner" />
        ) : (
            <MicIcon className="size-4" />
        );

    return (
        <Button
            aria-label={label}
            className={className}
            disabled={disabled || isBusy}
            icon={icon}
            onClick={onClick}
            size="icon"
            variant="ghost"
        />
    );
}
