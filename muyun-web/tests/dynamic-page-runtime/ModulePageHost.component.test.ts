import { shallowMount } from '@vue/test-utils';
import { assert, it } from 'vitest';
import DynamicModuleHost from '@/dynamic-page-runtime/DynamicModuleHost.vue';
import ModulePageHost from '@/dynamic-page-runtime/ModulePageHost.vue';

it('forwards neutral module page descriptors to the shared compatibility implementation', () => {
  const descriptor = {
    pageType: 'dynamic-module' as const,
    openMode: 'dynamic-runner' as const,
    hostType: 'module-page-host' as const,
    target: { moduleAlias: 'iam.organization', pageMode: 'LIST' as const },
    tabPolicy: { identity: 'by-target' as const },
  };
  const wrapper = shallowMount(ModulePageHost, { props: { descriptor } });

  assert.deepEqual(wrapper.findComponent(DynamicModuleHost).props('descriptor'), descriptor);
});
