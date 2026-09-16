import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import RecordFormFields from '@/platform-components/RecordFormFields.vue';
import type { RecordFormFieldDescriptor } from '@/platform-components/recordFormFieldModel.ts';
import type { ModuleContext } from '@muyun/web-core';

describe('RecordFormFields', () => {
  it('keeps the compact picker and exposes lazy-tree expansion as an additional reference action', async () => {
    const open = vi.fn();
    const ScopedTreePickerStub = defineComponent({
      name: 'ScopedTreePicker',
      emits: ['update:value', 'select'],
      setup(_, { expose }) {
        expose({ open });
        return () => h('div', { class: 'scoped-tree-picker-stub' });
      },
    });
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'departmentId',
        {
          fieldRef: { fieldName: 'departmentId' },
          label: '所属部门',
          reference: { targetModuleAlias: 'iam.department', cardinality: 'ONE' },
        },
      ],
    ]);
    const provider = {
      loadRoot: vi.fn(),
      loadChildren: vi.fn(),
      resolve: vi.fn().mockResolvedValue([]),
    };
    const wrapper = mount(RecordFormFields, {
      props: {
        record: { departmentId: 'department-old' },
        fields,
        pickerConfigs: {
          departmentId: {
            context: {} as never,
            scopedTree: { title: '选择所属部门', provider },
          },
        },
      },
      global: { stubs: { ScopedTreePicker: ScopedTreePickerStub } },
    });

    expect(wrapper.findComponent({ name: 'RecordPicker' }).exists()).toBe(true);
    expect(wrapper.find('.record-form-scoped-tree-trigger').text()).toContain('展开选择');
    await wrapper.get('.record-form-scoped-tree-trigger button').trigger('click');
    expect(open).toHaveBeenCalledOnce();

    wrapper.findComponent(ScopedTreePickerStub).vm.$emit('update:value', 'department-new');
    expect(wrapper.emitted('update:field')).toContainEqual(['departmentId', 'department-new']);
  });

  it('disables expanded tree selection with a source-supplied scope guide', () => {
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'departmentId',
        {
          fieldRef: { fieldName: 'departmentId' },
          label: '所属部门',
          reference: { targetModuleAlias: 'iam.department', cardinality: 'ONE' },
        },
      ],
    ]);
    const wrapper = mount(RecordFormFields, {
      props: {
        record: {},
        fields,
        pickerConfigs: {
          departmentId: {
            context: {} as never,
            scopedTree: {
              disabled: true,
              unavailableMessage: '请先选择所属机构，再展开选择部门',
              provider: { loadRoot: vi.fn(), loadChildren: vi.fn(), resolve: vi.fn() },
            },
          },
        },
      },
    });

    expect(wrapper.find('.record-form-scoped-tree-trigger').text()).toContain('请先选择所属机构');
    expect(wrapper.get('.record-form-scoped-tree-trigger button').attributes('disabled')).toBeDefined();
  });

  it('emits selected reference projections separately from mutation field changes', async () => {
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'studentId',
        {
          fieldRef: { fieldName: 'studentId' },
          label: '学生',
          reference: {
            targetModuleAlias: 'education.student',
            cardinality: 'ONE',
            displayProjections: [{ targetField: 'studentNo', outputField: 'studentNo' }],
          },
        },
      ],
    ]);
    const wrapper = mount(RecordFormFields, {
      props: {
        record: { studentId: 'student-1' },
        fields,
        pickerConfigs: { studentId: { context: {} as never } },
      },
    });

    wrapper.findComponent({ name: 'RecordPicker' }).vm.$emit('selection-resolved', {
      id: 'student-1',
      studentNo: 'S2026001',
    });
    await flushPromises();

    expect(wrapper.emitted('reference-projections-change')).toContainEqual([
      'studentId',
      { studentNo: 'S2026001' },
    ]);
    expect(wrapper.emitted('update:field')).toBeUndefined();
  });

  it('keeps common-picker selection patches separate from historical display resolution', async () => {
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'studentId',
        {
          fieldRef: { fieldName: 'studentId' },
          label: '学生',
          reference: {
            targetModuleAlias: 'education.student',
            cardinality: 'ONE',
            displayProjections: [{ targetField: 'studentNo', outputField: 'studentNo' }],
          },
        },
      ],
    ]);
    const provider = {
      identity: {
        targetModuleAlias: 'education.student',
        source: { kind: 'sourceField' as const, id: 'education.enrollment:studentId:resolve' },
      },
      searchPage: vi.fn().mockResolvedValue({ records: [], total: 0 }),
      resolve: vi.fn().mockResolvedValue([]),
    };
    const wrapper = mount(RecordFormFields, {
      props: {
        record: { studentId: 'student-1' },
        fields,
        pickerConfigs: { studentId: { context: {} as never, provider } },
      },
    });
    const picker = wrapper.findComponent({ name: 'ReferencePicker' });
    expect(picker.exists()).toBe(true);

    picker.vm.$emit('selection-resolved', [
      {
        id: 'student-1',
        title: '王华',
        projections: { studentNo: 'S1' },
        affectPatch: { classId: 'class-1' },
      },
    ]);
    await flushPromises();
    expect(wrapper.emitted('reference-projections-change')).toContainEqual([
      'studentId',
      { studentNo: 'S1' },
    ]);
    expect(wrapper.emitted('update:field')).toBeUndefined();

    picker.vm.$emit('select', [{ id: 'student-1', title: '王华', affectPatch: { classId: 'class-1' } }]);
    expect(wrapper.emitted('update:field')).toContainEqual(['classId', 'class-1']);
  });

  it('routes ordinary source TREE references through the shared picker for single and multiple values', async () => {
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'departmentId',
        {
          fieldRef: { fieldName: 'departmentId' },
          label: '所属部门',
          reference: {
            targetModuleAlias: 'iam.department',
            cardinality: 'ONE',
            candidateDelivery: 'SOURCE_FIELD',
            pickerMode: 'TREE',
          },
        },
      ],
      [
        'departmentIds',
        {
          fieldRef: { fieldName: 'departmentIds' },
          label: '可见部门',
          reference: {
            targetModuleAlias: 'iam.department',
            cardinality: 'MANY',
            candidateDelivery: 'SOURCE_FIELD',
            pickerMode: 'TREE',
          },
        },
      ],
    ]);
    const provider = {
      identity: {
        targetModuleAlias: 'iam.department',
        source: { kind: 'sourceField' as const, id: 'iam.role:departmentId:resolve' },
      },
      searchPage: vi.fn().mockResolvedValue({ records: [], total: 0 }),
      loadTree: vi.fn().mockResolvedValue([]),
      resolve: vi.fn().mockResolvedValue([]),
    };
    const wrapper = mount(RecordFormFields, {
      props: {
        record: { departmentId: 'department-old', departmentIds: ['department-other'] },
        fields,
        pickerConfigs: {
          departmentId: { context: {} as never, provider, mode: 'tree' },
          departmentIds: { context: {} as never, provider, mode: 'tree' },
        },
      },
    });
    const pickers = wrapper.findAllComponents({ name: 'ReferencePicker' });

    expect(pickers).toHaveLength(2);
    expect(pickers[0]!.props('multiple')).toBe(false);
    expect(pickers[1]!.props('multiple')).toBe(true);
    pickers[0]!.vm.$emit('update:value', 'department-rd');
    pickers[0]!.vm.$emit('select', [{ id: 'department-rd', title: '研发部', affectPatch: { code: 'RD' } }]);
    pickers[1]!.vm.$emit('update:value', ['department-rd', 'department-sales']);
    pickers[1]!.vm.$emit('select', [
      { id: 'department-rd', title: '研发部', affectPatch: { code: 'RD' } },
      { id: 'department-sales', title: '销售部', affectPatch: { region: 'east' } },
    ]);
    await flushPromises();

    expect(wrapper.emitted('update:field')).toContainEqual(['departmentId', 'department-rd']);
    expect(wrapper.emitted('update:field')).toContainEqual([
      'departmentIds',
      ['department-rd', 'department-sales'],
    ]);
    expect(wrapper.emitted('update:field')).toContainEqual(['code', 'RD']);
    expect(wrapper.emitted('update:field')).toContainEqual(['region', 'east']);
  });

  it('renders declared override fields with explicit inherit, enabled and disabled states', async () => {
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'accessModeOverride',
        {
          fieldRef: { fieldName: 'accessModeOverride' },
          label: '访问方式覆盖',
          uiType: 'select',
          overrideOf: 'accessMode',
        },
      ],
      [
        'actionAuthOverride',
        {
          fieldRef: { fieldName: 'actionAuthOverride' },
          label: '动作授权覆盖',
          uiType: 'switch',
          valueType: 'BOOLEAN',
          overrideOf: 'actionAuth',
        },
      ],
    ]);
    const wrapper = mount(RecordFormFields, {
      props: {
        record: { accessMode: 'AUTH_REQUIRED', actionAuth: true },
        fields,
        fallback: {
          accessModeOverride: {
            label: '访问方式覆盖',
            options: [
              { label: '需要授权', value: 'AUTH_REQUIRED' },
              { label: '登录可用', value: 'LOGIN_REQUIRED' },
            ],
          },
        },
      },
    });

    const selects = wrapper.findAllComponents({ name: 'UiSelect' });
    expect(selects).toHaveLength(2);
    expect(selects[0].props('value')).toBe('__muyun_inherit__');
    expect(selects[0].props('options')).toContainEqual({
      label: '继承（需要授权）',
      value: '__muyun_inherit__',
    });
    expect(selects[1].props('options')).toEqual([
      { label: '继承（开启）', value: '__muyun_inherit__' },
      { label: '开启', value: 'true' },
      { label: '关闭', value: 'false' },
    ]);

    await wrapper.setProps({
      record: { accessMode: 'AUTH_REQUIRED', actionAuth: true, actionAuthOverride: false },
    });
    expect(wrapper.findAllComponents({ name: 'UiSelect' })[1].props('value')).toBe('false');

    await wrapper.setProps({
      record: { accessMode: 'AUTH_REQUIRED', actionAuth: true, actionAuthOverride: true },
    });
    expect(wrapper.findAllComponents({ name: 'UiSelect' })[1].props('value')).toBe('true');

    selects[0].vm.$emit('update:value', 'LOGIN_REQUIRED');
    selects[1].vm.$emit('update:value', 'false');
    selects[0].vm.$emit('update:value', '__muyun_inherit__');
    expect(wrapper.emitted('update:field')).toContainEqual(['accessModeOverride', 'LOGIN_REQUIRED']);
    expect(wrapper.emitted('update:field')).toContainEqual(['actionAuthOverride', false]);
    expect(wrapper.emitted('update:field')).toContainEqual(['accessModeOverride', undefined]);
  });

  it('defaults ordinary switches to off while keeping enabled status on', () => {
    const fields = new Map<string, RecordFormFieldDescriptor>([
      ['primaryPosition', { fieldRef: { fieldName: 'primaryPosition' }, label: '主任职', uiType: 'switch' }],
      ['enabled', { fieldRef: { fieldName: 'enabled' }, label: '启用状态', uiType: 'enabledStatus' }],
    ]);
    const wrapper = mount(RecordFormFields, { props: { record: {}, fields } });

    expect(wrapper.findComponent({ name: 'UiSwitch' }).props('checked')).toBe(false);
    expect(wrapper.findComponent({ name: 'RecordStatusSwitch' }).props('enabled')).toBe(true);
  });

  it('publishes and presents draft-aware required-field errors before submission', async () => {
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'primaryValueKey',
        {
          fieldRef: { fieldName: 'primaryValueKey' },
          label: '主分量键',
          required: {
            formula: {
              expression: "{valueShape} == 'COMPOSITE'",
              program: {
                schemaVersion: 1,
                profile: 'WEB_UI',
                referencedFields: ['valueShape'],
                root: {
                  kind: 'BINARY',
                  operator: '==',
                  arguments: [
                    { kind: 'FIELD', field: 'valueShape', arguments: [] },
                    { kind: 'VALUE', value: 'COMPOSITE', arguments: [] },
                  ],
                },
              },
            },
          },
        },
      ],
    ]);
    const wrapper = mount(RecordFormFields, {
      props: { record: { valueShape: 'COMPOSITE', primaryValueKey: '' }, fields },
    });

    expect(wrapper.find('.record-form-field--validation-pulse').exists()).toBe(false);
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([
      { valid: false, errors: { primaryValueKey: '请填写主分量键' } },
    ]);

    await wrapper.setProps({ validationRequestKey: 1 });
    await wrapper.vm.$nextTick();
    expect(wrapper.find('.record-form-field--validation-pulse').exists()).toBe(true);
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);

    await wrapper.setProps({ record: { valueShape: 'COMPOSITE', primaryValueKey: 'value' } });
    expect(wrapper.find('.record-form-field--validation-pulse').exists()).toBe(false);
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([{ valid: true, errors: {} }]);
  });

  it('renders one divider between adjacent semantic groups', () => {
    const firstGroup = {
      groupCode: 'identity',
      title: '基本信息',
      fields: [{ fieldName: 'title' }],
    };
    const secondGroup = {
      groupCode: 'branding',
      title: '品牌配置',
      fields: [{ fieldName: 'subtitle' }],
    };
    const fields = new Map<string, RecordFormFieldDescriptor>([
      ['title', { fieldRef: { fieldName: 'title' }, label: '名称', formGroup: firstGroup }],
      ['subtitle', { fieldRef: { fieldName: 'subtitle' }, label: '副标题', formGroup: secondGroup }],
    ]);

    const wrapper = mount(RecordFormFields, {
      props: {
        record: { title: '', subtitle: '' },
        fields,
      },
    });

    expect(wrapper.findAll('.record-form-group-heading').map((heading) => heading.text())).toEqual([
      '基本信息',
      '品牌配置',
    ]);
    // One leading boundary, one shared group boundary, and one trailing boundary.
    expect(wrapper.findAll('.record-form-group-divider')).toHaveLength(3);
  });

  it('uses the numeric input adapter for platform numeric control aliases', () => {
    const fields = new Map<string, RecordFormFieldDescriptor>([
      ['amount', { fieldRef: { fieldName: 'amount' }, label: '金额', uiType: 'amount' }],
    ]);

    const wrapper = mount(RecordFormFields, {
      props: { record: { amount: '12.50' }, fields },
    });

    const input = wrapper.findComponent({ name: 'UiInput' });
    expect(input.props('type')).toBe('number');

    // INTEGER stays a JSON number; LONG and DECIMAL deliberately use a lossless text wire form.
    input.vm.$emit('update:value', '23.40');
    expect(wrapper.emitted('update:field')).toContainEqual(['amount', 23.4]);
  });

  it('emits lossless string wire values for LONG and DECIMAL field descriptors', () => {
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'externalSequence',
        {
          fieldRef: { fieldName: 'externalSequence' },
          label: '外部序号',
          valueType: 'LONG',
          uiType: 'integer',
        },
      ],
      [
        'amount',
        { fieldRef: { fieldName: 'amount' }, label: '金额', valueType: 'DECIMAL', uiType: 'amount' },
      ],
    ]);
    const wrapper = mount(RecordFormFields, {
      props: { record: { externalSequence: '9007199254740993', amount: '0.123456789012345678' }, fields },
    });
    const inputs = wrapper.findAllComponents({ name: 'UiInput' });

    inputs[0].vm.$emit('update:value', '9007199254740993');
    inputs[1].vm.$emit('update:value', '0.123456789012345678');

    expect(wrapper.emitted('update:field')).toContainEqual(['externalSequence', '9007199254740993']);
    expect(wrapper.emitted('update:field')).toContainEqual(['amount', '0.123456789012345678']);
  });

  it('uses native date and datetime transports for executable field-control renderers', () => {
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'deliveryDate',
        {
          fieldRef: { fieldName: 'deliveryDate' },
          label: '交付日期',
          fieldControl: { alias: 'date', rendererType: 'DATE', valueShape: 'SCALAR' },
        },
      ],
      [
        'scheduledAt',
        {
          fieldRef: { fieldName: 'scheduledAt' },
          label: '预约时间',
          fieldControl: { alias: 'datetime', rendererType: 'DATETIME', valueShape: 'SCALAR' },
        },
      ],
    ]);
    const wrapper = mount(RecordFormFields, {
      props: { record: { deliveryDate: '2026-08-20', scheduledAt: '2026-08-20T10:30:00Z' }, fields },
    });
    const inputs = wrapper.findAllComponents({ name: 'UiInput' });

    expect(inputs.map((input) => input.props('type'))).toEqual(['date', 'datetime-local']);
    expect(inputs[1].props('step')).toBe('1');
    inputs[0].vm.$emit('update:value', '2026-08-21');
    const localDateTime = String(inputs[1].props('value'));
    expect(localDateTime).toMatch(/^2026-08-20T\d{2}:30:00$/);
    inputs[1].vm.$emit('update:value', '2026-08-21T11:00:37');
    expect(wrapper.emitted('update:field')).toContainEqual(['deliveryDate', '2026-08-21']);
    expect(wrapper.emitted('update:field')).toContainEqual([
      'scheduledAt',
      new Date('2026-08-21T11:00:37').toISOString().replace(/\.\d{3}Z$/, 'Z'),
    ]);
  });

  it('round-trips JSON editors as parsed object payloads and reports malformed JSON', async () => {
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'payload',
        {
          fieldRef: { fieldName: 'payload' },
          label: '扩展信息',
          fieldControl: { alias: 'json', rendererType: 'JSON', valueShape: 'SCALAR' },
        },
      ],
    ]);
    const wrapper = mount(RecordFormFields, { props: { record: { payload: { level: 2 } }, fields } });
    const textarea = wrapper.findComponent({ name: 'UiTextArea' });

    expect(textarea.props('value')).toContain('"level": 2');
    textarea.vm.$emit('update:value', '{"level":3,"tags":["vip"]}');
    expect(wrapper.emitted('update:field')).toContainEqual(['payload', { level: 3, tags: ['vip'] }]);

    textarea.vm.$emit('update:value', '{bad');
    await wrapper.vm.$nextTick();
    expect(wrapper.find('[role="alert"]').text()).toContain('有效 JSON');

    textarea.vm.$emit('update:value', '"not an object"');
    await wrapper.vm.$nextTick();
    expect(wrapper.find('[role="alert"]').text()).toContain('有效 JSON');
  });

  it('publishes invalid editor and unsupported-control state, then recovers after correction', async () => {
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'payload',
        {
          fieldRef: { fieldName: 'payload' },
          label: '扩展信息',
          fieldControl: { alias: 'json', rendererType: 'JSON', valueShape: 'SCALAR' },
        },
      ],
    ]);
    const wrapper = mount(RecordFormFields, {
      props: { record: { id: 'record-1', payload: {} }, fields, formSessionKey: 1 },
    });
    const textarea = wrapper.findComponent({ name: 'UiTextArea' });

    textarea.vm.$emit('update:value', '{bad');
    await wrapper.vm.$nextTick();
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([
      expect.objectContaining({ valid: false, errors: { payload: '请输入有效 JSON' } }),
    ]);
    expect(wrapper.emitted('update:field')).toBeUndefined();

    textarea.vm.$emit('update:value', '{"level":3}');
    await wrapper.vm.$nextTick();
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([expect.objectContaining({ valid: true })]);
    expect(wrapper.emitted('update:field')).toContainEqual(['payload', { level: 3 }]);
  });

  it('clears parser errors when the form session receives a new record', async () => {
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'payload',
        {
          fieldRef: { fieldName: 'payload' },
          label: '扩展信息',
          fieldControl: { alias: 'json', rendererType: 'JSON', valueShape: 'SCALAR' },
        },
      ],
    ]);
    const wrapper = mount(RecordFormFields, {
      props: { record: { id: 'record-1', payload: {} }, fields, formSessionKey: 1 },
    });
    wrapper.findComponent({ name: 'UiTextArea' }).vm.$emit('update:value', '{bad');
    await wrapper.vm.$nextTick();

    // Normal immutable draft updates must retain the parser failure.
    await wrapper.setProps({ record: { id: 'record-1', payload: { untouched: true } } });
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([expect.objectContaining({ valid: false })]);

    await wrapper.setProps({ record: { id: 'record-2', payload: {} }, formSessionKey: 2 });
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([expect.objectContaining({ valid: true })]);
  });

  it('marks an illegal numeric editor invalid without replacing the saved draft value', async () => {
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'quantity',
        { fieldRef: { fieldName: 'quantity' }, label: '数量', valueType: 'INTEGER', uiType: 'integer' },
      ],
    ]);
    const wrapper = mount(RecordFormFields, { props: { record: { quantity: 2 }, fields } });
    const input = wrapper.findComponent({ name: 'UiInput' });

    input.vm.$emit('update:value', '2.5');
    await wrapper.vm.$nextTick();
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([
      expect.objectContaining({ valid: false, errors: { quantity: '请输入有效数字' } }),
    ]);
    expect(wrapper.emitted('update:field')).toBeUndefined();

    input.vm.$emit('update:value', '3');
    await wrapper.vm.$nextTick();
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([expect.objectContaining({ valid: true })]);
    expect(wrapper.emitted('update:field')).toContainEqual(['quantity', 3]);
  });

  it('shows an explicit non-editable diagnostic for an unregistered resolved renderer', () => {
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'schedule',
        {
          fieldRef: { fieldName: 'schedule' },
          label: '排期',
          fieldControl: {
            alias: 'date_range',
            rendererType: 'DATE_RANGE',
            valueShape: 'COMPOSITE',
            bindings: [
              { key: 'start', valueType: 'DATE' },
              { key: 'end', valueType: 'DATE' },
            ],
          },
        },
      ],
    ]);

    const wrapper = mount(RecordFormFields, { props: { record: { schedule: '' }, fields } });

    expect(wrapper.find('[role="alert"]').text()).toContain('已拒绝编辑');
    expect(wrapper.findComponent({ name: 'UiInput' }).exists()).toBe(false);
  });

  it('refuses a multi-select without option binding instead of serializing the collection through UiInput', () => {
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'categoryCodes',
        {
          fieldRef: { fieldName: 'categoryCodes' },
          label: '分类',
          fieldControl: { alias: 'multi_select', rendererType: 'MULTI_SELECT', valueShape: 'COLLECTION' },
        },
      ],
    ]);
    const wrapper = mount(RecordFormFields, { props: { record: { categoryCodes: ['vip'] }, fields } });

    expect(wrapper.find('[role="alert"]').text()).toContain('已拒绝编辑');
    expect(wrapper.findComponent({ name: 'UiInput' }).exists()).toBe(false);
    expect(wrapper.findComponent({ name: 'UiSelect' }).exists()).toBe(false);
  });

  it('refuses a select without option binding instead of degrading the enum to free text', () => {
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'status',
        {
          fieldRef: { fieldName: 'status' },
          label: '状态',
          fieldControl: { alias: 'select', rendererType: 'SELECT', valueShape: 'SCALAR' },
        },
      ],
    ]);
    const wrapper = mount(RecordFormFields, { props: { record: { status: 'OPEN' }, fields } });

    expect(wrapper.find('[role="alert"]').text()).toContain('已拒绝编辑');
    expect(wrapper.findComponent({ name: 'UiInput' }).exists()).toBe(false);
    expect(wrapper.findComponent({ name: 'UiSelect' }).exists()).toBe(false);
  });

  it('keeps a bound multi-select payload as an array', () => {
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'categoryCodes',
        {
          fieldRef: { fieldName: 'categoryCodes' },
          label: '分类',
          fieldControl: { alias: 'multi_select', rendererType: 'MULTI_SELECT', valueShape: 'COLLECTION' },
          option: {
            binding: { sourceType: 'dictionary', source: 'crm.category' },
            selectionMode: 'MULTIPLE',
            inlineItems: [
              { code: 'vip', title: '重点客户', enabled: true },
              { code: 'new', title: '新客户', enabled: true },
            ],
          },
        },
      ],
    ]);
    const wrapper = mount(RecordFormFields, { props: { record: { categoryCodes: ['vip'] }, fields } });
    const select = wrapper.findComponent({ name: 'UiSelect' });

    expect(select.exists()).toBe(true);
    expect(select.props('mode')).toBe('multiple');
    select.vm.$emit('update:value', ['vip', 'new']);
    expect(wrapper.emitted('update:field')).toContainEqual(['categoryCodes', ['vip', 'new']]);
  });

  it('resolves child option fields with their entity alias while ordinary forms retain the module default', async () => {
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'attendanceStatus',
        {
          fieldRef: { fieldName: 'attendanceStatus' },
          label: '参加状态',
          option: {
            binding: { sourceType: 'dictionary', source: 'education.exam_attendance_status' },
            selectionMode: 'SINGLE',
          },
        },
      ],
    ]);
    const childRequest = vi.fn(async () => [{ code: 'ATTENDED', title: '已参加', enabled: true }]);
    const mainRequest = vi.fn(async () => [{ code: 'ATTENDED', title: '已参加', enabled: true }]);

    mount(RecordFormFields, {
      props: {
        record: { attendanceStatus: 'ATTENDED' },
        fields,
        optionContext: moduleContext('education.exam', childRequest),
        optionEntityAlias: 'exam_participant',
      },
    });
    mount(RecordFormFields, {
      props: {
        record: { attendanceStatus: 'ATTENDED' },
        fields,
        optionContext: moduleContext('education.exam', mainRequest),
      },
    });
    await flushPromises();

    expect(childRequest).toHaveBeenCalledWith(
      expect.objectContaining({
        path: '/platform.module/education.exam/fields/attendanceStatus/options',
        query: { enabledOnly: false, entityAlias: 'exam_participant' },
      }),
    );
    expect(mainRequest).toHaveBeenCalledWith(
      expect.objectContaining({
        path: '/platform.module/education.exam/fields/attendanceStatus/options',
        query: { enabledOnly: false },
      }),
    );
  });

  it('restores editor identities from display-enriched mutation response values', () => {
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'valueShape',
        {
          fieldRef: { fieldName: 'valueShape' },
          label: '值形态',
          fieldControl: { alias: 'select', rendererType: 'SELECT', valueShape: 'SCALAR' },
          option: {
            binding: { sourceType: 'enum', source: 'ValueShape' },
            selectionMode: 'SINGLE',
            inlineItems: [{ code: 'SCALAR', title: '标量', enabled: true }],
          },
        },
      ],
      [
        'defaultFieldSpecAlias',
        {
          fieldRef: { fieldName: 'defaultFieldSpecAlias' },
          label: '默认字段规格',
          uiType: 'recordPicker',
        },
      ],
    ]);
    const wrapper = mount(RecordFormFields, {
      props: {
        record: {
          valueShape: { code: 'SCALAR', title: '标量' },
          defaultFieldSpecAlias: { id: 'text', title: '长文本' },
        },
        fields,
        pickerConfigs: { defaultFieldSpecAlias: { context: {} as never } },
      },
    });

    expect(wrapper.findComponent({ name: 'UiSelect' }).props('value')).toBe('SCALAR');
    expect(wrapper.findComponent({ name: 'RecordPicker' }).props('value')).toBe('text');
  });

  it('applies a selected reference affect patch as ordinary form field updates', async () => {
    const fields = new Map<string, RecordFormFieldDescriptor>([
      ['customerId', { fieldRef: { fieldName: 'customerId' }, label: '客户', uiType: 'recordPicker' }],
    ]);
    const wrapper = mount(RecordFormFields, {
      props: { record: {}, fields, pickerConfigs: { customerId: { context: {} as never } } },
    });

    wrapper.findComponent({ name: 'RecordPicker' }).vm.$emit('select', {
      id: 'customer-1',
      affectPatch: { customerCode: 'C-001', customerName: '星云科技' },
    });
    await wrapper.vm.$nextTick();

    expect(wrapper.emitted('update:field')).toContainEqual(['customerCode', 'C-001']);
    expect(wrapper.emitted('update:field')).toContainEqual(['customerName', '星云科技']);
  });

  it('recomputes UI rules from declared single-reference projections without mutating the draft', async () => {
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'moduleAlias',
        {
          fieldRef: { fieldName: 'moduleAlias' },
          label: '模块',
          uiType: 'recordPicker',
          reference: {
            targetModuleAlias: 'platform.module',
            cardinality: 'ONE',
            selectionProjections: [{ path: ['entryType'] }],
          },
        },
      ],
      [
        'pageMode',
        {
          fieldRef: { fieldName: 'pageMode' },
          label: '页面模式',
          visible: {
            formula: {
              expression: "{moduleAlias.entryType} == 'MODULE'",
              program: {
                schemaVersion: 1,
                profile: 'WEB_UI',
                referencedFields: ['moduleAlias.entryType'],
                root: {
                  kind: 'BINARY',
                  operator: '==',
                  arguments: [
                    { kind: 'FIELD', field: 'moduleAlias.entryType', arguments: [] },
                    { kind: 'VALUE', value: 'MODULE', arguments: [] },
                  ],
                },
              },
            },
          },
        },
      ],
    ]);
    const record = { moduleAlias: 'platform.module', pageMode: 'LIST' };
    const wrapper = mount(RecordFormFields, {
      props: { record, fields, pickerConfigs: { moduleAlias: { context: {} as never } } },
    });
    const picker = wrapper.findComponent({ name: 'RecordPicker' });

    expect(wrapper.text()).not.toContain('页面模式');
    picker.vm.$emit('selection-resolved', {
      id: 'platform.module',
      projections: { entryType: 'MODULE', internalOnly: 'not-in-formula-context' },
    });
    await wrapper.vm.$nextTick();

    expect(wrapper.text()).toContain('页面模式');
    expect(record).toEqual({ moduleAlias: 'platform.module', pageMode: 'LIST' });
    expect(wrapper.emitted('update:field')).toBeUndefined();

    picker.vm.$emit('selection-resolved', undefined);
    await wrapper.vm.$nextTick();
    expect(wrapper.text()).not.toContain('页面模式');
  });

  it('applies affect patches from selected multi-value references in selection order', async () => {
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'customerIds',
        {
          fieldRef: { fieldName: 'customerIds' },
          label: '客户',
          uiType: 'text',
          reference: { targetModuleAlias: 'crm.customer', cardinality: 'MANY' },
        },
      ],
    ]);
    const wrapper = mount(RecordFormFields, {
      props: {
        record: { customerIds: [] },
        fields,
        pickerConfigs: { customerIds: { context: {} as never } },
      },
    });

    wrapper.findComponent({ name: 'RecordMultiPicker' }).vm.$emit('select', [
      { id: 'customer-1', affectPatch: { customerCode: 'C-001' } },
      { id: 'customer-2', affectPatch: { customerCode: 'C-002' } },
    ]);
    await wrapper.vm.$nextTick();

    expect(wrapper.emitted('update:field')).toContainEqual(['customerCode', 'C-001']);
    expect(wrapper.emitted('update:field')).toContainEqual(['customerCode', 'C-002']);
  });

  it('uses descriptor presentations for source references while retaining ID mutations and read projections', async () => {
    const provider = {
      identity: {
        targetModuleAlias: 'purchase.supplier',
        source: { kind: 'sourceField' as const, id: 'purchase.order:supplierId:resolve' },
      },
      searchPage: vi.fn().mockResolvedValue({ records: [], total: 0 }),
      resolve: vi.fn().mockResolvedValue([]),
    };
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'supplierId',
        {
          fieldRef: { fieldName: 'supplierId' },
          label: '供应商',
          fieldControl: {
            alias: 'record_picker',
            rendererType: 'RECORD_PICKER',
            valueShape: 'SCALAR',
            properties: { presentation: 'DIALOG' },
          },
          reference: {
            targetModuleAlias: 'purchase.supplier',
            cardinality: 'ONE',
            candidateDelivery: 'SOURCE_FIELD',
            displayProjections: [{ targetField: 'supplierCode', outputField: 'supplierCode' }],
          },
        },
      ],
      [
        'supplierIds',
        {
          fieldRef: { fieldName: 'supplierIds' },
          label: '可选供应商',
          fieldControl: {
            alias: 'record_picker',
            rendererType: 'RECORD_PICKER',
            valueShape: 'COLLECTION',
            properties: { presentation: 'DROPDOWN' },
          },
          reference: {
            targetModuleAlias: 'purchase.supplier',
            cardinality: 'MANY',
            candidateDelivery: 'SOURCE_FIELD',
          },
        },
      ],
    ]);
    const wrapper = mount(RecordFormFields, {
      props: {
        record: { supplierId: 'supplier-1', supplierIds: ['supplier-2'] },
        fields,
        pickerConfigs: {
          supplierId: { context: {} as never, provider },
          supplierIds: { context: {} as never, provider },
        },
      },
    });
    let pickers = wrapper.findAllComponents({ name: 'ReferencePicker' });

    expect(pickers).toHaveLength(2);
    expect(pickers[0]!.props('mode')).toBe('dialog');
    expect(pickers[0]!.props('multiple')).toBe(false);
    expect(pickers[1]!.props('mode')).toBe('dropdown');
    expect(pickers[1]!.props('multiple')).toBe(true);

    // A retained ID is not sufficient while the reference input contains an unresolved draft.
    for (const picker of pickers) {
      for (const status of ['editing', 'resolving', 'unmatched', 'error'] as const) {
        picker.vm.$emit('validity-change', { valid: false, status, message: '请完成引用选择' });
        expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([
          expect.objectContaining({ valid: false }),
        ]);
        expect(wrapper.emitted('update:field')).toBeUndefined();
      }
      picker.vm.$emit('validity-change', { valid: true, status: 'ready' });
      expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([{ valid: true, errors: {} }]);
    }
    pickers[0]!.vm.$emit('validity-change', { valid: false, status: 'editing' });
    await wrapper.setProps({ validationRequestKey: 1 });
    expect(wrapper.findAllComponents({ name: 'ReferencePicker' })[0]!.element).toBe(pickers[0]!.element);
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([expect.objectContaining({ valid: false })]);
    await wrapper.setProps({ disabled: true });
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([{ valid: true, errors: {} }]);
    await wrapper.setProps({ disabled: false });
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([expect.objectContaining({ valid: false })]);
    await wrapper.setProps({ formSessionKey: 'next-edit' });
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([{ valid: true, errors: {} }]);
    pickers = wrapper.findAllComponents({ name: 'ReferencePicker' });

    pickers[0]!.vm.$emit('update:value', 'supplier-3');
    pickers[0]!.vm.$emit('selection-resolved', [
      { id: 'supplier-3', title: '新供应商', projections: { supplierCode: 'SUP-003' } },
    ]);
    pickers[1]!.vm.$emit('update:value', ['supplier-4', 'supplier-5']);
    await flushPromises();

    expect(wrapper.emitted('update:field')).toContainEqual(['supplierId', 'supplier-3']);
    expect(wrapper.emitted('update:field')).toContainEqual(['supplierIds', ['supplier-4', 'supplier-5']]);
    expect(wrapper.emitted('reference-projections-change')).toContainEqual([
      'supplierId',
      { supplierCode: 'SUP-003' },
    ]);
  });

  it('uses dictionary-only renderers without changing code-valued form mutations', async () => {
    const DictionaryPickerStub = defineComponent({
      name: 'DictionaryPicker',
      props: ['value', 'items', 'selectionMode', 'mode'],
      emits: ['update:value'],
      template: '<div class="dictionary-picker-stub" />',
    });
    const DictionaryRadioGroupStub = defineComponent({
      name: 'DictionaryRadioGroup',
      props: ['value', 'items', 'maxOptions', 'disabled'],
      emits: ['update:value'],
      template: '<div class="dictionary-radio-group-stub" />',
    });
    const dictionaryOption = {
      binding: { sourceType: 'dictionary', source: 'education.exam_status' },
      selectionMode: 'SINGLE' as const,
      inlineItems: [
        { code: 'draft', title: '草稿', enabled: true },
        { code: 'published', title: '已发布', enabled: true },
      ],
    };
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'status',
        {
          fieldRef: { fieldName: 'status' },
          label: '状态',
          fieldControl: {
            alias: 'dictionary_dialog',
            rendererType: 'DICTIONARY_PICKER',
            valueShape: 'SCALAR',
            properties: {},
          },
          option: dictionaryOption,
        },
      ],
      [
        'visibility',
        {
          fieldRef: { fieldName: 'visibility' },
          label: '可见性',
          fieldControl: {
            alias: 'dictionary_radio',
            rendererType: 'DICTIONARY_RADIO_GROUP',
            valueShape: 'SCALAR',
            properties: { maxOptions: '12' },
          },
          option: dictionaryOption,
        },
      ],
    ]);
    const wrapper = mount(RecordFormFields, {
      props: { record: { status: 'draft', visibility: 'published' }, fields },
      global: {
        stubs: {
          DictionaryPicker: DictionaryPickerStub,
          DictionaryRadioGroup: DictionaryRadioGroupStub,
        },
      },
    });

    const dialog = wrapper.findComponent(DictionaryPickerStub);
    const radio = wrapper.findComponent(DictionaryRadioGroupStub);
    expect(dialog.props('value')).toBe('draft');
    expect(dialog.props('selectionMode')).toBe('SINGLE');
    expect(dialog.props('mode')).toBe('dialog');
    expect(radio.props('value')).toBe('published');
    expect(radio.props('maxOptions')).toBe('12');

    dialog.vm.$emit('update:value', 'published');
    radio.vm.$emit('update:value', 'draft');
    await wrapper.vm.$nextTick();
    expect(wrapper.emitted('update:field')).toContainEqual(['status', 'published']);
    expect(wrapper.emitted('update:field')).toContainEqual(['visibility', 'draft']);
  });

  it('normalizes lower-case CodeTitleEnum cardinality before the unified dictionary picker emits a value', async () => {
    const DictionaryPickerStub = defineComponent({
      name: 'DictionaryPicker',
      props: ['value', 'items', 'selectionMode', 'mode'],
      emits: ['update:value'],
      template: '<div class="dictionary-picker-stub" />',
    });
    const wrapper = mount(RecordFormFields, {
      props: {
        record: { status: 'draft' },
        fields: new Map<string, RecordFormFieldDescriptor>([
          [
            'status',
            {
              fieldRef: { fieldName: 'status' },
              label: '状态',
              fieldControl: {
                alias: 'dictionary_dialog',
                rendererType: 'DICTIONARY_PICKER',
                valueShape: 'SCALAR',
                properties: {},
              },
              option: {
                binding: { sourceType: 'dictionary', source: 'education.exam_status' },
                selectionMode: 'single' as never,
              },
            },
          ],
        ]),
      },
      global: { stubs: { DictionaryPicker: DictionaryPickerStub } },
    });

    const dialog = wrapper.findComponent(DictionaryPickerStub);
    expect(dialog.props('selectionMode')).toBe('SINGLE');
    expect(dialog.props('mode')).toBe('dialog');
    dialog.vm.$emit('update:value', 'published');
    await wrapper.vm.$nextTick();
    expect(wrapper.emitted('update:field')).toContainEqual(['status', 'published']);
  });

  it('routes dictionary dropdown aliases through the same picker while preserving code arrays', async () => {
    const DictionaryPickerStub = defineComponent({
      name: 'DictionaryPicker',
      props: ['value', 'items', 'selectionMode', 'mode'],
      emits: ['update:value'],
      template: '<div class="dictionary-picker-stub" />',
    });
    const wrapper = mount(RecordFormFields, {
      props: {
        record: { statuses: ['draft'] },
        fields: new Map<string, RecordFormFieldDescriptor>([
          [
            'statuses',
            {
              fieldRef: { fieldName: 'statuses' },
              label: '状态集合',
              fieldControl: {
                alias: 'dictionary_multi_dropdown',
                rendererType: 'MULTI_SELECT',
                valueShape: 'COLLECTION',
                properties: {},
              },
              option: {
                binding: { sourceType: 'dictionary', source: 'education.exam_status' },
                selectionMode: 'MULTIPLE',
              },
            },
          ],
        ]),
      },
      global: { stubs: { DictionaryPicker: DictionaryPickerStub } },
    });

    const picker = wrapper.findComponent(DictionaryPickerStub);
    expect(picker.props('mode')).toBe('dropdown');
    expect(picker.props('selectionMode')).toBe('MULTIPLE');
    expect(picker.props('value')).toEqual(['draft']);
    picker.vm.$emit('update:value', ['published', 'archived']);
    await wrapper.vm.$nextTick();
    expect(wrapper.emitted('update:field')).toContainEqual(['statuses', ['published', 'archived']]);
  });

  it('routes the default dictionary select through the unified dropdown without changing enum selects', () => {
    const DictionaryPickerStub = defineComponent({
      name: 'DictionaryPicker',
      props: ['mode'],
      template: '<div class="dictionary-picker-stub" />',
    });
    const wrapper = mount(RecordFormFields, {
      props: {
        record: {},
        fields: new Map<string, RecordFormFieldDescriptor>([
          [
            'dictionaryStatus',
            {
              fieldRef: { fieldName: 'dictionaryStatus' },
              label: '字典状态',
              option: {
                binding: { sourceType: 'dictionary', source: 'education.exam_status' },
                selectionMode: 'SINGLE',
                inlineItems: [{ code: 'draft', title: '草稿', enabled: true }],
              },
            },
          ],
          [
            'enumStatus',
            {
              fieldRef: { fieldName: 'enumStatus' },
              label: '枚举状态',
              option: {
                binding: { sourceType: 'enum', source: 'status' },
                selectionMode: 'SINGLE',
                inlineItems: [{ code: 'draft', title: '草稿', enabled: true }],
              },
            },
          ],
        ]),
      },
      global: { stubs: { DictionaryPicker: DictionaryPickerStub } },
    });

    expect(wrapper.findAllComponents(DictionaryPickerStub)).toHaveLength(1);
    expect(wrapper.findComponent(DictionaryPickerStub).props('mode')).toBe('dropdown');
    expect(wrapper.findAllComponents({ name: 'UiSelect' })).toHaveLength(1);
  });

  it('blocks form submission for an unmatched dictionary draft without duplicating the picker-level visual prompt', async () => {
    const DictionaryPickerStub = defineComponent({
      name: 'DictionaryPicker',
      emits: ['validity-change'],
      template: '<div class="dictionary-picker-stub" />',
    });
    const wrapper = mount(RecordFormFields, {
      props: {
        record: { status: 'enabled' },
        fields: new Map<string, RecordFormFieldDescriptor>([
          [
            'status',
            {
              fieldRef: { fieldName: 'status' },
              label: '验收状态',
              option: {
                binding: { sourceType: 'dictionary', source: 'education.exam_status' },
                selectionMode: 'SINGLE',
                inlineItems: [{ code: 'enabled', title: '启用', enabled: true }],
              },
            },
          ],
        ]),
      },
      global: { stubs: { DictionaryPicker: DictionaryPickerStub } },
    });

    wrapper.findComponent(DictionaryPickerStub).vm.$emit('validity-change', {
      valid: false,
      status: 'unmatched',
      message: '未找到匹配的字典项',
    });
    await wrapper.vm.$nextTick();

    expect(wrapper.text()).not.toContain('未找到匹配的字典项');
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([
      { valid: false, errors: { status: '未找到匹配的字典项' } },
    ]);
    expect(wrapper.emitted('update:field')).toBeUndefined();
  });

  it('blocks radio rendering for a hierarchical or oversized dictionary instead of truncating candidates', async () => {
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'status',
        {
          fieldRef: { fieldName: 'status' },
          label: '状态',
          fieldControl: {
            alias: 'dictionary_radio',
            rendererType: 'DICTIONARY_RADIO_GROUP',
            valueShape: 'SCALAR',
            properties: { maxOptions: '1' },
          },
          option: {
            binding: { sourceType: 'dictionary', source: 'education.exam_status' },
            selectionMode: 'SINGLE',
            inlineItems: [
              { code: 'parent', title: '父项', enabled: true },
              { code: 'child', title: '子项', enabled: true, parentCode: 'parent' },
            ],
          },
        },
      ],
    ]);
    const wrapper = mount(RecordFormFields, { props: { record: { status: 'child' }, fields } });

    expect(wrapper.text()).toContain('层级字典不支持 radio 单选组');
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([
      expect.objectContaining({
        valid: false,
        errors: expect.objectContaining({ status: expect.any(String) }),
      }),
    ]);
  });
});

function moduleContext(moduleAlias: string, request: ReturnType<typeof vi.fn>): ModuleContext<unknown> {
  return { moduleAlias, http: { request } } as never;
}
