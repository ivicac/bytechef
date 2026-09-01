import {
    KnowledgeBaseTagsEntry,
    Tag,
    TagInput,
    UpdateKnowledgeBaseTagsInput,
    useUpdateKnowledgeBaseTagsMutation,
} from '@/shared/middleware/graphql';
import {QueryKey, useQueryClient} from '@tanstack/react-query';
import {useMemo} from 'react';

interface UpdateKnowledgeBaseTagsVarsI {
    input: UpdateKnowledgeBaseTagsInput;
}

type TagsByKnowledgeBaseDataType = {knowledgeBaseTagsByKnowledgeBase: KnowledgeBaseTagsEntry[]} | undefined;

interface UseKnowledgeBaseListItemTagListProps {
    knowledgeBaseId: string;
    remainingTags?: Tag[];
    tags: Tag[];
}

export default function useKnowledgeBaseListItemTagList({
    knowledgeBaseId,
    remainingTags,
    tags,
}: UseKnowledgeBaseListItemTagListProps) {
    const queryClient = useQueryClient();

    // The tags-by-knowledge-base query is scoped to a workspace, so its cache key carries the workspace id as
    // variables. These reads and writes therefore match by key PREFIX -- an exact-key read would silently miss every
    // cached entry and turn the optimistic update into a no-op.
    const updateTagsMutation = useUpdateKnowledgeBaseTagsMutation({
        onError: (_err, _vars, ctx) => {
            for (const [queryKey, data] of ctx?.previous ?? []) {
                queryClient.setQueryData(queryKey, data);
            }
        },
        onMutate: async (variables: UpdateKnowledgeBaseTagsVarsI) => {
            await queryClient.cancelQueries({queryKey: ['knowledgeBaseTagsByKnowledgeBase']});

            const previous = queryClient.getQueriesData<TagsByKnowledgeBaseDataType>({
                queryKey: ['knowledgeBaseTagsByKnowledgeBase'],
            }) as [QueryKey, TagsByKnowledgeBaseDataType][];

            queryClient.setQueriesData<TagsByKnowledgeBaseDataType>(
                {queryKey: ['knowledgeBaseTagsByKnowledgeBase']},
                (data) => {
                    if (!data?.knowledgeBaseTagsByKnowledgeBase) return data;

                    const withTempIds = (variables.input.tags ?? []).map((tag: TagInput) => ({
                        ...tag,
                        id: String(tag.id ?? -Math.floor(Date.now() + Math.random() * 1000)),
                    }));

                    const updated = data.knowledgeBaseTagsByKnowledgeBase.map((entry) =>
                        entry.knowledgeBaseId === knowledgeBaseId ? {...entry, tags: withTempIds} : entry
                    );

                    const hasEntry = data.knowledgeBaseTagsByKnowledgeBase.some(
                        (entry) => entry.knowledgeBaseId === knowledgeBaseId
                    );

                    return hasEntry
                        ? {...data, knowledgeBaseTagsByKnowledgeBase: updated}
                        : {
                              ...data,
                              knowledgeBaseTagsByKnowledgeBase: [
                                  ...data.knowledgeBaseTagsByKnowledgeBase,
                                  {knowledgeBaseId, tags: withTempIds},
                              ],
                          };
                }
            );

            return {previous};
        },
        onSettled: () => {
            queryClient.invalidateQueries({queryKey: ['knowledgeBaseTags']});
            queryClient.invalidateQueries({queryKey: ['knowledgeBaseTagsByKnowledgeBase']});
            queryClient.invalidateQueries({queryKey: ['knowledgeBases']});
        },
    });

    const convertedTags = useMemo(() => tags.map((tag) => ({...tag, id: Number(tag.id)})), [tags]);

    const convertedRemainingTags = useMemo(
        () => remainingTags?.map((tag) => ({...tag, id: Number(tag.id)})),
        [remainingTags]
    );

    return {
        convertedRemainingTags,
        convertedTags,
        updateTagsMutation,
    };
}
