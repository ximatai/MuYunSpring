import { flushPromises, shallowMount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import { configureModuleContext, type HttpClient, type HttpRequestOptions } from '@/web-core';
import MetadataGovernanceSurface from '@/views/MetadataGovernanceSurface.vue';
import { confirmAction } from '@muyun/vue-ui-antdv';

vi.mock('@muyun/vue-ui-antdv', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@muyun/vue-ui-antdv')>()),
  confirmAction: vi.fn(),
}));

const mounted = new Set<ReturnType<typeof shallowMount>>();

afterEach(() => {
  mounted.forEach((wrapper) => wrapper.unmount());
  mounted.clear();
  vi.clearAllMocks();
});

it('keeps main entity capabilities out of the data-model editor', async () => {
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

  expect(wrapper.text()).toContain('＋ 字段');
  expect(wrapper.text()).toContain('＋ 子元数据');
  expect(wrapper.find('[data-testid="capability-checkbox"]').exists()).toBe(false);
  expect(request.mock.calls.map(([options]) => options.path)).not.toContain(
    '/platform.module/education.exam/metadata-model/change-set-preview',
  );
});

it('keeps the field editor open while save confirmation is pending', async () => {
  const confirmation = deferred<boolean>();
  vi.mocked(confirmAction).mockReturnValue(confirmation.promise);
  configureModuleContext({ http: fakeHttp() });
  const wrapper = shallowMount(MetadataGovernanceSurface, {
    props: { moduleAlias: 'education.exam' },
    global: { stubs: governanceStubs() },
  });
  mounted.add(wrapper);
  await flushPromises();
  await flushPromises();

  await wrapper.get('[data-testid="model-tree"]').trigger('click');
  await flushPromises();
  const edit = wrapper.findAll('[data-testid="action-button"]').find((button) => button.text() === '编辑');
  await edit?.trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('存储字段规格');

  const save = wrapper.findAll('[data-testid="action-button"]').find((button) => button.text() === '保存');
  await save?.trigger('click');
  await flushPromises();

  expect(vi.mocked(confirmAction)).toHaveBeenCalledTimes(1);
  expect(wrapper.text()).toContain('存储字段规格');
  confirmation.resolve(false);
  await flushPromises();
  expect(wrapper.text()).toContain('存储字段规格');
});

it('keeps the edited field selected after a successful save refresh', async () => {
  vi.mocked(confirmAction).mockResolvedValue(true);
  configureModuleContext({ http: fakeHttp() });
  const wrapper = shallowMount(MetadataGovernanceSurface, {
    props: { moduleAlias: 'education.exam' },
    global: { stubs: governanceStubs() },
  });
  mounted.add(wrapper);
  await flushPromises();
  await flushPromises();

  await wrapper.get('[data-testid="model-tree"]').trigger('click');
  await flushPromises();
  const edit = wrapper.findAll('[data-testid="action-button"]').find((button) => button.text() === '编辑');
  await edit?.trigger('click');
  await flushPromises();
  const save = wrapper.findAll('[data-testid="action-button"]').find((button) => button.text() === '保存');
  await save?.trigger('click');
  await flushPromises();
  await flushPromises();

  expect(wrapper.text()).toContain('编辑');
  expect(wrapper.text()).not.toContain('＋ 字段');
});

function governanceStubs() {
  return {
    ManagementWorkspace: { template: '<section><slot /></section>' },
    ManagementExplorerColumn: { template: '<section><slot /></section>' },
    RecordExplorerPanel: { template: '<section><slot /></section>' },
    RecordDetailPanel: {
      template: '<section><slot name="status" /><slot /><slot name="actions" /></section>',
    },
    RecordFormGrid: { template: '<form><slot /></form>' },
    UiTree: {
      props: { nodes: Array },
      emits: ['select'],
      template:
        '<button data-testid="model-tree" @click="$emit(\'select\', nodes[0]?.children?.[0])"><slot /></button>',
    },
    UiActionButton: {
      emits: ['click'],
      template: '<button data-testid="action-button" @click="$emit(\'click\')"><slot /></button>',
    },
  };
}

function fakeHttp(): HttpClient {
  return {
    request: <T>(request: HttpRequestOptions) => Promise.resolve(responseFor(request) as T),
  };
}

function responseFor(options: HttpRequestOptions) {
  if (options.path === '/platform.module/platform.module/context')
    return { moduleAlias: 'platform.module', capabilities: [], actions: [] };
  if (options.path === '/platform.module/education.exam/metadata-relations/query')
    return {
      records: [
        { id: 'rel-main', metadataId: 'meta-main', relationRole: 'MAIN' },
        { id: 'rel-child', metadataId: 'meta-child', relationRole: 'CHILD', parentMetadataId: 'meta-main' },
      ],
      pages: 1,
      totalKnown: true,
    };
  if (options.path === '/platform.metadata/view/meta-main')
    return { id: 'meta-main', alias: 'exam', title: '考试', version: 3 };
  if (options.path === '/platform.metadata/view/meta-child')
    return { id: 'meta-child', alias: 'exam_student', title: '参考学生', version: 2 };
  if (options.path === '/platform.metadata/meta-main/fields/query')
    return {
      records: [
        {
          id: 'title',
          fieldName: 'title',
          columnName: 'title',
          fieldSpecAlias: 'string',
          title: '考试名称',
          fieldOwnership: 'BUSINESS',
          fieldForm: 'PHYSICAL',
        },
      ],
      pages: 1,
      totalKnown: true,
    };
  if (options.path === '/platform.metadata/meta-child/fields/query')
    return {
      records: [
        {
          id: 'student',
          fieldName: 'studentId',
          title: '学生',
          fieldOwnership: 'BUSINESS',
          fieldForm: 'PHYSICAL',
        },
      ],
      pages: 1,
      totalKnown: true,
    };
  if (options.path.endsWith('/field-properties')) return [];
  if (options.path.endsWith('/record-count')) return { relationId: 'rel-main', recordCount: 0 };
  if (options.path.endsWith('/capabilities'))
    return {
      systemFields: [],
      capabilities: [
        {
          capability: 'TREE',
          enabled: false,
          configurable: true,
          reason: options.path.includes('rel-child') ? '子实体不能启用该模块保留能力。' : '可由主实体声明。',
          fieldContributions: [],
          defaultKind: 'RUNTIME',
          defaultDescription: '',
        },
      ],
    };
  if (options.path === '/platform.field_spec/query') return { records: [], pages: 1, totalKnown: true };
  if (options.path.endsWith('/metadata-model/change-set-preview'))
    return {
      errors: [],
      fieldImpacts: [{ operation: 'UPDATE', fieldName: 'title', columnName: 'title' }],
      schemaImpacts: [],
      orderImpacts: [],
      proposalFingerprint: 'fingerprint',
    };
  if (options.path.endsWith('/metadata-model/change-set-apply')) return {};
  throw new Error(`Unexpected request: ${options.path}`);
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((next) => {
    resolve = next;
  });
  return { promise, resolve };
}
