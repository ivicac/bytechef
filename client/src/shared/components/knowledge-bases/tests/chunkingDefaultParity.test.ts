import {readFileSync} from 'node:fs';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {describe, expect, it} from 'vitest';

const currentDirectory = dirname(fileURLToPath(import.meta.url));

const repositoryRoot = resolve(currentDirectory, '../../../../../..');

const KNOWLEDGE_BASE_ENTITY_PATH =
    'server/libs/platform/platform-knowledge-base/platform-knowledge-base-api/src/main/java/com/bytechef/platform/knowledgebase/domain/KnowledgeBase.java';

const CREATE_DIALOG_HOOK_PATH = '../components/hooks/useCreateKnowledgeBaseDialog.ts';

/**
 * The three chunking settings, paired with the dialog state that offers each one. The entity field and the state name
 * differ for the overlap, which is `overlap` on the wire and `overlapSize` in the dialog.
 */
const CHUNKING_FIELDS = [
    {entityField: 'maxChunkSize', stateName: 'maxChunkSize'},
    {entityField: 'minChunkSizeChars', stateName: 'minChunkSizeChars'},
    {entityField: 'overlap', stateName: 'overlapSize'},
];

const readEntitySource = (): string => {
    const path = resolve(repositoryRoot, KNOWLEDGE_BASE_ENTITY_PATH);

    try {
        return readFileSync(path, 'utf8');
    } catch {
        throw new Error(
            `Could not read the KnowledgeBase entity at ${KNOWLEDGE_BASE_ENTITY_PATH}. It is the single source of the ` +
                "create dialog's chunking defaults, so if it moved, update the path here rather than deleting this test."
        );
    }
};

const readEntityDefault = (source: string, entityField: string): number => {
    const match = source.match(new RegExp(`private int ${entityField} = (\\d+);`));

    if (match === null) {
        throw new Error(
            `KnowledgeBase no longer declares a default for \`${entityField}\`. The create dialog sends nothing for ` +
                'an untouched chunking box precisely so the entity governs, so without an initializer here a ' +
                'knowledge base created from the UI silently gets 0.'
        );
    }

    return Number(match[1]);
};

/**
 * The two sides of the defect this test exists to keep closed.
 *
 * `KnowledgeBase` declared `minChunkSizeChars = 100` while the create dialog pre-filled `useState('1')`, so a
 * knowledge base created from the UI with the field untouched got a hundredth of the intended floor and one created
 * through the API got 100. Chunk size decides what a search can return, so it was not cosmetic -- and it was
 * invisible from either side alone, because both were internally consistent and nothing named the other.
 *
 * The fix removed the second copy rather than correcting it: the dialog now sends nothing for an untouched box, and
 * the entity's own initializer applies. That leaves exactly two ways for the agreement to break, and this test fails
 * on both -- the client restating a default again, or the entity ceasing to carry one.
 */
describe('knowledge base chunking defaults', () => {
    const entitySource = readEntitySource();
    const hookSource = readFileSync(resolve(currentDirectory, CREATE_DIALOG_HOOK_PATH), 'utf8');

    it.each(CHUNKING_FIELDS)('KnowledgeBase carries the default for $entityField', ({entityField}) => {
        expect(readEntityDefault(entitySource, entityField)).toBeGreaterThan(0);
    });

    it.each(CHUNKING_FIELDS)('the create dialog states no default for $stateName', ({stateName}) => {
        const setterName = `set${stateName.charAt(0).toUpperCase()}${stateName.slice(1)}`;

        const match = hookSource.match(new RegExp(`const \\[${stateName}, ${setterName}\\] = useState\\(([^)]*)\\);`));

        if (match === null) {
            throw new Error(
                `useCreateKnowledgeBaseDialog no longer declares \`${stateName}\` as a useState. If the dialog ` +
                    'changed shape, re-pin this test on the new one rather than dropping it.'
            );
        }

        expect(match[1]).toBe("''");
    });
});
