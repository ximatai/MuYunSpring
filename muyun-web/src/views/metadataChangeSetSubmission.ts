import { codeOperationErrorMessage } from '@muyun/web-core';
import { platformLanguageContext, type LanguageContext } from '@muyun/web-core';
import { AppError, OperationUsageError, OperationRejectedError, type HttpClient } from '@muyun/web-core';
import type {
  MetadataModelChangeSetProposal,
  MetadataFieldPropertyChangeSetPayload,
} from './metadataModelEditSession';
import {
  applyMetadataModelChangeSet,
  previewMetadataModelChangeSet,
  type MetadataChangeSetPreview,
} from './metadataModelChangeSetClient';

export class MetadataChangeSetPrecheckError extends OperationUsageError {}

/** Both page and conversation confirm the same immutable, server-validated change set. */
export async function prepareMetadataChangeSetSubmission(
  http: HttpClient,
  moduleAlias: string,
  candidate: MetadataModelChangeSetProposal,
  isCurrent: () => boolean,
  signal?: AbortSignal,
  fieldSpecTitles: Readonly<Record<string, string>> = {},
  capabilityTitles: Readonly<Record<string, string>> = {},
  language: LanguageContext = platformLanguageContext,
) {
  const proposal: MetadataModelChangeSetProposal = JSON.parse(JSON.stringify(candidate));
  const requireCurrent = () => {
    if (!isCurrent())
      throw new MetadataChangeSetPrecheckError(
        codeOperationErrorMessage(
          language,
          'platform.screen.metadataChangeSetSubmission.the-configuration-candidate-or-baseline-changed-precheck-and-confirm-again',
          '配置候选或基线已变化，请重新预检并确认。',
        ),
      );
  };
  requireCurrent();
  const preview = await previewMetadataModelChangeSet(http, moduleAlias, proposal, signal);
  requireCurrent();
  if (preview.errors.length)
    throw new MetadataChangeSetPrecheckError(
      preview.errors.map((issue) => issue.message).join(language.t('platform.list.clause-separator', '；')),
    );
  if (!preview.proposalFingerprint)
    throw new MetadataChangeSetPrecheckError(
      codeOperationErrorMessage(
        language,
        'platform.screen.metadataChangeSetSubmission.the-precheck-result-has-no-candidate-fingerprint-precheck-again',
        '预检结果缺少候选指纹，请重新预检。',
      ),
    );
  let submitted = false;
  return {
    lines: [
      ...proposal.relationDrafts.flatMap(({ capabilitySelections }) =>
        Object.entries(capabilitySelections ?? {})
          .filter(([, enabled]) => enabled)
          .map(([capability]) =>
            language.t(
              'platform.screen.metadataChangeSetSubmission.enable-core-capability-value',
              '启用「{value1}」基础能力',
              { value1: capabilityTitles[capability] ?? capability },
            ),
          ),
      ),
      ...preview.warnings.map((item) =>
        language.t('platform.screen.metadataChangeSetSubmission.warning-value', '注意：{value1}', {
          value1: item.message,
        }),
      ),
      ...preview.fieldImpacts
        .filter((item) => item.platformManaged)
        .map((item) =>
          language.t(
            'platform.screen.metadataChangeSetSubmission.enable-core-capability-value-label',
            '启用基础能力：{value1}',
            { value1: item.description },
          ),
        ),
      ...proposal.relationDrafts.flatMap(({ fieldDrafts }) =>
        fieldDrafts.map(({ operation, field, property }) => {
          if (!field)
            return language.t(
              'platform.screen.metadataChangeSetSubmission.remove-field-check-the-detailed-changes-below',
              '移除字段；请核对下方详细变更。',
            );
          const changes = [
            field.fieldSpecAlias ? fieldSpecTitles[field.fieldSpecAlias] : undefined,
            field.required === undefined
              ? undefined
              : field.required
                ? language.t('platform.screen.metadataChangeSetSubmission.required', '必须填写')
                : language.t('platform.screen.metadataChangeSetSubmission.optional', '可以不填'),
            field.uniqueField === undefined
              ? undefined
              : field.uniqueField
                ? language.t(
                    'platform.screen.metadataChangeSetSubmission.values-must-be-unique',
                    '内容不可重复',
                  )
                : language.t('platform.screen.metadataChangeSetSubmission.duplicates-allowed', '允许重复'),
            field.enabled === false
              ? language.t('platform.screen.metadataChangeSetSubmission.disable-this-field', '停用此字段')
              : undefined,
            property?.fixedDefault
              ? language.t(
                  'platform.screen.metadataChangeSetSubmission.fixed-default-value-value',
                  '固定默认值：{value1}',
                  {
                    value1:
                      property.fixedDefault.value ??
                      language.t('platform.screen.metadataChangeSetSubmission.none', '无'),
                  },
                )
              : undefined,
          ].filter(Boolean);
          const action =
            operation === 'ADD'
              ? language.t('platform.screen.ManagedDetailRelationSurface.add', '新增')
              : operation === 'DELETE'
                ? language.t('platform.screen.RecordPermissionDialog.remove', '移除')
                : language.t('platform.screen.RecordQueryListPanel.edit', '修改');
          return changes.length
            ? language.t('platform.metadata.field-change-with-details', '{action}「{title}」：{details}', {
                action,
                title: field.title || field.fieldName,
                details: changes.join(language.t('platform.list.comma-separator', '，')),
              })
            : language.t('platform.metadata.field-change', '{action}「{title}」', {
                action,
                title: field.title || field.fieldName,
              });
        }),
      ),
      ...(preview.orderImpacts.length
        ? [
            language.t(
              'platform.screen.metadataChangeSetSubmission.save-the-current-order-changes',
              '保存当前排序调整。',
            ),
          ]
        : []),
      ...(proposal.relationDrafts.some(({ fieldDrafts }) =>
        fieldDrafts.some(({ property }) => property && property.kind !== 'BASIC'),
      )
        ? [
            language.t(
              'platform.screen.metadataChangeSetSubmission.reference-or-option-configuration-is-included-check-targets-and-scope',
              '包含关联或选项配置，请核对详细变更中的目标和范围。',
            ),
          ]
        : []),
    ],
    details: metadataChangeConfirmationLines(preview, proposal, language),
    async apply() {
      if (!isCurrent())
        throw new OperationRejectedError(
          language.t(
            'platform.screen.metadataChangeSetSubmission.the-configuration-candidate-or-baseline-changed-precheck-and-confirm-again',
            '配置候选或基线已变化，请重新预检并确认。',
          ),
        );
      if (submitted)
        throw new Error(
          language.t(
            'platform.screen.metadataChangeSetSubmission.this-configuration-was-already-submitted-verify-its-result-before-saving',
            '该配置已提交，请核实结果，不要重复保存。',
          ),
        );
      submitted = true;
      try {
        await applyMetadataModelChangeSet(http, moduleAlias, proposal, preview.proposalFingerprint);
      } catch (cause) {
        // The atomic metadata command rejects these responses before accepting configuration.
        // Transport errors and server failures do not establish whether publication committed.
        if (cause instanceof AppError && [400, 401, 403, 404, 409, 422].includes(cause.status ?? 0)) {
          submitted = false; // Only a new explicit confirmation may retry a proven rejected command.
          throw new OperationRejectedError(cause.message);
        }
        throw cause;
      }
    },
  };
}

function metadataChangeConfirmationLines(
  preview: MetadataChangeSetPreview,
  proposal: MetadataModelChangeSetProposal,
  language: LanguageContext = platformLanguageContext,
): string[] {
  return [
    ...preview.warnings.map((item) =>
      language.t('platform.screen.metadataChangeSetSubmission.warning-value', '注意：{value1}', {
        value1: item.message,
      }),
    ),
    ...preview.fieldImpacts.map((item) =>
      language.t(
        'platform.screen.metadataChangeSetSubmission.field-value-value',
        '字段「{value1}」：{value2}',
        { value1: item.fieldName, value2: item.description },
      ),
    ),
    ...proposal.relationDrafts.flatMap(({ fieldDrafts }) =>
      fieldDrafts.flatMap(({ field, property }) => {
        if (!field) return [];
        const options = [
          [language.t('platform.form.required-marker', '必填'), field.required],
          [language.t('platform.screen.MetadataGovernanceSurface.unique', '唯一'), field.uniqueField],
          [language.t('platform.screen.metadataGovernanceEditor.index', '索引'), field.indexed],
          [
            language.t('platform.screen.metadataGovernanceEditor.allow-sorting', '允许排序'),
            field.sortableField,
          ],
          [language.t('platform.screen.MetadataGovernanceSurface.title-field', '标题字段'), field.titleField],
          [language.t('platform.screen.WorkflowWorkbenchView.enabled', '启用'), field.enabled],
        ] as const;
        return [
          language.t(
            'platform.screen.metadataChangeSetSubmission.value-value-type-value-value',
            '{value1}（{value2}）：类型 {value3}；{value4}',
            {
              value1: field.title || field.fieldName,
              value2: field.fieldName,
              value3: field.fieldSpecAlias,
              value4: options
                .filter(([, value]) => value !== undefined)
                .map(([label, value]) =>
                  language.t('platform.metadata.option-value', '{label}：{value}', {
                    label,
                    value: value
                      ? language.t('platform.screen.ManagedDetailRelationInlineSurface.yes', '是')
                      : language.t('platform.screen.ManagedDetailRelationInlineSurface.no', '否'),
                  }),
                )
                .join(language.t('platform.list.clause-separator', '；')),
            },
          ),
          ...metadataPropertyConfirmationLines(property, language),
          ...(property?.fixedDefault
            ? [
                language.t(
                  'platform.screen.metadataChangeSetSubmission.fixed-default-value-value-used-only-when-omitted-in-new',
                  '固定默认值：{value1}；仅用于新记录未填写时，不改变已有数据。',
                  {
                    value1:
                      property.fixedDefault.value ??
                      language.t('platform.screen.metadataChangeSetSubmission.none', '无'),
                  },
                ),
              ]
            : []),
        ];
      }),
    ),
    ...preview.schemaImpacts.map((item) => item.description),
    ...(preview.orderImpacts.length > 0
      ? [
          language.t(
            'platform.screen.metadataChangeSetSubmission.save-the-current-order-changes',
            '保存当前排序调整。',
          ),
        ]
      : []),
  ];
}

function metadataPropertyConfirmationLines(
  property?: MetadataFieldPropertyChangeSetPayload,
  language: LanguageContext = platformLanguageContext,
): string[] {
  if (property?.kind === 'MODULE_REFERENCE' && property.referenceConfig) {
    const reference = property.referenceConfig;
    const defaultValue = language.t(
      'platform.screen.metadataChangeSetSubmission.platform-default',
      '按平台默认',
    );
    return [
      language.t('platform.screen.metadataChangeSetSubmission.reference-module', '关联模块：') +
        (reference.targetModuleAlias || defaultValue),
      ...(reference.targetMetadataId
        ? [
            language.t('platform.screen.metadataChangeSetSubmission.reference-metadata', '关联元数据：') +
              reference.targetMetadataId,
          ]
        : []),
      language.t('platform.screen.metadataChangeSetSubmission.reference-key-field', '关联标识字段：') +
        (reference.targetKeyField || defaultValue),
      language.t('platform.screen.metadataChangeSetSubmission.reference-display-field', '关联显示字段：') +
        (reference.targetLabelField || defaultValue),
      language.t('platform.screen.metadataChangeSetSubmission.selection-count', '选择数量：') +
        (reference.cardinality === 'ONE'
          ? language.t('platform.screen.MetadataGovernanceSurface.single-selection', '单选')
          : reference.cardinality === 'MANY'
            ? language.t('platform.screen.MetadataGovernanceSurface.multiple-selection', '多选')
            : defaultValue),
      language.t('platform.screen.metadataChangeSetSubmission.when-target-is-unavailable', '目标不可用时：') +
        (reference.targetUnavailablePolicy
          ? {
              PRESERVE_HISTORY: language.t(
                'platform.screen.metadataChangeSetSubmission.preserve-historical-references',
                '保留历史引用',
              ),
              RESTRICT: language.t(
                'platform.screen.metadataChangeSetSubmission.prevent-target-deletion',
                '阻止目标删除',
              ),
              CASCADE_DELETE: language.t(
                'platform.screen.metadataChangeSetSubmission.cascade-delete-referencing-records',
                '级联删除关联记录',
              ),
            }[reference.targetUnavailablePolicy]
          : defaultValue),
      language.t('platform.screen.metadataChangeSetSubmission.enabled-state-requirement', '启用限制：') +
        (reference.requireEnabled === undefined
          ? defaultValue
          : reference.requireEnabled
            ? language.t(
                'platform.screen.metadataChangeSetSubmission.require-the-target-to-exist-and-be-enabled-on-every',
                '每次保存时要求目标存在且已启用',
              )
            : language.t(
                'platform.screen.metadataChangeSetSubmission.no-enabled-state-requirement',
                '不限制启用状态',
              )),
      language.t('platform.screen.metadataChangeSetSubmission.value-mappings', '选择回填：') +
        (reference.affectMappings?.length
          ? reference.affectMappings.join(language.t('platform.list.clause-separator', '；'))
          : language.t('platform.screen.metadataChangeSetSubmission.none', '无')),
      language.t('platform.screen.metadataChangeSetSubmission.reference-projections', '引用投影：') +
        (reference.projectionMappings?.length
          ? reference.projectionMappings.join(language.t('platform.list.clause-separator', '；'))
          : language.t('platform.screen.metadataChangeSetSubmission.none', '无')),
    ];
  }
  if (property?.kind === 'DICTIONARY' && property.dictionaryConfig) {
    const dictionary = property.dictionaryConfig;
    return [
      language.t('platform.screen.metadataChangeSetSubmission.option-dictionary', '选项字典：') +
        [dictionary.dictionaryApplicationAlias, dictionary.dictionaryCategoryAlias].filter(Boolean).join('.'),
      language.t('platform.screen.metadataChangeSetSubmission.selection-count', '选择数量：') +
        (dictionary.selectionMode === 'multiple'
          ? language.t('platform.screen.MetadataGovernanceSurface.multiple-selection', '多选')
          : dictionary.selectionMode === 'single'
            ? language.t('platform.screen.MetadataGovernanceSurface.single-selection', '单选')
            : language.t('platform.screen.metadataChangeSetSubmission.platform-default', '按平台默认')),
    ];
  }
  return [];
}
