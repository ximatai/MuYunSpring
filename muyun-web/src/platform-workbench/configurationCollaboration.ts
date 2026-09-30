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
  function filter(capabilities: AssistantCapability[], editorVisible = false) {
    return capabilities
      .filter((capability) => {
        // Target selection loads an authorized catalog; it neither opens the editor nor changes a candidate.
        if (
          ['rules.select-module', 'configuration.select-metadata-module'].includes(capability.descriptor.code)
        )
          return true;
        if (!task.value) return capability.effect === 'read' && !capability.propose;
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
  function filterConstruction(capabilities: AssistantCapability[]) {
    return capabilities.flatMap((capability) => {
      if (capability.descriptor.code !== 'construction.prepare-page') return [capability];
      // The headless page workspace is not yet available. Keep the existing bounded publisher
      // only for an explicit conversation-only task; visual work uses the shared page candidate.
      return task.value?.mode === 'conversation' ? filter([capability]) : [];
    });
  }
  return { task, revision, restore, capabilities, filter, filterConstruction };
}
export type ConfigurationCollaboration = ReturnType<typeof createConfigurationCollaboration>;
