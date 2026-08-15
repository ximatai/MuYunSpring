import type { Component } from 'vue';
import type { PageLayoutMode, RouteQueryValue } from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';
import type { RecordActionItem, RecordQueryListColumn, QueryListRecord } from '@muyun/platform-components';

/**
 * Frontend-owned, constrained composition for a descriptor-driven module page.
 *
 * This deliberately lives in application source instead of the backend UI DSL:
 * the descriptor remains the source of data and platform facts, while a business
 * application may contribute small Vue capabilities at named platform regions.
 */
export interface ModulePageEnhancement {
  id: string;
  target: ModulePageEnhancementTarget;
  list?: ModuleListEnhancement;
  detail?: ModuleDetailEnhancement;
  workspaceViews?: ModulePageWorkspaceView[];
}

export interface ModulePageEnhancementTarget {
  moduleAlias: string;
  /** Omit to apply to every list view of the module. */
  viewCode?: string;
}

export interface ModuleListEnhancement {
  actions?: ModulePageActionContribution[];
  columns?: ModulePageColumnContribution[];
  /** Replaces the visual renderer of a descriptor-owned column without adding a duplicate column. */
  cellComponents?: ModulePageCellComponentContribution[];
  /** Width of the list's fixed operation column; defaults to the platform compact width. */
  actionColumnWidth?: string | number;
  /**
   * Uses a business action code to authorize the platform's standard “查看”
   * entry points. The platform verifies the selected record before opening the
   * drawer, so the row action and double-click share one authorization path.
   */
  viewActionCode?: string;
  rowActions?: ModulePageRecordActionContribution[];
  batchActions?: ModulePageBatchActionContribution[];
}

/** Actions appended to the standard record-detail operation area in view mode. */
export interface ModuleDetailEnhancement {
  actions?: ModulePageRecordActionContribution[];
  sections?: ModulePageDetailSection[];
  /**
   * Replaces the descriptor-generated detail body while retaining the platform
   * view drawer, its list entry points, and its lifecycle.
   *
   * A custom drawer body is intentionally read-only from the platform's point
   * of view: generic edit and enable/disable controls are withheld so that the
   * business module owns every operation it exposes in the view.
   */
  drawer?: ModulePageDetailDrawer;
}

export interface ModulePageActionContribution extends RecordActionItem {
  key: string;
  /** Authorizes this toolbar action against the selected scoped-list record instead of the page module. */
  authorization?: 'scope-record';
  /** Derives the action state from the current scoped-list selection when the page has one. */
  state?(
    context: ModulePageActionStateContext,
  ): Partial<Pick<RecordActionItem, 'visible' | 'disabled' | 'disabledReason'>> | undefined;
  run(context: ModulePageActionContext): void | Promise<void>;
}

export interface ModulePageRecordActionContribution extends RecordActionItem {
  key: string;
  /** Resolves record-specific visibility or enabled state without letting business code own the table shell. */
  state?(record: QueryListRecord): Partial<Pick<RecordActionItem, 'visible' | 'disabled'>> | undefined;
  run(context: ModulePageRecordActionContext): void | Promise<void>;
}

export interface ModulePageBatchActionContribution extends RecordActionItem {
  key: string;
  run(context: ModulePageBatchActionContext): void | Promise<void>;
}

export interface ModulePageDetailSection {
  key: string;
  title: string;
  component: Component;
}

/** Business content rendered inside the platform-owned standard view drawer. */
export interface ModulePageDetailDrawer {
  /** The component receives only the documented ModulePageDrawerContext prop. */
  component: Component;
  /** Keeps the platform drawer shell while allowing dense business views more room. */
  width?: number | string;
  /**
   * Defaults to true. Set false when the business detail body has its own
   * governed read endpoint and only needs the selected list record as context.
   */
  loadRecord?: boolean;
}

export interface ModulePageColumnContribution extends RecordQueryListColumn {
  key: string;
  /** Insert adjacent to a descriptor column. Omit to append after descriptor columns. */
  before?: string;
  after?: string;
  /** A normal Vue component, constrained to one table cell by the platform table shell. */
  cell: Component;
}

export interface ModulePageCellComponentContribution {
  key: string;
  cell: Component;
}

export interface ModulePageDrawer {
  title: string;
  width?: number | string;
  /** The component receives only the documented ModulePageDrawerContext prop. */
  component: Component;
}

export type ModulePageWorkspaceViewInput = Record<string, RouteQueryValue>;

/** A business-owned view with stable, serializable identity. */
export interface ModulePageWorkspaceView<
  TInput extends ModulePageWorkspaceViewInput = ModulePageWorkspaceViewInput,
> {
  type: string;
  moduleAlias: string;
  /** Optional business route used for restoration; platform generates a stable workspace route by default. */
  route?: string;
  component: Component;
  titleOf(input: TInput): string;
  parse(query: Record<string, RouteQueryValue>): TInput | undefined;
  layout?: PageLayoutMode;
}

export interface ModulePageDrawerContext {
  module: ModuleContext<QueryListRecord>;
  record?: QueryListRecord;
  /** The currently selected scope record for a scoped list workspace, if applicable. */
  scope?: ModulePageScopeContext;
  /** Reloads only the current list query and preserves its query and editor state. */
  refreshList(): void;
  close(): void;
  reload(): void;
}

export interface ModulePageActionContext {
  module: ModuleContext<QueryListRecord>;
  /** The currently selected scope record for a scoped list workspace, if applicable. */
  scope?: ModulePageScopeContext;
  /** Reloads only the current list query and preserves its query and editor state. */
  refreshList(): void;
  openDrawer(drawer: ModulePageDrawer): void;
  openWorkspaceTab<TInput extends ModulePageWorkspaceViewInput>(
    view: ModulePageWorkspaceView<TInput>,
    input: TInput,
  ): void;
  reload(): void;
}

/** Read-only scope state supplied by a descriptor-owned scoped list workspace. */
export interface ModulePageScopeContext {
  moduleAlias: string;
  record?: QueryListRecord;
}

/** Small state-only context used while resolving a toolbar action's visibility or enabled state. */
export interface ModulePageActionStateContext {
  module: ModuleContext<QueryListRecord>;
  scope?: ModulePageScopeContext;
}

export interface ModulePageRecordActionContext extends ModulePageActionContext {
  record: QueryListRecord;
}

export interface ModulePageBatchActionContext extends ModulePageActionContext {
  records: QueryListRecord[];
  clearSelection(): void;
}

export interface ModulePageDetailSectionContext {
  module: ModuleContext<QueryListRecord>;
  record: QueryListRecord;
  /** Reloads only the current list query and preserves its query and editor state. */
  refreshList(): void;
  reload(): void;
}

export interface ModulePageEnhancementRegistry {
  resolve(moduleAlias: string, viewCode?: string): ModulePageEnhancement | undefined;
  workspaceViews(): readonly ModulePageWorkspaceView[];
}

export function createModulePageEnhancementRegistry(
  enhancements: readonly ModulePageEnhancement[],
): ModulePageEnhancementRegistry {
  const byTarget = new Map<string, ModulePageEnhancement>();
  const workspaceViews: ModulePageWorkspaceView[] = [];
  const ids = new Set<string>();
  for (const enhancement of enhancements) {
    if (ids.has(enhancement.id)) {
      throw new Error(`重复的模块页面增强：${enhancement.id}`);
    }
    ids.add(enhancement.id);
    const key = targetKey(enhancement.target.moduleAlias, enhancement.target.viewCode);
    if (byTarget.has(key)) {
      throw new Error(`模块页面增强目标重复：${key}`);
    }
    assertUniqueContributionKeys(enhancement);
    for (const view of enhancement.workspaceViews ?? []) {
      if (view.moduleAlias !== enhancement.target.moduleAlias) {
        throw new Error(`模块页面增强 ${enhancement.id} 的工作视图模块不一致：${view.type}`);
      }
      workspaceViews.push(view);
    }
    byTarget.set(key, enhancement);
  }
  return {
    resolve(moduleAlias, viewCode) {
      return (
        (viewCode ? byTarget.get(targetKey(moduleAlias, viewCode)) : undefined) ??
        byTarget.get(targetKey(moduleAlias))
      );
    },
    workspaceViews() {
      return workspaceViews;
    },
  };
}

let currentRegistry = createModulePageEnhancementRegistry([]);

/** Configure once from the consuming application's composition root. */
export function configureModulePageEnhancements(enhancements: readonly ModulePageEnhancement[]) {
  currentRegistry = createModulePageEnhancementRegistry(enhancements);
}

export function resolveModulePageEnhancement(moduleAlias: string, viewCode?: string) {
  return currentRegistry.resolve(moduleAlias, viewCode);
}

export function modulePageWorkspaceViews() {
  return currentRegistry.workspaceViews();
}

function targetKey(moduleAlias: string, viewCode?: string) {
  return `${moduleAlias}#${viewCode ?? '*'}`;
}

function assertUniqueContributionKeys(enhancement: ModulePageEnhancement) {
  const regions = [
    enhancement.list?.actions ?? [],
    enhancement.list?.columns ?? [],
    enhancement.list?.cellComponents ?? [],
    enhancement.list?.rowActions ?? [],
    enhancement.list?.batchActions ?? [],
    enhancement.detail?.actions ?? [],
    enhancement.detail?.sections ?? [],
  ];
  if (
    regions.some(
      (contributions) =>
        new Set(contributions.map((contribution) => contribution.key)).size !== contributions.length,
    )
  ) {
    throw new Error(`模块页面增强 ${enhancement.id} 在同一列表区域存在重复的贡献 key`);
  }
  assertNoReservedActionKeys(enhancement.id, enhancement.list?.actions ?? [], ['create']);
  assertNoReservedActionKeys(enhancement.id, enhancement.list?.rowActions ?? [], ['view', 'edit', 'delete']);
  assertNoReservedActionKeys(enhancement.id, enhancement.list?.batchActions ?? [], ['create']);
  assertNoReservedActionKeys(enhancement.id, enhancement.detail?.actions ?? [], [
    'create',
    'update',
    'delete',
  ]);
}

function assertNoReservedActionKeys(
  enhancementId: string,
  actions: ReadonlyArray<ModulePageActionContribution | ModulePageRecordActionContribution>,
  reservedKeys: readonly string[],
) {
  const conflict = actions.find((action) => reservedKeys.includes(action.key));
  if (conflict) {
    throw new Error(`模块页面增强 ${enhancementId} 不能覆盖平台标准动作：${conflict.key}`);
  }
}
