import { config, mount, shallowMount } from '@vue/test-utils';
import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import UiTree from '@/vue-ui-antdv/components/UiTree.vue';
import PageCompositionDescriptorPreview from '@/views/PageCompositionDescriptorPreview.vue';
import { PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE } from '@/views/pageCompositionDragPayload';
import type { ResolvedDetailRelationDescriptor, ResolvedModuleUiDescriptor } from '@/web-contracts/index.ts';

const originalStubs = config.global.stubs;
beforeEach(() => {
  config.global.stubs = {
    ...originalStubs,
    RecordFormGrid: false,
    RecordDetailExtensionSection: false,
    RecordRelationTable: false,
    RecordQueryListSurface: false,
    ManagementPanelHeader: false,
    UiSearchInput: false,
  };
});
afterEach(() => {
  config.global.stubs = originalStubs;
});

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
  expect(list.findComponent({ name: 'UiSearchInput' }).props()).toMatchObject({
    value: '',
    placeholder: '搜索验收记录',
    disabled: true,
  });
  expect(list.findComponent({ name: 'RecordQueryListSurface' }).props()).toMatchObject({
    pageable: true,
    total: 1,
    pageNum: 1,
    pages: 1,
    pageSize: 20,
    pageSizeOptions: [10, 20, 50],
    paginationDisabled: true,
  });
});

it('supports list keyboard actions for field inspection and configuration', async () => {
  const list = mount(PageCompositionDescriptorPreview, {
    attachTo: document.body,
    props: { descriptor: descriptor(), moduleAlias: 'platform.module', mode: 'list' },
    global: { stubs: { UiDataTable: tableStub } },
  });
  await list.get('.page-composition-descriptor-preview__field').trigger('keydown', { key: ' ' });
  expect(list.emitted('configureField')).toEqual([['list', 'enabled']]);
});

it('exposes the active preview mode as an external metadata drop target', async () => {
  const emptyList = descriptor();
  emptyList.page!.list!.fields = { ...emptyList.page!.list!.fields, fields: [] };
  const wrapper = mount(PageCompositionDescriptorPreview, {
    attachTo: document.body,
    props: {
      descriptor: emptyList,
      moduleAlias: 'platform.module',
      mode: 'list',
      acceptExternalDrop: true,
    },
    global: { stubs: { UiDataTable: tableStub } },
  });
  const source = mount(UiTree, {
    global: { stubs: { UiDataTable: tableStub } },
    attachTo: document.body,
    props: {
      nodes: [{ key: 'field', title: 'Field' }],
      draggable: true,
      dragOperations: ['copy'],
      dragPayloadType: PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE,
      dragPayloadOf: () => ({ kind: 'field', fieldId: 'field' }),
    },
  });
  try {
    await source.get('[data-ui-tree-key]').trigger('mousedown', { button: 0 });
    await wrapper.vm.$nextTick();
    const preview = wrapper.get('[data-composer-target="list:empty"]');
    await preview.trigger('mousemove', { buttons: 1, clientX: 30, clientY: 30 });
    await preview.trigger('mouseup', { clientX: 30, clientY: 30 });
    expect(wrapper.emitted('placement-drop')).toHaveLength(1);
    expect(wrapper.emitted('placement-drop')?.[0]?.[1]).toEqual({
      container: { kind: 'list' },
      position: 'inside',
    });
  } finally {
    source.unmount();
    wrapper.unmount();
  }
});

it('renders an external metadata field inline before it is dropped into an existing form grid', async () => {
  const preview = mount(PageCompositionDescriptorPreview, {
    attachTo: document.body,
    props: {
      descriptor: descriptorWithEditor(),
      moduleAlias: 'platform.module',
      mode: 'edit',
      acceptExternalDrop: true,
      structure: {
        list: [],
        form: [{ id: 'title-id', fieldName: 'title', title: '考试名称' }],
        groups: [],
        relations: [],
      },
    },
  });
  const source = mount(UiTree, {
    attachTo: document.body,
    props: {
      nodes: [{ key: 'reviewDate', title: '复核日期' }],
      draggable: true,
      dragOperations: ['copy'],
      dragPayloadType: PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE,
      dragPayloadOf: () => ({
        kind: 'field',
        fieldId: 'review-date-id',
        fieldName: 'reviewDate',
        title: '复核日期',
        fieldSpecAlias: 'date',
      }),
    },
  });
  const target = preview.get('[data-page-composition-layout-key="edit:field:title"]');
  const original = document.elementFromPoint;
  const originalRect = HTMLElement.prototype.getBoundingClientRect;
  Object.defineProperty(document, 'elementFromPoint', { configurable: true, value: () => target.element });
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    if (this === target.element)
      return { x: 0, y: 0, left: 0, right: 180, top: 0, bottom: 60, width: 180, height: 60 } as DOMRect;
    return originalRect.call(this);
  });
  try {
    await source
      .get('[data-ui-tree-key="reviewDate"]')
      .trigger('mousedown', { button: 0, clientX: 0, clientY: 0 });
    await target.trigger('mousemove', { buttons: 1, clientX: 100, clientY: 30 });
    await preview.vm.$nextTick();

    expect(
      preview.get('.record-form-field-host:has(.page-composer-external-field-preview--dragging)').text(),
    ).toContain('复核日期');
    expect(preview.get('input[type="date"]').attributes('type')).toBe('date');
    expect(preview.findAll('.record-form-field').map((field) => field.text())).toEqual([
      expect.stringContaining('复核日期'),
      expect.stringContaining('考试名称'),
    ]);

    await preview.setProps({ placementCommitFailed: true });
    expect(preview.find('.page-composer-external-field-preview--dragging').exists()).toBe(false);

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await preview.vm.$nextTick();
    expect(preview.find('.page-composer-external-field-preview--dragging').exists()).toBe(false);
  } finally {
    Object.defineProperty(document, 'elementFromPoint', { configurable: true, value: original });
    vi.restoreAllMocks();
    source.unmount();
    preview.unmount();
  }
});

it('ignores unrelated external drags', async () => {
  const wrapper = mount(PageCompositionDescriptorPreview, {
    attachTo: document.body,
    props: {
      descriptor: descriptor(),
      moduleAlias: 'platform.module',
      mode: 'list',
      acceptExternalDrop: true,
    },
    global: { stubs: { UiDataTable: tableStub } },
  });
  const source = mount(UiTree, {
    global: { stubs: { UiDataTable: tableStub } },
    attachTo: document.body,
    props: {
      nodes: [{ key: 'unrelated', title: 'Other' }],
      draggable: true,
      dragOperations: ['copy'],
      dragPayloadType: 'text/plain',
      dragPayloadOf: () => ({ text: 'not metadata' }),
    },
  });
  await source.get('[data-ui-tree-key]').trigger('mousedown', { button: 0 });
  await wrapper.vm.$nextTick();

  expect(wrapper.emitted('placement-drop')).toBeUndefined();
  expect(wrapper.find('[data-composer-target="list:end"]').exists()).toBe(false);
});

it('uses each action item as a before-or-after drop target instead of treating the whole detail bar as one target', async () => {
  const preview = mount(PageCompositionDescriptorPreview, {
    attachTo: document.body,
    props: {
      descriptor: descriptor(),
      moduleAlias: 'platform.module',
      mode: 'detail',
      acceptExternalDrop: true,
      actionPlacements: [
        { actionCode: 'delete', anchor: 'detail' },
        { actionCode: 'update', anchor: 'detail' },
      ],
      moduleActions: [
        { actionCode: 'delete', title: '删除', actionLevel: 'RECORD', authorized: true },
        { actionCode: 'update', title: '编辑', actionLevel: 'RECORD', authorized: true },
      ],
    },
    global: { stubs: { UiDataTable: tableStub } },
  });
  const source = mount(UiTree, {
    attachTo: document.body,
    props: {
      nodes: [{ key: 'update', title: '编辑' }],
      draggable: true,
      dragOperations: ['copy'],
      dragPayloadType: PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE,
      dragPayloadOf: () => ({ kind: 'action', actionCode: 'update' }),
    },
  });
  const deleteTarget = preview.get('[data-page-action-key="delete"]');
  const original = document.elementFromPoint;
  const originalRect = HTMLElement.prototype.getBoundingClientRect;
  const rectSpy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (
    this: HTMLElement,
  ) {
    if (this.getAttribute('data-page-action-key') === 'delete')
      return {
        x: 0,
        y: 0,
        left: 0,
        right: 100,
        top: 0,
        bottom: 24,
        width: 100,
        height: 24,
        toJSON: () => ({}),
      } as DOMRect;
    return originalRect.call(this);
  });
  Object.defineProperty(document, 'elementFromPoint', {
    configurable: true,
    value: () => preview.get('[data-page-action-key="delete"]').element,
  });
  try {
    await source
      .get('[data-ui-tree-key="update"]')
      .trigger('mousedown', { button: 0, clientX: 0, clientY: 0 });
    await deleteTarget.trigger('mousemove', { buttons: 1, clientX: 40, clientY: 12 });
    await preview.vm.$nextTick();

    expect(preview.get('[data-page-action-key="delete"]').classes()).toContain(
      'page-composition-action-preview__button--drop-before',
    );
    expect(preview.find('.page-composition-action-preview--drop-active').exists()).toBe(false);
    expect(
      preview.findAll('[data-page-action-key]').map((element) => element.attributes('data-page-action-key')),
    ).toEqual(['update', 'delete']);
    expect(preview.find('[data-page-action-key="update"]').classes()).toContain(
      'page-composition-action-preview__button--transient',
    );

    await preview
      .get('[data-page-action-key="delete"]')
      .trigger('mouseup', { button: 0, clientX: 40, clientY: 12 });

    await source
      .get('[data-ui-tree-key="update"]')
      .trigger('mousedown', { button: 0, clientX: 0, clientY: 0 });
    await preview
      .get('[data-page-action-key="delete"]')
      .trigger('mousemove', { buttons: 1, clientX: 90, clientY: 12 });
    await preview.vm.$nextTick();
    expect(preview.get('[data-page-action-key="delete"]').classes()).toContain(
      'page-composition-action-preview__button--drop-after',
    );
    await preview
      .get('[data-page-action-key="delete"]')
      .trigger('mouseup', { button: 0, clientX: 90, clientY: 12 });

    expect(preview.emitted('action-drop')).toEqual([
      [{ actionCode: 'update' }, { anchor: 'detail', index: 0 }],
      [{ actionCode: 'update' }, { anchor: 'detail', index: 1 }],
    ]);
  } finally {
    Object.defineProperty(document, 'elementFromPoint', { configurable: true, value: original });
    rectSpy.mockRestore();
    source.unmount();
    preview.unmount();
  }
});

it('animates stable list fields into their new descriptor order', async () => {
  const animate = vi.fn();
  const originalAnimate = HTMLElement.prototype.animate;
  const originalRect = HTMLElement.prototype.getBoundingClientRect;
  const originalFrame = window.requestAnimationFrame;
  const callbacks: FrameRequestCallback[] = [];
  HTMLElement.prototype.animate = animate as typeof HTMLElement.prototype.animate;
  HTMLElement.prototype.getBoundingClientRect = function () {
    const key = this.dataset.pageCompositionLayoutKey;
    const kind = key?.split(':')[1];
    const peers = [
      ...(this.parentElement?.querySelectorAll(`[data-page-composition-layout-key^="list:${kind}:"]`) ?? []),
    ];
    const left = key ? peers.indexOf(this) * 120 : 0;
    return { x: left, y: 0, top: 0, left, right: left + 100, bottom: 24, width: 100, height: 24 } as DOMRect;
  };
  window.requestAnimationFrame = ((callback: FrameRequestCallback) => {
    callbacks.push(callback);
    return callbacks.length;
  }) as typeof window.requestAnimationFrame;
  try {
    const wrapper = mount(PageCompositionDescriptorPreview, {
      attachTo: document.body,
      props: { descriptor: descriptor(), moduleAlias: 'platform.module', mode: 'list' },
      global: { stubs: { UiDataTable: tableStub } },
    });

    await wrapper.setProps({ descriptor: descriptorWithReorderedList() });
    // The final DOM order and its animation are committed together, before another frame or drag.
    expect(callbacks).toHaveLength(0);

    expect(animate).toHaveBeenCalledWith(
      expect.arrayContaining([expect.objectContaining({ transform: 'translate(-120px, 0px)' })]),
      expect.objectContaining({ duration: 300 }),
    );
    animate.mockClear();
    await wrapper.setProps({ selectedFieldName: 'list:enabled' });
    expect(animate).not.toHaveBeenCalled();
    expect(callbacks).toHaveLength(0);
    wrapper.unmount();
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
  HTMLElement.prototype.animate = undefined as unknown as typeof HTMLElement.prototype.animate;
  HTMLElement.prototype.getBoundingClientRect = function () {
    const key = this.dataset.pageCompositionLayoutKey;
    const kind = key?.split(':')[1];
    const peers = [
      ...(this.parentElement?.querySelectorAll(`[data-page-composition-layout-key^="list:${kind}:"]`) ?? []),
    ];
    const left = key ? peers.indexOf(this) * 120 : 0;
    return { x: left, y: 0, top: 0, left, right: left + 100, bottom: 24, width: 100, height: 24 } as DOMRect;
  };
  window.requestAnimationFrame = ((callback: FrameRequestCallback) => {
    callbacks.push(callback);
    return callbacks.length;
  }) as typeof window.requestAnimationFrame;
  try {
    const wrapper = mount(PageCompositionDescriptorPreview, {
      attachTo: document.body,
      props: { descriptor: descriptor(), moduleAlias: 'platform.module', mode: 'list' },
      global: { stubs: { UiDataTable: tableStub } },
    });

    await wrapper.setProps({ descriptor: descriptorWithReorderedList() });
    // The final DOM order and its animation are committed together, before another frame or drag.
    expect(callbacks).toHaveLength(0);

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

  const quickSearch = wrapper.getComponent({ name: 'UiSearchInput' });
  expect(quickSearch.props('placeholder')).toBe('搜索验收记录');
  expect(quickSearch.props('disabled')).toBe(true);
});

it('renders the same runtime form field renderer for the editable state', () => {
  const wrapper = shallowMount(PageCompositionDescriptorPreview, {
    props: { descriptor: descriptorWithEditor(), moduleAlias: 'platform.module', mode: 'edit' },
  });

  expect(wrapper.findComponent({ name: 'RecordFormFields' }).props('fieldNames')).toEqual(['title']);
});

it.each(['TREE_MANAGEMENT', 'FLAT_MANAGEMENT', 'LIST_DETAIL_CARD'] as const)(
  'renders group headings through the standard detail renderer in the %s page preview',
  (template) => {
    const value = descriptorWithTwoGroups();
    value.page!.template = template;
    const wrapper = mount(PageCompositionDescriptorPreview, {
      props: { descriptor: value, moduleAlias: 'platform.module', mode: 'detail' },
      global: { stubs: { UiDataTable: tableStub } },
    });
    try {
      expect(wrapper.findAll('.record-detail-group-heading h3').map((node) => node.text())).toEqual([
        '基础信息',
        '科目信息',
      ]);
      expect(wrapper.findAll('[data-field-name]').map((node) => node.attributes('data-field-name'))).toEqual([
        'examDate',
        'subject',
      ]);
    } finally {
      wrapper.unmount();
    }
  },
);

it('uses the display projection group and field order instead of the editor order', () => {
  const value = descriptorWithTwoGroups();
  value.page!.detail.display = {
    ...value.page!.detail.editor!,
    fields: [...value.page!.detail.editor!.fields].reverse(),
    formGroups: [{ groupCode: 'read', title: '详情信息', fields: [{ fieldName: 'subject' }] }],
  };
  const wrapper = mount(PageCompositionDescriptorPreview, {
    props: { descriptor: value, moduleAlias: 'platform.module', mode: 'detail' },
  });
  try {
    expect(
      wrapper.findAll('.record-detail-group-heading h3, [data-field-name] dt').map((node) => node.text()),
    ).toEqual(['详情信息', '科目', '考试日期']);
  } finally {
    wrapper.unmount();
  }
});

it.each(['detail', 'edit'] as const)(
  'keeps empty headings anchored across hidden fields in %s preview',
  (mode) => {
    const value = descriptorWithTwoGroups();
    value.page!.detail.editor!.fields.unshift({
      fieldRef: { fieldName: 'title' },
      label: '隐藏名称',
      visible: { constant: false },
    });
    value.page!.detail.editor!.fields[1]!.visible = { constant: false };
    const wrapper = mount(PageCompositionDescriptorPreview, {
      props: {
        descriptor: value,
        moduleAlias: 'platform.module',
        mode,
        structure: {
          list: [],
          relations: [],
          form: [{ id: 'title', fieldName: 'title', title: '隐藏名称' }],
          groups: [
            { id: 'empty', groupCode: 'empty', title: '前置空分组', fields: [] },
            {
              id: 'basic',
              groupCode: 'basic',
              title: '基础信息',
              fields: [
                { id: 'examDate', fieldName: 'examDate', title: '隐藏日期' },
                { id: 'subject', fieldName: 'subject', title: '科目' },
              ],
            },
            { id: 'tail', groupCode: 'tail', title: '末尾空分组', fields: [] },
          ],
          order: [
            { kind: 'group', id: 'empty' },
            { kind: 'field', id: 'title' },
            { kind: 'group', id: 'basic' },
            { kind: 'group', id: 'tail' },
          ],
        },
      },
    });
    try {
      expect(wrapper.findAll('h3').map((node) => node.text())).toEqual([
        '前置空分组',
        '基础信息',
        '末尾空分组',
      ]);
      expect(wrapper.text()).not.toContain('隐藏名称');
      expect(wrapper.text()).not.toContain('考试日期');
    } finally {
      wrapper.unmount();
    }
  },
);

it('shows empty design groups in mixed order without injecting a drop placeholder into the detail layout', () => {
  const wrapper = mount(PageCompositionDescriptorPreview, {
    props: {
      descriptor: descriptorWithEditor(),
      moduleAlias: 'platform.module',
      mode: 'detail',
      structure: {
        list: [],
        form: [{ id: 'title', fieldName: 'title', title: '名称' }],
        relations: [],
        groups: [{ id: 'empty', groupCode: 'empty', title: '空分组', fields: [] }],
        order: [
          { kind: 'group', id: 'empty' },
          { kind: 'field', id: 'title' },
        ],
      },
    },
  });
  try {
    expect(wrapper.get('[data-composer-target="detail:group:empty"]').text()).toContain('空分组');
    expect(
      wrapper.findAll('.record-detail-fields h3, .record-detail-fields dt').map((node) => node.text()),
    ).toEqual(['空分组', '考试名称']);
    expect(wrapper.find('.page-composer-drop-zone').exists()).toBe(false);
  } finally {
    wrapper.unmount();
  }
});

it('preserves every server-resolved FormGroup in the editable preview', () => {
  const wrapper = shallowMount(PageCompositionDescriptorPreview, {
    props: { descriptor: descriptorWithTwoGroups(), moduleAlias: 'platform.module', mode: 'edit' },
  });

  const forms = wrapper.findAllComponents({ name: 'RecordFormFields' });
  expect(forms.map((form) => form.props('fieldNames'))).toEqual([['examDate', 'subject']]);
  expect(forms[0].props('fields').get('subject').formGroup.groupCode).toBe('subject');
});

it.each(['detail', 'edit'] as const)(
  'opens column configuration from the %s preview header',
  async (mode) => {
    const wrapper = mount(PageCompositionDescriptorPreview, {
      props: { descriptor: descriptorWithRelation(), moduleAlias: 'platform.module', mode },
    });
    try {
      const header = wrapper.get(
        `[data-page-composition-layout-key="${mode}:relation:participants:header:studentName"]`,
      );
      await header.trigger('dblclick');
      expect(wrapper.emitted('configureRelationField')).toEqual([['participants', 'studentName']]);
      await header.trigger('keydown', { key: 'Enter' });
      expect(wrapper.emitted('configureRelationField')).toHaveLength(2);
    } finally {
      wrapper.unmount();
    }
  },
);

it('renders a detail relation projection as a standard descriptor-driven table', () => {
  const wrapper = shallowMount(PageCompositionDescriptorPreview, {
    props: { descriptor: descriptorWithRelation(), moduleAlias: 'platform.module', mode: 'detail' },
  });

  const relationTable = wrapper.findComponent({ name: 'RecordRelationTable' });
  expect(relationTable.props('columns')).toEqual([
    { fieldName: 'studentNo', title: '学号' },
    { fieldName: 'studentName', title: '学生姓名' },
    { fieldName: 'score', title: '成绩', width: 180, align: 'right' },
  ]);
  expect(relationTable.props('rows')).toMatchObject([
    { studentNo: '示例学号', studentName: '示例学生姓名', score: 96 },
  ]);
});

it('renders an editable local child-table preview from the server-resolved projection', async () => {
  const wrapper = mount(PageCompositionDescriptorPreview, {
    attachTo: document.body,
    props: { descriptor: descriptorWithRelation(), moduleAlias: 'platform.module', mode: 'edit' },
    global: { stubs: { UiDataTable: tableStub } },
  });

  expect(wrapper.text()).toContain('参考学生');
  expect(wrapper.findComponent({ name: 'RecordRelationTable' }).props('columns')).toEqual([
    { fieldName: 'studentNo', title: '学号' },
    { fieldName: 'studentName', title: '学生姓名' },
    { fieldName: 'score', title: '成绩', width: 180, align: 'right' },
  ]);
  expect(wrapper.findAll('th').at(-1)!.attributes('style')).toContain('text-align: right');
  expect(wrapper.findAll('col').at(-1)!.attributes('style')).toContain('width: 180px');
  const studentNo = wrapper.get(
    '[data-page-composition-layout-key="edit:relation:participants:field:studentNo"] input',
  );
  await studentNo.setValue('20260001');
  expect((studentNo.element as HTMLInputElement).value).toBe('20260001');
  expect(wrapper.findAllComponents({ name: 'RecordFormFields' })).toHaveLength(4);
});

it('uses relation value facts for the same scalar editor families as the runtime form', () => {
  const value = descriptorWithRelation();
  const relation = value.detailRelations![0];
  relation.listProjection!.fields = [
    { fieldName: 'deliveryDate', title: '交付日期', valueType: 'DATE' },
    { fieldName: 'amount', title: '含税单价', valueType: 'DECIMAL' },
    { fieldName: 'urgent', title: '紧急采购', valueType: 'BOOLEAN' },
  ];
  value.editorContributions![0].editor.fields = [
    {
      fieldRef: { fieldName: 'deliveryDate' },
      label: '交付日期',
      valueType: 'DATE',
      fieldControl: { alias: 'date', rendererType: 'DATE', valueShape: 'SCALAR' },
    },
    {
      fieldRef: { fieldName: 'amount' },
      label: '含税单价',
      valueType: 'DECIMAL',
      fieldControl: { alias: 'number', rendererType: 'NUMBER', valueShape: 'SCALAR' },
    },
    {
      fieldRef: { fieldName: 'urgent' },
      label: '紧急采购',
      valueType: 'BOOLEAN',
      fieldControl: { alias: 'switch', rendererType: 'SWITCH', valueShape: 'SCALAR' },
    },
  ];
  const wrapper = mount(PageCompositionDescriptorPreview, {
    attachTo: document.body,
    props: {
      descriptor: value,
      moduleAlias: 'platform.module',
      mode: 'edit',
    },
    global: { stubs: { UiDataTable: tableStub } },
  });

  expect(
    wrapper
      .get('[data-page-composition-layout-key="edit:relation:participants:field:deliveryDate"] input')
      .attributes('type'),
  ).toBe('date');
  expect(
    wrapper
      .get('[data-page-composition-layout-key="edit:relation:participants:field:amount"] input')
      .attributes('type'),
  ).toBe('number');
  expect(wrapper.getComponent({ name: 'UiSwitch' }).props('checked')).toBe(true);
});

it.each([null, undefined])(
  'preserves a cleared form sample (%s) across descriptor updates',
  async (value) => {
    const wrapper = shallowMount(PageCompositionDescriptorPreview, {
      props: { descriptor: descriptorWithEditor(), moduleAlias: 'platform.module', mode: 'edit' },
    });
    const form = wrapper.getComponent({ name: 'RecordFormFields' });
    form.vm.$emit('update:field', 'title', value);
    await wrapper.setProps({ descriptor: descriptorWithEditor() });

    expect(Object.hasOwn(form.props('record'), 'title')).toBe(true);
    expect(form.props('record').title).toBe(value);
    wrapper.unmount();
  },
);

it('retains child sample values while projections reorder and initializes only added columns', async () => {
  const wrapper = mount(PageCompositionDescriptorPreview, {
    attachTo: document.body,
    props: { descriptor: descriptorWithRelation(), moduleAlias: 'platform.module', mode: 'edit' },
    global: { stubs: { UiDataTable: tableStub } },
  });
  await wrapper
    .get('[data-page-composition-layout-key="edit:relation:participants:field:studentNo"] input')
    .setValue('20260001');
  await wrapper
    .get('[data-page-composition-layout-key="edit:relation:participants:field:studentName"] input')
    .setValue('');
  const next = descriptorWithRelation();
  next.detailRelations![0].listProjection!.fields = [
    { fieldName: 'studentName', title: '学生姓名' },
    { fieldName: 'studentNo', title: '学号' },
    { fieldName: 'note', title: '备注' },
  ];
  await wrapper.setProps({ descriptor: next });

  expect(wrapper.getComponent({ name: 'RecordRelationTable' }).props('rows')).toEqual([
    {
      id: 'page-composition-relation-preview:participants',
      studentName: '',
      studentNo: '20260001',
      note: '示例备注',
    },
  ]);
  expect(
    (
      wrapper.get('[data-page-composition-layout-key="edit:relation:participants:field:studentNo"] input')
        .element as HTMLInputElement
    ).value,
  ).toBe('20260001');
  await wrapper.setProps({ descriptor: descriptorWithEditor() });
  await wrapper.setProps({ descriptor: descriptorWithRelation() });
  expect(
    (
      wrapper.get('[data-page-composition-layout-key="edit:relation:participants:field:studentNo"] input')
        .element as HTMLInputElement
    ).value,
  ).toBe('示例学号');
  wrapper.unmount();
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
        { fieldName: 'score', title: '成绩', width: 180, align: 'right' },
      ],
    },
  };
  return {
    ...value,
    detailRelations: [relation],
    editorContributions: [
      {
        resource: 'exam_participant',
        editor: {
          viewCode: 'participant_editor',
          viewKind: 'FORM',
          fields: ['studentNo', 'studentName', 'score', 'note'].map((fieldName) => ({
            fieldRef: { fieldName },
            label: fieldName,
            uiType: 'input',
          })),
        },
      },
    ],
  };
}

it.each(['tree', 'flat'] as const)(
  'bridges %s metadata dragging to preview hover, cancellation and drop',
  async (displayMode) => {
    const source = mount(UiTree, {
      attachTo: document.body,
      props: {
        displayMode,
        nodes: [{ key: 'field', title: '字段' }],
        draggable: true,
        dragOperations: ['copy'],
        dragPayloadType: PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE,
        dragPayloadOf: () => ({ kind: 'field', fieldId: 'field' }),
      },
    });
    const preview = mount(PageCompositionDescriptorPreview, {
      attachTo: document.body,
      props: {
        descriptor: descriptor(),
        moduleAlias: 'platform.module',
        mode: 'list',
        acceptExternalDrop: true,
      },
      global: { stubs: { UiDataTable: tableStub } },
    });
    const original = document.elementFromPoint;
    try {
      const node = source.get('[data-ui-tree-key="field"]');
      for (const cancel of [true, false]) {
        await node.trigger('mousedown', { button: 0, clientX: 0, clientY: 0 });
        await preview.vm.$nextTick();
        const target = preview.get('[data-page-composition-layout-key="list:header:tags"]');
        Object.defineProperty(document, 'elementFromPoint', {
          configurable: true,
          value: () => target.element,
        });
        await target.trigger('mousemove', { buttons: 1, clientX: 200, clientY: 100 });
        expect(preview.find('.page-composer-drop-indicator').exists()).toBe(false);
        if (cancel) {
          document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
          await preview.vm.$nextTick();
          expect(preview.find('.page-composer-drop-indicator').exists()).toBe(false);
        }
        await target.trigger('mouseup', { button: 0, clientX: 200, clientY: 100 });
        expect(preview.emitted('placement-drop')?.length ?? 0).toBe(cancel ? 0 : 1);
      }
      expect(preview.find('.page-composer-drop-indicator').exists()).toBe(false);
      expect(preview.emitted('placement-drop')![0][0]).toEqual({
        kind: 'metadata',
        metadata: { kind: 'field', fieldId: 'field' },
      });
    } finally {
      Object.defineProperty(document, 'elementFromPoint', { configurable: true, value: original });
      source.unmount();
      preview.unmount();
    }
  },
);

it('cancels a staged preview placement when the pointer leaves its canvas or the user right-clicks', async () => {
  const source = mount(UiTree, {
    attachTo: document.body,
    props: {
      nodes: [{ key: 'field', title: '字段' }],
      draggable: true,
      dragOperations: ['copy'],
      dragPayloadType: PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE,
      dragPayloadOf: () => ({ kind: 'field', fieldId: 'field' }),
    },
  });
  const preview = mount(PageCompositionDescriptorPreview, {
    attachTo: document.body,
    props: {
      descriptor: descriptor(),
      moduleAlias: 'platform.module',
      mode: 'list',
      acceptExternalDrop: true,
    },
    global: { stubs: { UiDataTable: tableStub } },
  });
  const original = document.elementFromPoint;
  try {
    await source
      .get('[data-ui-tree-key="field"]')
      .trigger('mousedown', { button: 0, clientX: 0, clientY: 0 });
    await preview.vm.$nextTick();
    const target = preview.get('[data-page-composition-layout-key="list:header:tags"]');
    Object.defineProperty(document, 'elementFromPoint', { configurable: true, value: () => target.element });
    await target.trigger('mousemove', { buttons: 1, clientX: 200, clientY: 100 });
    expect(preview.find('.page-composer-drop-indicator').exists()).toBe(false);

    Object.defineProperty(document, 'elementFromPoint', { configurable: true, value: () => document.body });
    document.dispatchEvent(
      new MouseEvent('mousemove', { bubbles: true, buttons: 1, clientX: 240, clientY: 100 }),
    );
    await preview.vm.$nextTick();
    expect(preview.find('.page-composer-drop-indicator').exists()).toBe(false);
    document.dispatchEvent(
      new MouseEvent('mouseup', { bubbles: true, button: 0, clientX: 240, clientY: 100 }),
    );
    expect(preview.emitted('placement-drop')).toBeUndefined();

    await source
      .get('[data-ui-tree-key="field"]')
      .trigger('mousedown', { button: 0, clientX: 0, clientY: 0 });
    await preview.vm.$nextTick();
    const renewedTarget = preview.get('[data-page-composition-layout-key="list:header:tags"]');
    Object.defineProperty(document, 'elementFromPoint', {
      configurable: true,
      value: () => renewedTarget.element,
    });
    await renewedTarget.trigger('mousemove', { buttons: 1, clientX: 200, clientY: 100 });
    await renewedTarget.trigger('contextmenu', { button: 2, clientX: 200, clientY: 100 });
    await preview.vm.$nextTick();
    expect(preview.find('.page-composer-drop-indicator').exists()).toBe(false);
    await renewedTarget.trigger('mouseup', { button: 0, clientX: 200, clientY: 100 });
    expect(preview.emitted('placement-drop')).toBeUndefined();
  } finally {
    Object.defineProperty(document, 'elementFromPoint', { configurable: true, value: original });
    source.unmount();
    preview.unmount();
  }
});

it('uses release coordinates for the final column edge and supports keyboard targets from a nested grip', async () => {
  const preview = mount(PageCompositionDescriptorPreview, {
    attachTo: document.body,
    props: {
      descriptor: descriptor(),
      moduleAlias: 'platform.module',
      mode: 'list',
      acceptExternalDrop: true,
    },
    global: { stubs: { UiDataTable: tableStub } },
  });
  const source = mount(UiTree, {
    global: { stubs: { UiDataTable: tableStub } },
    attachTo: document.body,
    props: {
      nodes: [{ key: 'new', title: 'New' }],
      draggable: true,
      dragOperations: ['copy'],
      dragPayloadType: PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE,
      dragPayloadOf: () => ({ kind: 'field', fieldId: 'new' }),
    },
  });
  const header = preview.get('[data-page-composition-layout-key="list:header:enabled"]');
  const original = document.elementFromPoint;
  Object.defineProperty(document, 'elementFromPoint', {
    configurable: true,
    value: () => preview.get('[data-page-composition-layout-key="list:header:enabled"]').element,
  });
  const rectSpy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
    x: 100,
    y: 0,
    left: 100,
    right: 200,
    top: 0,
    bottom: 30,
    width: 100,
    height: 30,
    toJSON: () => ({}),
  });
  try {
    await preview.vm.$nextTick();
    await source.vm.$nextTick();
    await source.get('[data-ui-tree-key="new"]').trigger('mousedown', { button: 0, clientX: 0, clientY: 10 });
    await header.trigger('mousemove', { buttons: 1, clientX: 110, clientY: 10 });
    expect(preview.find('.page-composer-drop-indicator').exists()).toBe(false);
    document.dispatchEvent(new MouseEvent('mouseup', { clientX: 190, clientY: 10, bubbles: true }));
    await preview.vm.$nextTick();
    expect(preview.emitted('placement-drop')![0][1]).toEqual({
      container: { kind: 'list' },
      anchorId: 'enabled',
      position: 'after',
    });
    const grip = preview.get('[data-composer-drag="list:header:enabled"]');
    (grip.element as HTMLElement).focus();
    await grip.trigger('keydown', { key: ' ' });
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    expect((document.activeElement as HTMLElement).dataset.uiDropKey).toBe('list:header:tags');
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    expect(preview.emitted('placement-drop')![1]).toEqual([
      { kind: 'node', container: { kind: 'list' }, nodeId: 'enabled' },
      { container: { kind: 'list' }, anchorId: 'tags', position: 'after' },
    ]);
  } finally {
    rectSpy.mockRestore();
    Object.defineProperty(document, 'elementFromPoint', { configurable: true, value: original });
    source.unmount();
    preview.unmount();
  }
});

it('keeps display-only fields in detail placement while editor-only fields stay in the form', () => {
  const value = descriptorWithEditor();
  value.page!.detail.display = {
    viewCode: 'display',
    viewKind: 'DETAIL',
    fields: [{ fieldRef: { fieldName: 'scope' }, label: '所属范围', uiType: 'input' }],
  };
  const wrapper = shallowMount(PageCompositionDescriptorPreview, {
    props: { descriptor: value, moduleAlias: 'platform.module', mode: 'detail' },
  });
  expect(wrapper.findComponent({ name: 'RecordDetailFields' }).props('fieldNames')).toEqual(['scope']);
  wrapper.unmount();
});

it('treats the right half of a detail-grid field as its after placement', async () => {
  const value = descriptorWithEditor();
  value.page!.detail!.display = {
    viewCode: 'display',
    viewKind: 'DETAIL',
    fields: [
      { fieldRef: { fieldName: 'department' }, label: '管理部门', uiType: 'input' },
      { fieldRef: { fieldName: 'enabledAt' }, label: '启用日期', uiType: 'datePicker' },
    ],
  };
  const preview = mount(PageCompositionDescriptorPreview, {
    attachTo: document.body,
    props: { descriptor: value, moduleAlias: 'platform.module', mode: 'detail', acceptExternalDrop: true },
  });
  const source = preview.get('[data-composer-drag="detail:field:department"]');
  const target = preview.get('[data-page-composition-layout-key="detail:field:enabledAt"]');
  const original = document.elementFromPoint;
  const targetElement = target.element as HTMLElement;
  const sourceElement = preview.get('[data-page-composition-layout-key="detail:field:department"]')
    .element as HTMLElement;
  const rectSpy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (
    this: HTMLElement,
  ) {
    if (this === targetElement)
      return { left: 100, right: 200, top: 0, bottom: 40, width: 100, height: 40 } as DOMRect;
    if (this === sourceElement)
      return { left: 0, right: 100, top: 0, bottom: 40, width: 100, height: 40 } as DOMRect;
    return { left: 0, right: 0, top: 0, bottom: 0, width: 0, height: 0 } as DOMRect;
  });
  Object.defineProperty(document, 'elementFromPoint', { configurable: true, value: () => targetElement });
  try {
    await source.trigger('mousedown', { button: 0, clientX: 10, clientY: 20 });
    await target.trigger('mousemove', { buttons: 1, clientX: 190, clientY: 20 });
    document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, clientX: 190, clientY: 20 }));
    expect(preview.emitted('placement-drop')?.[0]).toEqual([
      { kind: 'node', container: { kind: 'form' }, nodeId: 'department' },
      { container: { kind: 'form' }, anchorId: 'enabledAt', position: 'after' },
    ]);
  } finally {
    rectSpy.mockRestore();
    Object.defineProperty(document, 'elementFromPoint', { configurable: true, value: original });
    preview.unmount();
  }
});

it.each(['TREE_MANAGEMENT', 'FLAT_MANAGEMENT'] as const)(
  'previews the shared card without a navigation mock for %s',
  (template) => {
    const definition = descriptor();
    definition.page = {
      ...definition.page!,
      template,
      list: undefined,
      explorer: {
        title: '任务导航',
        searchPlaceholder: '搜索任务编号',
        titleField: 'code',
        secondaryField: 'owner',
        emptyDescription: '暂无记录',
        recordLabel: '任务',
        fallbackTitle: '未命名',
        mutedWhenDisabled: false,
      },
      quickSearchFields: ['code'],
    };
    const wrapper = mount(PageCompositionDescriptorPreview, {
      props: {
        descriptor: definition,
        moduleAlias: 'platform.module',
        mode: 'detail',
      },
    });
    expect(wrapper.find('[data-testid="page-composer-detail-preview"]').exists()).toBe(true);
    wrapper.unmount();
  },
);
