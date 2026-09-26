import { nextTick } from 'vue';
import { AssistantCapabilityUsageError, type AssistantCapability } from '@muyun/web-core';
import { createRecordFormAssistantCapabilities } from './recordFormAssistantCapabilities';
import type { RelationDraftController, RelationDraftRegistry } from './relationDraftController';

export function createRelationDraftAssistantCapabilities(
  registry: RelationDraftRegistry,
  contextRevision: () => string,
) {
  let active:
    | {
        controller: RelationDraftController;
        rowKey: string;
        capabilities: ReturnType<typeof createRecordFormAssistantCapabilities>;
      }
    | undefined;
  const select = (controller: RelationDraftController, rowKey: string) => {
    const form = controller.form(rowKey);
    if (!form) throw new AssistantCapabilityUsageError('明细行已变化，请重新读取');
    active = {
      controller,
      rowKey,
      capabilities: createRecordFormAssistantCapabilities({
        get editorMode() {
          return form.editorMode;
        },
        get editingRecord() {
          return form.editingRecord;
        },
        get formFields() {
          return form.formFields;
        },
        get referencePickerConfigs() {
          return form.referencePickerConfigs;
        },
        contextRevision,
        updateDraftFields: (...args) => form.updateDraftFields(...args),
        updateDraftReference: (...args) => form.updateDraftReference(...args),
      }),
    };
  };
  const current = () =>
    active &&
    registry.list().includes(active.controller) &&
    active.controller.rowKeys().includes(active.rowKey)
      ? active
      : undefined;
  const operation = (action: 'add-row' | 'select-row' | 'remove-row'): AssistantCapability => ({
    effect: action === 'select-row' ? 'read' : 'draft',
    descriptor: {
      code: `relation.${action}`,
      description:
        action === 'add-row'
          ? '在指定明细增加空白草稿行并选中供助手填写，不保存。随后使用 relation.form 和 relation.reference 能力。'
          : action === 'select-row'
            ? '选择 relation.describe 返回的明细行供助手填写；这不会修改其他行或保存。'
            : '从当前整单草稿移除指定明细行，需整单保存确认后才生效。',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: action === 'add-row' ? ['relationCode'] : ['relationCode', 'rowKey'],
        properties: {
          relationCode: { type: 'string', enum: registry.list().map((entry) => entry.code) },
          ...(action === 'add-row' ? {} : { rowKey: { type: 'string', minLength: 1 } }),
        },
      },
    },
    parseInput(input) {
      const value = input as Record<string, unknown> | null;
      if (
        !value ||
        typeof value.relationCode !== 'string' ||
        (action !== 'add-row' && typeof value.rowKey !== 'string')
      )
        throw new AssistantCapabilityUsageError('请使用当前明细及行标识');
      return { relationCode: value.relationCode, rowKey: value.rowKey as string | undefined };
    },
    async execute(input, context) {
      const { relationCode, rowKey } = input as { relationCode: string; rowKey?: string };
      const controller = registry.list().find((entry) => entry.code === relationCode);
      if (!controller || (action !== 'add-row' && !controller.rowKeys().includes(rowKey!)))
        throw new AssistantCapabilityUsageError('明细或行已不可编辑，请重新读取');
      let selected = rowKey;
      if (action === 'select-row') context.commitInternalState(() => select(controller, rowKey!));
      else
        await context.applyEffect(() => {
          if (action === 'add-row') {
            selected = controller.add();
            select(controller, selected);
          } else {
            controller.remove(rowKey!);
            if (active?.controller === controller && active.rowKey === rowKey) active = undefined;
          }
        }, nextTick);
      return { relationCode, rowKey: selected, saved: false };
    },
  });
  return (): AssistantCapability[] => {
    if (!registry.list().length) return [];
    const selected = current();
    return [
      {
        effect: 'read',
        descriptor: {
          code: 'relation.describe',
          description:
            '分页读取当前可编辑聚合明细及稳定行标识；用 relationCode 和 nextOffset 继续读取。detailsOmitted 表示需选行后用 relation.form.describe 读取详情。先选行或新增行，再使用 relation.form/reference 修改；最后由主表 form.prepare-save 审阅整单，不能独立保存明细。',
          inputSchema: {
            type: 'object',
            additionalProperties: false,
            properties: {
              relationCode: { type: 'string' },
              offset: { type: 'integer', minimum: 0 },
              limit: { type: 'integer', minimum: 1, maximum: 20 },
            },
          },
        },
        parseInput(input) {
          if (!input || typeof input !== 'object' || Array.isArray(input))
            throw new AssistantCapabilityUsageError('请提供明细读取参数');
          const value = input as Record<string, unknown>;
          const { relationCode, offset = 0, limit = 20 } = value;
          if (
            Object.keys(value).some((key) => !['relationCode', 'offset', 'limit'].includes(key)) ||
            (relationCode !== undefined && typeof relationCode !== 'string') ||
            !Number.isSafeInteger(offset) ||
            Number(offset) < 0 ||
            !Number.isInteger(limit) ||
            Number(limit) < 1 ||
            Number(limit) > 20
          )
            throw new AssistantCapabilityUsageError('无效明细分页参数');
          return {
            relationCode: relationCode as string | undefined,
            offset: Number(offset),
            limit: Number(limit),
          };
        },
        async execute(input, context) {
          const { relationCode, offset, limit } = input as {
            relationCode?: string;
            offset: number;
            limit: number;
          };
          const controllers = registry.list().filter((entry) => !relationCode || entry.code === relationCode);
          if (relationCode && !controllers.length)
            throw new AssistantCapabilityUsageError('明细已不可用，请重新读取');
          let remaining = 12000;
          const relations = [];
          for (const controller of controllers) {
            const keys = controller.rowKeys();
            const rows = [];
            for (const [index, rowKey] of keys.slice(offset, offset + limit).entries()) {
              const form = controller.form(rowKey);
              if (!form) continue;
              const describe = createRecordFormAssistantCapabilities(form)().find(
                (capability) => capability.descriptor.code === 'form.describe',
              );
              const facts = describe ? await describe.execute({}, context) : undefined;
              const cost = JSON.stringify(facts ?? {}).length;
              const detailsOmitted = cost > remaining;
              if (!detailsOmitted) remaining -= cost;
              rows.push({
                rowKey,
                row: offset + index + 1,
                ...(detailsOmitted ? { detailsOmitted: true } : { form: facts }),
              });
            }
            relations.push({
              relationCode: controller.code,
              title: controller.title,
              count: keys.length,
              rows,
              offset,
              nextOffset: offset + rows.length < keys.length ? offset + rows.length : null,
              truncated: offset + rows.length < keys.length,
            });
          }
          return {
            relations,
            active: selected ? { relationCode: selected.controller.code, rowKey: selected.rowKey } : null,
          };
        },
      },
      operation('add-row'),
      operation('select-row'),
      operation('remove-row'),
      ...(selected?.capabilities().map((capability) => ({
        ...capability,
        descriptor: {
          ...capability.descriptor,
          code: `relation.${capability.descriptor.code}`,
          description: `仅操作已选中的明细 ${selected.controller.title} 行 ${selected.rowKey}，不操作主表。${capability.descriptor.description}`,
        },
      })) ?? []),
    ];
  };
}
