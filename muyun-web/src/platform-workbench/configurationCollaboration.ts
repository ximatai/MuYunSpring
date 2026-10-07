import { ref } from 'vue';
import {
  AssistantCapabilityUsageError,
  emptyAssistantCapabilityInputSchema,
  parseEmptyAssistantCapabilityInput,
  type AssistantCapability,
  type AssistantConfigurationTask,
} from '@muyun/web-core';

/** A task's collaboration preference is conversation state, never configuration or save authority. */
export function createConfigurationCollaboration() {
  const task = ref<AssistantConfigurationTask>();
  const revision = ref(0);
  function restore(value?: AssistantConfigurationTask) {
    task.value = value ? { ...value } : undefined;
    revision.value++;
  }
  function parseMode(input: unknown, starting: boolean) {
    if (!input || typeof input !== 'object' || Array.isArray(input))
      throw new AssistantCapabilityUsageError('请选择本次配置的协作方式');
    const value = input as Record<string, unknown>;
    if (
      Object.keys(value).some((key) => !(starting ? ['goal', 'mode'] : ['mode']).includes(key)) ||
      (value.mode !== 'conversation' && value.mode !== 'visual' && !(starting && value.mode === undefined)) ||
      (starting && (typeof value.goal !== 'string' || !value.goal.trim() || value.goal.length > 200))
    )
      throw new AssistantCapabilityUsageError('请提供配置目标和有效的协作方式');
    return {
      mode: (value.mode ?? 'visual') as AssistantConfigurationTask['mode'],
      goal: String(value.goal ?? '').trim(),
    };
  }
  function capabilities(): AssistantCapability[] {
    const starting = !task.value;
    return [
      {
        effect: 'configuration-draft',
        descriptor: {
          code: starting ? 'configuration.start-task' : 'configuration.switch-mode',
          description: starting
            ? 'Start the current configuration task after understanding the goal. Default to visual collaboration unless the user prefers conversation-only; do not ask users to choose technical modes. In visual collaboration open the shared editor to show complex changes, then offer confirmation in this conversation. This preference lasts for the whole task, across modules and turns. Does not save configuration or open a page.'
            : 'Change the current task collaboration mode ONLY when the user explicitly requests a switch. Keep the goal and all configuration candidates. Never switch because navigation changed or a capability is unavailable; explain limitations and ask first. Does not save or open a page.',
          inputSchema: {
            type: 'object',
            additionalProperties: false,
            required: starting ? ['goal'] : ['mode'],
            properties: {
              mode: { type: 'string', enum: ['conversation', 'visual'] },
              ...(starting ? { goal: { type: 'string', minLength: 1, maxLength: 200 } } : {}),
            },
          },
        },
        parseInput: (input) => parseMode(input, starting),
        async execute(input, context) {
          const value = input as AssistantConfigurationTask;
          return context.applyEffect(() => {
            restore({ goal: starting ? value.goal : task.value!.goal, mode: value.mode });
            return { ...task.value, savedConfiguration: false };
          });
        },
      },
      ...(task.value
        ? [
            {
              effect: 'configuration-draft' as const,
              descriptor: {
                code: 'configuration.finish-task',
                description:
                  'End the current configuration task when the user finishes, abandons it, or explicitly starts a different goal. Clears only the collaboration preference; does not save or discard configuration candidates. Do not finish after each field or module.',
                inputSchema: emptyAssistantCapabilityInputSchema(),
              },
              parseInput: parseEmptyAssistantCapabilityInput,
              async execute(_input, context) {
                return context.applyEffect(() => {
                  restore();
                  return { ended: true, savedConfiguration: false };
                });
              },
            } satisfies AssistantCapability,
          ]
        : []),
    ];
  }
  function boundary(editor?: { visible: boolean; kind?: string; openingCapability?: string }) {
    const state = !task.value
      ? 'START_TASK_REQUIRED'
      : !editor
        ? 'SELECT_CONFIGURATION_TARGET_REQUIRED'
        : task.value.mode === 'visual' && !editor.visible
          ? 'OPEN_SELECTED_EDITOR_REQUIRED'
          : 'READY';
    return {
      scope: 'assistant-shared-configuration-editor',
      appliesTo: 'selected-module-metadata-page-and-rule-candidates',
      state,
      draftEditingAvailable: state === 'READY',
      ...(editor ? { editorKind: editor.kind, openingCapability: editor.openingCapability } : {}),
      ...(state === 'START_TASK_REQUIRED'
        ? {
            guidance:
              'Only configuration drafting requires starting a task; authorized governance reads remain available.',
          }
        : {}),
      ...(state === 'SELECT_CONFIGURATION_TARGET_REQUIRED'
        ? {
            guidance:
              'No shared module editor is selected. This boundary does not block requirements planning, standard application/module record forms, or authorized menu navigation. Select an existing module using a current selection capability when editing its configuration; when the needed object does not exist, use the authorized standard management entry and record commands. Reuse the user’s already explicit goal and scope; target selection is not a new approval. Do not invent an application-binding prerequisite or ask the user to perform technical binding.',
          }
        : {}),
      ...(state === 'OPEN_SELECTED_EDITOR_REQUIRED'
        ? {
            guidance:
              'The selected configuration catalog remains valid. Visual collaboration temporarily hides draft-editing commands until its shared editor is opened. Use configurationEditor.openingCapability for the selected editor kind; metadata defines fields and relations, page composition only arranges already-defined fields, and rules define calculations and validations; do not infer missing platform support, reread unchanged catalogs, switch targets, or ask the user to implement the configuration.',
          }
        : {}),
    };
  }
  function filter(capabilities: AssistantCapability[], editorVisible = false) {
    return capabilities
      .filter((capability) => {
        // Target selection loads an authorized catalog; it neither opens the editor nor changes a candidate.
        if (
          [
            'rules.select-module',
            'configuration.select-metadata-module',
            'configuration.select-page-module',
          ].includes(capability.descriptor.code)
        )
          return true;
        if (!task.value)
          return capability.effect === 'page' || (capability.effect === 'read' && !capability.propose);
        if (task.value.mode === 'conversation') return capability.effect !== 'page';
        return (
          capability.effect !== 'configuration-draft' ||
          editorVisible ||
          capability.descriptor.code === 'configuration.discard-metadata-draft'
        );
      })
      .map((capability) => {
        const propose = capability.propose;
        if (!propose) return capability;
        return {
          ...capability,
          propose(output) {
            const proposal = propose(output);
            const epoch = revision.value;
            const isCurrent = () => epoch === revision.value && proposal.isCurrent();
            return {
              ...proposal,
              continuation:
                proposal.continuation ??
                (task.value
                  ? {
                      message:
                        '本次配置已确认完成。核实当前平台事实，继续用户已明确目标中的剩余事项；已保存内容不重建，新的保存仍须独立确认。现行配置是事实来源，需求方案是范围和验收依据；不因换页或方案候选状态重复确认已明确目标。用户只准备、比较、暂缓或取消的要求仍然有效，目标已完成时说明结果并停止。',
                      isCurrent: () => epoch === revision.value,
                    }
                  : undefined),
              isCurrent,
              async execute() {
                if (!isCurrent()) throw new AssistantCapabilityUsageError('协作方式或任务已变化，请重新确认');
                return proposal.execute();
              },
            };
          },
        } satisfies AssistantCapability;
      });
  }
  return { task, revision, restore, capabilities, filter, boundary };
}
export type ConfigurationCollaboration = ReturnType<typeof createConfigurationCollaboration>;
