import { computed, defineComponent, inject, provide, toRaw, type InjectionKey, type PropType } from 'vue';
import type { HttpClient } from '../http';
import { createModuleAbilities, type ModuleAbilities } from './abilities';
import {
  createModuleRuntimeContextState,
  scopeModuleRuntimeLanguage,
  type ModuleActionState,
  type ModuleRecordActionAvailability,
  type ModuleRuntimeAction,
  type ModuleRuntimeContextState,
} from './runtimeContext';
import {
  createModuleCrudClient,
  createNavigatorReferenceCrudClient,
  createNavigatorReferenceTreeClient,
  type NavigatorReferenceRequestContext,
  createModuleTreeClient,
  type ModuleEnableClient,
  type ModuleCrudClient,
  type ModuleTreeClient,
} from './staticModuleClient';

import { platformLanguageContext, useLanguageContext, type LanguageContext } from '../language';

export interface ModuleContext<TRecord> {
  moduleAlias: string;
  http: HttpClient;
  crud: ModuleCrudClient<TRecord>;
  runtime: ModuleRuntimeContextState;
  abilities: ModuleAbilities<TRecord>;
  action(actionCode: string, recordId?: string): ModuleActionState | undefined;
  runtimeAction(actionCode: string): ModuleRuntimeAction | undefined;
  can(actionCode: string, recordId?: string): boolean | undefined;
  recordActions(recordId: string): Promise<ModuleRecordActionAvailability>;
  recordActionsBatch?(recordIds: string[]): Promise<ModuleRecordActionAvailability[]>;
  recordActionsSnapshot(recordId: string): ModuleRecordActionAvailability | undefined;
  invalidateRecordActions?(recordIds?: string[]): void;
}

export interface ModuleTreeContext<TRecord> extends ModuleContext<TRecord> {
  tree: ModuleTreeClient<TRecord>;
}

export interface ModuleContextConfig {
  language?: LanguageContext;
  http?: HttpClient;
  httpFactory?: () => HttpClient;
}

export interface ModuleContextOptions extends ModuleContextConfig {
  moduleAlias: string;
  runtimeAccess?: 'MENU' | 'REFERENCE' | 'VIEW';
  navigatorReference?: NavigatorReferenceRequestContext;
}

const moduleContextConfigKey: InjectionKey<ModuleContextConfig> = Symbol('muyun.module-context-config');
const moduleAliasKey: InjectionKey<Readonly<{ value: string | undefined }>> = Symbol('muyun.module-alias');
const moduleContextKey: InjectionKey<Readonly<{ value: ModuleContext<unknown> | undefined }>> =
  Symbol('muyun.module-context');
const contextLanguages = new WeakMap<object, LanguageContext>();
let defaultModuleContextConfig: ModuleContextConfig | undefined;

export function configureModuleContext(config: ModuleContextConfig) {
  defaultModuleContextConfig = config;
}

export function provideModuleContextConfig(config: ModuleContextConfig) {
  provide(moduleContextConfigKey, config);
}

export function createModuleContext<TRecord>(options: ModuleContextOptions): ModuleContext<TRecord> {
  const http = resolveModuleHttpClient(options);
  return moduleContextOf<TRecord>(
    http,
    options.moduleAlias,
    options.runtimeAccess,
    options.navigatorReference,
    options.language ?? defaultModuleContextConfig?.language,
  );
}

export function createModuleTreeContext<TRecord>(options: ModuleContextOptions): ModuleTreeContext<TRecord> {
  const http = resolveModuleHttpClient(options);
  return moduleTreeContextOf<TRecord>(
    http,
    options.moduleAlias,
    options.runtimeAccess,
    options.navigatorReference,
    options.language ?? defaultModuleContextConfig?.language,
  );
}

export function useModuleContext<TRecord>(
  options: Partial<ModuleContextOptions> = {},
): ModuleContext<TRecord> {
  const config = inject(moduleContextConfigKey, undefined);
  const injectedContext = inject(moduleContextKey, undefined);
  const injectedModuleAlias = inject(moduleAliasKey, undefined);
  const moduleAlias = options.moduleAlias ?? injectedModuleAlias?.value;
  if (!moduleAlias) {
    throw new Error('Module context requires a moduleAlias');
  }
  if (
    !options.http &&
    !options.httpFactory &&
    options.runtimeAccess === undefined &&
    options.navigatorReference === undefined &&
    injectedContext?.value?.moduleAlias === moduleAlias
  ) {
    const context = injectedContext.value as ModuleContext<TRecord>;
    if (!options.language || options.language === contextLanguages.get(toRaw(context))) return context;
    // A language override is a display scope: retain the authorized runtime, transport and business session.
    const language = options.language;
    const runtime = scopeModuleRuntimeLanguage(context.runtime, language, context);
    const localized = {
      ...context,
      runtime,
      action: runtime.action,
      runtimeAction: runtime.runtimeAction,
    };
    contextLanguages.set(localized, language);
    return localized;
  }
  const http = resolveModuleHttpClient(options, config);
  return moduleContextOf<TRecord>(
    http,
    moduleAlias,
    options.runtimeAccess,
    options.navigatorReference,
    options.language ?? config?.language ?? useLanguageContext(),
  );
}

export function useModuleTreeContext<TRecord>(
  options: Partial<ModuleContextOptions> = {},
): ModuleTreeContext<TRecord> {
  const config = inject(moduleContextConfigKey, undefined);
  const injectedModuleAlias = inject(moduleAliasKey, undefined);
  const moduleAlias = options.moduleAlias ?? injectedModuleAlias?.value;
  if (!moduleAlias) {
    throw new Error('Module tree context requires a moduleAlias');
  }
  const http = resolveModuleHttpClient(options, config);
  return moduleTreeContextOf<TRecord>(
    http,
    moduleAlias,
    options.runtimeAccess,
    options.navigatorReference,
    options.language ?? config?.language ?? useLanguageContext(),
  );
}

/** New consumers capture the current immutable transport; existing contexts keep their original client. */
export const ModuleHttpProvider = defineComponent({
  name: 'ModuleHttpProvider',
  props: { http: { type: Object as PropType<HttpClient>, required: true } },
  setup(props, { slots }) {
    provideModuleContextConfig({
      get http() {
        return props.http;
      },
    });
    provide(
      moduleContextKey,
      computed(() => undefined),
    );
    return () => slots.default?.();
  },
});

export const ModuleContextProvider = defineComponent({
  name: 'ModuleContextProvider',
  props: {
    moduleAlias: {
      type: String,
      required: false,
      default: undefined,
    },
    /** Reuse an already-authorized module session without recreating a MENU runtime. */
    context: {
      type: Object as PropType<ModuleContext<unknown>>,
      required: false,
      default: undefined,
    },
  },
  setup(props, { slots }) {
    const config = inject(moduleContextConfigKey, undefined);
    const language = useLanguageContext();
    const moduleContext = computed<ModuleContext<unknown> | undefined>(() => {
      if (props.context) return props.context;
      if (!props.moduleAlias) {
        return undefined;
      }
      const http = resolveModuleHttpClient({}, config);
      return moduleContextOf<unknown>(
        http,
        props.moduleAlias,
        undefined,
        undefined,
        config?.language ?? language,
      );
    });
    provide(
      moduleAliasKey,
      computed(() => props.context?.moduleAlias ?? props.moduleAlias),
    );
    provide(moduleContextKey, moduleContext);
    return () => slots.default?.();
  },
});

function moduleContextOf<TRecord>(
  http: HttpClient,
  moduleAlias: string,
  runtimeAccess: 'MENU' | 'REFERENCE' | 'VIEW' = 'MENU',
  navigatorReference?: NavigatorReferenceRequestContext,
  language?: LanguageContext,
): ModuleContext<TRecord> {
  const { crud, tree } = moduleClientsFor<TRecord>(http, moduleAlias, runtimeAccess, navigatorReference);
  const enable: ModuleEnableClient = {
    enable: crud.enable,
    disable: crud.disable,
  };
  const runtime = createModuleRuntimeContextState(http, moduleAlias, runtimeAccess, language);
  const context: ModuleContext<TRecord> = {
    moduleAlias,
    http,
    crud,
    runtime,
    abilities: createModuleAbilities(moduleAlias, runtime, { crud, tree, enable }),
    action: runtime.action,
    runtimeAction: runtime.runtimeAction,
    can: runtime.can,
    recordActions: runtime.recordActions,
    recordActionsBatch: runtime.recordActionsBatch,
    recordActionsSnapshot: runtime.recordActionsSnapshot,
    invalidateRecordActions: runtime.invalidateRecordActions,
  };
  contextLanguages.set(context, language ?? platformLanguageContext);
  return context;
}

function moduleClientsFor<TRecord>(
  http: HttpClient,
  moduleAlias: string,
  runtimeAccess: 'MENU' | 'REFERENCE' | 'VIEW',
  navigatorReference?: NavigatorReferenceRequestContext,
) {
  if (runtimeAccess === 'REFERENCE') {
    return {
      crud: createNavigatorReferenceCrudClient<TRecord>(http, { moduleAlias, navigatorReference }),
      tree: createNavigatorReferenceTreeClient<TRecord>(http, { moduleAlias, navigatorReference }),
    };
  }
  return {
    crud: createModuleCrudClient<TRecord>(http, { moduleAlias }),
    tree: createModuleTreeClient<TRecord>(http, { moduleAlias }),
  };
}

function moduleTreeContextOf<TRecord>(
  http: HttpClient,
  moduleAlias: string,
  runtimeAccess: 'MENU' | 'REFERENCE' | 'VIEW' = 'MENU',
  navigatorReference?: NavigatorReferenceRequestContext,
  language?: LanguageContext,
): ModuleTreeContext<TRecord> {
  const context = moduleContextOf<TRecord>(http, moduleAlias, runtimeAccess, navigatorReference, language);
  const treeContext = {
    ...context,
    tree: runtimeCheckedTreeClient(context),
  };
  contextLanguages.set(treeContext, language ?? platformLanguageContext);
  return treeContext;
}

function runtimeCheckedTreeClient<TRecord>(context: ModuleContext<TRecord>): ModuleTreeClient<TRecord> {
  const tree = async () => {
    await context.runtime.ready;
    return context.abilities.tree();
  };
  return {
    querySchema: async (options) => (await tree()).querySchema(options),
    query: async (request) => (await tree()).query(request),
    view: async (id) => (await tree()).view(id),
    insert: async (record, options) => (await tree()).insert(record, options),
    update: async (id, record, options) => (await tree()).update(id, record, options),
    delete: async (id, request) => (await tree()).delete(id, request),
    enable: async (id, request) => (await tree()).enable(id, request),
    disable: async (id, request) => (await tree()).disable(id, request),
    tree: async (request) => (await tree()).tree(request),
    treeFlat: async (options) => (await tree()).treeFlat(options),
    subtree: async (id, options) => (await tree()).subtree(id, options),
    sort: async (id, request, options) => (await tree()).sort(id, request, options),
  };
}

function resolveModuleHttpClient(
  options: ModuleContextConfig,
  injectedConfig?: ModuleContextConfig,
): HttpClient {
  const config =
    options.http || options.httpFactory ? options : (injectedConfig ?? defaultModuleContextConfig);
  const http = config?.http ?? config?.httpFactory?.();
  if (!http) {
    throw new Error('Module context requires an HttpClient or httpFactory');
  }
  return http;
}
