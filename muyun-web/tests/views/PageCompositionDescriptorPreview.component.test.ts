import { mount, shallowMount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import PageCompositionDescriptorPreview from '@/views/PageCompositionDescriptorPreview.vue';
import type { ResolvedDetailRelationDescriptor, ResolvedModuleUiDescriptor } from '@/web-contracts/index.ts';

it('uses the standard list cell semantic component for descriptor list previews', () => {
  const list = shallowMount(PageCompositionDescriptorPreview, {
    props: { descriptor: descriptor(), moduleAlias: 'platform.module', mode: 'list' },
    global: {
      stubs: {
        UiDataTable: {
          props: ['columns', 'rows'],
          template: `
            <div>
              <template v-for="row in rows" :key="row.id">
                <template v-for="column in columns" :key="column.key">
                  <slot name="cell" :record="row" :column="column" />
                </template>
              </template>
            </div>
          `,
        },
      },
    },
  });

  expect(list.findAllComponents({ name: 'RecordQueryListCell' })).toHaveLength(2);
  expect(list.findComponent({ name: 'UiInput' }).props()).toMatchObject({
    value: '',
    placeholder: '搜索验收记录',
    disabled: true,
  });
});

it('supports list keyboard actions for field inspection and configuration', async () => {
  const list = mount(PageCompositionDescriptorPreview, {
    props: { descriptor: descriptor(), moduleAlias: 'platform.module', mode: 'list' },
    global: { stubs: { UiDataTable: tableStub } },
  });
  await list.get('button').trigger('keydown', { key: ' ' });
  expect(list.emitted('configureField')).toEqual([['list', 'enabled']]);
});

it('exposes the active preview mode as an external metadata drop target', async () => {
  const wrapper = mount(PageCompositionDescriptorPreview, {
    props: {
      descriptor: descriptor(),
      moduleAlias: 'platform.module',
      mode: 'list',
      acceptExternalDrop: true,
    },
    global: { stubs: { UiDataTable: tableStub } },
  });
  const dataTransfer = { dropEffect: 'none' } as unknown as DataTransfer;
  const preview = wrapper.get('[data-testid="page-composer-list-preview"]');

  await preview.trigger('dragover', { dataTransfer });
  await preview.trigger('drop', { dataTransfer });

  expect(wrapper.emitted('metadata-drop')).toHaveLength(1);
  expect(wrapper.emitted('metadata-drop')?.[0]?.[0]).toBe('list');
});

it('animates stable list fields into their new descriptor order', async () => {
  const animate = vi.fn();
  const originalAnimate = HTMLElement.prototype.animate;
  const originalRect = HTMLElement.prototype.getBoundingClientRect;
  const originalFrame = window.requestAnimationFrame;
  const callbacks: FrameRequestCallback[] = [];
  const positions = new Map<string, number>([
    ['list:header:enabled', 0],
    ['list:header:tags', 120],
    ['list:field:enabled', 0],
    ['list:field:tags', 120],
  ]);
  HTMLElement.prototype.animate = animate as typeof HTMLElement.prototype.animate;
  HTMLElement.prototype.getBoundingClientRect = function () {
    const key = this.dataset.pageCompositionLayoutKey;
    const left = key ? (positions.get(key) ?? 0) : 0;
    return { x: left, y: 0, top: 0, left, right: left + 100, bottom: 24, width: 100, height: 24 } as DOMRect;
  };
  window.requestAnimationFrame = ((callback: FrameRequestCallback) => {
    callbacks.push(callback);
    return callbacks.length;
  }) as typeof window.requestAnimationFrame;
  try {
    const wrapper = mount(PageCompositionDescriptorPreview, {
      props: { descriptor: descriptor(), moduleAlias: 'platform.module', mode: 'list' },
      global: { stubs: { UiDataTable: tableStub } },
    });

    await wrapper.setProps({ descriptor: descriptorWithReorderedList() });
    positions.set('list:header:enabled', 120);
    positions.set('list:header:tags', 0);
    positions.set('list:field:enabled', 120);
    positions.set('list:field:tags', 0);
    callbacks.splice(0).forEach((callback) => callback(0));

    expect(animate).toHaveBeenCalledWith(
      expect.arrayContaining([expect.objectContaining({ transform: 'translate(-120px, 0px)' })]),
      expect.objectContaining({ duration: 300 }),
    );
  } finally {
    HTMLElement.prototype.animate = originalAnimate;
    HTMLElement.prototype.getBoundingClientRect = originalRect;
    window.requestAnimationFrame = originalFrame;
  }
});

it('falls back to CSS transforms when the browser has no Web Animations API', async () => {
  const originalAnimate = HTMLElement.prototype.animate;
  const originalRect = HTMLElement.prototype.getBoundingClientRect;
  const originalFrame = window.requestAnimationFrame;
  const callbacks: FrameRequestCallback[] = [];
  const positions = new Map<string, number>([
    ['list:header:enabled', 0],
    ['list:header:tags', 120],
    ['list:field:enabled', 0],
    ['list:field:tags', 120],
  ]);
  HTMLElement.prototype.animate = undefined as unknown as typeof HTMLElement.prototype.animate;
  HTMLElement.prototype.getBoundingClientRect = function () {
    const key = this.dataset.pageCompositionLayoutKey;
    const left = key ? (positions.get(key) ?? 0) : 0;
    return { x: left, y: 0, top: 0, left, right: left + 100, bottom: 24, width: 100, height: 24 } as DOMRect;
  };
  window.requestAnimationFrame = ((callback: FrameRequestCallback) => {
    callbacks.push(callback);
    return callbacks.length;
  }) as typeof window.requestAnimationFrame;
  try {
    const wrapper = mount(PageCompositionDescriptorPreview, {
      props: { descriptor: descriptor(), moduleAlias: 'platform.module', mode: 'list' },
      global: { stubs: { UiDataTable: tableStub } },
    });

    await wrapper.setProps({ descriptor: descriptorWithReorderedList() });
    positions.set('list:header:enabled', 120);
    positions.set('list:header:tags', 0);
    positions.set('list:field:enabled', 120);
    positions.set('list:field:tags', 0);
    callbacks.splice(0).forEach((callback) => callback(0));

    expect(
      wrapper.get('[data-page-composition-layout-key="list:field:enabled"]').attributes('style'),
    ).toContain('transform');
  } finally {
    HTMLElement.prototype.animate = originalAnimate;
    HTMLElement.prototype.getBoundingClientRect = originalRect;
    window.requestAnimationFrame = originalFrame;
  }
});

it('renders the descriptor-owned quick-search placeholder as a disabled template control', () => {
  const wrapper = shallowMount(PageCompositionDescriptorPreview, {
    props: { descriptor: descriptor(), moduleAlias: 'platform.module', mode: 'list' },
  });

  const quickSearch = wrapper.getComponent({ name: 'UiInput' });
  expect(quickSearch.props('placeholder')).toBe('搜索验收记录');
  expect(quickSearch.props('disabled')).toBe(true);
});

it('renders the same runtime form field renderer for the editable state', () => {
  const wrapper = shallowMount(PageCompositionDescriptorPreview, {
    props: { descriptor: descriptorWithEditor(), moduleAlias: 'platform.module', mode: 'edit' },
  });

  expect(wrapper.findComponent({ name: 'RecordFormFields' }).props('fieldNames')).toEqual(['title']);
});

it('preserves every server-resolved FormGroup in the editable preview', () => {
  const wrapper = shallowMount(PageCompositionDescriptorPreview, {
    props: { descriptor: descriptorWithTwoGroups(), moduleAlias: 'platform.module', mode: 'edit' },
  });

  const form = wrapper.findComponent({ name: 'RecordFormFields' });
  const fields = form.props('fields') as Map<string, { formGroup?: { groupCode: string } }>;
  expect(form.props('fieldNames')).toEqual(['examDate', 'subject']);
  expect(fields.get('examDate')?.formGroup?.groupCode).toBe('basic');
  expect(fields.get('subject')?.formGroup?.groupCode).toBe('subject');
});

it('renders a detail relation projection as a standard descriptor-driven table', () => {
  const wrapper = shallowMount(PageCompositionDescriptorPreview, {
    props: { descriptor: descriptorWithRelation(), moduleAlias: 'platform.module', mode: 'detail' },
  });

  const relationTable = wrapper.findComponent({ name: 'UiDataTable' });
  expect(relationTable.props('columns')).toEqual([
    { key: 'studentNo', title: '学号' },
    { key: 'studentName', title: '学生姓名' },
    { key: 'score', title: '成绩', align: 'right' },
  ]);
  expect(relationTable.props('rows')).toMatchObject([
    { studentNo: '示例学号', studentName: '示例学生姓名', score: 96 },
  ]);
});

it('renders an editable local child-table preview from the server-resolved projection', async () => {
  const wrapper = mount(PageCompositionDescriptorPreview, {
    props: { descriptor: descriptorWithRelation(), moduleAlias: 'platform.module', mode: 'edit' },
    global: { stubs: { UiDataTable: tableStub } },
  });

  expect(wrapper.text()).toContain('参考学生');
  expect(wrapper.findComponent({ name: 'UiDataTable' }).props('columns')).toEqual([
    { key: 'studentNo', title: '学号' },
    { key: 'studentName', title: '学生姓名' },
    { key: 'score', title: '成绩', align: 'right' },
  ]);
  const studentNo = wrapper.get('input[aria-label="参考学生：学号"]');
  await studentNo.setValue('20260001');
  expect((studentNo.element as HTMLInputElement).value).toBe('20260001');
  expect(wrapper.text()).toContain('可直接编辑示例值以检查编辑态');
});

const tableStub = {
  name: 'UiDataTable',
  props: ['columns', 'rows'],
  template: `
    <div>
      <template v-for="column in columns" :key="\`header:\${column.key}\`">
        <slot name="header" :column="column" />
      </template>
      <template v-for="row in rows" :key="row.id">
        <template v-for="column in columns" :key="column.key">
          <slot name="cell" :record="row" :column="column" />
        </template>
      </template>
    </div>
  `,
};

function descriptor(): ResolvedModuleUiDescriptor {
  return {
    schemaVersion: '1',
    moduleAlias: 'platform.module',
    page: {
      template: 'FLAT_MANAGEMENT',
      list: {
        searchPlaceholder: '搜索验收记录',
        fields: {
          viewCode: 'list',
          viewKind: 'LIST',
          fields: [
            { fieldRef: { fieldName: 'enabled' }, label: '启用状态', uiType: 'enabledStatus' },
            { fieldRef: { fieldName: 'tags' }, label: '标签', uiType: 'tagList' },
          ],
        },
      },
      detail: { emptyDescription: '暂无详情', createTitle: '新建' },
      traits: [],
    },
  };
}

function descriptorWithReorderedList(): ResolvedModuleUiDescriptor {
  const value = descriptor();
  return {
    ...value,
    page: {
      ...value.page!,
      list: {
        ...value.page!.list!,
        fields: {
          ...value.page!.list!.fields,
          fields: [...value.page!.list!.fields.fields].reverse(),
        },
      },
    },
  };
}

function descriptorWithEditor(): ResolvedModuleUiDescriptor {
  const value = descriptor();
  return {
    ...value,
    page: {
      ...value.page!,
      detail: {
        ...value.page!.detail,
        editor: {
          viewCode: 'editor',
          viewKind: 'FORM',
          fields: [{ fieldRef: { fieldName: 'title' }, label: '考试名称', uiType: 'input' }],
        },
      },
    },
  };
}

function descriptorWithTwoGroups(): ResolvedModuleUiDescriptor {
  const value = descriptor();
  return {
    ...value,
    page: {
      ...value.page!,
      detail: {
        ...value.page!.detail,
        editor: {
          viewCode: 'editor',
          viewKind: 'FORM',
          fields: [
            { fieldRef: { fieldName: 'examDate' }, label: '考试日期', uiType: 'datePicker' },
            { fieldRef: { fieldName: 'subject' }, label: '科目', uiType: 'input' },
          ],
          formGroups: [
            { groupCode: 'basic', title: '基础信息', fields: [{ fieldName: 'examDate' }] },
            { groupCode: 'subject', title: '科目信息', fields: [{ fieldName: 'subject' }] },
          ],
        },
      },
    },
  };
}

function descriptorWithRelation(): ResolvedModuleUiDescriptor {
  const value = descriptorWithEditor();
  const relation: ResolvedDetailRelationDescriptor = {
    code: 'participants',
    title: '参考学生',
    readOnly: true,
    sourceModuleAlias: 'education.exam',
    sourceEntityAlias: 'exam',
    targetModuleAlias: 'education.exam',
    targetEntityAlias: 'exam_participant',
    parentBinding: 'examId',
    refreshOnDetailReload: true,
    listProjection: {
      fields: [
        { fieldName: 'studentNo', title: '学号' },
        { fieldName: 'studentName', title: '学生姓名' },
        { fieldName: 'score', title: '成绩', align: 'right' },
      ],
    },
  };
  return { ...value, detailRelations: [relation] };
}
