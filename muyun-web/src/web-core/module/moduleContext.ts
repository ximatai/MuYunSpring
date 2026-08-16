import { computed, defineComponent, inject, provide, type InjectionKey } from 'vue';
import type { HttpClient } from '../http';
import { createModuleAbilities, type ModuleAbilities } from './abilities';
import {
  createModuleRuntimeContextState,
  type ModuleActionState,
  type ModuleRecordActionAvailability,
  type ModuleRuntimeAction,
  type ModuleRuntimeContextState,
} from './runtimeContext';
import {
  createStaticModuleCrudClient,
  createNavigatorReferenceCrudClient,
  createNavigatorReferenceTreeClient,
  createStaticModuleTreeClient,
  type ModuleEnableClient,
  type StaticModuleCrudClient,
  type StaticModuleTreeClient,
} from './staticModuleClient';

export interface ModuleContext<TRecord> {
  moduleAlias: string;
  http: HttpClient;
  crud: StaticModuleCrudClient<TRecord>;
  runtime: ModuleRuntimeContextState;
  abilities: ModuleAbilities<TRecord>;
  action(actionCode: string, recordId?: string): ModuleActionState | undefined;
  runtimeAction(actionCode: string): ModuleRuntimeAction | undefined;
  can(actionCode: string, recordId?: string): boolean | undefined;
  recordActions(recordId: string): Promise<ModuleRecordActionAvailability>;
  recordActionsSnapshot(recordId: string): ModuleRecordActionAvailability | undefined;
}

export interface ModuleTreeContext<TRecord> extends ModuleContext<TRecord> {
  tree: StaticModuleTreeClient<TRecord>;
}

export interface ModuleContextConfig {
  http?: HttpClient;
  httpFactory?: () => HttpClient;
}

export interface ModuleContextOptions extends ModuleContextConfig {
  moduleAlias: string;
  runtimeAccess?: 'MENU' | 'REFERENCE';
}

const moduleContextConfigKey: InjectionKey<ModuleContextConfig> = Symbol('muyun.module-context-config');
const moduleAliasKey: InjectionKey<Readonly<{ value: string | undefined }>> = Symbol('muyun.module-alias');
const moduleContextKey: InjectionKey<Readonly<{ value: ModuleContext<unknown> | undefined }>> =
  Symbol('muyun.module-context');
let defaultModuleContextConfig: ModuleContextConfig | undefined;

export function configureModuleContext(config: ModuleContextConfig) {
  defaultModuleContextConfig = config;
}

export function provideModuleContextConfig(config: ModuleContextConfig) {
  provide(moduleContextConfigKey, config);
}

export function createModuleContext<TRecord>(options: ModuleContextOptions): ModuleContext<TRecord> {
  const http = resolveModuleHttpClient(options);
  return moduleContextOf<TRecord>(http, options.moduleAlias, options.runtimeAccess);
}

export function createModuleTreeContext<TRecord>(options: ModuleContextOptions): ModuleTreeContext<TRecord> {
  const http = resolveModuleHttpClient(options);
  return moduleTreeContextOf<TRecord>(http, options.moduleAlias, options.runtimeAccess);
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
  if (!options.http && !options.httpFactory && injectedContext?.value?.moduleAlias === moduleAlias) {
    return injectedContext.value as ModuleContext<TRecord>;
  }
  const http = resolveModuleHttpClient(options, config);
  return moduleContextOf<TRecord>(http, moduleAlias);
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
  return moduleTreeContextOf<TRecord>(http, moduleAlias);
}

export const ModuleContextProvider = defineComponent({
  name: 'ModuleContextProvider',
  props: {
    moduleAlias: {
      type: String,
      required: false,
      default: undefined,
    },
  },
  setup(props, { slots }) {
    const config = inject(moduleContextConfigKey, undefined);
    const moduleContext = computed<ModuleContext<unknown> | undefined>(() => {
      if (!props.moduleAlias) {
        return undefined;
      }
      const http = resolveModuleHttpClient({}, config);
      return moduleContextOf<unknown>(http, props.moduleAlias);
    });
    provide(
      moduleAliasKey,
      computed(() => props.moduleAlias),
    );
    provide(moduleContextKey, moduleContext);
    return () => slots.default?.();
  },
});

function moduleContextOf<TRecord>(http: HttpClient, moduleAlias: string, runtimeAccess: 'MENU' | 'REFERENCE' = 'MENU'): ModuleContext<TRecord> {
  const { crud, tree } = moduleClientsFor<TRecord>(http, moduleAlias, runtimeAccess);
  const enable: ModuleEnableClient = {
    enable: crud.enable,
    disable: crud.disable,
  };
  const runtime = createModuleRuntimeContextState(http, moduleAlias, runtimeAccess);
  return {
    moduleAlias,
    http,
    crud,
    runtime,
    abilities: createModuleAbilities(moduleAlias, runtime, { crud, tree, enable }),
    action: runtime.action,
    runtimeAction: runtime.runtimeAction,
    can: runtime.can,
    recordActions: runtime.recordActions,
    recordActionsSnapshot: runtime.recordActionsSnapshot,
  };
}

function moduleClientsFor<TRecord>(http: HttpClient, moduleAlias: string, runtimeAccess: 'MENU' | 'REFERENCE') {
  if (runtimeAccess === 'REFERENCE') {
    return {
      crud: createNavigatorReferenceCrudClient<TRecord>(http, { moduleAlias }),
      tree: createNavigatorReferenceTreeClient<TRecord>(http, { moduleAlias }),
    };
  }
  return {
    crud: createStaticModuleCrudClient<TRecord>(http, { moduleAlias }),
    tree: createStaticModuleTreeClient<TRecord>(http, { moduleAlias }),
  };
}

function moduleTreeContextOf<TRecord>(http: HttpClient, moduleAlias: string, runtimeAccess: 'MENU' | 'REFERENCE' = 'MENU'): ModuleTreeContext<TRecord> {
  const context = moduleContextOf<TRecord>(http, moduleAlias, runtimeAccess);
  return {
    ...context,
    tree: runtimeCheckedTreeClient(context),
  };
}

function runtimeCheckedTreeClient<TRecord>(context: ModuleContext<TRecord>): StaticModuleTreeClient<TRecord> {
  const tree = async () => {
    await context.runtime.ready;
    return context.abilities.tree();
  };
  return {
    querySchema: async (options) => (await tree()).querySchema(options),
    query: async (request) => (await tree()).query(request),
    view: async (id) => (await tree()).view(id),
    insert: async (record) => (await tree()).insert(record),
    update: async (id, record) => (await tree()).update(id, record),
    delete: async (id, request) => (await tree()).delete(id, request),
    enable: async (id, request) => (await tree()).enable(id, request),
    disable: async (id, request) => (await tree()).disable(id, request),
    tree: async (request) => (await tree()).tree(request),
    treeFlat: async (options) => (await tree()).treeFlat(options),
    subtree: async (id, options) => (await tree()).subtree(id, options),
    sort: async (id, request) => (await tree()).sort(id, request),
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
