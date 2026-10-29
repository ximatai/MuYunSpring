import { computed, inject, toValue, type InjectionKey, type Ref, type MaybeRefOrGetter } from 'vue';

export type UiSidePanelScope = 'tab' | 'viewport';

export const sidePanelHostKey: InjectionKey<Ref<HTMLElement | undefined>> =
  Symbol('muyun.ui-side-panel-host');

/** Inline panels own their anchor; tab portals require an injected, mounted host. */
export function useUiSidePanelAvailability(
  renderMode: MaybeRefOrGetter<'inline' | 'portal'>,
  scope: MaybeRefOrGetter<UiSidePanelScope>,
) {
  const host = inject(sidePanelHostKey, undefined);
  return computed(
    () => toValue(renderMode) === 'inline' || toValue(scope) === 'viewport' || Boolean(host?.value),
  );
}
