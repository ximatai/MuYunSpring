import { createConstructionDeliveryCapabilities } from './constructionDelivery';
import { createConstructionReferenceDiscoveryCapabilities } from './constructionReferenceDiscovery';
import { createConstructionFieldCapabilities } from './constructionFields';
import { createConstructionInitializationCapabilities } from './constructionInitialization';
import { shallowRef } from 'vue';
import { constructionPlanLimits } from '@muyun/web-contracts';
import type {
  ConstructionTask,
  ConstructionPlanContent,
  ConstructionPlanSnapshot,
  ConstructionPlanSummary,
} from '@muyun/web-contracts';
import {
  createUuid,
  codeOperationErrorMessage,
  type OperationErrorMessage,
  platformLanguageContext,
  type LanguageContext,
  AppError,
  AssistantCapabilityUsageError,
  AssistantOperationRejectedError,
  emptyAssistantCapabilityInputSchema,
  parseEmptyAssistantCapabilityInput,
  type AssistantCapability,
  type AssistantOperationProposal,
  type ConstructionPlanClient,
} from '@muyun/web-core';

const listKeys = [
  'inScope',
  'outOfScope',
  'relationships',
  'rules',
  'questions',
  'assumptions',
  'acceptanceExamples',
] as const;
export const planSections: Record<(typeof listKeys)[number], string> = {
  inScope: '本期范围',
  outOfScope: '暂不建设',
  relationships: '对象关系',
  rules: '业务规则',
  questions: '未决问题',
  assumptions: '待验证假设',
  acceptanceExamples: '验收例子',
};
const textSchema = (maxLength: number) => ({ type: 'string', minLength: 1, maxLength });
const itemSchema = (properties: Record<string, unknown>) => ({
  type: 'object',
  additionalProperties: false,
  required: Object.keys(properties),
  properties,
});
const arraySchema = (items: unknown, maxItems: number = constructionPlanLimits.structuredItems) => ({
  type: 'array',
  maxItems,
  items,
});
export function localizedPlanSections(language: LanguageContext = platformLanguageContext) {
  return Object.fromEntries(
    Object.entries(planSections).map(([key, original]) => [
      key,
      language.t(`platform.construction.section.${key}`, original),
    ]),
  ) as typeof planSections;
}
export function createConstructionPlanContentSchema(language: LanguageContext = platformLanguageContext) {
  const sections = localizedPlanSections(language);
  return itemSchema({
    title: textSchema(120),
    goal: textSchema(1500),
    ...Object.fromEntries(
      listKeys.map((key) => [
        key,
        {
          ...arraySchema(textSchema(500), constructionPlanLimits.textItems),
          description: language.t(
            'platform.screen.constructionPlanSession.value-at-most-value-items-summarize-related-details-by-business',
            '{value1}：最多{value2}项，按业务目标归纳相关细节，不要求每个字段独占一项；一项可由多个 requirements 兑现。不要删减已确认需求。',
            { value1: sections[key], value2: constructionPlanLimits.textItems },
          ),
        },
      ]),
    ),
    questions: {
      ...arraySchema(textSchema(500), constructionPlanLimits.textItems),
      description: language.t(
        'platform.screen.constructionPlanSession.record-only-unresolved-questions-that-affect-current-scope-and-still',
        '仅记录仍待用户决定且影响本期范围的问题。用户已回答的问题应移出此处并落实到范围、规则或决定；已决定暂不做的内容放 outOfScope，不能同时留作未决问题。不要丢弃真正未回答的问题。',
      ),
    },
    assumptions: {
      ...arraySchema(textSchema(500), constructionPlanLimits.textItems),
      description: language.t(
        'platform.screen.constructionPlanSession.record-only-unverified-assumptions-put-explicit-user-requirements-into-scope',
        '仅记录尚未证实的假设；用户已明确的要求记入范围、规则或决定，实际使用验证步骤记入 acceptanceExamples。',
      ),
    },
    objects: {
      description: language.t(
        'platform.screen.constructionPlanSession.modules-that-require-independent-management-in-the-current-scope-configure',
        '本期需要独立管理的模块。各项通过标准治理独立配置；moduleAlias 仅填写已发现的实际标准模块，关联不创建配置，未关联填写空字符串；仅作为某个模块内部明细的子表不要另列对象，以所属模块的 CHILD 需求和 relation.field 字段路径表达。对象 key 是方案标识，不是 moduleAlias 或 metadataId。',
      ),
      ...arraySchema(
        itemSchema({
          key: { ...textSchema(64), pattern: '^[a-z][a-z0-9_-]*$' },
          name: textSchema(120),
          purpose: textSchema(500),
          moduleAlias: {
            type: ['string', 'null'],
            maxLength: 128,
            description: language.t(
              'platform.screen.constructionPlanSession.alias-of-a-discovered-standard-module-an-empty-string-removes',
              '已发现的标准模块别名；空字符串解除关联，null 保留旧方案历史关联。不得从对象 key 猜测。',
            ),
          },
        }),
      ),
    },
    requirements: {
      description: language.t(
        'platform.screen.constructionPlanSession.technical-requirement-mappings-may-be-empty-when-discussing-or-saving',
        '技术兑现映射。讨论和保存业务共识时可以为空；实施前按标准能力逐步补齐，不为凑齐映射而猜测字段或将普通录入伪装为人工补救。',
      ),
      type: 'array',
      maxItems: 64,
      items: itemSchema({
        section: { type: 'string', enum: ['SCOPE', 'RULE', 'RELATION'] },
        index: { type: 'integer', minimum: 0, maximum: constructionPlanLimits.textItems - 1 },
        objectKey: {
          ...textSchema(64),
          description: language.t(
            'platform.screen.constructionPlanSession.the-owning-independent-module-s-key-in-objects-child-tables',
            '所属独立模块在 objects 中的 key；子表及其字段仍填写父模块的 key。',
          ),
        },
        mode: {
          type: 'string',
          enum: ['FIELD', 'REQUIRED', 'UNIQUE', 'REFERENCE', 'CHILD', 'CALCULATION', 'MANUAL', 'UNSUPPORTED'],
          description: language.t(
            'platform.screen.constructionPlanSession.describe-how-the-system-fulfills-a-requirement-not-how-it',
            '表达系统如何兑现需求，不表达测试方式。CALCULATION 等自动功能即使需要人工试算，仍按实际配置绑定，试算写入 acceptanceExamples。MANUAL 表示已明确由人承担的业务步骤，不是待用户确认或尚未验收；普通表单录入用对应字段检查，不能用 MANUAL 替代尚未核实的自动能力；先查能力目录，无法兑现时用 UNSUPPORTED 并协商范围。RELATION 只能使用 REFERENCE、CHILD 或 UNSUPPORTED，不能用普通字段存在代替关联。',
          ),
        },
        fieldName: {
          type: 'string',
          maxLength: 128,
          description: language.t(
            'platform.screen.constructionPlanSession.field-required-unique-reference-calculation-require-a-field-name-field',
            'FIELD/REQUIRED/UNIQUE/REFERENCE/CALCULATION 必须填写字段名，主表用 field，直接子表用 relation.field；CHILD 只填关系标识（小写字母开头，小写字母数字下划线，最多63字符），不能填实体路径；MANUAL/UNSUPPORTED 必须填空字符串。',
          ),
        },
        explanation: textSchema(500),
        reference: {
          anyOf: [
            { type: 'null' },
            itemSchema({
              objectKey: { type: 'string', maxLength: 64 },
              moduleAlias: { type: 'string', maxLength: 128 },
            }),
          ],
        },
      }),
    },
    decisions: arraySchema(
      itemSchema({
        statement: textSchema(500),
        source: { type: 'string', enum: ['USER_REQUIREMENT', 'RECOMMENDATION'] },
      }),
    ),
  });
}
export const constructionPlanContentSchema = createConstructionPlanContentSchema();
const planChangeKeys = ['title', 'goal', ...listKeys, 'decisions'] as const;
function planChangesSchema(schema: typeof constructionPlanContentSchema) {
  return {
    type: 'object',
    additionalProperties: false,
    minProperties: 1,
    properties: Object.fromEntries(planChangeKeys.map((key) => [key, schema.properties[key]])),
  };
}
const constructionPlanChangesSchema = planChangesSchema(constructionPlanContentSchema);
function planParser(language: LanguageContext) {
  function fail(message: string | OperationErrorMessage): never {
    throw new AssistantCapabilityUsageError(message);
  }
  function record(value: unknown): Record<string, unknown> {
    if (!value || typeof value !== 'object' || Array.isArray(value))
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.invalid-plan-format',
          '方案格式无效',
        ),
      );
    return value as Record<string, unknown>;
  }
  function text(value: unknown, max: number): string {
    if (typeof value !== 'string' || !value.trim() || value.length > max)
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.plan-text-is-empty-or-exceeds-the-length-limit',
          '方案文本为空或超出长度限制',
        ),
      );
    return value.trim();
  }
  function list<T>(
    value: unknown,
    parse: (entry: unknown) => T,
    path: string,
    max: number = constructionPlanLimits.structuredItems,
  ): T[] {
    if (!Array.isArray(value))
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.value-must-be-an-array',
          '{value1} 必须是数组',
          {
            value1: path,
          },
        ),
      );
    if (value.length > max)
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.value-has-value-items-the-limit-is-value-summarize-related',
          '{value1} 当前 {value2} 项，最多 {value3} 项。请归纳相关业务要求，不删除已确认需求；同一项可用多个 requirements 分别兑现。',
          { value1: path, value2: value.length, value3: max },
        ),
      );
    return value.map(parse);
  }
  return { fail, record, text, list };
}
export function parseConstructionPlan(
  value: unknown,
  language: LanguageContext = platformLanguageContext,
): ConstructionPlanContent {
  const parser = planParser(language);
  const { record, text, list } = parser;
  const fail: (message: string | OperationErrorMessage) => never = parser.fail;
  const input = record(value);
  if (Object.keys(input).some((key) => !Object.hasOwn(constructionPlanContentSchema.properties, key)))
    fail(
      codeOperationErrorMessage(
        language,
        'platform.screen.constructionPlanSession.the-plan-contains-unsupported-properties',
        '方案包含不支持的属性',
      ),
    );
  const content = {
    title: text(input.title, 120),
    goal: text(input.goal, 1500),
    ...Object.fromEntries(
      listKeys.map((key) => [
        key,
        list(input[key], (item) => text(item, 500), key, constructionPlanLimits.textItems),
      ]),
    ),
    objects: list(
      input.objects,
      (value) => {
        const object = record(value);
        const key = text(object.key, 64);
        if (!/^[a-z][a-z0-9_-]*$/.test(key))
          fail(
            codeOperationErrorMessage(
              language,
              'platform.screen.constructionPlanSession.invalid-business-object-identifier',
              '业务对象标识格式无效',
            ),
          );
        const moduleAlias = object.moduleAlias;
        if (
          moduleAlias != null &&
          (typeof moduleAlias !== 'string' ||
            moduleAlias.length > 128 ||
            (moduleAlias !== '' && !/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(moduleAlias)))
        )
          fail(
            codeOperationErrorMessage(
              language,
              'platform.screen.constructionPlanSession.select-the-alias-of-an-actual-standard-module',
              '请选择实际标准模块的别名',
            ),
          );
        return {
          key,
          name: text(object.name, 120),
          purpose: text(object.purpose, 500),
          ...(moduleAlias !== undefined ? { moduleAlias } : {}),
        };
      },
      'objects',
    ),
    decisions: list(
      input.decisions,
      (value) => {
        const decision = record(value);
        if (decision.source !== 'USER_REQUIREMENT' && decision.source !== 'RECOMMENDATION')
          fail(
            codeOperationErrorMessage(
              language,
              'platform.screen.constructionPlanSession.invalid-requirement-source',
              '需求来源无效',
            ),
          );
        return { statement: text(decision.statement, 500), source: decision.source };
      },
      'decisions',
    ),
  } as ConstructionPlanContent;
  if (input.requirements !== undefined) {
    if (!Array.isArray(input.requirements) || input.requirements.length > 64)
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.at-most-64-requirement-mappings-are-allowed',
          '需求兑现项最多 64 项',
        ),
      );
    const bindings = new Set<string>();
    content.requirements = input.requirements.map((entry, requirementIndex) => {
      const item = record(entry);
      if (
        !['SCOPE', 'RULE', 'RELATION'].includes(String(item.section)) ||
        ![
          'FIELD',
          'REQUIRED',
          'UNIQUE',
          'REFERENCE',
          'CHILD',
          'CALCULATION',
          'MANUAL',
          'UNSUPPORTED',
        ].includes(String(item.mode))
      )
        fail(
          codeOperationErrorMessage(
            language,
            'platform.screen.constructionPlanSession.invalid-requirement-fulfillment-mode',
            '需求兑现方式无效',
          ),
        );
      const section = item.section as 'SCOPE' | 'RULE' | 'RELATION';
      const mode = item.mode as
        | 'FIELD'
        | 'REQUIRED'
        | 'UNIQUE'
        | 'REFERENCE'
        | 'CHILD'
        | 'CALCULATION'
        | 'MANUAL'
        | 'UNSUPPORTED';
      const source =
        section === 'SCOPE' ? content.inScope : section === 'RULE' ? content.rules : content.relationships;
      if (!Number.isInteger(item.index) || Number(item.index) < 0 || Number(item.index) >= source.length)
        fail(
          codeOperationErrorMessage(
            language,
            'platform.screen.constructionPlanSession.requirements-value-index-value-does-not-refer-to-a-requirement',
            'requirements[{value1}].index={value2} 未引用本版要求（{value3}）；{value4}。请修正该引用并保留完整方案，不得删除已确认要求或其他必填部分。',
            {
              value1: requirementIndex,
              value2: String(item.index),
              value3: section === 'SCOPE' ? 'inScope' : section === 'RULE' ? 'rules' : 'relationships',
              value4: source.length
                ? language.t(
                    'platform.screen.constructionPlanSession.valid-indices-are-0-through-value',
                    '有效索引为 0 至 {value1}',
                    { value1: source.length - 1 },
                  )
                : language.t(
                    'platform.screen.constructionPlanSession.this-section-is-empty-in-the-current-revision-and-cannot',
                    '本版该部分为空，不能引用',
                  ),
            },
          ),
        );
      const objectKey = text(item.objectKey, 64);
      if (!content.objects.some((object) => object.key === objectKey))
        fail(
          codeOperationErrorMessage(
            language,
            'platform.screen.constructionPlanSession.the-mapped-business-object-does-not-exist',
            '兑现项业务对象不存在',
          ),
        );
      const fieldName = typeof item.fieldName === 'string' ? item.fieldName.trim() : '';
      const field = ['FIELD', 'REQUIRED', 'UNIQUE', 'REFERENCE', 'CALCULATION'].includes(mode);
      if (
        field
          ? !/^[a-z][a-zA-Z0-9_]{0,63}(\.[a-z][a-zA-Z0-9_]{0,63})?$/.test(fieldName)
          : mode === 'CHILD'
            ? !/^[a-z][a-z0-9_]{0,62}$/.test(fieldName)
            : Boolean(fieldName)
      )
        fail(
          codeOperationErrorMessage(
            language,
            'platform.screen.constructionPlanSession.requirements-value-fieldname-does-not-match-value-value',
            'requirements[{value1}].fieldName 与 {value2} 不匹配：{value3}。',
            {
              value1: requirementIndex,
              value2: mode,
              value3: field
                ? language.t(
                    'platform.screen.constructionPlanSession.enter-a-field-name-use-relation-field-for-a-direct',
                    '请填字段名，直接子表使用 relation.field（只支持一层），每段以小写字母开头',
                  )
                : mode === 'CHILD'
                  ? language.t(
                      'platform.screen.constructionPlanSession.enter-only-a-direct-child-relation-identifier-a-lowercase-initial',
                      '只填直接子关系标识，以小写字母开头，仅小写字母、数字、下划线，最多63字符',
                    )
                  : language.t(
                      'platform.screen.constructionPlanSession.this-mode-does-not-check-a-field-enter-an-empty',
                      '此方式不检查字段，必须填空字符串',
                    ),
            },
          ),
        );
      const reference = item.reference == null ? null : record(item.reference);
      if ((mode === 'REFERENCE') !== Boolean(reference))
        fail(
          codeOperationErrorMessage(
            language,
            'platform.screen.constructionPlanSession.a-reference-requirement-must-declare-its-target',
            '引用兑现项必须声明目标',
          ),
        );
      let target: { objectKey: string; moduleAlias: string } | null = null;
      if (reference) {
        const targetObject = typeof reference.objectKey === 'string' ? reference.objectKey.trim() : '';
        const targetModule = typeof reference.moduleAlias === 'string' ? reference.moduleAlias.trim() : '';
        if (Boolean(targetObject) === Boolean(targetModule))
          fail(
            codeOperationErrorMessage(
              language,
              'platform.screen.constructionPlanSession.choose-either-a-plan-object-or-an-existing-module',
              '请选择方案对象或已有模块之一',
            ),
          );
        if (
          targetObject &&
          (!content.objects.some((object) => object.key === targetObject) || targetObject === objectKey)
        )
          fail(
            codeOperationErrorMessage(
              language,
              'platform.screen.constructionPlanSession.the-reference-target-must-be-another-object-in-this-plan',
              '引用目标必须是方案中的另一个对象',
            ),
          );
        if (
          targetModule &&
          (targetModule.length > 128 || !/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(targetModule))
        )
          fail(
            codeOperationErrorMessage(
              language,
              'platform.screen.constructionPlanSession.invalid-reference-module-identifier',
              '引用模块标识无效',
            ),
          );
        target = { objectKey: targetObject, moduleAlias: targetModule };
      }
      if (section === 'RELATION' && mode !== 'UNSUPPORTED' && mode !== 'REFERENCE' && mode !== 'CHILD')
        fail(
          codeOperationErrorMessage(
            language,
            'platform.screen.constructionPlanSession.a-regular-field-cannot-fulfill-an-object-relationship',
            '普通字段不能兑现对象关联',
          ),
        );
      const key = [section, item.index, objectKey, mode, fieldName].join(':');
      if (bindings.has(key))
        fail(
          codeOperationErrorMessage(
            language,
            'platform.screen.constructionPlanSession.requirement-mappings-must-be-unique',
            '需求兑现项不能重复',
          ),
        );
      bindings.add(key);
      return {
        section,
        index: Number(item.index),
        objectKey,
        mode,
        fieldName,
        explanation: text(item.explanation, 500),
        ...(target ? { reference: target } : {}),
      };
    });
  }
  if (new Set(content.objects.map((object) => object.key)).size !== content.objects.length)
    fail(
      codeOperationErrorMessage(
        language,
        'platform.screen.constructionPlanSession.business-object-identifiers-must-be-unique',
        '业务对象标识不能重复',
      ),
    );
  if (new TextEncoder().encode(JSON.stringify(content)).length > constructionPlanLimits.contentBytes)
    fail(
      codeOperationErrorMessage(
        language,
        'platform.screen.constructionPlanSession.plan-content-is-too-long',
        '方案内容过长',
      ),
    );
  return content;
}
export function presentConstructionPlan(
  content: ConstructionPlanContent,
  language: LanguageContext = platformLanguageContext,
) {
  const sections = [
    ...Object.entries(localizedPlanSections(language)).map(([key, label]) => ({
      title: label,
      expanded: ['inScope', 'rules', 'questions'].includes(key),
      lines: content[key as keyof typeof planSections].map((item) =>
        language.t('platform.screen.useModulePageSession.value-value', '{value1}：{value2}', {
          value1: label,
          value2: item,
        }),
      ),
    })),
    {
      title: language.t('platform.screen.constructionPlanSession.business-objects', '业务对象'),
      expanded: true,
      lines: content.objects.map((object) =>
        language.t(
          'platform.screen.constructionPlanSession.business-object-value-valuevalue',
          '业务对象：{value1} — {value2}{value3}',
          {
            value1: object.name,
            value2: object.purpose,
            value3: object.moduleAlias
              ? language.t(
                  'platform.screen.constructionPlanSession.linked-module-value',
                  '（关联模块：{value1}）',
                  { value1: object.moduleAlias },
                )
              : '',
          },
        ),
      ),
    },
    {
      title: language.t(
        'platform.screen.constructionPlanSession.user-requirements-and-recommendations',
        '用户要求与建议',
      ),
      expanded: false,
      lines: content.decisions.map((decision) =>
        language.t('platform.screen.useModulePageSession.value-value', '{value1}：{value2}', {
          value1:
            decision.source === 'USER_REQUIREMENT'
              ? language.t('platform.screen.constructionPlanSession.user-requirement', '用户要求')
              : language.t('platform.screen.constructionPlanSession.recommendation', '建议'),
          value2: decision.statement,
        }),
      ),
    },
    {
      title: language.t('platform.screen.constructionPlanSession.requirement-fulfillment', '逐项兑现方式'),
      expanded: false,
      lines: (content.requirements ?? []).map((item) => {
        const source =
          item.section === 'SCOPE'
            ? content.inScope
            : item.section === 'RULE'
              ? content.rules
              : content.relationships;
        const mode = {
          FIELD: language.t(
            'platform.screen.constructionPlanSession.verify-that-the-field-exists',
            '核对字段存在',
          ),
          REQUIRED: language.t('platform.screen.constructionPlanSession.system-required-field', '系统必填'),
          UNIQUE: language.t(
            'platform.screen.constructionPlanSession.system-uniqueness-constraint',
            '系统防重复',
          ),
          REFERENCE: language.t(
            'platform.screen.constructionPlanSession.select-one-record-from-the-related-business',
            '从关联业务中选择一条记录',
          ),
          CHILD: language.t(
            'platform.screen.constructionPlanSession.enter-multiple-detail-rows-in-one-record',
            '同一张记录填写多行明细',
          ),
          CALCULATION: language.t(
            'platform.screen.constructionPlanSession.calculated-automatically-on-save-trial-verification-is-still-required',
            '保存时自动计算，仍需试算核验',
          ),
          MANUAL: language.t(
            'platform.screen.ConstructionPlanCard.handled-by-people-as-agreed',
            '按约定由人处理',
          ),
          UNSUPPORTED: language.t('platform.screen.ConstructionPlanCard.unsupported', '暂不支持'),
        }[item.mode];
        return language.t(
          'platform.screen.constructionPlanSession.fulfillment-value-value-value',
          '兑现方式：{value1} — {value2}；{value3}',
          { value1: source[item.index], value2: mode, value3: item.explanation },
        );
      }),
    },
  ].filter((section) => section.lines.length);
  return {
    title: content.title,
    sections,
    lines: [
      content.goal,
      ...sections.flatMap((section) => section.lines),
      language.t(
        'platform.screen.ConstructionPlanCard.this-confirmation-saves-the-requirements-scope-business-configuration-is-not',
        '本次仅确认需求范围，不创建或发布业务配置；已有建设结果保留。',
      ),
    ],
  };
}

export function createConstructionPlanSession(
  client: ConstructionPlanClient,
  identity: () => string,
  governanceAvailable: () => boolean = () => false,
  language: LanguageContext = platformLanguageContext,
) {
  const parser = planParser(language);
  const { record, text } = parser;
  const fail: (message: string | OperationErrorMessage) => never = parser.fail;
  const parse = (value: unknown) => parseConstructionPlan(value, language);
  const state = shallowRef<{
    planId?: string;
    saved?: ConstructionPlanSnapshot;
    candidate?: ConstructionPlanContent;
    generation: number;
    reviewRequired?: boolean;
  }>({ generation: 0 });
  const savedPlans = shallowRef<ConstructionPlanSummary[]>([]);
  const manualEditing = shallowRef(false);
  let owner = identity();
  let unresolved = false;
  let submitting = false;
  let linkedRecordPending = false;
  const recovery = shallowRef<() => Promise<unknown>>();
  function syncIdentity() {
    if (owner === identity()) return;
    owner = identity();
    linkedRecordPending = false;
    manualEditing.value = false;
    unresolved = false;
    submitting = false;
    recovery.value = undefined;
    savedPlans.value = [];
    state.value = { generation: state.value.generation + 1 };
  }
  function current() {
    syncIdentity();
    return state.value;
  }
  function dirty() {
    const value = current();
    return (
      !!value.candidate &&
      JSON.stringify(parse(value.candidate)) !== JSON.stringify(value.saved && parse(value.saved.content))
    );
  }
  function requireDesignOpen() {
    if (current().saved?.constructionStatus === 'DELIVERED')
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.this-business-has-been-delivered-read-current-low-code-governance',
          '此业务已交付，请读取当前低代码治理配置进行改进；历史方案仅供参考',
        ),
      );
    if (state.value.planId && !state.value.candidate && linkedRecordPending)
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.read-the-construction-record-linked-to-this-conversation-first-for',
          '请先读取此对话关联的建设记录；改进已有业务可直接读取当前治理配置',
        ),
      );
  }
  function resetConversation(planId?: string) {
    syncIdentity();
    linkedRecordPending = Boolean(planId);
    manualEditing.value = false;
    unresolved = false;
    submitting = false;
    recovery.value = undefined;
    savedPlans.value = [];
    state.value = { planId, generation: state.value.generation + 1 };
  }
  function beginManualEdit() {
    requireDesignOpen();
    syncIdentity();
    if (unresolved)
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.query-the-previous-confirmation-result-first',
          '上次确认结果尚未查明，请先查询结果',
        ),
      );
    manualEditing.value = true;
    state.value = { ...state.value, generation: state.value.generation + 1 };
  }
  function cancelManualEdit() {
    manualEditing.value = false;
  }
  function edit(value: unknown) {
    syncIdentity();
    requireDesignOpen();
    if (unresolved)
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.query-the-previous-confirmation-result-first',
          '上次确认结果尚未查明，请先查询结果',
        ),
      );
    const content = parse(value);
    manualEditing.value = false;
    state.value = {
      ...state.value,
      planId: state.value.planId ?? createUuid().replaceAll('-', ''),
      candidate: content,
      reviewRequired: false,
      generation: state.value.generation + 1,
    };
  }
  function editManually(value: unknown) {
    const before = current().candidate;
    const content = parse(value);
    const semanticChange =
      before &&
      (['goal', ...listKeys] as const).some(
        (key) => JSON.stringify(before[key]) !== JSON.stringify(content[key]),
      );
    const reviewRequired =
      !!state.value.reviewRequired ||
      !!(
        semanticChange &&
        (content.questions.length ||
          content.assumptions.length ||
          content.rules.length ||
          content.decisions.length ||
          content.objects.length ||
          content.relationships.length)
      );
    edit(content);
    state.value = { ...state.value, reviewRequired };
  }
  function revise(value: unknown) {
    const before = current();
    if (!before.candidate)
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.prepare-the-complete-requirements-plan-first',
          '请先整理完整需求方案',
        ),
      );
    if (before.reviewRequired)
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.linked-content-needs-review-after-manual-edits-update-the-complete',
          '人工修改后的关联内容尚待核对，请更新完整方案',
        ),
      );
    const changes = record(value);
    if (
      !Object.keys(changes).length ||
      Object.keys(changes).some((key) => !Object.hasOwn(constructionPlanChangesSchema.properties, key))
    )
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.a-partial-revision-must-contain-valid-business-plan-sections',
          '局部修订须提供有效的业务方案部分',
        ),
      );
    const content = parse({ ...before.candidate, ...changes });
    for (const requirement of before.candidate.requirements ?? []) {
      const key =
        requirement.section === 'SCOPE'
          ? 'inScope'
          : requirement.section === 'RULE'
            ? 'rules'
            : 'relationships';
      if (before.candidate[key][requirement.index] !== content[key][requirement.index])
        fail(
          codeOperationErrorMessage(
            language,
            'platform.screen.constructionPlanSession.these-changes-affect-requirements-linked-to-existing-configuration-review-their',
            '修改涉及已有配置对应的要求，请同时核对兑现方式并更新完整方案',
          ),
        );
    }
    edit(content);
  }
  function discardCandidate() {
    syncIdentity();
    if (unresolved)
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.query-the-previous-confirmation-result-first',
          '上次确认结果尚未查明，请先查询结果',
        ),
      );
    manualEditing.value = false;
    state.value = {
      ...state.value,
      candidate: state.value.saved ? structuredClone(state.value.saved.content) : undefined,
      reviewRequired: false,
      generation: state.value.generation + 1,
    };
  }
  async function listSaved() {
    syncIdentity();
    const scope = owner;
    const result = await client.list();
    syncIdentity();
    if (scope !== owner)
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.the-signed-in-identity-changed-read-again',
          '登录身份已变化，请重新读取',
        ),
      );
    savedPlans.value = result;
    return result;
  }
  async function load(id: string) {
    const before = current();
    const scope = owner;
    if (before.planId && before.planId !== id)
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.this-conversation-already-has-a-construction-goal-start-a-new',
          '当前对话已关联一个建设目标，讨论其他独立目标请新建对话',
        ),
      );
    if (dirty() || unresolved || manualEditing.value)
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.confirm-the-current-candidate-or-have-the-user-discard-it',
          '请先确认当前候选，或由用户放弃候选后再恢复方案',
        ),
      );
    const result = await client.read(id);
    syncIdentity();
    if (scope !== owner || state.value.generation !== before.generation)
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.the-current-plan-changed-restore-it-again',
          '当前方案已变化，请重新恢复',
        ),
      );
    return { result, generation: before.generation, scope };
  }
  function applyLoaded(loaded: Awaited<ReturnType<typeof load>>) {
    syncIdentity();
    if (loaded.scope !== owner || loaded.generation !== state.value.generation)
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.the-current-plan-changed-restore-it-again',
          '当前方案已变化，请重新恢复',
        ),
      );
    linkedRecordPending = false;
    state.value = {
      planId: loaded.result.planId,
      saved: loaded.result,
      candidate: structuredClone(loaded.result.content),
      generation: state.value.generation + 1,
    };
  }
  async function restore(id: string, isCurrent: () => boolean = () => true) {
    const loaded = await load(id);
    if (!isCurrent())
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.the-restoration-entry-changed-select-the-plan-again',
          '恢复入口已变化，请重新选择方案',
        ),
      );
    applyLoaded(loaded);
  }
  async function history() {
    const before = current();
    const scope = owner;
    if (!before.planId || !before.saved)
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.restore-or-confirm-a-plan-first',
          '请先恢复或确认方案',
        ),
      );
    const result = await client.history(before.planId);
    current();
    if (owner !== scope || before.generation !== state.value.generation)
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.the-current-plan-changed-read-its-history-again',
          '当前方案已变化，请重新读取历史',
        ),
      );
    return result;
  }
  function changes() {
    const value = current();
    if (!value.candidate) return [];
    const labels = {
      title: language.t('platform.reference-picker.name', '名称'),
      goal: language.t('platform.screen.ConstructionPlanCard.business-goal', '业务目标'),
      ...localizedPlanSections(language),
      objects: language.t('platform.screen.constructionPlanSession.business-objects', '业务对象'),
      decisions: language.t(
        'platform.screen.constructionPlanSession.requirements-and-recommendations',
        '需求与建议',
      ),
      requirements: language.t(
        'platform.screen.constructionPlanSession.requirement-fulfillment',
        '逐项兑现方式',
      ),
    };
    return Object.entries(labels)
      .filter(
        ([key]) =>
          JSON.stringify(value.candidate?.[key as keyof ConstructionPlanContent]) !==
          JSON.stringify(value.saved?.content[key as keyof ConstructionPlanContent]),
      )
      .map(([, label]) => label);
  }
  function prepare(): AssistantOperationProposal {
    requireDesignOpen();
    const before = current();
    const scope = owner;
    if (manualEditing.value)
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.finish-or-cancel-manual-editing-first',
          '请先完成或取消人工修改',
        ),
      );
    if (before.reviewRequired)
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.the-goal-or-scope-changed-review-related-questions-assumptions-and',
          '目标或范围已修改，请先核对关联问题、假设和规则，再准备确认',
        ),
      );
    if (submitting)
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.confirmation-is-being-submitted-wait-for-the-result',
          '正在提交确认，请等待结果',
        ),
      );
    if (unresolved)
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.query-the-previous-confirmation-result-first',
          '上次确认结果尚未查明，请先查询结果',
        ),
      );
    if (!before.planId || !before.candidate || !dirty())
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.prepare-or-edit-the-requirements-plan-first',
          '请先整理或修改需求方案',
        ),
      );
    if (!before.candidate.inScope.length || !before.candidate.acceptanceExamples.length)
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.define-the-current-scope-and-at-least-one-acceptance-example',
          '确认前须明确本期范围和至少一个验收例子',
        ),
      );
    const planId = before.planId;
    const content = structuredClone(before.candidate);
    const bindings = content.requirements ?? [];
    const covered = new Set(bindings.map((item) => `${item.section}:${item.index}`)).size;
    const missing = content.inScope.length + content.rules.length + content.relationships.length - covered;
    const unsupported = bindings.filter((item) => item.mode === 'UNSUPPORTED');
    const manual = bindings.filter((item) => item.mode === 'MANUAL');
    const requestId = createUuid();
    const expectedRevision = before.saved?.revision ?? 0;
    const isCurrent = () => {
      current();
      return owner === scope && state.value.generation === before.generation && !submitting;
    };
    function accepted(result: ConstructionPlanSnapshot) {
      current();
      if (owner === scope && state.value.generation === before.generation) {
        unresolved = false;
        recovery.value = undefined;
        state.value = {
          planId,
          candidate: structuredClone(result.content),
          saved: result,
          generation: before.generation + 1,
        };
      }
      return {
        title: language.t(
          'platform.screen.constructionPlanSession.requirements-plan-confirmed',
          '需求方案已确认',
        ),
        lines: [
          language.t(
            'platform.screen.ConstructionPlanCard.value-revision-value',
            '{value1} · 第 {value2} 版',
            { value1: result.content.title, value2: result.revision },
          ),
          constructionPlanBindings(result).length
            ? language.t(
                'platform.screen.constructionPlanSession.standard-module-linked-query-current-configuration-to-verify-it-subsequent',
                '已关联标准模块；当前配置须查询核实，后续变更仍须独立审阅和授权。',
              )
            : language.t(
                'platform.screen.constructionPlanSession.no-standard-module-linked-subsequent-configuration-changes-require-their-own',
                '尚未关联标准模块。后续配置变更仍须独立审阅和授权。',
              ),
        ],
      };
    }
    const proposal: AssistantOperationProposal = {
      confirmLabel: language.t(
        'platform.screen.constructionPlanSession.confirm-the-current-plan',
        '确认本期方案',
      ),
      modelSummary: language.t(
        'platform.screen.constructionPlanSession.save-the-requirements-plan-revision-discussed-here-this-does-not',
        '保存当前讨论的需求方案版本，不创建或发布业务配置。',
      ),
      presentation: {
        lines: [
          content.title,
          language.t('platform.screen.constructionPlanSession.goal-value', '目标：{value1}', {
            value1: content.goal.length > 180 ? content.goal.slice(0, 180) + '…' : content.goal,
          }),
          language.t(
            'platform.screen.ConstructionPlanCard.changes-in-this-revision-value',
            '本版变化：{value1}',
            { value1: changes().join(language.t('platform.list.separator', '、')) },
          ),
          language.t(
            'platform.screen.constructionPlanSession.confirmation-saves-requirements-so-they-can-be-recovered-after-refresh',
            '确认只保存需求，刷新后可找回；不会创建应用或保存订单。',
          ),
          ...(missing || unsupported.length
            ? [
                language.t(
                  'platform.screen.constructionPlanSession.review-before-implementation-value-requirements-need-mappings-and-value-are',
                  '实施前尚需核对：{value1} 条要求待映射，{value2} 项暂不支持。可先保存本期业务共识，后续实施仍须核实。',
                  { value1: missing, value2: unsupported.length },
                ),
              ]
            : [
                language.t(
                  'platform.screen.constructionPlanSession.business-scope-and-fulfillment-mappings-are-prepared-verify-actual-behavior',
                  '业务范围与兑现方式已整理，具体效果在配置完成后验证。',
                ),
              ]),
          ...manual
            .slice(0, 3)
            .map((item) =>
              language.t(
                'platform.screen.constructionPlanSession.agreed-human-responsibility-value',
                '已约定由人处理：{value1}',
                { value1: item.explanation.slice(0, 140) },
              ),
            ),
          ...(manual.length > 3
            ? [
                language.t(
                  'platform.screen.constructionPlanSession.expand-to-view-value-more-agreed-human-responsibilities',
                  '另有 {value1} 项已约定的人工事项，可展开查看。',
                  { value1: manual.length - 3 },
                ),
              ]
            : []),
          ...unsupported.slice(0, 3).map((item) =>
            language.t('platform.screen.constructionPlanSession.to-be-agreed-value', '待商定：{value1}', {
              value1: item.explanation.slice(0, 140),
            }),
          ),
          content.relationships.length
            ? language.t(
                'platform.screen.constructionPlanSession.this-plan-contains-object-relationships-verify-targets-and-confirm-reference',
                '方案含对象关联，后续须核实目标并单独确认引用配置；本次不会创建关联。',
              )
            : language.t(
                'platform.screen.constructionPlanSession.next-review-achievable-scope-and-build-usable-pages-gradually',
                '下一步再核对可实现范围，逐步建设可用页面。',
              ),
        ],
        details: {
          title: language.t(
            'platform.screen.constructionPlanSession.view-full-scope-rules-and-acceptance-examples',
            '查看完整范围、规则和验收例子',
          ),
          lines: presentConstructionPlan(content, language).lines,
        },
        title: language.t(
          'platform.screen.constructionPlanSession.confirm-current-plan-revision-value',
          '确认本期方案 · 第 {value1} 版',
          { value1: expectedRevision + 1 },
        ),
      },
      expiresAt: Date.now() + 5 * 60_000,
      isCurrent,
      async execute() {
        if (!isCurrent())
          throw new AssistantOperationRejectedError(
            language.t(
              'platform.screen.constructionPlanSession.the-plan-changed-review-it-again',
              '方案已变化，请重新审阅',
            ),
          );
        unresolved = true;
        submitting = true;
        recovery.value = () => proposal.lookup();
        try {
          return accepted(await client.confirm(planId, { requestId, expectedRevision, content }));
        } catch (error) {
          if (error instanceof AppError && [400, 401, 403, 404, 409, 422].includes(error.status ?? 0)) {
            if (owner === scope) {
              unresolved = false;
              recovery.value = undefined;
            }
            throw new AssistantOperationRejectedError(error.message);
          }
          throw error;
        } finally {
          if (owner === scope) submitting = false;
        }
      },
      async lookup() {
        current();
        if (owner !== scope)
          fail(
            codeOperationErrorMessage(
              language,
              'platform.screen.constructionPlanSession.the-signed-in-identity-changed-read-again',
              '登录身份已变化，请重新读取',
            ),
          );
        const result = await client.confirmation(planId, requestId);
        return result ? accepted(result) : undefined;
      },
    };
    return proposal;
  }
  function facts() {
    const value = current();
    return {
      generation: value.generation,
      task: currentTask(),
      planId: value.planId,
      revision: value.saved?.revision ?? 0,
      candidate: value.saved?.constructionStatus === 'DELIVERED' ? undefined : value.candidate,
      configurationSource: 'CURRENT_GOVERNANCE',
      deliveredObjectKeys: value.saved?.deliveredObjectKeys ?? [],
      dirty: dirty(),
      persistence:
        value.saved?.constructionStatus === 'DELIVERED'
          ? 'DELIVERY_HISTORY'
          : linkedRecordPending
            ? 'HISTORY_NOT_LOADED'
            : unresolved
              ? 'UNKNOWN'
              : dirty()
                ? 'UNSAVED_CANDIDATE'
                : value.saved
                  ? 'SAVED_REQUIREMENTS'
                  : 'NO_PLAN',
      persistenceExplanation:
        value.saved?.constructionStatus === 'DELIVERED'
          ? language.t(
              'platform.screen.constructionPlanSession.this-plan-has-been-delivered-and-is-a-historical-design',
              '此方案已交付，仅为历史设计记录，不代表当前配置。后续改进直接读取当前低代码治理信息，无需恢复或修改旧方案。',
            )
          : linkedRecordPending
            ? language.t(
                'platform.screen.constructionPlanSession.the-linked-construction-record-has-not-been-read-for-improvements',
                '关联建设记录尚未读取。改进已有业务直接读取当前治理配置；继续未完成设计时使用 construction.restore 读取绑定的 planId。',
              )
            : unresolved
              ? language.t(
                  'platform.screen.constructionPlanSession.the-save-result-is-uncertain-query-the-result-before-saving',
                  '保存结果尚未确定，请先查询结果，不要重复保存。',
                )
              : dirty()
                ? language.t(
                    'platform.screen.constructionPlanSession.current-changes-are-unsaved-and-will-be-lost-on-refresh',
                    '当前修改尚未保存，刷新会丢失；已有已确认版本仍保留。',
                  )
                : value.saved
                  ? language.t(
                      'platform.screen.constructionPlanSession.the-saved-requirements-plan-does-not-prove-that-the-application',
                      '已保存的是需求方案，不代表应用可用或业务单据已保存。',
                    )
                  : language.t(
                      'platform.screen.constructionPlanSession.no-saved-plan-yet',
                      '尚无已保存方案。',
                    ),
      manualEditing: manualEditing.value,
      reviewRequired: !!value.reviewRequired,
      confirmationResultUnknown: unresolved,
      constructionStatus: value.saved?.constructionStatus ?? 'NOT_STARTED',
      initializations: value.saved?.initializations ?? [],
      moduleBindings: constructionPlanBindings(value.saved),
      fieldChanges: value.saved?.fieldChanges ?? [],
      deliveries: value.saved?.deliveries ?? [],
      confirmationIsNotPublication: true,
      governanceAvailable: governanceAvailable(),
    };
  }
  const fieldCapabilities = createConstructionFieldCapabilities(
    client,
    () => {
      const value = current();
      return {
        saved: value.saved,
        generation: value.generation,
        dirty: dirty() || !!value.reviewRequired,
        editing: manualEditing.value,
      };
    },
    (result, invalidate = true) => {
      const value = current();
      if (!value.saved) return;
      state.value = {
        ...value,
        saved: {
          ...value.saved,
          fieldChanges: [
            ...value.saved.fieldChanges.filter((receipt) => receipt.requestId !== result.receipt.requestId),
            result.receipt,
          ],
        },
        generation: value.generation + (invalidate ? 1 : 0),
      };
    },
    language,
  );
  const deliveryCapabilities = createConstructionDeliveryCapabilities(
    client,
    () => {
      const value = current();
      return {
        saved: value.saved,
        generation: value.generation,
        dirty: dirty() || !!value.reviewRequired,
        editing: manualEditing.value,
      };
    },
    async () => {
      const id = current().planId;
      if (id) await restore(id);
    },
    language,
  );
  const task = shallowRef<ConstructionTask>();
  const taskGeneration = shallowRef(-1);
  async function readTask() {
    const before = current();
    const scope = owner;
    if (!before.saved || dirty() || before.reviewRequired)
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.confirm-the-latest-requirements-and-fulfillment-mappings-first',
          '请先确认最新需求及兑现方式',
        ),
      );
    const result = await client.task(before.saved.planId);
    current();
    if (owner !== scope || state.value.generation !== before.generation)
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.the-plan-changed-read-the-task-again',
          '方案已变化，请重新读取任务',
        ),
      );
    if (result.planRevision !== before.saved.revision)
      fail(
        codeOperationErrorMessage(
          language,
          'platform.screen.constructionPlanSession.requirements-were-updated-in-another-conversation-restore-the-latest-plan',
          '需求已在其他会话更新，请恢复最新方案后继续',
        ),
      );
    task.value = result;
    taskGeneration.value = before.generation;
    return result;
  }
  function currentTask() {
    return taskGeneration.value === current().generation ? task.value : undefined;
  }
  function withContinuation(capability: AssistantCapability): AssistantCapability {
    if (
      !governanceAvailable() ||
      !capability.propose ||
      capability.descriptor.code === 'construction.prepare-acceptance'
    )
      return capability;
    const propose = capability.propose;
    return {
      ...capability,
      propose(output) {
        const before = current();
        const planId = before.planId;
        const revision =
          (before.saved?.revision ?? 0) +
          (capability.descriptor.code === 'construction.prepare-confirmation' ? 1 : 0);
        const proposal = propose(output);
        return {
          ...proposal,
          continuation: proposal.continuation ?? {
            message: language.t(
              'platform.screen.constructionPlanSession.the-previous-operation-was-confirmed-successfully-read-current-platform-facts',
              '上一项已确认成功。读取当前平台事实，继续用户目标中尚未完成的部分；已提交内容不重建，新的保存须另行确认。需求方案只提供范围与验收依据，现行配置是事实来源。',
            ),
            isCurrent: () =>
              current().saved?.planId === planId &&
              current().saved?.revision === revision &&
              !dirty() &&
              !manualEditing.value,
          },
        };
      },
    };
  }
  function capabilities(): AssistantCapability[] {
    const constructionPlanContentSchema = createConstructionPlanContentSchema(language);
    const constructionPlanChangesSchema = planChangesSchema(constructionPlanContentSchema);
    const empty = (
      code: string,
      description: string,
      execute: () => Promise<unknown>,
    ): AssistantCapability => ({
      effect: 'read',
      descriptor: { code, description, inputSchema: emptyAssistantCapabilityInputSchema() },
      parseInput: parseEmptyAssistantCapabilityInput,
      execute,
    });
    const value = current();
    const canBuild =
      governanceAvailable() &&
      !!value.saved &&
      value.saved.constructionStatus !== 'DELIVERED' &&
      !dirty() &&
      !value.reviewRequired &&
      !manualEditing.value;
    const result: AssistantCapability[] = [
      ...(governanceAvailable() ? createConstructionReferenceDiscoveryCapabilities(client, language) : []),
      ...(canBuild && constructionPlanBindings(value.saved).length
        ? [...fieldCapabilities, ...deliveryCapabilities]
        : []),
      ...(canBuild
        ? createConstructionInitializationCapabilities(
            client,
            () => {
              const value = current();
              return {
                saved: value.saved,
                generation: value.generation,
                dirty: dirty() || !!value.reviewRequired,
                editing: manualEditing.value,
              };
            },
            language,
          )
        : []),
      ...(canBuild
        ? [
            {
              ...empty(
                'construction.task',
                'Read current requirement evidence and possible next actions for every object. Choose relevant actions from the user goal and actual dependencies; list order is not an execution sequence or authorization. Do not repeat completed writes.',
                readTask,
              ),
              present: (result: unknown) => ({
                title: language.t('platform.screen.constructionPlanSession.next-steps', '接下来要做的事'),
                lines: (result as ConstructionTask).objects.map((item) =>
                  language.t('platform.screen.useModulePageSession.value-value', '{value1}：{value2}', {
                    value1: item.title,
                    value2: item.complete
                      ? language.t(
                          'platform.screen.constructionPlanSession.historical-construction-delivered-read-current-configuration-for-improvements',
                          '历史建设已交付；后续改进读取当前配置',
                        )
                      : item.options.map((option) => option.explanation).join('；'),
                  }),
                ),
              }),
            },
          ]
        : []),
      empty(
        'construction.describe',
        'Read the bound design when continuing unfinished construction. Delivered designs are historical; existing business improvements read current standard governance without this lookup.',
        async () => facts(),
      ),
      empty(
        'construction.history',
        'List immutable confirmed requirements revisions for the active plan. This never restores an execution approval.',
        async () =>
          (await history()).map(({ revision, confirmedAt, content }) => ({
            revision,
            confirmedAt,
            title: content.title,
          })),
      ),
      empty(
        'construction.find-saved',
        'Find unfinished personal designs only when the user wants to resume unbuilt work. Improving an existing business does not require finding or restoring a plan; discover its current modules and read standard governance instead.',
        listSaved,
      ),
      {
        effect: 'configuration-draft',
        descriptor: {
          code: 'construction.propose',
          description:
            'Create or replace the COMPLETE local requirements candidate. For a small correction to an existing candidate prefer construction.revise, which preserves untouched sections. Does not require a selected module editor. Preserve all confirmed scope, decisions and acceptanceExamples; distinguish user requirements from recommendations. requirements may be [] while agreeing business scope; add technical bindings only from verified standard configuration and the design contract, never guessed field names. If reviewRequired, reconcile dependent sections against human edits and preserve unanswered questions. No persistence or business configuration changes.',
          inputSchema: itemSchema({
            generation: { type: 'integer', minimum: 0 },
            content: constructionPlanContentSchema,
          }),
        },
        parseInput(input) {
          const value = record(input);
          return { generation: value.generation, content: parse(value.content) };
        },
        async execute(input, context) {
          const value = input as { generation: number; content: ConstructionPlanContent };
          if (manualEditing.value)
            fail(
              codeOperationErrorMessage(
                language,
                'platform.screen.constructionPlanSession.finish-or-cancel-manual-editing-first',
                '请先完成或取消人工修改',
              ),
            );
          if (value.generation !== current().generation)
            fail(
              codeOperationErrorMessage(
                language,
                'platform.screen.constructionPlanSession.the-candidate-changed-read-construction-describe-again-first',
                '候选已变化，请先重新读取 construction.describe',
              ),
            );
          context.applyEffect(() => edit(value.content));
          return facts();
        },
        present: () => ({
          title: language.t(
            'platform.screen.constructionPlanSession.requirements-plan-updated-not-yet-saved',
            '需求方案已更新，尚未保存',
          ),
          lines: [
            language.t(
              'platform.screen.constructionPlanSession.changes-in-this-revision-value-view-details-in-the-business',
              '本版变化：{value1}。可在业务建设方案中查看详情。',
              { value1: changes().join(language.t('platform.list.separator', '、')) },
            ),
          ],
        }),
      },
      {
        effect: 'configuration-draft',
        descriptor: {
          code: 'construction.revise',
          description:
            'Revise only specified BUSINESS sections of the existing local requirements candidate. Read its current generation and content; changes replaces each specified section in full, while omitted sections, objects and configuration mappings are preserved. For example move an outOfScope clause into rules by changing those two arrays, without repeating title, goal or acceptanceExamples. Preserve unrelated clauses and reconcile the user correction across relevant sections. Does not require a selected module editor, save, or authorize implementation. If a changed clause already has configuration mappings or reviewRequired, use construction.propose with the reconciled complete content instead.',
          inputSchema: itemSchema({
            generation: { type: 'integer', minimum: 0 },
            changes: constructionPlanChangesSchema,
          }),
        },
        parseInput(input) {
          const value = record(input);
          return { generation: value.generation, changes: record(value.changes) };
        },
        async execute(input, context) {
          const value = input as { generation: number; changes: Record<string, unknown> };
          if (manualEditing.value)
            fail(
              codeOperationErrorMessage(
                language,
                'platform.screen.constructionPlanSession.finish-or-cancel-manual-editing-first',
                '请先完成或取消人工修改',
              ),
            );
          if (value.generation !== current().generation)
            fail(
              codeOperationErrorMessage(
                language,
                'platform.screen.constructionPlanSession.the-candidate-changed-read-construction-describe-again-first',
                '候选已变化，请先重新读取 construction.describe',
              ),
            );
          context.applyEffect(() => revise(value.changes));
          return facts();
        },
        present: () => ({
          title: language.t(
            'platform.screen.constructionPlanSession.requirements-plan-revised-not-yet-saved',
            '需求方案已修订，尚未保存',
          ),
          lines: [
            language.t(
              'platform.screen.constructionPlanSession.changes-in-this-revision-value-other-plan-content-is-retained',
              '本版变化：{value1}。其他方案内容保留，保存须另行确认。',
              { value1: changes().join(language.t('platform.list.separator', '、')) },
            ),
          ],
        }),
      },
      {
        ...empty(
          'construction.prepare-confirmation',
          'Prepare a human-only confirmation card for the current requirements candidate. This does not approve implementation.',
          async () => ({ awaitingHumanConfirmation: true }),
        ),
        propose: () => prepare(),
      },
      {
        effect: 'configuration-draft',
        descriptor: {
          code: 'construction.restore',
          description:
            'Bind an unbound conversation to one saved design, or refresh its already bound design. Never switch this conversation to another plan. Delivered plans are historical only; use current governance for changes. Does not restore approvals or overwrite a changed candidate.',
          inputSchema: itemSchema({ planId: textSchema(32) }),
        },
        parseInput(input) {
          return { planId: text(record(input).planId, 32) };
        },
        async execute(input, context) {
          const { planId } = input as { planId: string };
          if (current().planId !== planId && !savedPlans.value.some((plan) => plan.planId === planId))
            fail(
              codeOperationErrorMessage(
                language,
                'platform.screen.constructionPlanSession.query-saved-plans-first',
                '请先查询已保存的方案',
              ),
            );
          const loaded = await load(planId);
          context.applyEffect(() => applyLoaded(loaded));
          return facts();
        },
      },
    ];
    return result
      .filter((capability) => {
        const code = capability.descriptor.code;
        if (!value.saved && code === 'construction.history') return false;
        if (value.planId && code === 'construction.find-saved') return false;
        if (
          (linkedRecordPending || value.saved?.constructionStatus === 'DELIVERED') &&
          ['construction.propose', 'construction.revise', 'construction.prepare-confirmation'].includes(code)
        )
          return false;
        if (!value.candidate && code === 'construction.revise') return false;
        return true;
      })
      .map(withContinuation);
  }
  return {
    language,
    task,
    currentTask,
    readTask,
    resetConversation,
    state,
    manualEditing,
    beginManualEdit,
    cancelManualEdit,
    savedPlans,
    recovery,
    history,
    changes,
    current,
    dirty,
    edit,
    editManually,
    revise,
    discardCandidate,
    listSaved,
    restore,
    prepare,
    capabilities,
    async progress(objectKey: string) {
      const before = current();
      if (!before.saved)
        fail(
          codeOperationErrorMessage(
            language,
            'platform.screen.constructionPlanSession.restore-a-confirmed-plan-first',
            '请先恢复已确认方案',
          ),
        );
      const result = await client.progress(before.saved!.planId, objectKey);
      const after = current();
      if (after.saved?.planId !== before.saved!.planId || after.generation !== before.generation)
        fail(
          codeOperationErrorMessage(
            language,
            'platform.screen.constructionPlanSession.the-construction-context-changed-query-again',
            '建设上下文已变化，请重新查询',
          ),
        );
      return result;
    },
    facts,
  };
}
export type ConstructionPlanSession = ReturnType<typeof createConstructionPlanSession>;

/** Legacy snapshots retain receipt identity only until a plan explicitly associates its standard modules. */
export function constructionPlanBindings(snapshot?: ConstructionPlanSnapshot) {
  if (!snapshot) return [];
  if (snapshot.moduleBindings) return snapshot.moduleBindings;
  return snapshot.content.objects.flatMap((object) => {
    const moduleAlias =
      object.moduleAlias ??
      snapshot.initializations.find((item) => item.objectKey === object.key)?.moduleAlias;
    return moduleAlias ? [{ objectKey: object.key, moduleAlias }] : [];
  });
}
