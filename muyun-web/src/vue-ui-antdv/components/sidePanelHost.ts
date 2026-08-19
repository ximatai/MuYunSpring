import type { InjectionKey, Ref } from 'vue';

export type UiSidePanelScope = 'tab' | 'viewport';

export const sidePanelHostKey: InjectionKey<Ref<HTMLElement | undefined>> =
  Symbol('muyun.ui-side-panel-host');
