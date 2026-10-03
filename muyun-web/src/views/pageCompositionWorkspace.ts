import { waitForConfigurationEditor } from './configurationEditorNavigation';
import { inject, provide, shallowRef, type InjectionKey } from 'vue';
import {
  OperationUsageError,
  createAssistantTurnRequester,
  type AssistantCapability,
  type AssistantConfigurationEditor,
  type HttpClient,
} from '@muyun/web-core';
import { createPageCompositionSession, type PageCompositionSession } from './pageCompositionSession';
import { createPageCompositionAssistantSurface } from './pageCompositionAssistantSurface';

/** Owns one candidate per module and identity, independently of the rendered governance tab. */
export function createPageCompositionWorkspace(
  http: HttpClient,
  identity: () => string,
  enabled: () => boolean,
  openEditor?: (alias: string) => void,
  onFocus?: () => void,
) {
  let owner = identity();
  const sessions = new Map<string, PageCompositionSession>();
  const active = shallowRef<PageCompositionSession>();
  const visible = shallowRef<PageCompositionSession>();
  function dispose() {
    sessions.forEach((value) => value.dispose());
    sessions.clear();
    active.value = undefined;
    visible.value = undefined;
  }
  function resetScope() {
    if (identity() !== owner) {
      dispose();
      owner = identity();
    }
  }
  function session(moduleAlias: string, moduleTitle?: string) {
    resetScope();
    let value = sessions.get(moduleAlias);
    if (!value) {
      const scope = owner;
      value = createPageCompositionSession(
        http,
        { moduleAlias, moduleTitle },
        {
          valid: () => identity() === scope,
          active: () => active.value === value,
        },
      );
      sessions.set(moduleAlias, value);
    }
    return value;
  }
  function clearFocus() {
    if (active.value) active.value.contextRevision.value++;
    active.value = undefined;
  }
  function focus(value: PageCompositionSession) {
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
      createPageCompositionAssistantSurface(active.value.adapter, createAssistantTurnRequester(http))
    );
  }
  return {
    session,
    focus,
    clearFocus,
    dispose,
    showEditor(value: PageCompositionSession) {
      resetScope();
      if (sessions.get(value.moduleAlias) === value) visible.value = value;
    },
    hideEditor(value: PageCompositionSession) {
      if (visible.value === value) visible.value = undefined;
    },
    editor(): AssistantConfigurationEditor | undefined {
      resetScope();
      const value = active.value;
      if (!value) return;
      return {
        moduleAlias: value.moduleAlias,
        title: value.adapter.describe().title,
        hasUnsavedChanges: value.hasUnsavedChanges.value,
        visible: visible.value === value,
        open: openEditor
          ? () => {
              resetScope();
              if (active.value === value) openEditor(value.moduleAlias);
            }
          : undefined,
      };
    },
    current() {
      resetScope();
      return {
        revision: JSON.stringify([
          owner,
          active.value?.moduleAlias,
          active.value?.contextRevision.value,
          visible.value?.moduleAlias,
        ]),
        facts: { pageConfiguration: surface()?.describe() },
      };
    },
    capabilities(): AssistantCapability[] {
      resetScope();
      if (!enabled()) return [];
      return [
        {
          effect: 'configuration-draft',
          descriptor: {
            code: 'configuration.select-page-module',
            description:
              'Select an existing module’s shared page candidate without opening its editor. Read the current page description, revise and preview it, then request publication confirmation. Existing unsaved changes are retained. No construction plan is required.',
            inputSchema: {
              type: 'object',
              additionalProperties: false,
              required: ['moduleAlias'],
              properties: {
                moduleAlias: {
                  type: 'string',
                  maxLength: 128,
                  description: 'Exact existing module alias from observed facts. Do not guess an alias.',
                },
              },
            },
          },
          parseInput(input: unknown) {
            if (
              !input ||
              typeof input !== 'object' ||
              Array.isArray(input) ||
              Object.keys(input).some((key) => key !== 'moduleAlias')
            )
              throw new OperationUsageError('请提供真实模块标识');
            const alias = (input as { moduleAlias?: unknown }).moduleAlias;
            if (
              typeof alias !== 'string' ||
              alias.length > 128 ||
              !/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(alias)
            )
              throw new OperationUsageError('请提供真实模块标识');
            return alias;
          },
          async execute(alias: string, context) {
            const retained = sessions.has(alias);
            const value = session(alias);
            // Loading belongs to the retained session; only focus is an invocation effect.
            await waitForConfigurationEditor(() => !value.isMutating.value, context.signal);
            const install = retained ? await value.prepareCatalogRefresh() : undefined;
            context.applyEffect(() => {
              install?.();
              focus(value);
            });
            return value.adapter.describe();
          },
        } satisfies AssistantCapability<string>,
        ...(surface()?.capabilities() ?? []),
      ];
    },
  };
}
export type PageCompositionWorkspace = ReturnType<typeof createPageCompositionWorkspace>;
const key: InjectionKey<PageCompositionWorkspace> = Symbol('page-composition-workspace');
export function providePageCompositionWorkspace(value: PageCompositionWorkspace) {
  provide(key, value);
}
export function usePageCompositionWorkspace() {
  return inject(key, undefined);
}
