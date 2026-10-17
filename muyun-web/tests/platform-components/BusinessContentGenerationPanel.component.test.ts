import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { defineComponent, h, reactive, ref } from 'vue';
import { afterEach, expect, it, vi } from 'vitest';
import {
  createLanguageContext,
  provideLanguageContext,
  type HttpRequestOptions,
  type ModuleContext,
} from '@muyun/web-core';
import type {
  BusinessContentGenerationBatchResult,
  BusinessContentGenerationSource,
  BusinessContentGenerationStatus,
  WebBusinessContentQueryStatus,
} from '@muyun/web-contracts';
import BusinessContentGenerationPanel from '@/platform-components/BusinessContentGenerationPanel.vue';

const wrappers: VueWrapper[] = [];
afterEach(() => wrappers.splice(0).forEach((wrapper) => wrapper.unmount()));
const Modal = defineComponent({
  props: ['open', 'title', 'confirmText'],
  emits: ['confirm', 'cancel'],
  setup:
    (props, { slots, emit }) =>
    () =>
      props.open
        ? h('section', { 'data-generation': '' }, [
            h('h2', props.title),
            slots.default?.(),
            h('button', { onClick: () => emit('confirm') }, props.confirmText),
          ])
        : null,
});
function result(
  state: BusinessContentGenerationStatus['state'],
  ids = ['row'],
  source?: BusinessContentGenerationSource,
): BusinessContentGenerationBatchResult {
  return {
    language: 'de',
    items: ids.map((id) => ({
      recordId: id,
      ownerId: source?.parentId ?? id,
      relationCode: source?.relationCode ?? null,
      childId: source?.parentId ? id : null,
      field: 'description',
      status: {
        state,
        reference: state === 'MISSING' ? null : 'job',
        problem: null,
        attempts: 0,
        retryAt: null,
      },
    })),
  };
}
function host(
  request: ReturnType<typeof vi.fn>,
  source: BusinessContentGenerationSource = { moduleAlias: 'quality.exception' },
) {
  const language = createLanguageContext();
  const initiallySelected = ref<string[]>([]);
  const status = reactive<WebBusinessContentQueryStatus>({
    language: 'de',
    missingRecords: 1,
    readings: {},
    generationScope: source,
  });
  const records = reactive([{ id: 'row', version: 1, label: 'R500 质量异常' }]);
  const context = {
    moduleAlias: 'quality.exception',
    http: { request },
    runtime: { snapshot: () => ({ uiDescriptor: { detailRelations: [] } }) },
  } as unknown as ModuleContext<unknown>;
  const wrapper = mount(
    defineComponent({
      setup() {
        provideLanguageContext(language);
        return () =>
          h(BusinessContentGenerationPanel, {
            status,
            records,
            context,
            initiallySelected: initiallySelected.value,
            fields: { description: '异常说明' },
          });
      },
    }),
    { global: { stubs: { UiModal: Modal } } },
  );
  wrappers.push(wrapper);
  const panel = wrapper.findComponent(BusinessContentGenerationPanel);
  return { wrapper, panel, records, status, initiallySelected };
}
function button(wrapper: VueWrapper, text: string) {
  const found = wrapper.findAll('button').find((entry) => entry.text() === text);
  if (!found) throw new Error('Missing button: ' + text);
  return found;
}
function generated(request: ReturnType<typeof vi.fn>) {
  return request.mock.calls.filter(([entry]) => !entry.path.endsWith('/status'));
}

it('previews the visible selection and its language without generating, then requires an explicit action', async () => {
  const request = vi.fn(async (entry: HttpRequestOptions) =>
    result(entry.path.endsWith('/status') ? 'MISSING' : 'READY'),
  );
  const { wrapper, panel, records } = host(request);
  const baseline = JSON.stringify(records);
  expect(request).not.toHaveBeenCalled();
  await button(wrapper, '补齐阅读译文').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('Deutsch');
  expect(wrapper.text()).toContain('异常说明');
  expect(wrapper.text()).toContain('待生成');
  expect(generated(request)).toHaveLength(0);
  expect(request.mock.calls[0][0]).toMatchObject({
    path: '/quality.exception/content/generation/status',
    body: { ids: ['row'], language: 'de', includeChildren: true },
  });
  await button(wrapper, '生成缺失译文').trigger('click');
  await flushPromises();
  expect(generated(request)).toHaveLength(1);
  expect(wrapper.text()).toContain('已有有效译文');
  expect(panel.emitted('updated')).toHaveLength(1);
  expect(JSON.stringify(records)).toBe(baseline);
});

it('uses the server-issued owned-child address and never treats a child identity as a root identity', async () => {
  const source = { moduleAlias: 'quality.exception', parentId: 'parent/one', relationCode: 'lines' };
  const request = vi.fn<(entry: HttpRequestOptions) => Promise<BusinessContentGenerationBatchResult>>(
    async () => result('MISSING', ['child/one'], source),
  );
  const { wrapper, records } = host(request, source);
  records.splice(0, 1, { id: 'child/one', version: 1, label: '复检说明' });
  await button(wrapper, '补齐阅读译文').trigger('click');
  await flushPromises();
  expect(wrapper.text()).not.toContain('包含所属明细');
  expect(request.mock.calls[0][0]).toMatchObject({
    path: '/quality.exception/view/parent%2Fone/relations/lines/content/generation/status',
    body: { ids: ['child/one'], includeChildren: false },
  });
  await button(wrapper, '生成缺失译文').trigger('click');
  await flushPromises();
  expect(generated(request)[0][0].path).toBe(
    '/quality.exception/view/parent%2Fone/relations/lines/content/generation',
  );
});

it('refreshes the owning list when work completes after closing and reopening the same selection', async () => {
  let state: BusinessContentGenerationStatus['state'] = 'MISSING';
  const request = vi.fn(async (entry: HttpRequestOptions) => {
    if (!entry.path.endsWith('/status')) state = 'PENDING';
    return result(state);
  });
  const { wrapper, panel } = host(request);
  await button(wrapper, '补齐阅读译文').trigger('click');
  await flushPromises();
  await button(wrapper, '生成缺失译文').trigger('click');
  await flushPromises();
  expect(panel.emitted('updated')).toBeUndefined();
  await button(wrapper, '关闭').trigger('click');
  await flushPromises();
  state = 'READY';
  await button(wrapper, '补齐阅读译文').trigger('click');
  await flushPromises();
  expect(panel.emitted('updated')).toHaveLength(1);
  await button(wrapper, '刷新状态').trigger('click');
  await flushPromises();
  expect(panel.emitted('updated')).toHaveLength(1);
  expect(generated(request)).toHaveLength(1);
});

it('retains a manually chosen subset when reopening completed work and refreshes only once', async () => {
  let state: BusinessContentGenerationStatus['state'] = 'MISSING';
  const request = vi.fn(async (entry: HttpRequestOptions) => {
    if (!entry.path.endsWith('/status')) state = 'PENDING';
    return result(state, (entry.body as { ids: string[] }).ids);
  });
  const { wrapper, panel, records } = host(request);
  records.push({ id: 'other', version: 1, label: '另一条异常' });
  await button(wrapper, '补齐阅读译文').trigger('click');
  await flushPromises();
  await wrapper.findAll('input[type="checkbox"]')[1].setValue(false);
  await flushPromises();
  await button(wrapper, '生成缺失译文').trigger('click');
  await flushPromises();
  expect(generated(request)[0][0].body).toMatchObject({ ids: ['row'] });
  await button(wrapper, '关闭').trigger('click');
  await flushPromises();
  state = 'READY';
  await button(wrapper, '补齐阅读译文').trigger('click');
  await flushPromises();
  expect(request.mock.lastCall?.[0].body).toMatchObject({ ids: ['row'] });
  expect(
    wrapper.findAll('input[type="checkbox"]').map((input) => (input.element as HTMLInputElement).checked),
  ).toEqual([true, false, true]);
  expect(panel.emitted('updated')).toHaveLength(1);
  await button(wrapper, '刷新状态').trigger('click');
  await flushPromises();
  expect(panel.emitted('updated')).toHaveLength(1);
  expect(generated(request)).toHaveLength(1);
});

it('initializes a fresh selection when the visible page changes after closing', async () => {
  const request = vi.fn(async (entry: HttpRequestOptions) =>
    result('MISSING', (entry.body as { ids: string[] }).ids),
  );
  const { wrapper, records } = host(request);
  await button(wrapper, '补齐阅读译文').trigger('click');
  await flushPromises();
  await wrapper.find('input[type="checkbox"]').setValue(false);
  await flushPromises();
  await button(wrapper, '关闭').trigger('click');
  records.splice(0, 1, { id: 'new-page', version: 1, label: '新页面异常' });
  await flushPromises();
  await button(wrapper, '补齐阅读译文').trigger('click');
  await flushPromises();
  expect(request.mock.lastCall?.[0].body).toMatchObject({ ids: ['new-page'] });
});

it('respects a new explicit table selection instead of reviving previously registered work', async () => {
  let state: BusinessContentGenerationStatus['state'] = 'MISSING';
  const request = vi.fn(async (entry: HttpRequestOptions) => {
    if (!entry.path.endsWith('/status')) state = 'PENDING';
    return result(state, (entry.body as { ids: string[] }).ids);
  });
  const { wrapper, panel, records, initiallySelected } = host(request);
  records.push({ id: 'other', version: 1, label: '另一条异常' });
  initiallySelected.value = ['row'];
  await flushPromises();
  await button(wrapper, '补齐阅读译文').trigger('click');
  await flushPromises();
  await button(wrapper, '生成缺失译文').trigger('click');
  await flushPromises();
  await button(wrapper, '关闭').trigger('click');
  initiallySelected.value = ['other'];
  state = 'READY';
  await flushPromises();
  await button(wrapper, '补齐阅读译文').trigger('click');
  await flushPromises();
  expect(request.mock.lastCall?.[0].body).toMatchObject({ ids: ['other'] });
  expect(panel.emitted('updated')).toBeUndefined();
});

it('withdraws the old dialog and ignores a late generation response after its parent scope changes', async () => {
  let finish!: (value: BusinessContentGenerationBatchResult) => void;
  const request = vi.fn(async (entry: HttpRequestOptions) =>
    entry.path.endsWith('/status')
      ? result('MISSING')
      : new Promise<BusinessContentGenerationBatchResult>((done) => {
          finish = done;
        }),
  );
  const { wrapper, panel, status } = host(request, {
    moduleAlias: 'quality.exception',
    parentId: 'old-parent',
    relationCode: 'lines',
  });
  await button(wrapper, '补齐阅读译文').trigger('click');
  await flushPromises();
  await button(wrapper, '生成缺失译文').trigger('click');
  await flushPromises();
  status.generationScope = {
    moduleAlias: 'quality.exception',
    parentId: 'new-parent',
    relationCode: 'lines',
  };
  await flushPromises();
  finish(result('READY'));
  await flushPromises();
  expect(wrapper.find('[data-generation]').exists()).toBe(false);
  expect(panel.emitted('updated')).toBeUndefined();
});

it('requires a bounded explicit subset when the visible page has more than fifty records', async () => {
  const request = vi.fn().mockResolvedValue(result('MISSING', ['row0']));
  const { wrapper, records } = host(request);
  records.splice(
    0,
    1,
    ...Array.from({ length: 51 }, (_, id) => ({ id: 'row' + id, version: 1, label: '业务 ' + id })),
  );
  await button(wrapper, '补齐阅读译文').trigger('click');
  await flushPromises();
  expect(request).not.toHaveBeenCalled();
  await wrapper.find('input[type="checkbox"]').setValue(true);
  await flushPromises();
  expect(request.mock.calls[0][0].body).toMatchObject({ ids: ['row0'], language: 'de' });
  expect(generated(request)).toHaveLength(0);
});

it('does not expose a generation action when the page source belongs to a different module', async () => {
  const request = vi.fn();
  const { wrapper } = host(request, { moduleAlias: 'other.module' });
  await flushPromises();
  expect(wrapper.findAll('button')).toHaveLength(0);
  expect(request).not.toHaveBeenCalled();
});
