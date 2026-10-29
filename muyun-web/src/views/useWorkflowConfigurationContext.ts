import { computed, inject, onMounted, toValue, watch, type MaybeRefOrGetter } from 'vue';
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
import { assistantBusinessScopeKey } from '../dynamic-page-runtime/assistantBusinessScope';

/** Shared module facts and formula assistance for definition management and design. */
export function useWorkflowConfigurationContext(moduleAlias: MaybeRefOrGetter<string>) {
  const language = useLanguageContext();
  const context = useModuleContext({ moduleAlias: 'platform.workflow.definition' });
  const client = createWorkflowDefinitionClient(context.http, toValue(moduleAlias));
  const currentClient = () => createWorkflowDefinitionClient(context.http, toValue(moduleAlias));
  const conditionTools = {
    loadFunctions: async () =>
      (await currentClient().formulaFunctions()).map((item) => ({
        name: item.name,
        title: item.title,
        description: item.description,
        example: item.example,
        signature: `${item.name}(${item.parameters.map((parameter) => parameter.name).join(', ')})`,
      })),
    validate: (expression: string) => currentClient().validateCondition(expression),
  };
  const currentUser = useCurrentUserContext();
  const businessScope = inject(assistantBusinessScopeKey, undefined);
  const factsScope = computed(() =>
    JSON.stringify([toValue(moduleAlias), currentUser?.value, businessScope?.value]),
  );
  const businessContext = computed(() => {
    // A scope owns its descriptor as well as its action decisions.
    void factsScope.value;
    return createModuleContext({
      http: context.http,
      language: language,
      moduleAlias: toValue(moduleAlias),
      runtimeAccess: 'VIEW',
    });
  });
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
    moduleAlias: toValue(moduleAlias),
    predicate: (change) => change.type === 'configuration-translations-changed',
    handler: async () => {
      try {
        await businessContext.value.runtime.refreshTexts?.();
      } catch (cause) {
        presentPlatformError(cause, { source: 'workflow-design', phase: 'load' });
      }
    },
  });
  const businessRuntime = computed(() => displayModuleRuntime(businessContext.value.runtime));
  const businessLoading = computed(
    () => !businessContext.value.runtime.snapshot() && !businessContext.value.runtime.error(),
  );
  const fields = computed(() =>
    [...resolveRecordFormFields(businessRuntime.value?.uiDescriptor)].map(([name, field]) => ({
      name,
      label: field.label ?? name,
      valueType: field.valueType,
      assistantPolicy: field.assistantPolicy,
      fieldControl: field.fieldControl,
      referenceModule: field.reference?.targetModuleAlias,
    })),
  );

  onMounted(async () => {
    try {
      await businessContext.value.runtime.ready;
    } catch (cause) {
      presentPlatformError(cause, { source: 'workflow-design', phase: 'load' });
    }
  });
  watch(factsScope, () => {
    void businessContext.value.runtime.ready.catch((cause) =>
      presentPlatformError(cause, { source: 'workflow-design', phase: 'load' }),
    );
  });
  return {
    context,
    client,
    language,
    businessRuntime,
    businessLoading,
    fields,
    organizationContext,
    conditionTools,
  };
}
