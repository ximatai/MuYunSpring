import { computed, ref } from 'vue';
import type { createPageCompositionDraftState } from './pageCompositionDraftState';
import {
  componentField,
  componentFieldUsageIndex,
  type PendingComponentField,
} from './pageCompositionComponents';

/** Session definitions survive removal so undo can restore identity; only referenced items are saved. */
export function usePageCompositionComponents(
  state: ReturnType<typeof createPageCompositionDraftState>,
  treeJson: () => string,
) {
  const pendingComponents = ref<PendingComponentField[]>([]);
  const pendingChildren = ref<Array<{ key: string; title: string; fields: PendingComponentField[] }>>([]);
  const pendingFieldSources = computed(() => pendingComponents.value.map(componentField));
  const fieldUsageIndex = computed(() => componentFieldUsageIndex(treeJson()));
  const activeComponents = computed(() =>
    pendingComponents.value.filter((field) => fieldUsageIndex.value.has(`field${field.key}`)),
  );
  const activeChildren = computed(() =>
    pendingChildren.value.flatMap((child) => {
      const relation = state.formRelations.value.find((item) => item.id === child.key);
      return relation
        ? [
            {
              ...child,
              title: relation.title,
              fields: child.fields.filter((field) => relation.fields.some((item) => item.id === field.key)),
            },
          ]
        : [];
    }),
  );
  const invalidTitle = (title: string) => !title.trim() || title.trim().length > 128;
  const componentNameInvalid = computed(() =>
    activeComponents.value.some((input) => invalidTitle(input.title)),
  );
  const childInvalid = computed(() =>
    activeChildren.value.some(
      (child) =>
        invalidTitle(child.title) ||
        !child.fields.length ||
        child.fields.some((field) => invalidTitle(field.title)),
    ),
  );
  const pendingSearchableFields = computed(() =>
    pendingComponents.value
      .filter((field) => field.component === 'text' || field.component === 'textarea')
      .map((field) => `field${field.key}`),
  );

  function updateField(key: string, changes: Partial<Pick<PendingComponentField, 'title' | 'required'>>) {
    const input = [
      ...pendingComponents.value,
      ...pendingChildren.value.flatMap((child) => child.fields),
    ].find((field) => field.key === key);
    if (!input) return;
    Object.assign(input, changes);
    const placements = [
      ...state.listFields.value,
      ...state.formRelations.value.flatMap((relation) => relation.fields),
      ...Object.values(state.layouts.value).flatMap((layout) => [
        ...layout.fields,
        ...layout.groups.flatMap((group) => group.fields),
      ]),
    ];
    placements.filter((field) => field.id === key).forEach((field) => Object.assign(field, changes));
  }

  function reset() {
    pendingComponents.value = [];
    pendingChildren.value = [];
  }

  return {
    fieldUsageIndex,
    pendingComponents,
    pendingChildren,
    pendingFieldSources,
    activeComponents,
    activeChildren,
    componentNameInvalid,
    childInvalid,
    pendingSearchableFields,
    updateField,
    reset,
  };
}
