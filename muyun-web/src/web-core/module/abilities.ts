import { moduleAbilityCodes, type ModuleAbilityCode } from './abilityCodes';
import { isRuntimeAbilityAvailable, type ModuleRuntimeContextState } from './runtimeContext';
import type { ModuleEnableClient, ModuleCrudClient, ModuleTreeClient } from './staticModuleClient';

export interface ModuleAbilityClients<TRecord> {
  crud: ModuleCrudClient<TRecord>;
  tree: ModuleTreeClient<TRecord>;
  enable: ModuleEnableClient;
}

export interface ModuleAbilities<TRecord> {
  crud(): ModuleCrudClient<TRecord>;
  tree(): ModuleTreeClient<TRecord>;
  enable(): ModuleEnableClient;
  tryCrud(): ModuleCrudClient<TRecord> | undefined;
  tryTree(): ModuleTreeClient<TRecord> | undefined;
  tryEnable(): ModuleEnableClient | undefined;
  has(ability: ModuleAbilityCode | string): boolean | undefined;
  hasCrud(): boolean | undefined;
  hasTree(): boolean | undefined;
  hasEnable(): boolean | undefined;
}

export function createModuleAbilities<TRecord>(
  moduleAlias: string,
  runtime: ModuleRuntimeContextState,
  clients: ModuleAbilityClients<TRecord>,
): ModuleAbilities<TRecord> {
  const requireAbility = <TAbility>(name: ModuleAbilityCode, value: TAbility | undefined) => {
    if (!value) {
      if (!runtime.snapshot()) {
        throw new Error(`Module runtime context is not ready: ${moduleAlias}`);
      }
      throw new Error(`Module ability is not available: ${moduleAlias}.${name}`);
    }
    return value;
  };
  const tryAbility = <TAbility>(name: ModuleAbilityCode, value: TAbility) =>
    isRuntimeAbilityAvailable(runtime.snapshot(), name) ? value : undefined;
  return {
    crud: () => requireAbility(moduleAbilityCodes.crud, tryAbility(moduleAbilityCodes.crud, clients.crud)),
    tree: () => requireAbility(moduleAbilityCodes.tree, tryAbility(moduleAbilityCodes.tree, clients.tree)),
    enable: () =>
      requireAbility(moduleAbilityCodes.enable, tryAbility(moduleAbilityCodes.enable, clients.enable)),
    tryCrud: () => tryAbility(moduleAbilityCodes.crud, clients.crud),
    tryTree: () => tryAbility(moduleAbilityCodes.tree, clients.tree),
    tryEnable: () => tryAbility(moduleAbilityCodes.enable, clients.enable),
    has: runtime.hasAbility,
    hasCrud: () => runtime.hasAbility(moduleAbilityCodes.crud),
    hasTree: () => runtime.hasAbility(moduleAbilityCodes.tree),
    hasEnable: () => runtime.hasAbility(moduleAbilityCodes.enable),
  };
}
