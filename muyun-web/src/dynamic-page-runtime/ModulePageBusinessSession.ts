import { defineComponent, proxyRefs, type PropType } from 'vue';
import {
  useModulePageSession,
  type ModulePageSessionProps,
  type ModulePageSessionView,
} from './useModulePageSession';
import type { QueryListRecord } from '@muyun/platform-components';

/** Owns disposable business effects; the sibling renderer owns the stable workspace. */
export default defineComponent({
  name: 'ModulePageBusinessSession',
  props: {
    descriptor: { type: Object as PropType<ModulePageSessionProps['descriptor']>, required: true },
    requireConfiguredPage: Boolean,
    recordOnly: Object as PropType<ModulePageSessionProps['recordOnly']>,
    tenantScope: Object as PropType<ModulePageSessionProps['tenantScope']>,
    tenantController: {
      type: Object as PropType<ModulePageSessionProps['tenantController']>,
      required: true,
    },
    http: Object as PropType<ModulePageSessionProps['http']>,
  },
  emits: {
    ready: (session: ModulePageSessionView) => Boolean(session),
    failed: (message: string) => Boolean(message),
    'interaction-state-change': (state: { editing: boolean; busy: boolean }) => Boolean(state),
    'record-only-change': (mutation: {
      type: 'saved' | 'deleted' | 'unavailable';
      record?: QueryListRecord;
    }) => Boolean(mutation),
    'record-only-close': () => true,
  },
  setup(props, { emit }) {
    const session = proxyRefs(
      useModulePageSession(props, emit, () => {
        if (session.pageBootstrapError) {
          emit('failed', session.pageBootstrapError);
          return;
        }
        emit('ready', session);
      }),
    );
    return () => null;
  },
});
