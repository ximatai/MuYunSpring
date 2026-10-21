import { expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { computed, defineComponent, h, ref } from 'vue';
import {
  configureModulePageEnhancementContributions,
  resolveModulePageEnhancement,
} from '@/dynamic-page-runtime/modulePageEnhancements';

it('keeps registrations across module re-evaluation and updates mounted consumers without replacing their drafts', async () => {
  const owner = 'registry-reload-test';
  const moduleAlias = 'demo.registry_reload';
  const contribution = (title: string) => ({
    id: owner,
    target: { moduleAlias },
    detail: { actions: [{ key: 'workflow', title, run() {} }] },
  });
  configureModulePageEnhancementContributions(owner, [contribution('审批流程')]);
  const wrapper = mount(
    defineComponent({
      setup() {
        const draft = ref('尚未保存');
        const enhancement = computed(() => resolveModulePageEnhancement(moduleAlias));
        return () =>
          h('section', [
            h('input', {
              value: draft.value,
              onInput: (event: Event) => {
                draft.value = (event.target as HTMLInputElement).value;
              },
            }),
            ...(enhancement.value?.detail?.actions ?? []).map((action) => h('button', action.title)),
          ]);
      },
    }),
  );
  try {
    const input = wrapper.get('input').element;
    await wrapper.get('input').setValue('正在编辑的内容');
    // Vite evaluates a fresh registry module while retaining its unchanged dependencies,
    // as it does when an imported component establishes an HMR acceptance boundary.
    // @ts-expect-error Vite query imports intentionally create another module evaluation.
    const reloaded = await import('@/dynamic-page-runtime/modulePageEnhancements?registry-reload');
    expect(reloaded.resolveModulePageEnhancement(moduleAlias)?.detail?.actions?.[0]?.title).toBe('审批流程');
    reloaded.configureModulePageEnhancementContributions(owner, [contribution('新的流程入口')]);
    await wrapper.vm.$nextTick();
    expect(wrapper.get('button').text()).toBe('新的流程入口');
    expect(wrapper.get('input').element).toBe(input);
    expect(wrapper.get('input').element.value).toBe('正在编辑的内容');
  } finally {
    wrapper.unmount();
    configureModulePageEnhancementContributions(owner, []);
  }
});
