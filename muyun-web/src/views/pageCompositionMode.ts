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
  hidden?: boolean;
  actionCode: string;
  anchor: PageCompositionActionAnchor;
}

/** The action's scope is shared by every palette and preview drop target. */
export interface PageCompositionActionCandidate {
  category?: string;
  executorType?: string;
  actionCode: string;
  actionLevel?: 'LIST' | 'RECORD' | 'BATCH' | 'ANY' | 'DEFAULT';
  formSupported?: boolean;
  bindingPending?: boolean;
}

export function canPlaceActionInAnchor(
  action: PageCompositionActionCandidate | undefined,
  anchor: PageCompositionActionAnchor,
) {
  if (!action) return false;
  if (action.category === 'CUSTOM' && action.bindingPending) return true;
  if (
    !pageActionIntent(action.actionCode, anchor) &&
    !(
      (anchor !== 'form' || action.formSupported === true) &&
      action.category === 'CUSTOM' &&
      (!action.executorType || action.executorType === 'SERVICE')
    )
  )
    return false;
  if (anchor === 'page') return action.actionLevel === 'LIST' || action.actionLevel === 'ANY';
  if (anchor === 'detail') return action.actionLevel === 'RECORD' || action.actionLevel === 'ANY';
  return (
    action.actionLevel === (action.actionCode === 'create' ? 'LIST' : 'RECORD') ||
    action.actionLevel === 'ANY' ||
    (action.category === 'CUSTOM' && action.formSupported === true)
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
      .filter(
        (action) => !!pageActionIntent(action.actionCode, anchor) && canPlaceActionInAnchor(action, anchor),
      )
      .map((action) => ({ actionCode: action.actionCode, anchor })),
  );
}

/** A single button can dispatch different module operations according to the business context. */
export function actionButtonKey(entry: PageCompositionActionPlacement): string {
  if (entry.anchor === 'form' && ['create', 'update'].includes(entry.actionCode)) return 'save';
  if (entry.anchor === 'detail' && ['enable', 'disable'].includes(entry.actionCode)) return 'status';
  return entry.actionCode;
}

export function actionButtons(entries: PageCompositionActionPlacement[]) {
  const seen = new Set<string>();
  return entries.filter((entry) => {
    const key = `${entry.anchor}:${actionButtonKey(entry)}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export function actionButtonMembers(
  entries: PageCompositionActionPlacement[],
  entry: PageCompositionActionPlacement,
) {
  return entries.filter(
    (candidate) => candidate.anchor === entry.anchor && actionButtonKey(candidate) === actionButtonKey(entry),
  );
}

/** Missing entries in an existing managed draft mean hidden, never silently restore them. */
export function withStandardActionEntries(
  entries: PageCompositionActionPlacement[],
  actions: PageCompositionActionCandidate[],
) {
  return [
    ...entries,
    ...defaultPageActionEntries(actions)
      .filter(
        (candidate) =>
          !entries.some(
            (entry) => entry.anchor === candidate.anchor && entry.actionCode === candidate.actionCode,
          ),
      )
      .map((entry) => ({ ...entry, hidden: true })),
  ];
}
