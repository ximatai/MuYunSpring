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

it('loads the target field catalog and selects its declared defaults for a new module reference', async () => {
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

  await wrapper
    .findAll('[data-testid="action-button"]')
    .find((item) => item.text().includes('编辑数据模型'))
    ?.trigger('click');
  await wrapper
    .findAll('[data-testid="action-button"]')
    .find((item) => item.text().includes('新增模块引用'))
    ?.trigger('click');
  await flushPromises();

  const inputs = wrapper.findAll('[data-testid="governance-input"]');
  await inputs.at(4)?.setValue('education.student');
  await flushPromises();
  await flushPromises();

  const catalogRequest = request.mock.calls
    .map(([options]) => options)
    .find((options) => options.path.includes('/reference-target-field-catalog?'));
  expect(catalogRequest).toMatchObject({
    method: 'GET',
    path: '/platform.module/education.exam/metadata-relations/rel-main/reference-target-field-catalog?targetModuleAlias=education.student',
  });
  expect(wrapper.text()).toContain('ID（id）');
  expect(wrapper.text()).toContain('标题（title）');
  expect(wrapper.text()).toContain('metadata-student');
  const selectors = wrapper.findAll('[data-testid="governance-select"]');
  expect((selectors.at(0)?.element as HTMLSelectElement | undefined)?.value).toBe('id');
  expect((selectors.at(1)?.element as HTMLSelectElement | undefined)?.value).toBe('title');
  expect(
    request.mock.calls.some(([options]) => options.path.includes('targetMetadataId=metadata-student')),
  ).toBe(true);
  expect(wrapper.text()).toContain('本期模块引用仅支持单选。');
  expect(wrapper.text()).not.toContain('多选');
});

it('clears the resolved metadata binding before loading a different reference target', async () => {
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

  await wrapper
    .findAll('[data-testid="action-button"]')
    .find((item) => item.text().includes('编辑数据模型'))
    ?.trigger('click');
  await wrapper
    .findAll('[data-testid="action-button"]')
    .find((item) => item.text().includes('新增模块引用'))
    ?.trigger('click');

  const targetModuleInput = wrapper.findAll('[data-testid="governance-input"]').at(4);
  await targetModuleInput?.setValue('education.student');
  await flushPromises();
  await flushPromises();
  expect(wrapper.text()).toContain('metadata-student');

  await targetModuleInput?.setValue('education.teacher');
  await flushPromises();
  await flushPromises();

  const teacherCatalogRequests = request.mock.calls
    .map(([options]) => options.path)
    .filter((path) => path.includes('targetModuleAlias=education.teacher'));
  expect(teacherCatalogRequests[0]).toBe(
    '/platform.module/education.exam/metadata-relations/rel-main/reference-target-field-catalog?targetModuleAlias=education.teacher',
  );
  expect(teacherCatalogRequests).not.toContainEqual(expect.stringContaining('metadata-student'));
  expect(wrapper.text()).toContain('metadata-teacher');
  expect(wrapper.text()).not.toContain('metadata-student');
});

it('keeps the current target catalog when an earlier target request fails late', async () => {
  let rejectStudentCatalog!: (cause: unknown) => void;
  const studentCatalog = new Promise<never>((_resolve, reject) => {
    rejectStudentCatalog = reject;
  });
  const http: HttpClient = {
    request: <T>(options: HttpRequestOptions) => {
      if (
        options.path.includes('targetModuleAlias=education.student') &&
        !options.path.includes('targetMetadataId=')
      ) {
        return studentCatalog as Promise<T>;
      }
      return Promise.resolve(responseFor(options) as T);
    },
  };
  configureModuleContext({ http });
  const wrapper = shallowMount(MetadataGovernanceSurface, {
    props: { moduleAlias: 'education.exam' },
    global: { stubs: governanceStubs() },
  });
  mounted.add(wrapper);
  await flushPromises();
  await flushPromises();

  await wrapper
    .findAll('[data-testid="action-button"]')
    .find((item) => item.text().includes('编辑数据模型'))
    ?.trigger('click');
  await wrapper
    .findAll('[data-testid="action-button"]')
    .find((item) => item.text().includes('新增模块引用'))
    ?.trigger('click');

  const targetModuleInput = wrapper.findAll('[data-testid="governance-input"]').at(4);
  await targetModuleInput?.setValue('education.student');
  await flushPromises();
  await targetModuleInput?.setValue('education.teacher');
  await flushPromises();
  await flushPromises();
  expect(wrapper.text()).toContain('metadata-teacher');

  rejectStudentCatalog(new Error('student catalog failed late'));
  await flushPromises();

  expect(wrapper.text()).toContain('metadata-teacher');
  expect(wrapper.text()).not.toContain('无法加载“education.student”的目标字段目录。');
});

it('makes a failed target-field catalog load visible and blocks the reference selectors', async () => {
  configureModuleContext({ http: fakeHttp('MAIN', true) });
  const wrapper = shallowMount(MetadataGovernanceSurface, {
    props: { moduleAlias: 'education.exam' },
    global: { stubs: governanceStubs() },
  });
  mounted.add(wrapper);
  await flushPromises();
  await flushPromises();
  await wrapper
    .findAll('[data-testid="action-button"]')
    .find((item) => item.text().includes('编辑数据模型'))
    ?.trigger('click');
  await wrapper
    .findAll('[data-testid="action-button"]')
    .find((item) => item.text().includes('新增模块引用'))
    ?.trigger('click');
  await wrapper.findAll('[data-testid="governance-input"]').at(4)?.setValue('education.student');
  await flushPromises();
  await flushPromises();

  expect(wrapper.text()).toContain('无法加载“education.student”的目标字段目录。');
  expect(wrapper.findAll('[data-testid="governance-select"]').at(0)?.attributes('disabled')).toBeDefined();
  expect(wrapper.findAll('[data-testid="governance-select"]').at(1)?.attributes('disabled')).toBeDefined();
});

function responseFor(
  options: HttpRequestOptions,
  relationRole: 'main' | 'child' | 'MAIN' | 'CHILD' = 'MAIN',
  failReferenceTargetCatalog = false,
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
  if (options.path === '/platform.module/education.exam/metadata-relations/rel-main/field-properties') {
    return [
      {
        fieldId: 'title',
        fieldName: 'title',
        kind: 'BASIC',
      },
    ];
  }
  if (
    options.path.startsWith(
      '/platform.module/education.exam/metadata-relations/rel-main/reference-target-field-catalog?',
    )
  ) {
    if (failReferenceTargetCatalog) throw new Error('catalog unavailable');
    const targetModuleAlias = new URLSearchParams(options.path.split('?')[1]).get('targetModuleAlias') ?? '';
    const targetName = targetModuleAlias.split('.').at(-1);
    return {
      targetModuleAlias,
      targetMetadataId: targetName ? `metadata-${targetName}` : undefined,
      keyFields: [
        { fieldName: 'id', title: 'ID', selectable: true },
        { fieldName: 'studentNo', title: '学号', defaultField: true, selectable: true },
      ],
      labelFields: [
        { fieldName: 'title', title: '标题', selectable: true },
        { fieldName: 'name', title: '姓名', defaultField: true, selectable: true },
      ],
    };
  }
  if (options.path === '/platform.field_spec/query') {
    return { records: [{ id: 'string', alias: 'string', title: '短文本' }], pages: 1, totalKnown: true };
  }
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
    RecordModeDrawer: {
      name: 'RecordModeDrawer',
      props: { open: Boolean },
      template: '<section v-if="open"><slot name="form" /><slot name="operation" /></section>',
    },
    UiInput: {
      name: 'UiInput',
      props: { value: String },
      emits: ['update:value'],
      template:
        '<input data-testid="governance-input" :value="value" @input="$emit(\'update:value\', $event.target.value)" />',
    },
    UiSelect: {
      name: 'UiSelect',
      props: { value: [String, Number], options: Array, disabled: Boolean },
      emits: ['update:value'],
      template:
        '<select data-testid="governance-select" :value="value" :disabled="disabled" @change="$emit(\'update:value\', $event.target.value)"><option v-for="option in options" :key="String(option.value)" :value="option.value">{{ option.label }}</option></select>',
    },
  };
}

function fakeHttp(
  relationRole: 'main' | 'child' | 'MAIN' | 'CHILD' = 'MAIN',
  failReferenceTargetCatalog = false,
): HttpClient {
  return {
    request: <T>(options: HttpRequestOptions) =>
      Promise.resolve(responseFor(options, relationRole, failReferenceTargetCatalog) as T),
  };
}
