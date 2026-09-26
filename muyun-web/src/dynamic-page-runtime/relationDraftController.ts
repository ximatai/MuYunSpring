import { inject, provide, shallowReactive, ref, type InjectionKey } from 'vue';
import type { RecordFormDraftAccess } from './recordFormDraftAccess';

/** Commands target the mounted aggregate editor; they never persist child records. */
export interface RelationDraftController {
  readonly code: string;
  readonly title: string;
  revision(): string;
  settle(): Promise<void>;
  rowKeys(): string[];
  form(rowKey: string): RecordFormDraftAccess | undefined;
  add(): string;
  remove(rowKey: string): void;
}

export function createRelationDraftRegistry() {
  const entries = shallowReactive(new Map<symbol, RelationDraftController>());
  const interaction = ref(0);
  return {
    register(controller: RelationDraftController) {
      const key = Symbol();
      entries.set(key, controller);
      return () => entries.delete(key);
    },
    list() {
      const all = [...entries.values()];
      return all.filter((entry) => all.filter((other) => other.code === entry.code).length === 1);
    },
    revision: () => JSON.stringify([...entries.values()].map((entry) => [entry.code, entry.revision()])),
    settle: () => Promise.all([...entries.values()].map((entry) => entry.settle())),
    interactionRevision: () => interaction.value,
    userChanged: () => {
      interaction.value += 1;
    },
  };
}

export type RelationDraftRegistry = ReturnType<typeof createRelationDraftRegistry>;
const key: InjectionKey<() => RelationDraftRegistry | undefined> = Symbol('relation-draft-controllers');
export function provideRelationDraftRegistry(current: () => RelationDraftRegistry) {
  provide(key, current);
}
export const useRelationDraftRegistry = () => inject(key, () => undefined);
