import { type HttpClient } from '@muyun/web-core';
import type {
  MetadataModelChangeSetProposal,
  MetadataFieldPropertyChangeSetPayload,
} from './metadataModelEditSession';
import {
  applyMetadataModelChangeSet,
  previewMetadataModelChangeSet,
  type MetadataChangeSetPreview,
} from './metadataModelChangeSetClient';

export class MetadataChangeSetPrecheckError extends Error {}

/** Both page and conversation confirm the same immutable, server-validated change set. */
export async function prepareMetadataChangeSetSubmission(
  http: HttpClient,
  moduleAlias: string,
  candidate: MetadataModelChangeSetProposal,
  isCurrent: () => boolean,
  signal?: AbortSignal,
  fieldSpecTitles: Readonly<Record<string, string>> = {},
) {
  const proposal: MetadataModelChangeSetProposal = JSON.parse(JSON.stringify(candidate));
  const requireCurrent = () => {
    if (!isCurrent()) throw new MetadataChangeSetPrecheckError('配置候选或基线已变化，请重新预检并确认。');
  };
  requireCurrent();
  const preview = await previewMetadataModelChangeSet(http, moduleAlias, proposal, signal);
  requireCurrent();
  if (preview.errors.length)
    throw new MetadataChangeSetPrecheckError(preview.errors.map((issue) => issue.message).join('；'));
  if (!preview.proposalFingerprint)
    throw new MetadataChangeSetPrecheckError('预检结果缺少候选指纹，请重新预检。');
  let submitted = false;
  return {
    lines: [
      ...preview.warnings.map((item) => `注意：${item.message}`),
      ...proposal.relationDrafts.flatMap(({ fieldDrafts }) =>
        fieldDrafts.map(({ operation, field }) => {
          if (!field) return '移除字段；请核对下方详细变更。';
          const changes = [
            field.fieldSpecAlias ? fieldSpecTitles[field.fieldSpecAlias] : undefined,
            field.required === undefined ? undefined : field.required ? '必须填写' : '可以不填',
            field.uniqueField === undefined ? undefined : field.uniqueField ? '内容不可重复' : '允许重复',
            field.enabled === false ? '停用此字段' : undefined,
          ].filter(Boolean);
          return `${operation === 'ADD' ? '新增' : operation === 'DELETE' ? '移除' : '修改'}「${field.title || field.fieldName}」${changes.length ? `：${changes.join('，')}` : ''}`;
        }),
      ),
      ...(preview.orderImpacts.length ? ['保存当前排序调整。'] : []),
      ...(proposal.relationDrafts.some(({ fieldDrafts }) =>
        fieldDrafts.some(({ property }) => property && property.kind !== 'BASIC'),
      )
        ? ['包含关联或选项配置，请核对详细变更中的目标和范围。']
        : []),
    ],
    details: metadataChangeConfirmationLines(preview, proposal),
    async apply() {
      requireCurrent();
      if (submitted) throw new Error('该配置已提交，请核实结果，不要重复保存。');
      submitted = true;
      await applyMetadataModelChangeSet(http, moduleAlias, proposal, preview.proposalFingerprint);
    },
  };
}

function metadataChangeConfirmationLines(
  preview: MetadataChangeSetPreview,
  proposal: MetadataModelChangeSetProposal,
): string[] {
  return [
    ...preview.warnings.map((item) => `注意：${item.message}`),
    ...preview.fieldImpacts.map((item) => `字段「${item.fieldName}」：${item.description}`),
    ...proposal.relationDrafts.flatMap(({ fieldDrafts }) =>
      fieldDrafts.flatMap(({ field, property }) => {
        if (!field) return [];
        const options = [
          ['必填', field.required],
          ['唯一', field.uniqueField],
          ['索引', field.indexed],
          ['允许排序', field.sortableField],
          ['标题字段', field.titleField],
          ['启用', field.enabled],
        ] as const;
        return [
          `${field.title || field.fieldName}（${field.fieldName}）：类型 ${field.fieldSpecAlias}；${options
            .filter(([, value]) => value !== undefined)
            .map(([label, value]) => `${label}：${value ? '是' : '否'}`)
            .join('；')}`,
          ...metadataPropertyConfirmationLines(property),
        ];
      }),
    ),
    ...preview.schemaImpacts.map((item) => item.description),
    ...(preview.orderImpacts.length > 0 ? ['保存当前排序调整。'] : []),
  ];
}

function metadataPropertyConfirmationLines(property?: MetadataFieldPropertyChangeSetPayload): string[] {
  if (property?.kind === 'MODULE_REFERENCE' && property.referenceConfig) {
    const reference = property.referenceConfig;
    const defaultValue = '按平台默认';
    return [
      '关联模块：' + (reference.targetModuleAlias || defaultValue),
      ...(reference.targetMetadataId ? ['关联元数据：' + reference.targetMetadataId] : []),
      '关联标识字段：' + (reference.targetKeyField || defaultValue),
      '关联显示字段：' + (reference.targetLabelField || defaultValue),
      '选择数量：' +
        (reference.cardinality === 'ONE' ? '单选' : reference.cardinality === 'MANY' ? '多选' : defaultValue),
      '目标不可用时：' +
        (reference.targetUnavailablePolicy
          ? {
              PRESERVE_HISTORY: '保留历史引用',
              RESTRICT: '阻止目标删除',
              CASCADE_DELETE: '级联删除关联记录',
            }[reference.targetUnavailablePolicy]
          : defaultValue),
      '启用限制：' +
        (reference.requireEnabled === undefined
          ? defaultValue
          : reference.requireEnabled
            ? '每次保存时要求目标存在且已启用'
            : '不限制启用状态'),
      '选择回填：' + (reference.affectMappings?.length ? reference.affectMappings.join('；') : '无'),
      '引用投影：' + (reference.projectionMappings?.length ? reference.projectionMappings.join('；') : '无'),
    ];
  }
  if (property?.kind === 'DICTIONARY' && property.dictionaryConfig) {
    const dictionary = property.dictionaryConfig;
    return [
      '选项字典：' +
        [dictionary.dictionaryApplicationAlias, dictionary.dictionaryCategoryAlias].filter(Boolean).join('.'),
      '选择数量：' +
        (dictionary.selectionMode === 'multiple'
          ? '多选'
          : dictionary.selectionMode === 'single'
            ? '单选'
            : '按平台默认'),
    ];
  }
  return [];
}
