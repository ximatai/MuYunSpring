import { pageActionIntent } from '@muyun/web-core';
import type { ManagementUiTree } from './pageCompositionDraftState';

export type CompositionMode = 'TREE_CARD' | 'LIST_CARD' | 'MICRO_LIST_CARD';
/** Issued by the platform template catalogue, shared by the editor's tree and field palette. */
export interface CompositionSkeleton {
  mode: CompositionMode;
  title: string;
  navigationTitle: string;
  fieldGroupTitle: string;
  columns: boolean;
  maxIdentityFields: number;
}
export interface ExplorerComposition {
  titleField: string;
  secondaryField?: string;
}
export type PageCompositionActionAnchor = 'page' | 'detail' | 'form';
export interface PageCompositionActionPlacement {
  title?: string;
  actionCode: string;
  anchor: PageCompositionActionAnchor;
}

/** The action's scope is shared by every palette and preview drop target. */
export interface PageCompositionActionCandidate {
  actionCode: string;
  actionLevel?: 'LIST' | 'RECORD' | 'BATCH' | 'ANY' | 'DEFAULT';
}

export function canPlaceActionInAnchor(
  action: PageCompositionActionCandidate | undefined,
  anchor: PageCompositionActionAnchor,
) {
  if (!action || !pageActionIntent(action.actionCode, anchor)) return false;
  if (anchor === 'page') return action.actionLevel === 'LIST' || action.actionLevel === 'ANY';
  if (anchor === 'detail') return action.actionLevel === 'RECORD' || action.actionLevel === 'ANY';
  return (
    action.actionLevel === 'ANY' ||
    action.actionLevel === (action.actionCode === 'create' ? 'LIST' : 'RECORD')
  );
}

export function modeAwareTree(
  tree: ManagementUiTree,
  skeleton: CompositionSkeleton,
  explorer: ExplorerComposition,
  quickSearchFields: string[],
  actions: PageCompositionActionPlacement[] = [],
) {
  return {
    ...tree,
    templateVersion: 4,
    mode: skeleton.mode,
    quickSearchFields,
    actions,
    nodes: tree.nodes.map((node) =>
      node.slot !== 'list'
        ? node
        : skeleton.columns
          ? { ...node, title: skeleton.navigationTitle }
          : { slot: 'explorer', title: skeleton.navigationTitle, fields: [], ...explorer },
    ),
  };
}

export function defaultPageActionEntries(
  actions: PageCompositionActionCandidate[],
): PageCompositionActionPlacement[] {
  return (['page', 'detail', 'form'] as const).flatMap((anchor) =>
    actions
      .filter((action) => canPlaceActionInAnchor(action, anchor))
      .map((action) => ({ actionCode: action.actionCode, anchor })),
  );
}
