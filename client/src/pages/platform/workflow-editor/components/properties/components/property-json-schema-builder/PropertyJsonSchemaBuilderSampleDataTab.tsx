import Button from '@/components/Button/Button';
import {SchemaRecordType} from '@/components/JsonSchemaBuilder/utils/types';
import {Note} from '@/components/Note';
import MonacoEditorLoader from '@/shared/components/MonacoEditorLoader';
import {TriangleAlertIcon} from 'lucide-react';
import {Suspense, lazy, useCallback, useMemo, useState} from 'react';

import {generateSchemaFromSample} from './utils/generateSchemaFromSample';

import type {StandaloneCodeEditorType} from '@/shared/components/MonacoTypes';

const MonacoEditor = lazy(() => import('@/shared/components/MonacoEditorWrapper'));

const GENERATION_ERROR_MESSAGE = 'Could not generate a schema from this sample. Please try again.';

const OVERWRITE_WARNING_MESSAGE = 'Generating will replace your current schema.';

interface PropertyJsonSchemaBuilderSampleDataTabProps {
    onGenerate: (newSchema: SchemaRecordType) => void;
    schema?: SchemaRecordType;
}

const PropertyJsonSchemaBuilderSampleDataTab = ({onGenerate, schema}: PropertyJsonSchemaBuilderSampleDataTabProps) => {
    const [errorMessage, setErrorMessage] = useState<string | undefined>();
    const [generating, setGenerating] = useState(false);
    const [sample, setSample] = useState('');

    const sampleIsValidJson = useMemo(() => {
        if (!sample.trim()) {
            return false;
        }

        try {
            JSON.parse(sample);

            return true;
        } catch {
            return false;
        }
    }, [sample]);

    const schemaHasProperties = useMemo(
        () => Object.keys((schema?.properties as SchemaRecordType | undefined) ?? {}).length > 0,
        [schema]
    );

    const handleEditorMount = useCallback((editor: StandaloneCodeEditorType) => {
        editor.focus();
    }, []);

    const handleEditorChange = useCallback((value: string | undefined) => {
        setSample(value ?? '');
        setErrorMessage(undefined);
    }, []);

    const handleGenerateClick = useCallback(async () => {
        setGenerating(true);
        setErrorMessage(undefined);

        try {
            const newSchema = await generateSchemaFromSample(sample);

            onGenerate(newSchema);

            setSample('');
        } catch {
            setErrorMessage(GENERATION_ERROR_MESSAGE);
        } finally {
            setGenerating(false);
        }
    }, [onGenerate, sample]);

    return (
        <div className="flex h-full flex-col gap-3">
            <p className="text-sm text-muted-foreground">
                Paste a sample JSON payload below. Its structure is used to infer the schema.
            </p>

            {schemaHasProperties && <Note content={OVERWRITE_WARNING_MESSAGE} icon={<TriangleAlertIcon />} />}

            <div className="min-h-96 flex-1 overflow-hidden rounded-md border border-border/50">
                <Suspense fallback={<MonacoEditorLoader />}>
                    <MonacoEditor
                        className="size-full"
                        defaultLanguage="json"
                        onChange={handleEditorChange}
                        onMount={handleEditorMount}
                        value={sample}
                    />
                </Suspense>
            </div>

            {errorMessage && (
                <p className="text-sm text-destructive" role="alert">
                    {errorMessage}
                </p>
            )}

            <div className="flex justify-end">
                <Button
                    disabled={!sampleIsValidJson || generating}
                    label={generating ? 'Generating...' : 'Generate'}
                    onClick={handleGenerateClick}
                />
            </div>
        </div>
    );
};

export default PropertyJsonSchemaBuilderSampleDataTab;
