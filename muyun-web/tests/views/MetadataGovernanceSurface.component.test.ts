import { flushPromises, shallowMount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import { configureModuleContext, type HttpClient, type HttpRequestOptions } from '@/web-core';
import MetadataGovernanceSurface from '@/views/MetadataGovernanceSurface.vue';

vi.mock('@muyun/vue-ui-antdv', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@muyun/vue-ui-antdv')>()),
  confirmAction: vi.fn().mockResolvedValue(true),
}));

const mounted = new Set<ReturnType<typeof shallowMount>>();

afterEach(() => {
  mounted.forEach((wrapper) => wrapper.unmount());
  mounted.clear();
});

it('holds a main-entity capability choice in the local edit session without a direct request', async () => {
  const http = fakeHttp();
  const request = vi.spyOn(http, 'request');
  configureModuleContext({ http });
  const wrapper = shallowMount(MetadataGovernanceSurface, {
    props: { moduleAlias: 'education.exam' },
    global: { stubs: governanceStubs() },
  });
  mounted.add(wrapper);
  await flushPromises();
  await flushPromises();

  const checkbox = wrapper
    .findAll('[data-testid="capability-checkbox"]')
    .find((item) => item.text().includes('树结构'));
  expect(wrapper.text()).toContain('树结构');
  expect(checkbox?.attributes('data-disabled')).toBe('true');

  const editButton = wrapper
    .findAll('[data-testid="action-button"]')
    .find((item) => item.text().includes('编辑数据模型'));
  await editButton?.trigger('click');
  await flushPromises();

  const editableCheckbox = wrapper
    .findAll('[data-testid="capability-checkbox"]')
    .find((item) => item.text().includes('树结构'));
  expect(editableCheckbox?.attributes('data-disabled')).toBe('false');

  await editableCheckbox?.trigger('click');
  await flushPromises();

  expect(request.mock.calls.some(([options]) => options.path.includes('capability-change'))).toBe(false);
  expect(
    wrapper
      .findAll('[data-testid="capability-checkbox"]')
      .find((item) => item.text().includes('树结构'))
      ?.attributes('data-checked'),
  ).toBe('true');
  expect(wrapper.text()).toContain('改动仅保存在当前草稿，尚未写入数据模型。');

  const cancelButton = wrapper
    .findAll('[data-testid="action-button"]')
    .find((item) => item.text().includes('取消编辑'));
  await cancelButton?.trigger('click');
  await flushPromises();

  const readonlyCheckbox = wrapper
    .findAll('[data-testid="capability-checkbox"]')
    .find((item) => item.text().includes('树结构'));
  expect(readonlyCheckbox?.attributes('data-disabled')).toBe('true');
  expect(readonlyCheckbox?.attributes('data-checked')).toBe('false');
  expect(wrapper.text()).not.toContain('改动仅保存在当前草稿，尚未写入数据模型。');
});

it('keeps child capabilities non-interactive and exposes the backend restriction reason', async () => {
  configureModuleContext({ http: fakeHttp('CHILD') });
  const wrapper = shallowMount(MetadataGovernanceSurface, {
    props: { moduleAlias: 'education.exam' },
    global: { stubs: governanceStubs() },
  });
  mounted.add(wrapper);
  await flushPromises();
  await flushPromises();

  const checkbox = wrapper
    .findAll('[data-testid="capability-checkbox"]')
    .find((item) => item.text().includes('树结构'));
  expect(checkbox?.attributes('data-disabled')).toBe('true');
  expect(wrapper.text()).toContain('子实体不能启用该模块保留能力。');
});

it('treats the runtime lower-case main role as editable', async () => {
  configureModuleContext({ http: fakeHttp('main') });
  const wrapper = shallowMount(MetadataGovernanceSurface, {
    props: { moduleAlias: 'education.exam' },
    global: { stubs: governanceStubs() },
  });
  mounted.add(wrapper);
  await flushPromises();
  await flushPromises();

  const editButton = wrapper
    .findAll('[data-testid="action-button"]')
    .find((item) => item.text().includes('编辑数据模型'));
  await editButton?.trigger('click');
  await flushPromises();

  const checkbox = wrapper
    .findAll('[data-testid="capability-checkbox"]')
    .find((item) => item.text().includes('树结构'));
  expect(checkbox?.attributes('data-disabled')).toBe('false');
});

it('previews then confirms and publishes the same fingerprinted proposal', async () => {
  const http = fakeHttp();
  const request = vi.spyOn(http, 'request');
  configureModuleContext({ http });
  const wrapper = shallowMount(MetadataGovernanceSurface, {
    props: { moduleAlias: 'education.exam' },
    global: { stubs: governanceStubs() },
  });
  mounted.add(wrapper);
  await flushPromises();
  await flushPromises();

  const editButton = wrapper
    .findAll('[data-testid="action-button"]')
    .find((item) => item.text().includes('编辑数据模型'));
  await editButton?.trigger('click');
  const tree = wrapper
    .findAll('[data-testid="capability-checkbox"]')
    .find((item) => item.text().includes('树结构'));
  await tree?.trigger('click');
  await flushPromises();

  const publishButton = wrapper
    .findAll('[data-testid="action-button"]')
    .find((item) => item.text().includes('预检并发布'));
  await publishButton?.trigger('click');
  await flushPromises();
  await flushPromises();

  const previewRequest = request.mock.calls
    .map(([options]) => options)
    .find((options) => options.path.endsWith('/change-set-preview'));
  const applyRequest = request.mock.calls
    .map(([options]) => options)
    .find((options) => options.path.endsWith('/change-set-apply'));
  expect(previewRequest?.method).toBe('POST');
  expect(applyRequest).toMatchObject({
    method: 'POST',
    body: { proposal: previewRequest?.body, proposalFingerprint: 'preview-fingerprint' },
  });
});

function responseFor(
  options: HttpRequestOptions,
  relationRole: 'main' | 'child' | 'MAIN' | 'CHILD' = 'MAIN',
) {
  if (options.path === '/platform.module/platform.module/context') {
    return { moduleAlias: 'platform.module', capabilities: [], actions: [] };
  }
  if (options.path === '/platform.module/education.exam/metadata-relations/query') {
    return {
      records: [{ id: 'rel-main', metadataId: 'meta-main', relationRole }],
      pages: 1,
      totalKnown: true,
    };
  }
  if (options.path === '/platform.metadata/view/meta-main') {
    return { id: 'meta-main', alias: 'exam', title: '考试' };
  }
  if (options.path === '/platform.metadata/meta-main/fields/query') {
    return {
      records: [
        { id: 'title', fieldName: 'title', title: '标题', fieldOwnership: 'BUSINESS', fieldForm: 'PHYSICAL' },
      ],
      pages: 1,
      totalKnown: true,
    };
  }
  if (options.path === '/platform.field_spec/query') return { records: [], pages: 1, totalKnown: true };
  if (options.path === '/platform.module/education.exam/metadata-relations/rel-main/capabilities') {
    return {
      capabilities: [
        {
          capability: 'TREE',
          enabled: false,
          configurable: true,
          reason:
            relationRole === 'CHILD' || relationRole === 'child'
              ? '子实体不能启用该模块保留能力。'
              : '能力由已保存字段事实推导。',
          fieldContributions: ['parentId'],
          defaultKind: 'RUNTIME',
          defaultDescription: '运行态写入根节点。',
        },
      ],
      systemFields: [],
    };
  }
  if (options.path.endsWith('/change-set-preview')) {
    return { errors: [], fieldImpacts: [], schemaImpacts: [], proposalFingerprint: 'preview-fingerprint' };
  }
  if (options.path.endsWith('/change-set-apply')) return {};
  throw new Error(`Unexpected request: ${options.path}`);
}

function governanceStubs() {
  return {
    RecordDetailPanel: {
      name: 'RecordDetailPanel',
      template: '<section><slot /><slot name="actions" /></section>',
    },
    UiActionButton: {
      name: 'UiActionButton',
      emits: ['click'],
      template: '<button data-testid="action-button" @click="$emit(\'click\')"><slot /></button>',
    },
    UiCheckbox: {
      name: 'UiCheckbox',
      props: { checked: Boolean, disabled: Boolean },
      emits: ['change'],
      template:
        '<button data-testid="capability-checkbox" :data-disabled="String(disabled)" :data-checked="String(checked)" @click="$emit(\'change\', !checked)"><slot /></button>',
    },
  };
}

function fakeHttp(relationRole: 'main' | 'child' | 'MAIN' | 'CHILD' = 'MAIN'): HttpClient {
  return {
    request: <T>(options: HttpRequestOptions) => Promise.resolve(responseFor(options, relationRole) as T),
  };
}
