import { inject, provide, shallowRef, type InjectionKey } from 'vue';
import {
  createUuid,
  OperationUsageError,
  parseEmptyAssistantCapabilityInput,
  emptyAssistantCapabilityInputSchema,
  type AssistantCapability,
  type AssistantInvocationToken,
  type AssistantOperationProposal,
  type HttpClient,
  type UserPreferenceStore,
} from '@muyun/web-core';
import { waitForConfigurationEditor } from '../../views/configurationEditorNavigation';
import { createModuleMenuReceiptStore, type MenuReceiptPointerStorage } from './moduleMenuReceiptStore';
import { createModuleMenuClient, menuSchemeScopeLabel } from './moduleMenuClient';
import {
  createModuleMenuSession,
  type ModuleMenuSession,
  type ModuleMenuRevision,
} from './moduleMenuSession';
import type { MenuTreeNode } from '@muyun/web-contracts';

export function createModuleMenuWorkspace(
  http: HttpClient,
  identity: () => string,
  enabled: () => boolean,
  openEditor?: (alias: string) => void,
  onFocus?: () => void,
  refreshMenus?: () => Promise<MenuTreeNode[]>,
  preferences?: UserPreferenceStore,
  pointerStorage?: MenuReceiptPointerStorage,
) {
  const client = createModuleMenuClient(http);
  let owner = identity();
  let permissionOwner: string | undefined;
  let accessGeneration = 0;
  let selectionVersion = '';
  let schemeKeys = new Map<string, string>();
  let positionKeys = new Map<string, string>();
  const allowed = shallowRef(false);
  const active = shallowRef<ModuleMenuSession>();
  const visible = shallowRef<ModuleMenuSession>();
  const sessions = new Map<string, ModuleMenuSession>();
  function dispose() {
    sessions.forEach((value) => value.dispose());
    sessions.clear();
    active.value = undefined;
    visible.value = undefined;
    allowed.value = false;
    permissionOwner = undefined;
    accessGeneration++;
    selectionVersion = '';
    schemeKeys.clear();
    positionKeys.clear();
  }
  function resetScope() {
    if (owner !== identity()) {
      dispose();
      owner = identity();
    }
    if (enabled() && permissionOwner !== owner) {
      permissionOwner = owner;
      const scope = owner;
      const generation = accessGeneration;
      void client
        .context('platform.menu')
        .then((result) => {
          if (identity() === scope && generation === accessGeneration)
            allowed.value = result.actions.some((item) => item.actionCode === 'create' && item.authorized);
        })
        .catch(() => {
          // A later interaction may retry; do not lock this identity out after a transient read failure.
          if (identity() === scope && generation === accessGeneration) permissionOwner = undefined;
        });
    }
  }
  function session(alias: string) {
    resetScope();
    if (!sessions.has(alias)) {
      const scope = owner;
      const value = createModuleMenuSession(
        client,
        alias,
        {
          valid: () => identity() === scope && sessions.get(alias) === value,
          active: () => active.value === value && visible.value === value,
        },
        createModuleMenuReceiptStore(
          scope,
          alias,
          () => identity() === scope && sessions.get(alias) === value,
          preferences,
          pointerStorage,
        ),
        refreshMenus,
      );
      sessions.set(alias, value);
    }
    return sessions.get(alias)!;
  }
  function clearFocus() {
    active.value?.invalidate();
    active.value = undefined;
  }
  function focus(value: ModuleMenuSession) {
    resetScope();
    if (sessions.get(value.moduleAlias) !== value) return;
    onFocus?.();
    if (active.value !== value) {
      clearFocus();
      active.value = value;
    }
  }
  function facts() {
    const value = active.value;
    if (!value) return;
    const scheme = value.options.value.find((item) => item.id === value.schemeId.value);
    const directory = value.directories.value.find((item) => item.menu.id === value.parentId.value);
    return {
      moduleAlias: value.moduleAlias,
      title: value.title.value,
      scheme: scheme?.title,
      scopeType: scheme?.scopeType,
      audience: menuSchemeScopeLabel(scheme),
      position: directory?.path ?? '顶层',
      openMode: value.openMode.value,
      saved: Boolean(value.saved.value),
      visibility: value.visibilityChecked.value ? Boolean(value.visibleMenu.value) : 'NOT_CHECKED',
      resultUnknown: value.resultUnknown.value,
      ready: value.ready.value,
      entryIssue: value.entryIssue.value,
    };
  }
  return {
    session,
    focus,
    clearFocus,
    dispose,
    showEditor(value: ModuleMenuSession) {
      resetScope();
      if (sessions.get(value.moduleAlias) === value) visible.value = value;
    },
    hideEditor(value: ModuleMenuSession) {
      if (visible.value === value) visible.value = undefined;
    },
    editor() {
      resetScope();
      const value = active.value;
      return (
        value && {
          moduleAlias: value.moduleAlias,
          title: '菜单入口',
          hasUnsavedChanges: value.ready.value && !value.saved.value,
          visible: visible.value === value,
          open: openEditor ? () => openEditor(value.moduleAlias) : undefined,
        }
      );
    },
    current() {
      resetScope();
      return {
        revision: JSON.stringify([
          owner,
          allowed.value,
          active.value?.moduleAlias,
          active.value?.revision.value,
          active.value?.visibilityChecked.value,
          active.value?.visibleMenu.value?.id,
          visible.value?.moduleAlias,
        ]),
        facts: { menuConfiguration: facts() },
      };
    },
    describe: facts,
    capabilities(
      settle?: (signal?: AbortSignal) => Promise<void | AssistantInvocationToken>,
    ): AssistantCapability[] {
      resetScope();
      if (!enabled() || !allowed.value) return [];
      const selected = active.value;
      const open: AssistantCapability[] =
        openEditor && settle
          ? [
              {
                effect: 'page',
                descriptor: {
                  code: 'configuration.open-menu-editor',
                  description:
                    'Open the standard Add to menu editor for a discovered existing module. Reuses its shared candidate; no construction plan or module binding is required. It can create a missing business entry after human review. Does not grant permissions. Inspect actual authorized menu schemes rather than repeatedly searching for an entry not yet created. A functional description need not be an exact scheme title; clarify the intended audience when scope is unresolved.',
                  inputSchema: {
                    type: 'object',
                    additionalProperties: false,
                    required: ['moduleAlias'],
                    properties: {
                      moduleAlias: {
                        type: 'string',
                        maxLength: 128,
                        description: 'Exact module alias from observed facts.',
                      },
                    },
                  },
                },
                parseInput(input: unknown) {
                  const value = input as { moduleAlias?: unknown };
                  if (
                    !value ||
                    typeof value !== 'object' ||
                    Array.isArray(value) ||
                    Object.keys(value).some((key) => key !== 'moduleAlias') ||
                    typeof value.moduleAlias !== 'string' ||
                    value.moduleAlias.length > 128 ||
                    !/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(value.moduleAlias)
                  )
                    throw new OperationUsageError('请提供已发现的正式模块标识');
                  return value.moduleAlias;
                },
                async execute(alias: string, context) {
                  const value = session(alias);
                  if (!value.ready.value) await value.load();
                  if (!value.ready.value)
                    throw new OperationUsageError(
                      value.error.value || value.entryIssue.value || '入口编辑尚未就绪，请重试加载',
                    );
                  context.applyEffect(
                    () => {
                      focus(value);
                      openEditor(alias);
                    },
                    async () => {
                      const signal = context.cancellationSignal ?? context.signal;
                      await waitForConfigurationEditor(
                        () => visible.value === value && !value.loading.value,
                        signal,
                      );
                      return settle(signal);
                    },
                  );
                  return { opened: true, saved: Boolean(value.saved.value), moduleAlias: alias };
                },
                present: () => ({
                  title: '菜单入口编辑已打开',
                  lines: ['已打开共享候选，尚未添加新入口。请按实际菜单方案和位置准备并审阅。'],
                }),
              },
            ]
          : [];
      if (!selected?.ready.value || !selected.authorized.value) return open;
      // Only issued opaque keys can select records; changing candidate or identity invalidates them.
      const candidateRevision = selected.revision.value;
      const catalogVersion = JSON.stringify([owner, selected.moduleAlias, candidateRevision]);
      if (selectionVersion !== catalogVersion) {
        selectionVersion = catalogVersion;
        schemeKeys = new Map();
        positionKeys = new Map();
      }
      const keyFor = (keys: Map<string, string>, id: string) => {
        const existing = [...keys].find(([, value]) => value === id)?.[0];
        if (existing) return existing;
        const key = createUuid();
        keys.set(key, id);
        return key;
      };
      const current = () => {
        resetScope();
        if (
          active.value !== selected ||
          visible.value !== selected ||
          selected.revision.value !== candidateRevision ||
          selectionVersion !== catalogVersion
        )
          throw new OperationUsageError('请先打开当前模块的标准入口编辑器并重新读取');
      };
      let proposal: AssistantOperationProposal | undefined;
      const capabilities: AssistantCapability[] = [
        ...open,
        {
          schemaDiscovery: 'eager',
          effect: 'read',
          descriptor: {
            code: 'menu.describe',
            description:
              'Read the current standard menu candidate and paged authorized schemes/positions. Scheme scope is explicit; the default belongs to the current user, which may differ from intended business users. Use returned selection keys. Saved and visible are separate facts. No business permissions are granted.',
            inputSchema: {
              type: 'object',
              additionalProperties: false,
              properties: { offset: { type: 'integer', minimum: 0 } },
            },
          },
          parseInput(input) {
            const value = input as { offset?: unknown };
            if (
              !value ||
              typeof value !== 'object' ||
              Array.isArray(value) ||
              Object.keys(value).some((key) => key !== 'offset') ||
              (value.offset !== undefined &&
                (!Number.isSafeInteger(value.offset) || Number(value.offset) < 0))
            )
              throw new OperationUsageError('目录分页参数无效');
            return Number(value.offset ?? 0);
          },
          async execute(offset: number) {
            current();
            const hasMore =
              selected.options.value.length > offset + 40 || selected.directories.value.length > offset + 40;
            return {
              ...facts(),
              schemes: selected.options.value.slice(offset, offset + 40).map((item) => ({
                selectionKey: keyFor(schemeKeys, item.id!),
                title: item.title ?? item.alias,
                scopeType: item.scopeType,
                audience: menuSchemeScopeLabel(item),
                tenant: item.tenantId,
                organization: item.organizationId,
                selected: item.id === selected.schemeId.value,
              })),
              positions: [
                { selectionKey: keyFor(positionKeys, 'root'), title: '顶层' },
                ...selected.directories.value
                  .slice(offset, offset + 40)
                  .map((item) => ({ selectionKey: keyFor(positionKeys, item.menu.id), title: item.path })),
              ],
              truncated: hasMore,
              ...(hasMore ? { nextOffset: offset + 40 } : {}),
            };
          },
        },
        {
          schemaDiscovery: 'eager',
          effect: 'configuration-draft',
          descriptor: {
            code: 'menu.revise-draft',
            description:
              'Revise the SAME standard menu candidate. Only returned schemeKey/positionKey may select placement; changing scheme resets position to root, then reread its positions. Keep business-user scope distinct from the administrator default. Only edits a draft; human confirmation adds it.',
            inputSchema: {
              type: 'object',
              additionalProperties: false,
              properties: {
                title: { type: 'string', maxLength: 120 },
                openMode: { type: 'string', enum: ['tab', 'window'] },
                schemeKey: { type: 'string' },
                positionKey: { type: 'string' },
              },
              minProperties: 1,
            },
          },
          parseInput(input) {
            const value = input as Record<string, unknown>;
            if (
              !value ||
              typeof value !== 'object' ||
              Array.isArray(value) ||
              !Object.keys(value).length ||
              Object.keys(value).some(
                (key) => !['title', 'openMode', 'schemeKey', 'positionKey'].includes(key),
              ) ||
              (value.title !== undefined &&
                (typeof value.title !== 'string' || [...value.title].length > 120)) ||
              (value.openMode !== undefined && value.openMode !== 'tab' && value.openMode !== 'window')
            )
              throw new OperationUsageError('菜单候选参数无效');
            const patch: ModuleMenuRevision = {
              title: value.title as string | undefined,
              openMode: value.openMode as 'tab' | 'window' | undefined,
            };
            if (value.schemeKey !== undefined) {
              if (typeof value.schemeKey !== 'string' || !schemeKeys.has(value.schemeKey))
                throw new OperationUsageError('菜单方案选择已失效，请重新读取');
              patch.schemeId = schemeKeys.get(value.schemeKey);
            }
            if (value.positionKey !== undefined) {
              if (typeof value.positionKey !== 'string' || !positionKeys.has(value.positionKey))
                throw new OperationUsageError('放置位置选择已失效，请重新读取');
              patch.parentId = positionKeys.get(value.positionKey);
            }
            return patch;
          },
          async execute(patch: ModuleMenuRevision, context) {
            current();
            const install = await selected.planRevision(patch);
            context.applyEffect(install);
            return { ...facts(), unsaved: true };
          },
        },
        {
          schemaDiscovery: 'eager',
          effect: 'read',
          descriptor: {
            code: 'menu.prepare-add',
            description:
              'Revalidate the actual module page and menu placement, then prepare HUMAN confirmation for this candidate. No construction plan required. Warn about another entry at the same placement. A saved but hidden entry is not failure and must not be recreated. Unknown results require original request lookup.',
            inputSchema: emptyAssistantCapabilityInputSchema(),
          },
          parseInput: parseEmptyAssistantCapabilityInput,
          async execute(_input, context) {
            current();
            const prepared = await selected.prepare();
            context.commitInternalState(() => {
              proposal = { ...prepared, modelSummary: '添加已审阅的标准菜单入口；不授予业务权限。' };
            });
            return { awaitingHumanConfirmation: true };
          },
          propose() {
            if (!proposal) throw new OperationUsageError('请先审阅当前入口候选');
            return proposal;
          },
        },
      ];
      return selected.saved.value || selected.resultUnknown.value
        ? capabilities.filter(
            (item) => !['menu.revise-draft', 'menu.prepare-add'].includes(item.descriptor.code),
          )
        : capabilities;
    },
  };
}
export type ModuleMenuWorkspace = ReturnType<typeof createModuleMenuWorkspace>;
const key: InjectionKey<ModuleMenuWorkspace> = Symbol('module-menu-workspace');
export function provideModuleMenuWorkspace(value: ModuleMenuWorkspace) {
  provide(key, value);
}
export function useModuleMenuWorkspace() {
  return inject(key, undefined);
}
