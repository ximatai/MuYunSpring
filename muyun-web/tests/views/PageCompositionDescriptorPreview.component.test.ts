import { mount, shallowMount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import UiTree from '@/vue-ui-antdv/components/UiTree.vue';
import PageCompositionDescriptorPreview from '@/views/PageCompositionDescriptorPreview.vue';
import { PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE } from '@/views/pageCompositionDragPayload';
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
    attachTo: document.body,
    props: { descriptor: descriptor(), moduleAlias: 'platform.module', mode: 'list' },
    global: { stubs: { UiDataTable: tableStub } },
  });
  await list.get('button').trigger('keydown', { key: ' ' });
  expect(list.emitted('configureField')).toEqual([['list', 'enabled']]);
});

it('exposes the active preview mode as an external metadata drop target', async () => {
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
      nodes: [{ key: 'field', title: 'Field' }],
      draggable: true,
      dragOperations: ['copy'],
      dragPayloadType: PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE,
      dragPayloadOf: () => ({ kind: 'field', fieldId: 'field' }),
    },
  });
  const preview = wrapper.get('[data-composer-target="list:end"]');
  await source.get('[data-ui-tree-key]').trigger('mousedown', { button: 0 });
  await preview.trigger('mousemove', { buttons: 1, clientX: 30, clientY: 30 });
  await preview.trigger('mouseup', { clientX: 30, clientY: 30 });
  expect(wrapper.emitted('placement-drop')).toHaveLength(1);
  expect(wrapper.emitted('placement-drop')?.[0]?.[1]).toEqual({
    container: { kind: 'list' },
    position: 'inside',
  });
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
  const preview = wrapper.get('[data-composer-target="list:end"]');
  await source.get('[data-ui-tree-key]').trigger('mousedown', { button: 0 });
  await preview.trigger('mousemove', { buttons: 1, clientX: 30, clientY: 30 });
  await preview.trigger('mouseup', { clientX: 30, clientY: 30 });

  expect(wrapper.emitted('placement-drop')).toBeUndefined();
  expect(preview.classes()).not.toContain('page-composition-descriptor-preview--drag-over');
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

  const forms = wrapper.findAllComponents({ name: 'RecordFormFields' });
  expect(forms.map((form) => form.props('fieldNames'))).toEqual([[], ['examDate'], ['subject']]);
  expect(wrapper.findAll('.page-composer-form-section > header')).toHaveLength(2);
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
    attachTo: document.body,
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
  await wrapper.get('input[aria-label="参考学生：学号"]').setValue('20260001');
  await wrapper.get('input[aria-label="参考学生：学生姓名"]').setValue('');
  const next = descriptorWithRelation();
  next.detailRelations![0].listProjection!.fields = [
    { fieldName: 'studentName', title: '学生姓名' },
    { fieldName: 'studentNo', title: '学号' },
    { fieldName: 'note', title: '备注' },
  ];
  await wrapper.setProps({ descriptor: next });

  expect(wrapper.getComponent({ name: 'UiDataTable' }).props('rows')).toEqual([
    {
      id: 'page-composition-relation-preview:participants',
      studentName: '',
      studentNo: '20260001',
      note: '示例备注',
    },
  ]);
  expect((wrapper.get('input[aria-label="参考学生：学号"]').element as HTMLInputElement).value).toBe(
    '20260001',
  );
  await wrapper.setProps({ descriptor: descriptorWithEditor() });
  await wrapper.setProps({ descriptor: descriptorWithRelation() });
  expect((wrapper.get('input[aria-label="参考学生：学号"]').element as HTMLInputElement).value).toBe(
    '示例学号',
  );
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
        { fieldName: 'score', title: '成绩', align: 'right' },
      ],
    },
  };
  return { ...value, detailRelations: [relation] };
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
    const target = preview.get('[data-composer-target="list:end"]');
    const original = document.elementFromPoint;
    Object.defineProperty(document, 'elementFromPoint', { configurable: true, value: () => target.element });
    try {
      const node = source.get('[data-ui-tree-key="field"]');
      for (const cancel of [true, false]) {
        await node.trigger('mousedown', { button: 0, clientX: 0, clientY: 0 });
        await target.trigger('mousemove', { buttons: 1, clientX: 200, clientY: 100 });
        expect(preview.find('.page-composer-drop-indicator').exists()).toBe(true);
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
    expect(preview.find('.page-composer-drop-indicator').exists()).toBe(true);
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
