import { waitForConfigurationEditor } from './configurationEditorNavigation';
import { inject, provide, shallowRef, type InjectionKey } from 'vue';
import {
  AssistantCapabilityUsageError,
  createAssistantTurnRequester,
  parseEmptyAssistantCapabilityInput,
  type AssistantInvocationToken,
  type AssistantCapability,
  type AssistantConfigurationEditor,
  type HttpClient,
} from '@muyun/web-core';
import { createBusinessRuleSession, type BusinessRuleSession } from './businessRuleSession';
import { createBusinessRuleAssistantSurface } from './businessRuleAssistantSurface';

export function createBusinessRuleWorkspace(
  http: HttpClient,
  identity: () => string,
  enabled: () => boolean,
  openRules?: (moduleAlias: string) => void,
  onFocus?: () => void,
) {
  let scope = identity();
  const sessions = new Map<string, BusinessRuleSession>();
  const active = shallowRef<BusinessRuleSession>();
  const visible = shallowRef<BusinessRuleSession>();
  function resetScope() {
    const current = identity();
    if (current !== scope) {
      scope = current;
      sessions.forEach((value) => value.dispose());
      sessions.clear();
      active.value = undefined;
      visible.value = undefined;
    }
  }
  function session(moduleAlias: string) {
    resetScope();
    if (!sessions.has(moduleAlias)) {
      const owner = scope;
      const created = createBusinessRuleSession(
        http,
        moduleAlias,
        () => identity() === owner && sessions.get(moduleAlias) === created,
        () => active.value === created,
      );
      sessions.set(moduleAlias, created);
    }
    return sessions.get(moduleAlias)!;
  }
  function focus(value: BusinessRuleSession) {
    resetScope();
    if (sessions.get(value.moduleAlias) !== value) return;
    onFocus?.();
    if (active.value !== value) {
      active.value?.invalidateConfirmations();
      active.value = value;
    }
  }
  function surface() {
    return (
      active.value &&
      createBusinessRuleAssistantSurface(active.value.adapter, createAssistantTurnRequester(http))
    );
  }
  return {
    session,
    focus,
    dispose() {
      sessions.forEach((value) => value.dispose());
      sessions.clear();
      active.value = undefined;
      visible.value = undefined;
    },
    showEditor(value: BusinessRuleSession) {
      resetScope();
      if (sessions.get(value.moduleAlias) === value) visible.value = value;
    },
    hideEditor(value: BusinessRuleSession) {
      if (visible.value === value) visible.value = undefined;
    },
    editor(): AssistantConfigurationEditor | undefined {
      resetScope();
      const value = active.value;
      if (!value) return;
      return {
        kind: 'rules',
        openingCapability: openRules ? 'rules.open-editor' : undefined,
        moduleAlias: value.moduleAlias,
        title: value.moduleAlias,
        hasUnsavedChanges: value.dirty.value,
        visible: visible.value === value,
        open: openRules
          ? () => {
              resetScope();
              if (active.value === value && sessions.get(value.moduleAlias) === value)
                openRules(value.moduleAlias);
            }
          : undefined,
      };
    },
    clearFocus() {
      active.value?.invalidateConfirmations();
      active.value = undefined;
    },
    current() {
      resetScope();
      return {
        revision: JSON.stringify([
          scope,
          active.value?.moduleAlias,
          active.value?.revision.value,
          visible.value?.moduleAlias,
        ]),
        facts: {
          ruleConfiguration: surface()?.describe(),
          draftLifetime: '规则草稿仅保留在当前工作区；打开治理页面可继续编辑，刷新或身份变化后不恢复。',
        },
      };
    },
    capabilities(
      settleNavigation?: (signal?: AbortSignal) => Promise<void | AssistantInvocationToken>,
    ): AssistantCapability[] {
      resetScope();
      if (!enabled()) return [];
      const selected = active.value;
      const openCapability: AssistantCapability[] =
        selected && openRules && settleNavigation
          ? [
              {
                effect: 'page',
                descriptor: {
                  code: 'rules.open-editor',
                  description:
                    'Open the existing governance editor for the currently selected rule candidate when the user wants to inspect or edit visually. Reuses the same unsaved draft. Does not apply rules. No module alias or URL is accepted; select the intended module first.',
                  inputSchema: { type: 'object', additionalProperties: false, properties: {} },
                },
                parseInput: parseEmptyAssistantCapabilityInput,
                async execute(_input, context) {
                  if (
                    active.value !== selected ||
                    !selected.snapshot.value ||
                    selected.loading.value ||
                    selected.loadFailed.value
                  )
                    throw new AssistantCapabilityUsageError('请先选择配置目标并等待规则加载完成');
                  context.applyEffect(
                    () => openRules(selected.moduleAlias),
                    async () => {
                      const signal = context.cancellationSignal ?? context.signal;
                      await waitForConfigurationEditor(
                        () =>
                          visible.value === selected &&
                          !selected.loading.value &&
                          (selected.ready.value || selected.submissionStatus.value === 'unknown'),
                        signal,
                      );
                      return settleNavigation(signal);
                    },
                  );
                  return { moduleAlias: selected.moduleAlias, opened: true, saved: false };
                },
              },
            ]
          : [];
      return [
        ...openCapability,
        {
          effect: 'read',
          changesReadState: true,
          descriptor: {
            code: 'rules.select-module',
            description:
              'Read an existing module’s calculation/validation rules and field catalogs without opening its governance page or selecting a business tenant. Use an actual module alias from discovery or current context. Reselecting the current target is a no-op; set refresh=true to reread changed governance. Reads standard authorized governance snapshots; no business write. Existing unsaved candidates are retained per module. After selection, use rules capabilities and current workspace ruleConfiguration facts. Drafts last only for this workspace. No approval or business tenant is inferred from chat history.',
            inputSchema: {
              type: 'object',
              additionalProperties: false,
              required: ['moduleAlias'],
              properties: { moduleAlias: { type: 'string', maxLength: 128 }, refresh: { type: 'boolean' } },
            },
          },
          parseInput(input) {
            const alias = (input as { moduleAlias?: unknown } | null)?.moduleAlias;
            if (
              typeof alias !== 'string' ||
              alias.length > 128 ||
              !/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(alias)
            )
              throw new AssistantCapabilityUsageError('请提供真实模块标识');
            const refresh = (input as { refresh?: unknown }).refresh;
            if (refresh !== undefined && typeof refresh !== 'boolean')
              throw new AssistantCapabilityUsageError('刷新选项必须为布尔值');
            return { moduleAlias: alias, refresh: refresh === true };
          },
          async execute(input, context) {
            const value = input as { moduleAlias: string; refresh: boolean };
            const selected = session(value.moduleAlias);
            if (active.value === selected && selected.ready.value && !value.refresh)
              return { moduleAlias: selected.moduleAlias, saved: false };
            await selected.load(
              (value.refresh || active.value !== selected) &&
                !selected.dirty.value &&
                !selected.editing.value,
              (accept) => {
                context.commitInternalState(() => {
                  accept();
                  focus(selected);
                });
              },
            );
            return { moduleAlias: selected.moduleAlias, saved: false };
          },
        },
        ...(surface()?.capabilities() ?? []),
      ];
    },
  };
}
export type BusinessRuleWorkspace = ReturnType<typeof createBusinessRuleWorkspace>;
const key: InjectionKey<BusinessRuleWorkspace> = Symbol('business-rule-workspace');
export function provideBusinessRuleWorkspace(workspace: BusinessRuleWorkspace) {
  provide(key, workspace);
}
export function useBusinessRuleWorkspace() {
  return inject(key, undefined);
}
