import { waitForConfigurationEditor } from './configurationEditorNavigation';
import { inject, provide, shallowRef, type InjectionKey } from 'vue';
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
) {
  let scope = identity();
  const sessions = new Map<string, MetadataEditorSession>();
  const active = shallowRef<MetadataEditorSession>();
  const visible = shallowRef<MetadataEditorSession>();
  function resetScope() {
    if (scope === identity()) return;
    scope = identity();
    sessions.forEach((value) => value.dispose());
    sessions.clear();
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
        valid: () => identity() === owner && sessions.get(moduleAlias) === created,
        confirmationScope: () => active.value === created,
      });
      sessions.set(moduleAlias, created);
    }
    return sessions.get(moduleAlias)!;
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
                relations: active.value.relations.value
                  .slice(0, 80)
                  .map(({ id, relationAlias, relationRole }) => ({
                    relationId: id,
                    alias: relationAlias,
                    role: relationRole,
                  })),
                relationsTruncated: active.value.relations.value.length > 80,
                draftLifetime: '元数据候选保留在当前工作区，关闭页面可继续；刷新或切换身份后不会恢复。',
              }
            : undefined,
        },
      };
    },
    capabilities(
      settleNavigation?: (signal?: AbortSignal) => Promise<void | AssistantInvocationToken>,
    ): AssistantCapability[] {
      resetScope();
      if (!enabled()) return [];
      const selected = active.value;
      return [
        {
          effect: 'configuration-draft',
          descriptor: {
            code: 'configuration.select-metadata-module',
            description:
              'Select an existing module metadata editor without opening a page. Use a discovered module alias. Keeps existing unsaved candidates; defaults to the existing selected relation or main relation. Optional relationId must come from current metadataConfiguration relations. This does not save, create a module or alter schema.',
            inputSchema: {
              type: 'object',
              additionalProperties: false,
              required: ['moduleAlias'],
              properties: {
                moduleAlias: { type: 'string', maxLength: 128 },
                relationId: { type: 'string', maxLength: 128 },
              },
            },
          },
          parseInput(input) {
            if (!input || typeof input !== 'object' || Array.isArray(input))
              throw new AssistantCapabilityUsageError('请提供模块标识');
            const value = input as Record<string, unknown>;
            if (
              Object.keys(value).some((key) => !['moduleAlias', 'relationId'].includes(key)) ||
              typeof value.moduleAlias !== 'string' ||
              value.moduleAlias.length > 128 ||
              !/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(value.moduleAlias)
            )
              throw new AssistantCapabilityUsageError('请提供真实模块标识');
            if (
              value.relationId !== undefined &&
              (typeof value.relationId !== 'string' || !value.relationId || value.relationId.length > 128)
            )
              throw new AssistantCapabilityUsageError('请提供真实元数据节点');
            return { moduleAlias: value.moduleAlias, relationId: value.relationId as string | undefined };
          },
          async execute(input, context) {
            const value = input as { moduleAlias: string; relationId?: string };
            const target = session(value.moduleAlias);
            await target.ensureLoaded();
            return context.applyEffect(() => {
              if (value.relationId) target.selectRelation(value.relationId);
              focus(target);
              return { moduleAlias: target.moduleAlias, saved: false };
            });
          },
        },
        ...(selected && selected.workspaceReady.value && !selected.loading.value && !selected.saving.value
          ? [
              ...surface()!.capabilities(),
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
                            await waitForConfigurationEditor(() => visible.value === selected, signal);
                            return settleNavigation(signal);
                          },
                        );
                        return { moduleAlias: selected.moduleAlias, opened: true, saved: false };
                      },
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
      active.value = undefined;
      visible.value = undefined;
    },
  };
}
export type MetadataWorkspace = ReturnType<typeof createMetadataWorkspace>;
const key: InjectionKey<MetadataWorkspace> = Symbol('metadata-workspace');
export function provideMetadataWorkspace(workspace: MetadataWorkspace) {
  provide(key, workspace);
}
export function useMetadataWorkspace() {
  return inject(key, undefined);
}
