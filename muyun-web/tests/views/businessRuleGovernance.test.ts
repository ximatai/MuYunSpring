import { describe, expect, it } from 'vitest';
import {
  businessRuleChangeImpact,
  editableProposals,
  externalTrialInputFields,
  filterBusinessRuleFields,
  formulaTemplates,
  insertFormulaText,
  newBusinessRule,
  presentableFormulaExpression,
  referencedFormulaFields,
  readonlyRules,
  typedSampleValue,
  formulaFieldUnusableReason,
  aggregateFieldInsertionReason,
  normalizeFormulaCapabilities,
  searchableFormulaCapabilities,
  type BusinessRuleSnapshot,
} from '@/views/businessRuleGovernance';

const snapshot: BusinessRuleSnapshot = {
  moduleAlias: 'education.exam',
  baselineFingerprint: 'baseline',
  editableFields: [],
  rules: [
    {
      code: 'calculation_total',
      kind: 'CALCULATION',
      phase: 'FORM_COMPUTE',
      targetField: 'total',
      expression: '{quantity} * {unitPrice}',
      enabled: true,
      editable: true,
    },
    {
      code: 'legacy_default',
      kind: 'DEFAULT',
      phase: 'BEFORE_SAVE',
      expression: '1',
      enabled: true,
      editable: false,
      readOnlyReason: '默认值在首期不提供治理入口。',
    },
  ],
};

describe('business-rule governance proposal mapping', () => {
  it('keeps editable calculation expressions as server-supplied right-hand-side text', () => {
    expect(editableProposals(snapshot)).toEqual([
      {
        code: 'calculation_total',
        kind: 'CALCULATION',
        targetField: 'total',
        expression: '{quantity} * {unitPrice}',
        enabled: true,
      },
    ]);
  });

  it('retains unsupported snapshot rules as read-only facts', () => {
    expect(readonlyRules(snapshot)).toEqual([snapshot.rules[1]]);
  });

  it('generates stable server-legal ASCII codes and avoids every retained snapshot code', () => {
    expect(newBusinessRule('CALCULATION', [{ code: 'calculation1' }, { code: 'calculation2' }])).toEqual({
      code: 'calculation3',
      kind: 'CALCULATION',
      expression: '',
      enabled: true,
    });
  });

  it('turns numeric and boolean samples into typed request values', () => {
    expect(typedSampleValue('12.5', 'DECIMAL')).toBe(12.5);
    expect(typedSampleValue('true', 'BOOLEAN')).toBe(true);
    expect(typedSampleValue('2026-09-10', 'DATE')).toBe('2026-09-10');
    expect(typedSampleValue('', 'STRING')).toBeUndefined();
  });

  it('searches field business titles first while retaining identifiers and types as supplements', () => {
    const fields = [
      { fieldName: 'quantity', title: '数量', fieldSpecAlias: 'decimal', valueType: 'DECIMAL' },
      { fieldName: 'enabled', title: '启用', fieldSpecAlias: 'boolean', valueType: 'BOOLEAN' },
    ];
    expect(filterBusinessRuleFields(fields, '数量')).toEqual([fields[0]]);
    expect(filterBusinessRuleFields(fields, 'boolean')).toEqual([fields[1]]);
    expect(filterBusinessRuleFields(fields, 'quantity')).toEqual([fields[0]]);
  });

  it('inserts a field at the selection and keeps the cursor after replacement text', () => {
    expect(insertFormulaText('{quantity} * 10', 13, 15, '{unitPrice}')).toEqual({
      value: '{quantity} * {unitPrice}',
      selectionStart: 24,
      selectionEnd: 24,
    });
  });

  it('only permits child fields in compatible aggregate arguments', () => {
    const productName = {
      id: 'lines.productName',
      name: 'lines.productName',
      label: '产品名称',
      valueType: 'STRING',
      aggregateFunctions: ['COUNT'],
    };
    expect(aggregateFieldInsertionReason(productName, 'SUM(', 4)).toContain('不能用于 SUM');
    expect(aggregateFieldInsertionReason(productName, 'COUNT(', 6)).toBeUndefined();
    expect(aggregateFieldInsertionReason(productName, '{amount} + ', 11)).toContain('只能作为');
  });

  it('does not treat field-shaped text in a quoted literal as a dependency', () => {
    expect(
      referencedFormulaFields("PRESENT({supplier.organization.regionCode}) && '{amount}' == '{ignored}'"),
    ).toEqual(['supplier.organization.regionCode']);
  });

  it('uses business names only for executable references in a readable expression', () => {
    const fields = [
      { fieldName: 'quantity', title: '数量', fieldSpecAlias: 'decimal', valueType: 'DECIMAL' },
    ];
    expect(presentableFormulaExpression("PRESENT({quantity}) && '{quantity}' == 'literal'", fields)).toBe(
      "PRESENT({数量（quantity）}) && '{quantity}' == 'literal'",
    );
  });

  it('uses only external inputs for a chain of enabled calculations', () => {
    const fields = [
      { fieldName: 'quantity', title: '数量', fieldSpecAlias: 'decimal', valueType: 'DECIMAL' },
      { fieldName: 'unitPrice', title: '单价', fieldSpecAlias: 'decimal', valueType: 'DECIMAL' },
      { fieldName: 'amount', title: '金额', fieldSpecAlias: 'decimal', valueType: 'DECIMAL' },
      { fieldName: 'taxedAmount', title: '含税金额', fieldSpecAlias: 'decimal', valueType: 'DECIMAL' },
    ];
    expect(
      externalTrialInputFields(fields, [
        {
          code: 'amount',
          kind: 'CALCULATION',
          targetField: 'amount',
          expression: '{quantity} * {unitPrice}',
          enabled: true,
        },
        {
          code: 'taxed',
          kind: 'CALCULATION',
          targetField: 'taxedAmount',
          expression: '{amount} * 1.06',
          enabled: true,
        },
        { code: 'validate', kind: 'VALIDATION', expression: '{taxedAmount} > 0', enabled: true },
      ]),
    ).toEqual([fields[0], fields[1]]);
  });

  it('offers actual eligible templates and reports added, modified, and deleted proposals', () => {
    const fields = [
      { fieldName: 'quantity', title: '数量', fieldSpecAlias: 'decimal', valueType: 'DECIMAL' },
      { fieldName: 'unitPrice', title: '单价', fieldSpecAlias: 'decimal', valueType: 'DECIMAL' },
      { fieldName: 'amount', title: '金额', fieldSpecAlias: 'decimal', valueType: 'DECIMAL' },
    ];
    expect(formulaTemplates('CALCULATION', fields, 'amount')[0]?.expression).toBe('{quantity} * {unitPrice}');
    expect(formulaTemplates('VALIDATION', fields, 'quantity')[0]?.expression).toBe('PRESENT({quantity})');
    expect(
      businessRuleChangeImpact(snapshot, [
        { ...editableProposals(snapshot)[0]!, expression: '{quantity} * 2' },
        { code: 'validation1', kind: 'VALIDATION', expression: 'PRESENT({quantity})', enabled: true },
      ]),
    ).toMatchObject({
      added: [{ code: 'validation1' }],
      modified: [{ code: 'calculation_total' }],
      deleted: [],
    });
  });

  it('uses the server catalogue as the single portable function description', () => {
    const capabilities = normalizeFormulaCapabilities([
      {
        name: 'PRESENT',
        category: '条件',
        title: '已填写',
        description: '判断字段是否有值',
        parameters: [{ name: 'value', description: '字段' }],
        returnType: 'BOOLEAN',
        example: 'PRESENT({quantity})',
      },
      { name: 'UNSUPPORTED', title: '不应显示' },
    ]);
    expect(capabilities).toEqual([
      expect.objectContaining({
        id: 'PRESENT',
        category: '条件',
        purpose: '已填写',
        description: '判断字段是否有值',
      }),
      expect.objectContaining({ id: 'UNSUPPORTED', insertion: 'UNSUPPORTED()' }),
    ]);
    expect(searchableFormulaCapabilities(capabilities, '填写')).toHaveLength(1);
    expect(normalizeFormulaCapabilities([])).toEqual([]);
    expect(normalizeFormulaCapabilities([{ name: 'COALESCE', title: '取第一个值' }])).toEqual([
      expect.objectContaining({ id: 'COALESCE', insertion: 'COALESCE()' }),
    ]);
  });

  it('makes only server-readable scalar roots draggable formula sources', () => {
    expect(
      formulaFieldUnusableReason({
        id: 'lines',
        name: 'lines',
        valueType: 'REFERENCE',
        referenceCardinality: 'MANY',
      }),
    ).toContain('集合引用');
    expect(
      formulaFieldUnusableReason({
        id: 'custom',
        name: 'custom',
        valueType: 'STRING',
        formulaReadable: false,
        formulaDisabledReason: '自定义键引用不能读取',
      }),
    ).toBe('自定义键引用不能读取');
    expect(
      formulaFieldUnusableReason({
        id: 'supplier.title',
        name: 'supplier.title',
        valueType: 'STRING',
        readOnly: true,
      }),
    ).toBeUndefined();
    expect(
      formulaFieldUnusableReason({
        id: 'supplier.secret',
        name: 'supplier.secret',
        valueType: 'STRING',
        formulaReadable: false,
        formulaDisabledReason: '受保护引用字段不能读取',
      }),
    ).toBe('受保护引用字段不能读取');
  });
});
