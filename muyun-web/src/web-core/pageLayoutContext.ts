import {
  computed,
  inject,
  provide,
  type ComputedRef,
  type InjectionKey,
  type MaybeRefOrGetter,
  toValue,
} from 'vue';
import type { PageLayoutMode } from '@muyun/web-contracts';

const pageLayoutKey: InjectionKey<ComputedRef<PageLayoutMode>> = Symbol('page-layout');

/** Provided by a page host; platform components consume the resolved page layout. */
export function providePageLayout(layout: MaybeRefOrGetter<PageLayoutMode | undefined>) {
  provide(
    pageLayoutKey,
    computed(() => toValue(layout) ?? 'flow'),
  );
}

/** Ordinary pages stay flow-layout when mounted outside a workbench host. */
export function usePageLayout() {
  return inject(
    pageLayoutKey,
    computed<PageLayoutMode>(() => 'flow'),
  );
}
