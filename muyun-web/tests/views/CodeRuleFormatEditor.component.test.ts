import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { UiSelect, UiInput } from '@muyun/vue-ui-antdv';
import CodeRuleFormatEditor from '@/views/CodeRuleFormatEditor.vue';
import { newCodeRule, newCodeSegment, type CodeTarget } from '@/views/codeConfiguration';

const target: CodeTarget = {
  moduleAlias: 'demo.order',
  entityAlias: 'order',
  title: '订单',
  source: 'STATIC',
  fields: [{ fieldName: 'code', title: '编号' }],
  sources: [],
};
function rule() {
  return newCodeRule(target, target.fields[0]!);
}
describe('CodeRuleFormatEditor advanced options', () => {
  it('offers source and counting policies only for applicable segments', () => {
    const draft = rule();
    draft.segments = [
      newCodeSegment('CONSTANT'),
      newCodeSegment('SYSTEM_TIME'),
      newCodeSegment('FIELD_VALUE'),
      newCodeSegment('SEQUENCE'),
    ];
    const wrapper = mount(CodeRuleFormatEditor, { props: { rule: draft, sources: [], editable: true } });
    try {
      const cards = wrapper.findAll('.code-format__segments > li');
      expect(cards[0]!.text()).not.toContain('来源为空时');
      expect(cards[0]!.text()).not.toContain('按该片段分别计数');
      expect(cards[1]!.text()).not.toContain('来源为空时');
      expect(cards[1]!.text()).toContain('按该片段分别计数');
      expect(cards[2]!.text()).toContain('来源为空时');
      expect(cards[3]!.text()).not.toContain('补位');
      expect(cards[3]!.text()).not.toContain('超长截取');
      expect(cards[3]!.text()).not.toContain('来源为空时');
      expect(cards[3]!.get('input[type="number"]').attributes('placeholder')).toBe('继承流水长度：4');
      expect(wrapper.text()).toContain('最大 9999');
    } finally {
      wrapper.unmount();
    }
  });
  it('keeps previously configured special options editable', () => {
    const draft = rule();
    Object.assign(draft.segments[0]!, { nullPolicy: 'SKIP_SEGMENT', sequenceBasis: true });
    Object.assign(draft.segments[1]!, { padMode: 'RIGHT', padChar: 'X', truncatePolicy: 'LEFT' });
    const wrapper = mount(CodeRuleFormatEditor, { props: { rule: draft, sources: [], editable: true } });
    try {
      const cards = wrapper.findAll('.code-format__segments > li');
      expect(cards[0]!.text()).toContain('来源为空时');
      expect(cards[0]!.text()).toContain('按该片段分别计数');
      expect(cards[1]!.text()).toContain('补位字符');
      expect(cards[1]!.text()).toContain('超长截取');
      expect(cards[1]!.find('input[value="X"]').exists()).toBe(true);
    } finally {
      wrapper.unmount();
    }
  });
  it('explains that a missing mapping source is defaulted before mapping', () => {
    const draft = rule();
    draft.segments[0] = {
      ...newCodeSegment('VALUE_MAPPING'),
      nullPolicy: 'USE_DEFAULT',
      fixedValue: 'other',
    };
    const wrapper = mount(CodeRuleFormatEditor, { props: { rule: draft, sources: [], editable: true } });
    try {
      expect(wrapper.text()).toContain('来源默认值');
      expect(wrapper.text()).toContain('默认值仍需匹配下方映射');
    } finally {
      wrapper.unmount();
    }
  });
});

it('restores per-type drafts and sequence policy while exploring segment types', async () => {
  const draft = rule();
  draft.segments[0]!.fixedValue = 'ACC-';
  draft.sequencePolicy!.startValue = 20;
  draft.sequencePolicy!.stepValue = 3;
  const wrapper = mount(CodeRuleFormatEditor, { props: { rule: draft, sources: [], editable: true } });
  try {
    const change = async (index: number, type: string) => {
      await wrapper
        .findAll('.code-format__segments > li')
        .at(index)!
        .findComponent(UiSelect)
        .vm.$emit('update:value', type);
    };
    await change(0, 'FIELD_VALUE');
    await change(0, 'CONSTANT');
    expect(draft.segments[0]!.fixedValue).toBe('ACC-');
    await change(1, 'CONSTANT');
    expect(draft.sequencePolicy).toBeNull();
    await change(1, 'SEQUENCE');
    expect(draft.sequencePolicy).toMatchObject({ startValue: 20, stepValue: 3 });
    await change(0, 'FIELD_VALUE');
    await change(0, 'CONSTANT');
    expect(draft.segments[0]!.fixedValue).toBe('ACC-');
  } finally {
    wrapper.unmount();
  }
});

it('selects declared context sources for save-triggered fields and accepts external names only for explicit actions', async () => {
  const draft = rule();
  draft.segments[0] = newCodeSegment('CONTEXT_VAR');
  const sources = [{ fieldName: 'category', title: '类别', type: 'STRING' }];
  const wrapper = mount(CodeRuleFormatEditor, {
    props: { rule: draft, sources, editable: true, trigger: 'SAVE' },
  });
  try {
    const context = () =>
      wrapper.findAll('label').find((item) => item.text().startsWith('动作传入的上下文名称'))!;
    expect(context().findComponent(UiSelect).props('options')).toEqual([
      { value: 'category', label: '类别' },
    ]);
    await wrapper.setProps({ trigger: 'EXPLICIT' });
    expect(context().findComponent(UiInput).exists()).toBe(true);
  } finally {
    wrapper.unmount();
  }
});
