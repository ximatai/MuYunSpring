import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import RecordFormFields from '@/platform-components/RecordFormFields.vue';
import type { RecordFormFieldDescriptor } from '@/platform-components/recordFormFieldModel.ts';

describe('RecordFormFields', () => {
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
            inlineItems: [{ code: 'vip', title: '重点客户', enabled: true }],
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
});
