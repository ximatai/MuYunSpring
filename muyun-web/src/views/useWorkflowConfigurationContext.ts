import { computed, onMounted } from 'vue';
import {
  createModuleContext,
  createWorkflowDefinitionClient,
  displayModuleRuntime,
  useLanguageContext,
  useModuleContext,
} from '@muyun/web-core';
import { presentPlatformError, resolveRecordFormFields } from '@muyun/platform-components';
import { useCurrentUserContext } from '../platform-admin-runtime/currentUserContext';
import { usePageDataChange } from '../platform-admin-runtime/pageRealtime';

/** Shared module facts and formula assistance for definition management and design. */
export function useWorkflowConfigurationContext(moduleAlias: string) {
  const language = useLanguageContext();
  const context = useModuleContext({ moduleAlias: 'platform.workflow.definition' });
  const client = createWorkflowDefinitionClient(context.http, moduleAlias);
  const conditionTools = {
    loadFunctions: async () =>
      (await client.formulaFunctions()).map((item) => ({
        name: item.name,
        title: item.title,
        description: item.description,
        example: item.example,
        signature: `${item.name}(${item.parameters.map((parameter) => parameter.name).join(', ')})`,
      })),
    validate: (expression: string) => client.validateCondition(expression),
  };
  const businessContext = createModuleContext({
    http: context.http,
    language: language,
    moduleAlias: moduleAlias,
    runtimeAccess: 'VIEW',
  });
  const currentUser = useCurrentUserContext();
  // Global templates have no tenant in which IAM organizations can be queried.
  const organizationContext = computed(() =>
    currentUser?.value?.tenantId
      ? createModuleContext<Record<string, unknown>>({
          http: context.http,
          language: language,
          moduleAlias: 'iam.organization',
          runtimeAccess: 'REFERENCE',
        })
      : undefined,
  );
  usePageDataChange({
    moduleAlias: moduleAlias,
    predicate: (change) => change.type === 'configuration-translations-changed',
    handler: async () => {
      try {
        await businessContext.runtime.refreshTexts?.();
      } catch (cause) {
        presentPlatformError(cause, { source: 'workflow-design', phase: 'load' });
      }
    },
  });
  const businessRuntime = computed(() => displayModuleRuntime(businessContext.runtime));
  const fields = computed(() =>
    [...resolveRecordFormFields(businessRuntime.value?.uiDescriptor)].map(([name, field]) => ({
      name,
      label: field.label ?? name,
      valueType: field.valueType,
      referenceModule: field.reference?.targetModuleAlias,
    })),
  );

  onMounted(async () => {
    try {
      await businessContext.runtime.ready;
    } catch (cause) {
      presentPlatformError(cause, { source: 'workflow-design', phase: 'load' });
    }
  });
  return { context, client, language, businessRuntime, fields, organizationContext, conditionTools };
}
