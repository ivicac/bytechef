import {KnowledgeBaseScopeType} from '@/shared/components/knowledge-bases/types';
import {act, renderHook} from '@testing-library/react';
import {ChangeEvent} from 'react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import useCreateKnowledgeBaseDialog from '../useCreateKnowledgeBaseDialog';

const hoisted = vi.hoisted(() => {
    return {
        embeddedMutate: vi.fn(),
        invalidateQueries: vi.fn(),
        mutate: vi.fn(),
    };
});

vi.mock('@tanstack/react-query', () => ({
    useQueryClient: vi.fn(() => ({
        invalidateQueries: hoisted.invalidateQueries,
    })),
}));

vi.mock('@/shared/middleware/graphql', () => ({
    useCreateEmbeddedKnowledgeBaseMutation: vi.fn((options: {onSuccess: () => void}) => ({
        isPending: false,
        mutate: (variables: unknown) => {
            hoisted.embeddedMutate(variables);
            options.onSuccess();
        },
    })),
    useCreateKnowledgeBaseMutation: vi.fn(() => ({
        isPending: false,
        mutate: hoisted.mutate,
    })),
}));

vi.mock('@/shared/stores/useEnvironmentStore', () => ({
    useEnvironmentStore: vi.fn(() => 0),
}));

const WORKSPACE_SCOPE: KnowledgeBaseScopeType = {type: 'WORKSPACE', workspaceId: 1049};
const EMBEDDED_SCOPE: KnowledgeBaseScopeType = {type: 'EMBEDDED'};

describe('useCreateKnowledgeBaseDialog', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    describe('initial state', () => {
        it('is closed', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            expect(result.current.open).toBe(false);
        });

        it('has empty name', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            expect(result.current.name).toBe('');
        });

        it('has empty description', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            expect(result.current.description).toBe('');
        });

        // Empty rather than a number: the dialog holds no chunking default of its own, so an untouched box is omitted
        // from the mutation and the entity's own default applies. See `chunkingDefaultParity.test.ts`.
        it('has empty minChunkSizeChars', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            expect(result.current.minChunkSizeChars).toBe('');
        });

        it('has empty maxChunkSize', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            expect(result.current.maxChunkSize).toBe('');
        });

        it('has empty overlapSize', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            expect(result.current.overlapSize).toBe('');
        });

        it('has no selected files', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            expect(result.current.selectedFiles).toEqual([]);
        });

        it('is not uploading', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            expect(result.current.uploading).toBe(false);
        });
    });

    describe('setName', () => {
        it('updates name', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.setName('New KB');
            });

            expect(result.current.name).toBe('New KB');
        });
    });

    describe('setDescription', () => {
        it('updates description', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.setDescription('New description');
            });

            expect(result.current.description).toBe('New description');
        });
    });

    describe('setMinChunkSizeChars', () => {
        it('updates minChunkSizeChars', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.setMinChunkSizeChars('5');
            });

            expect(result.current.minChunkSizeChars).toBe('5');
        });
    });

    describe('setMaxChunkSize', () => {
        it('updates maxChunkSize', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.setMaxChunkSize('2048');
            });

            expect(result.current.maxChunkSize).toBe('2048');
        });
    });

    describe('setOverlapSize', () => {
        it('updates overlapSize', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.setOverlapSize('100');
            });

            expect(result.current.overlapSize).toBe('100');
        });
    });

    describe('handleFileChange', () => {
        it('adds files to selected files', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            const mockFile = new File(['content'], 'test.pdf', {type: 'application/pdf'});

            act(() => {
                result.current.handleFileChange({
                    target: {files: [mockFile]},
                } as unknown as ChangeEvent<HTMLInputElement>);
            });

            expect(result.current.selectedFiles).toHaveLength(1);
            expect(result.current.selectedFiles[0].file.name).toBe('test.pdf');
            expect(result.current.selectedFiles[0].status).toBe('pending');
        });
    });

    describe('removeFile', () => {
        it('removes file at index', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            const mockFile1 = new File(['content1'], 'test1.pdf', {type: 'application/pdf'});
            const mockFile2 = new File(['content2'], 'test2.pdf', {type: 'application/pdf'});

            act(() => {
                result.current.handleFileChange({
                    target: {files: [mockFile1, mockFile2]},
                } as unknown as ChangeEvent<HTMLInputElement>);
            });

            act(() => {
                result.current.removeFile(0);
            });

            expect(result.current.selectedFiles).toHaveLength(1);
            expect(result.current.selectedFiles[0].file.name).toBe('test2.pdf');
        });
    });

    describe('canSubmit', () => {
        it('is false when name is empty', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            expect(result.current.canSubmit).toBe(false);
        });

        it('is true when name is not empty', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.setName('Test KB');
            });

            expect(result.current.canSubmit).toBe(true);
        });

        it('is false when name is only whitespace', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.setName('   ');
            });

            expect(result.current.canSubmit).toBe(false);
        });
    });

    describe('handleSubmit', () => {
        it('calls mutation with form data', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.setName('Test KB');
                result.current.setDescription('Test description');
                result.current.setMinChunkSizeChars('5');
                result.current.setMaxChunkSize('2048');
                result.current.setOverlapSize('100');
            });

            act(() => {
                result.current.handleSubmit();
            });

            expect(hoisted.mutate).toHaveBeenCalledWith({
                environmentId: '0',
                knowledgeBase: {
                    description: 'Test description',
                    maxChunkSize: 2048,
                    minChunkSizeChars: 5,
                    name: 'Test KB',
                    overlap: 100,
                },
                workspaceId: '1049',
            });
        });

        it('trims whitespace from name', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.setName('  Test KB  ');
            });

            act(() => {
                result.current.handleSubmit();
            });

            expect(hoisted.mutate).toHaveBeenCalledWith(
                expect.objectContaining({
                    knowledgeBase: expect.objectContaining({
                        name: 'Test KB',
                    }),
                })
            );
        });

        it('sets description to undefined when empty', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.setName('Test KB');
                result.current.setDescription('');
            });

            act(() => {
                result.current.handleSubmit();
            });

            expect(hoisted.mutate).toHaveBeenCalledWith(
                expect.objectContaining({
                    knowledgeBase: expect.objectContaining({
                        description: undefined,
                    }),
                })
            );
        });
    });

    // The dialog used to pre-fill these three with numbers of its own -- 1, 1024 and 200 against the entity's 100,
    // 1024 and 200 -- so a knowledge base created from the UI with the minimum untouched got a hundredth of the
    // intended floor while one created through the API got the entity's. Nothing connected a Java field initializer
    // to a `useState` string, so neither side could see it. The fix is that the dialog now names no default at all.
    describe('chunking settings', () => {
        it('omits all three from the workspace mutation when they are untouched', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.setName('docs');
            });

            act(() => {
                result.current.handleSubmit();
            });

            const [variables] = hoisted.mutate.mock.calls[0];

            expect(variables.knowledgeBase.minChunkSizeChars).toBeUndefined();
            expect(variables.knowledgeBase.maxChunkSize).toBeUndefined();
            expect(variables.knowledgeBase.overlap).toBeUndefined();
        });

        it('omits all three from the embedded mutation when they are untouched', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(EMBEDDED_SCOPE));

            act(() => {
                result.current.setName('docs');
            });

            act(() => {
                result.current.handleSubmit();
            });

            const [variables] = hoisted.embeddedMutate.mock.calls[0];

            expect(variables.input.minChunkSizeChars).toBeUndefined();
            expect(variables.input.maxChunkSize).toBeUndefined();
            expect(variables.input.overlap).toBeUndefined();
        });

        // Omission has to be per field, not a blanket "the user touched chunking" flag: one tuned setting must not
        // drag the other two off the entity's defaults and onto whatever the boxes happened to show.
        it('sends only the field that was touched', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.setName('docs');
                result.current.setMinChunkSizeChars('250');
            });

            act(() => {
                result.current.handleSubmit();
            });

            const [variables] = hoisted.mutate.mock.calls[0];

            expect(variables.knowledgeBase.minChunkSizeChars).toBe(250);
            expect(variables.knowledgeBase.maxChunkSize).toBeUndefined();
            expect(variables.knowledgeBase.overlap).toBeUndefined();
        });

        // Clearing a number input to retype it is normal mid-edit, and `parseInt('')` would put a NaN on the wire
        // rather than nothing.
        it('omits a field that was typed into and then cleared, rather than sending NaN', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.setName('docs');
                result.current.setMaxChunkSize('512');
            });

            act(() => {
                result.current.setMaxChunkSize('');
            });

            act(() => {
                result.current.handleSubmit();
            });

            const [variables] = hoisted.mutate.mock.calls[0];

            expect(variables.knowledgeBase.maxChunkSize).toBeUndefined();
        });

        it('clears all three again once the create succeeds', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(EMBEDDED_SCOPE));

            act(() => {
                result.current.setName('docs');
                result.current.setMinChunkSizeChars('250');
                result.current.setMaxChunkSize('512');
                result.current.setOverlapSize('64');
            });

            act(() => {
                result.current.handleSubmit();
            });

            expect(result.current.minChunkSizeChars).toBe('');
            expect(result.current.maxChunkSize).toBe('');
            expect(result.current.overlapSize).toBe('');
        });
    });

    describe('embedded scope', () => {
        it('calls the embedded mutation, and not the workspace one', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(EMBEDDED_SCOPE));

            act(() => {
                result.current.setName('docs');
            });

            act(() => {
                result.current.handleSubmit();
            });

            expect(hoisted.embeddedMutate).toHaveBeenCalledWith({
                input: {
                    description: undefined,
                    environmentId: '0',
                    maxChunkSize: undefined,
                    minChunkSizeChars: undefined,
                    name: 'docs',
                    overlap: undefined,
                },
            });
            expect(hoisted.mutate).not.toHaveBeenCalled();
        });

        // The embedded input dropped these on the way out, so the console's only escape from the default chunking was
        // deleting the knowledge base and re-embedding every document in it.
        it('carries the chunking settings the dialog holds', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(EMBEDDED_SCOPE));

            act(() => {
                result.current.setName('docs');
                result.current.setMaxChunkSize('512');
                result.current.setMinChunkSizeChars('40');
                result.current.setOverlapSize('64');
            });

            act(() => {
                result.current.handleSubmit();
            });

            const [variables] = hoisted.embeddedMutate.mock.calls[0];

            expect(variables.input.maxChunkSize).toBe(512);
            expect(variables.input.minChunkSizeChars).toBe(40);
            expect(variables.input.overlap).toBe(64);
        });
    });

    describe('workspace scope', () => {
        it('calls the workspace mutation, and not the embedded one', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.setName('docs');
            });

            act(() => {
                result.current.handleSubmit();
            });

            expect(hoisted.mutate).toHaveBeenCalled();
            expect(hoisted.embeddedMutate).not.toHaveBeenCalled();
        });
    });

    describe('handleOpenChange', () => {
        it('opens dialog', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.handleOpenChange(true);
            });

            expect(result.current.open).toBe(true);
        });

        it('closes dialog and resets form', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.setOpen(true);
                result.current.setName('Test');
            });

            act(() => {
                result.current.handleOpenChange(false);
            });

            expect(result.current.open).toBe(false);
            expect(result.current.name).toBe('');
        });
    });

    describe('formatFileSize', () => {
        it('formats 0 bytes', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            expect(result.current.formatFileSize(0)).toBe('0 Bytes');
        });

        it('formats kilobytes', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            expect(result.current.formatFileSize(1024)).toBe('1 KB');
        });

        it('formats megabytes', () => {
            const {result} = renderHook(() => useCreateKnowledgeBaseDialog(WORKSPACE_SCOPE));

            expect(result.current.formatFileSize(1048576)).toBe('1 MB');
        });
    });
});
