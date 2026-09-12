/**
 * Which surface a knowledge base list is being read for. The data table twin of this type carries the same
 * reasoning; see `@/shared/components/data-tables/types`. The embedded arm carries nothing beyond its tag: every
 * knowledge base in an environment is visible to every account, and the accounts are separated inside a knowledge
 * base by the chunk owner rather than by which knowledge bases they can see.
 */
export type KnowledgeBaseScopeType = {type: 'WORKSPACE'; workspaceId: number} | {type: 'EMBEDDED'};
