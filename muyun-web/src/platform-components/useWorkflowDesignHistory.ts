import { computed, onScopeDispose, ref, watch, type Ref } from 'vue';
import type { WorkflowDesign } from '@muyun/web-contracts';

/** The design is authoritative; graph cells and viewport transforms are never history entries. */
export function useWorkflowDesignHistory(design: Ref<WorkflowDesign>, limit = 100) {
  const entries = ref([JSON.stringify(design.value)]);
  const index = ref(0);
  const saved = ref(entries.value[0]);
  let restoring = false;
  let revision = 0;
  onScopeDispose(() => {
    revision++;
  });
  watch(
    () => JSON.stringify(design.value),
    (value) => {
      if (restoring || value === entries.value[index.value]) return;
      revision++;
      entries.value = [...entries.value.slice(0, index.value + 1), value].slice(-limit);
      index.value = entries.value.length - 1;
    },
    { flush: 'sync' },
  );
  function restore(position: number) {
    if (position < 0 || position >= entries.value.length) return;
    revision++;
    restoring = true;
    index.value = position;
    design.value = JSON.parse(entries.value[position]);
    restoring = false;
  }
  function reset(value: WorkflowDesign, persisted: WorkflowDesign = value) {
    revision++;
    restoring = true;
    design.value = value;
    entries.value = [JSON.stringify(value)];
    index.value = 0;
    saved.value = JSON.stringify(persisted);
    restoring = false;
  }
  return {
    /** An async edit may commit only while this design revision and owning scope remain current. */
    checkpoint() {
      const expected = revision;
      return () => revision === expected;
    },
    canUndo: computed(() => index.value > 0),
    canRedo: computed(() => index.value < entries.value.length - 1),
    dirty: computed(() => JSON.stringify(design.value) !== saved.value),
    undo: () => restore(index.value - 1),
    redo: () => restore(index.value + 1),
    markSaved: () => {
      saved.value = JSON.stringify(design.value);
    },
    reset,
  };
}
