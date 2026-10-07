import { waitForConfigurationEditor } from './configurationEditorNavigation';
import { inject, provide, shallowReactive, shallowRef, type InjectionKey } from 'vue';
import {
  AssistantCapabilityUsageError,
  createAssistantTurnRequester,
  emptyAssistantCapabilityInputSchema,
  parseEmptyAssistantCapabilityInput,
  type AssistantCapability,
  type AssistantConfigurationEditor,
  type AssistantInvocationToken,
  type HttpClient,
} from '@muyun/web-core';
import { readModuleActivationFeedback } from './moduleRuntimeActivation';
import { createMetadataEditorSession, type MetadataEditorSession } from './metadataEditorSession';
import { createMetadataGovernanceAssistantSurface } from './metadataGovernanceAssistantSurface';

export function createMetadataWorkspace(
  http: HttpClient,
  identity: () => string,
  enabled: () => boolean,
  openEditor?: (moduleAlias: string, moduleTitle?: string) => void,
  onFocus?: () => void,
  openPageEditor?: (moduleAlias: string, moduleTitle?: string) => void,
) {
  let scope = identity();
  const sessions = new Map<string, MetadataEditorSession>();
  const committedRevisions = shallowReactive(new Map<string, number>());
  const active = shallowRef<MetadataEditorSession>();
  const visible = shallowRef<MetadataEditorSession>();
  function resetScope() {
    if (scope === identity()) return;
    scope = identity();
    sessions.forEach((value) => value.dispose());
    sessions.clear();
    committedRevisions.clear();
    active.value = undefined;
    visible.value = undefined;
  }
  function clearFocus() {
    active.value?.invalidateConfirmations();
    active.value = undefined;
  }
  function session(moduleAlias: string, moduleTitle?: string) {
    resetScope();
    if (!sessions.has(moduleAlias)) {
      const owner = scope;
      const created = createMetadataEditorSession(http, {
        moduleAlias,
        moduleTitle,
        refreshActivation: (alias) => readModuleActivationFeedback(http, alias),
        onCommitted: (alias) => committedRevisions.set(alias, (committedRevisions.get(alias) ?? 0) + 1),
        valid: () => identity() === owner && sessions.get(moduleAlias) === created,
        confirmationScope: () => active.value === created,
      });
      sessions.set(moduleAlias, created);
    }
    return sessions.get(moduleAlias)!;
  }
  function editorReady(value: MetadataEditorSession) {
    return (
      (value.workspaceReady.value ||
        value.submissionStatus.value === 'unknown' ||
        value.committedNeedsReload.value) &&
      !value.loading.value &&
      !value.saving.value
    );
  }

  function focus(value: MetadataEditorSession) {
    resetScope();
    if (sessions.get(value.moduleAlias) !== value) return;
    onFocus?.();
    if (active.value !== value) {
      clearFocus();
      active.value = value;
    }
  }
  function surface() {
    return (
      active.value &&
      createMetadataGovernanceAssistantSurface(active.value.adapter, createAssistantTurnRequester(http))
    );
  }
  return {
    session,
    focus,
    committedRevision: (moduleAlias: string) => committedRevisions.get(moduleAlias) ?? 0,
    showEditor(value: MetadataEditorSession) {
      resetScope();
      if (sessions.get(value.moduleAlias) === value) visible.value = value;
    },
    hideEditor(value: MetadataEditorSession) {
      if (visible.value === value) visible.value = undefined;
    },
    editor(): AssistantConfigurationEditor | undefined {
      resetScope();
      const value = active.value;
      if (!value) return;
      return {
        kind: 'metadata',
        openingCapability: openEditor ? 'configuration.open-metadata-editor' : undefined,
        moduleAlias: value.moduleAlias,
        title: value.title.value,
        hasUnsavedChanges: value.dirty.value,
        visible: visible.value === value,
        open: openEditor
          ? () => {
              resetScope();
              if (active.value === value && sessions.get(value.moduleAlias) === value)
                openEditor(value.moduleAlias, value.title.value);
            }
          : undefined,
      };
    },
    clearFocus,
    current() {
      resetScope();
      return {
        revision: JSON.stringify([
          scope,
          active.value?.moduleAlias,
          active.value?.contextRevision.value,
          active.value?.loading.value,
          active.value?.saving.value,
          visible.value?.moduleAlias,
        ]),
        facts: {
          metadataConfiguration: active.value
            ? {
                ...surface()!.describe(),
                draftLifetime: '元数据候选保留在当前工作区，关闭页面可继续；刷新或切换身份后不会恢复。',
              }
            : undefined,
        },
      };
    },
    capabilities(
      settleNavigation?: (
        signal?: AbortSignal,
        requireFormal?: boolean,
      ) => Promise<void | AssistantInvocationToken>,
    ): AssistantCapability[] {
      resetScope();
      if (!enabled()) return [];
      const selected = active.value;
      return [
        ...(selected
          ? [
              {
                effect: 'read' as const,
                descriptor: {
                  code: 'configuration.list-metadata-relations',
                  description:
                    'Read the current module metadata relation identities and roles before selecting a main entity or child table. Returns a bounded page; use offset to read further relations. Does not change the selected relation or candidate.',
                  inputSchema: {
                    type: 'object',
                    additionalProperties: false,
                    properties: { offset: { type: 'integer', minimum: 0 } },
                  },
                },
                parseInput(input: unknown) {
                  if (!input || typeof input !== 'object' || Array.isArray(input))
                    throw new AssistantCapabilityUsageError('请提供目录分页参数');
                  const value = input as Record<string, unknown>;
                  if (
                    Object.keys(value).some((key) => key !== 'offset') ||
                    (value.offset !== undefined &&
                      (!Number.isSafeInteger(value.offset) || Number(value.offset) < 0))
                  )
                    throw new AssistantCapabilityUsageError('目录分页位置无效');
                  return Number(value.offset ?? 0);
                },
                async execute(offset: unknown) {
                  const start = offset as number;
                  const relations = selected.relations.value;
                  return {
                    moduleAlias: selected.moduleAlias,
                    relations: relations
                      .slice(start, start + 40)
                      .map(({ id, relationAlias, relationRole }) => ({
                        relationId: id,
                        alias: relationAlias,
                        role: relationRole,
                      })),
                    total: relations.length,
                    nextOffset: start + 40 < relations.length ? start + 40 : null,
                  };
                },
              },
            ]
          : []),
        ...(openPageEditor && settleNavigation
          ? [
              {
                effect: 'page' as const,
                descriptor: {
                  code: 'configuration.open-page-editor',
                  description:
                    'Open the standard page composition editor to adjust forms, lists and detail layouts for an existing module. Use moduleAlias from the current page or discovered modules. No metadata selection is required. Does not change metadata or publish.',
                  inputSchema: {
                    type: 'object',
                    additionalProperties: false,
                    required: ['moduleAlias'],
                    properties: { moduleAlias: { type: 'string', maxLength: 128 } },
                  },
                },
                parseInput(input: unknown) {
                  if (!input || typeof input !== 'object' || Array.isArray(input))
                    throw new AssistantCapabilityUsageError('请提供真实模块标识');
                  const value = input as Record<string, unknown>;
                  if (
                    Object.keys(value).some((key) => key !== 'moduleAlias') ||
                    typeof value.moduleAlias !== 'string' ||
                    value.moduleAlias.length > 128 ||
                    !/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(value.moduleAlias)
                  )
                    throw new AssistantCapabilityUsageError('请提供真实模块标识');
                  return { moduleAlias: value.moduleAlias };
                },
                async execute(input: unknown, context: Parameters<AssistantCapability['execute']>[1]) {
                  const { moduleAlias } = input as { moduleAlias: string };
                  context.applyEffect(
                    () =>
                      openPageEditor(
                        moduleAlias,
                        selected?.moduleAlias === moduleAlias ? selected.title.value : undefined,
                      ),
                    () => settleNavigation(context.cancellationSignal ?? context.signal, true),
                  );
                  return { moduleAlias, opened: true, saved: false };
                },
              },
            ]
          : []),
        {
          effect: 'configuration-draft',
          descriptor: {
            code: 'configuration.select-metadata-module',
            description:
              'Select an existing module metadata editor without opening a page. Use a discovered module alias. Keeps existing unsaved candidates; defaults to the existing selected relation or main relation. Do not pass relationId. After selection, use configuration.select-metadata-relation to choose a real existing node if needed. Reselecting the current target is a no-op; set refresh=true only to reread changed governance. This does not save, create a module or alter schema.',
            inputSchema: {
              type: 'object',
              additionalProperties: false,
              required: ['moduleAlias'],
              properties: {
                moduleAlias: { type: 'string', maxLength: 128 },
                refresh: { type: 'boolean' },
              },
            },
          },
          parseInput(input) {
            if (!input || typeof input !== 'object' || Array.isArray(input))
              throw new AssistantCapabilityUsageError('请提供模块标识');
            const value = input as Record<string, unknown>;
            if (
              Object.keys(value).some((key) => !['moduleAlias', 'relationId', 'refresh'].includes(key)) ||
              typeof value.moduleAlias !== 'string' ||
              value.moduleAlias.length > 128 ||
              !/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(value.moduleAlias)
            )
              throw new AssistantCapabilityUsageError('请提供真实模块标识');
            if ('relationId' in value)
              throw new AssistantCapabilityUsageError(
                '选择模块时请省略 relationId；选定模块后通过 configuration.select-metadata-relation 选择实际存在的节点。',
              );
            if (value.refresh !== undefined && typeof value.refresh !== 'boolean')
              throw new AssistantCapabilityUsageError('刷新选项必须为布尔值');
            return {
              moduleAlias: value.moduleAlias,
              refresh: value.refresh === true,
            };
          },
          async execute(input, context) {
            const value = input as { moduleAlias: string; refresh: boolean };
            const target = session(value.moduleAlias);
            if (
              active.value === target &&
              target.workspaceReady.value &&
              !target.loading.value &&
              !target.saving.value &&
              !value.refresh
            )
              return { moduleAlias: target.moduleAlias, saved: false };
            await target.ensureLoaded(value.refresh || active.value !== target, (accept) => {
              context.applyEffect(() => {
                accept();
                focus(target);
              });
            });
            return { moduleAlias: target.moduleAlias, saved: false };
          },
          present() {
            return {
              title: `已选中配置对象：${active.value?.title.value ?? '当前模块'}`,
              lines: [
                visible.value === active.value
                  ? '共享编辑页当前可见。'
                  : '当前仅在工作区选中对象，尚未打开对应编辑页。',
                '本次选择不创建或保存配置，已有候选保留。',
              ],
            };
          },
        },
        ...(selected && editorReady(selected)
          ? [
              ...surface()!.capabilities(),
              ...(selected.adapter.summary().factsAvailable !== false &&
              selected.relations.value.some((relation) => relation.id)
                ? [metadataRelationSelectionCapability(selected)]
                : []),
              ...(openEditor && settleNavigation
                ? [
                    {
                      effect: 'page' as const,
                      descriptor: {
                        code: 'configuration.open-metadata-editor',
                        description:
                          'Open the standard metadata governance page for the current shared candidate when the user wants visual editing. No save and no arbitrary URL. Closing the page retains this draft in the workspace.',
                        inputSchema: emptyAssistantCapabilityInputSchema(),
                      },
                      parseInput: parseEmptyAssistantCapabilityInput,
                      async execute(_input: unknown, context: Parameters<AssistantCapability['execute']>[1]) {
                        context.applyEffect(
                          () => openEditor(selected.moduleAlias, selected.title.value),
                          async () => {
                            const signal = context.cancellationSignal ?? context.signal;
                            await waitForConfigurationEditor(
                              () => visible.value === selected && editorReady(selected),
                              signal,
                            );
                            return settleNavigation(signal);
                          },
                        );
                        return { moduleAlias: selected.moduleAlias, opened: true, saved: false };
                      },
                      present: () => ({
                        title: `已打开配置编辑页：${selected.title.value}`,
                        lines: ['展示当前共享候选，尚未提交新的配置。'],
                      }),
                    },
                  ]
                : []),
            ]
          : []),
      ];
    },
    dispose() {
      sessions.forEach((value) => value.dispose());
      sessions.clear();
      committedRevisions.clear();
      active.value = undefined;
      visible.value = undefined;
    },
  };
}
export type MetadataWorkspace = ReturnType<typeof createMetadataWorkspace>;

function metadataRelationSelectionCapability(target: MetadataEditorSession): AssistantCapability {
  const relationIds = target.relations.value.flatMap((relation) => (relation.id ? [relation.id] : []));
  return {
    effect: 'configuration-draft',
    descriptor: {
      code: 'configuration.select-metadata-relation',
      description:
        'Select an existing node in the currently selected metadata module. Only the returned real relation IDs are accepted. Does not create nodes, save configuration or discard drafts.',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['relationId'],
        properties: { relationId: { type: 'string', enum: relationIds } },
      },
    },
    parseInput(input) {
      const value = input as Record<string, unknown> | undefined;
      if (
        !value ||
        typeof value !== 'object' ||
        Array.isArray(value) ||
        Object.keys(value).some((key) => key !== 'relationId') ||
        typeof value.relationId !== 'string' ||
        !relationIds.includes(value.relationId)
      )
        throw new AssistantCapabilityUsageError('请选择当前模块目录中的真实元数据节点');
      return { relationId: value.relationId };
    },
    async execute(input, context) {
      const { relationId } = input as { relationId: string };
      if (target.adapter.summary().selectedRelation?.relationId === relationId)
        return { relationId, saved: false };
      if (target.dirty.value)
        throw new AssistantCapabilityUsageError('请先保存或取消当前字段候选，再切换元数据');
      return context.applyEffect(() => {
        target.selectRelation(relationId);
        return { relationId, saved: false };
      });
    },
  };
}

const key: InjectionKey<MetadataWorkspace> = Symbol('metadata-workspace');
export function provideMetadataWorkspace(workspace: MetadataWorkspace) {
  provide(key, workspace);
}
export function useMetadataWorkspace() {
  return inject(key, undefined);
}
