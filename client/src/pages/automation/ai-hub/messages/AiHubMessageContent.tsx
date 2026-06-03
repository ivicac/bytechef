import {MarkdownText} from '@/components/assistant-ui/markdown-text';
import AiHubCreateConnectionMessage, {
    CreateConnectionDataI,
} from '@/pages/automation/ai-hub/connect/AiHubCreateConnectionMessage';
import AiHubSelectConnectionMessage, {
    SelectConnectionDataI,
} from '@/pages/automation/ai-hub/connect/AiHubSelectConnectionMessage';
import AiHubAskUserQuestionMessage, {
    AskUserQuestionDataI,
} from '@/pages/automation/ai-hub/messages/AiHubAskUserQuestionMessage';
import AiHubRunErrorMessage, {RunErrorDataI} from '@/pages/automation/ai-hub/messages/AiHubRunErrorMessage';
import {AiHubToolCallFallback} from '@/pages/automation/ai-hub/messages/AiHubToolCallRenderer';
import {DataMessagePartProps, MessagePrimitive, type SourceMessagePartComponent} from '@assistant-ui/react';
import {ExternalLinkIcon} from 'lucide-react';

const CreateConnectionData = (props: DataMessagePartProps<CreateConnectionDataI>) => (
    <AiHubCreateConnectionMessage {...props} />
);

const AskUserQuestionData = (props: DataMessagePartProps<AskUserQuestionDataI>) => (
    <AiHubAskUserQuestionMessage {...props} />
);

const RunErrorData = (props: DataMessagePartProps<RunErrorDataI>) => <AiHubRunErrorMessage {...props} />;

const SelectConnectionData = (props: DataMessagePartProps<SelectConnectionDataI>) => (
    <AiHubSelectConnectionMessage {...props} />
);

const SourceComponent: SourceMessagePartComponent = ({title, url}) => {
    if (!url) {
        return null;
    }

    const linkLabel = title && title.length > 0 ? title : url;

    return (
        <a
            className="my-1 inline-flex items-center gap-1 rounded-md border border-border bg-muted/50 px-2 py-0.5 text-xs text-muted-foreground hover:bg-muted hover:text-foreground"
            href={url}
            rel="noreferrer"
            target="_blank"
        >
            <ExternalLinkIcon className="size-3" />

            <span className="max-w-[18rem] truncate">{linkLabel}</span>
        </a>
    );
};

const AiHubMessageContent = () => {
    return (
        <MessagePrimitive.Parts
            components={{
                Source: SourceComponent,
                Text: MarkdownText,
                data: {
                    by_name: {
                        'ask-user-question': AskUserQuestionData,
                        'create-connection': CreateConnectionData,
                        'run-error': RunErrorData,
                        'select-connection': SelectConnectionData,
                    },
                },
                tools: {
                    Fallback: AiHubToolCallFallback,
                },
            }}
        />
    );
};

export default AiHubMessageContent;
