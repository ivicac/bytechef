import {SchemaRecordType} from '@/components/JsonSchemaBuilder/utils/types';
import {getCookie} from '@/shared/util/cookie-utils';

/**
 * Derives a JSON schema from a sample JSON payload via the platform schema generator.
 *
 * Lives in its own module so callers can be tested without stubbing global fetch.
 */
export const generateSchemaFromSample = async (sampleJson: string): Promise<SchemaRecordType> => {
    const response = await fetch('/api/platform/internal/generate-schema', {
        body: sampleJson,
        headers: {
            'Content-Type': 'application/json',
            'X-XSRF-TOKEN': getCookie('XSRF-TOKEN') || '',
        },
        method: 'POST',
    });

    if (!response.ok) {
        throw new Error(`Schema generation failed with status ${response.status}`);
    }

    return await response.json();
};
