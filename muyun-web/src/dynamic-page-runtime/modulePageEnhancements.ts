import type { Component } from 'vue';
import type { PageDescriptor, PageLayoutMode, RouteQueryValue } from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';
import type { RecordActionItem, RecordQueryListColumn, QueryListRecord } from '@muyun/platform-components';
import type { DrawerTitleAction } from '@muyun/platform-components';

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
  /**
   * Optional frontend-owned activation hook for a descriptor page. It may load
   * auxiliary, already-authorized platform facts used by the enhancement, but
   * never receives a backend component name or executable descriptor payload.
   */
  activate?(context: ModulePageEnhancementActivationContext): void | (() => void);
  list?: ModuleListEnhancement;
  detail?: ModuleDetailEnhancement;
  /**
   * The presentation selected when a user invokes the standard record-view intent.
   *
   * Both the platform "查看" action and a list double-click enter the same record
   * view flow. Applications may replace only the drawer body, while the runtime
   * retains that flow's action checks, shell and lifecycle.
   */
  recordView?: ModulePageRecordView;
  workspaceViews?: ModulePageWorkspaceView<ModulePageWorkspaceViewInput>[];
}

export interface ModulePageEnhancementTarget {
  moduleAlias: string;
  /** Omit to apply to every list view of the module. */
  viewCode?: string;
}

export interface ModulePageEnhancementActivationContext {
  module: ModuleContext<QueryListRecord>;
}

export interface ModuleListEnhancement {
  actions?: ModulePageActionContribution[];
  columns?: ModulePageColumnContribution[];
  /** Replaces the visual renderer of a descriptor-owned column without adding a duplicate column. */
  cellComponents?: ModulePageCellComponentContribution[];
  /** Width of the list's fixed operation column; defaults to the platform compact width. */
  actionColumnWidth?: string | number;
  rowActions?: ModulePageRecordActionContribution[];
  batchActions?: ModulePageBatchActionContribution[];
}

/** Actions appended to the standard record-detail operation area in view mode. */
export interface ModuleDetailEnhancement {
  actions?: ModulePageRecordActionContribution[];
  sections?: ModulePageDetailSection[];
}

/** Application-owned presentation of the platform's standard record-view intent. */
export interface ModulePageRecordView {
  /**
   * Replaces the descriptor-generated detail body while retaining the platform
   * view drawer, its list entry points, and its lifecycle.
   *
   * A custom drawer body is intentionally read-only from the platform's point
   * of view: generic edit and enable/disable controls are withheld so that the
   * business module owns every operation it exposes in the view.
   */
  drawer: ModulePageDetailDrawer;
  /**
   * Optional record-level business grant required in addition to the standard
   * view entry point. This protects business drawer data without turning its
   * custom endpoints into a second "查看" UI action.
   */
  authorizationActionCode?: string;
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

/** A business-owned action rendered by the platform in a semantic drawer region. */
export type ModulePageDrawerAction = DrawerTitleAction;

/** Matches the Workbench input boundary; `parse` remains responsible for route-value validation. */
export type ModulePageWorkspaceViewInput = object;

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
  /** Replaces contextual actions beside the drawer title; actions are cleared with the drawer. */
  setTitleActions(actions: ModulePageDrawerAction[]): void;
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
  /** Opens an existing, platform-owned page descriptor through the Workbench. */
  openPage(descriptor: PageDescriptor): void;
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
  workspaceViews(): readonly ModulePageWorkspaceView<ModulePageWorkspaceViewInput>[];
}

export function createModulePageEnhancementRegistry(
  enhancements: readonly ModulePageEnhancement[],
): ModulePageEnhancementRegistry {
  const enhancementsByTarget = new Map<string, ModulePageEnhancement[]>();
  const workspaceViews: ModulePageWorkspaceView<ModulePageWorkspaceViewInput>[] = [];
  const ids = new Set<string>();
  const workspaceViewTypes = new Set<string>();
  for (const enhancement of enhancements) {
    if (ids.has(enhancement.id)) {
      throw new Error(`重复的模块页面增强：${enhancement.id}`);
    }
    ids.add(enhancement.id);
    assertUniqueContributionKeys(enhancement);
    for (const view of enhancement.workspaceViews ?? []) {
      if (view.moduleAlias !== enhancement.target.moduleAlias) {
        throw new Error(`模块页面增强 ${enhancement.id} 的工作视图模块不一致：${view.type}`);
      }
      if (workspaceViewTypes.has(view.type)) {
        throw new Error(`重复的模块页面工作视图类型：${view.type}`);
      }
      workspaceViewTypes.add(view.type);
      workspaceViews.push(view);
    }
    const key = targetKey(enhancement.target.moduleAlias, enhancement.target.viewCode);
    const targetEnhancements = enhancementsByTarget.get(key) ?? [];
    targetEnhancements.push(enhancement);
    enhancementsByTarget.set(key, targetEnhancements);
  }
  // Validate target composition at registration time instead of leaving a
  // conflicting contribution to fail only when a user opens that page.
  for (const targetEnhancements of enhancementsByTarget.values()) {
    const target = targetEnhancements[0].target;
    composeModulePageEnhancements(targetEnhancements, target);
    if (target.viewCode) {
      const fallback = enhancementsByTarget.get(targetKey(target.moduleAlias));
      if (fallback) {
        composeModulePageEnhancements([...fallback, ...targetEnhancements], target);
      }
    }
  }
  return {
    resolve(moduleAlias, viewCode) {
      const fallback = enhancementsByTarget.get(targetKey(moduleAlias));
      const specific = viewCode ? enhancementsByTarget.get(targetKey(moduleAlias, viewCode)) : undefined;
      if (!fallback && !specific) return undefined;
      return composeModulePageEnhancements([...(fallback ?? []), ...(specific ?? [])], {
        moduleAlias,
        viewCode: specific ? viewCode : undefined,
      });
    },
    workspaceViews() {
      return workspaceViews;
    },
  };
}

const enhancementContributionsByOwner = new Map<string, readonly ModulePageEnhancement[]>();
const legacyApplicationOwner = 'legacy-application';
let currentRegistry = createModulePageEnhancementRegistry([]);

/**
 * Replaces one owner's contribution set while retaining every other owner.
 *
 * This is the composition API for first-party defaults and consuming
 * applications. Multiple owners may contribute to the same module target;
 * their independent regions are merged and conflicting regions fail fast.
 */
export function configureModulePageEnhancementContributions(
  owner: string,
  enhancements: readonly ModulePageEnhancement[],
) {
  const normalizedOwner = owner.trim();
  if (!normalizedOwner) throw new Error('模块页面增强贡献 owner 不能为空');
  enhancementContributionsByOwner.set(normalizedOwner, enhancements);
  currentRegistry = createModulePageEnhancementRegistry([...enhancementContributionsByOwner.values()].flat());
}

/**
 * Legacy application composition API. It replaces only the legacy
 * application's contribution set; first-party and named-owner defaults remain
 * registered. New composition roots should use
 * configureModulePageEnhancementContributions(owner, enhancements).
 */
export function configureModulePageEnhancements(enhancements: readonly ModulePageEnhancement[]) {
  configureModulePageEnhancementContributions(legacyApplicationOwner, enhancements);
}

export function resolveModulePageEnhancement(moduleAlias: string, viewCode?: string) {
  return currentRegistry.resolve(moduleAlias, viewCode);
}

export function modulePageWorkspaceViews(): readonly ModulePageWorkspaceView<ModulePageWorkspaceViewInput>[] {
  return currentRegistry.workspaceViews();
}

function targetKey(moduleAlias: string, viewCode?: string) {
  return `${moduleAlias}#${viewCode ?? '*'}`;
}

function composeModulePageEnhancements(
  enhancements: readonly ModulePageEnhancement[],
  target: ModulePageEnhancementTarget,
): ModulePageEnhancement {
  if (enhancements.length === 1) return enhancements[0];
  const actionColumnWidths = enhancements
    .map((enhancement) => enhancement.list?.actionColumnWidth)
    .filter((width): width is string | number => width !== undefined);
  if (actionColumnWidths.length > 1) {
    throw new Error(
      `模块页面增强目标 ${targetKey(target.moduleAlias, target.viewCode)} 重复声明列表操作列宽`,
    );
  }
  const recordViews = enhancements
    .map((enhancement) => enhancement.recordView)
    .filter((recordView): recordView is ModulePageRecordView => recordView !== undefined);
  if (recordViews.length > 1) {
    throw new Error(
      `模块页面增强目标 ${targetKey(target.moduleAlias, target.viewCode)} 重复声明业务查看呈现`,
    );
  }
  const list = composeListEnhancement(enhancements, actionColumnWidths[0]);
  const detail = composeDetailEnhancement(enhancements);
  const composed: ModulePageEnhancement = {
    id: enhancements.map((enhancement) => enhancement.id).join(', '),
    target,
    ...(list ? { list } : {}),
    ...(detail ? { detail } : {}),
    ...(recordViews[0] ? { recordView: recordViews[0] } : {}),
    workspaceViews: enhancements.flatMap((enhancement) => enhancement.workspaceViews ?? []),
  };
  const activations = enhancements
    .map((enhancement) => enhancement.activate)
    .filter((activate): activate is NonNullable<ModulePageEnhancement['activate']> => activate !== undefined);
  if (activations.length > 0) {
    composed.activate = (context) => {
      const cleanups = activations
        .map((activate) => activate(context))
        .filter((cleanup): cleanup is () => void => typeof cleanup === 'function');
      return () => cleanups.reverse().forEach((cleanup) => cleanup());
    };
  }
  assertUniqueContributionKeys(composed);
  return composed;
}

function composeListEnhancement(
  enhancements: readonly ModulePageEnhancement[],
  actionColumnWidth: string | number | undefined,
): ModuleListEnhancement | undefined {
  const list = {
    actions: enhancements.flatMap((enhancement) => enhancement.list?.actions ?? []),
    columns: enhancements.flatMap((enhancement) => enhancement.list?.columns ?? []),
    cellComponents: enhancements.flatMap((enhancement) => enhancement.list?.cellComponents ?? []),
    rowActions: enhancements.flatMap((enhancement) => enhancement.list?.rowActions ?? []),
    batchActions: enhancements.flatMap((enhancement) => enhancement.list?.batchActions ?? []),
    ...(actionColumnWidth === undefined ? {} : { actionColumnWidth }),
  };
  return Object.values(list).some((value) => (Array.isArray(value) ? value.length > 0 : value !== undefined))
    ? list
    : undefined;
}

function composeDetailEnhancement(
  enhancements: readonly ModulePageEnhancement[],
): ModuleDetailEnhancement | undefined {
  const detail = {
    actions: enhancements.flatMap((enhancement) => enhancement.detail?.actions ?? []),
    sections: enhancements.flatMap((enhancement) => enhancement.detail?.sections ?? []),
  };
  return detail.actions.length > 0 || detail.sections.length > 0 ? detail : undefined;
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
    throw new Error(`模块页面增强 ${enhancement.id} 在同一页面区域存在重复的贡献 key`);
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
