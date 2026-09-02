/**
 * Which surface a data table list is being read for. The embedded arm carries nothing beyond its tag: every table in
 * an environment is visible to every account, and the accounts are separated inside a table by the row owner rather
 * than by which tables they can see.
 */
export type DataTableScopeType = {type: 'WORKSPACE'; workspaceId: number} | {type: 'EMBEDDED'};
