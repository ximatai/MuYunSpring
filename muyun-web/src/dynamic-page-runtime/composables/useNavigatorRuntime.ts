import { ref, type Ref } from 'vue';
import {
  resolveRecordDetailFields,
  resolveRecordFormFields,
  type QueryListRecord,
  type RecordFormFieldDescriptor,
} from '@muyun/platform-components';
import type {
  ResolvedModuleUiDescriptor,
  ResolvedModulePageDescriptor,
  ResolvedPageContextBindingDescriptor,
  ResolvedPageNavigatorLevelDescriptor,
} from '@muyun/web-contracts';
import { createModuleContext, type ModuleContext } from '@muyun/web-core';

/** Runtime state that belongs to the page/navigator session rather than to a Vue host. */
export interface NavigatorLevelRuntime {
  descriptor: ResolvedPageNavigatorLevelDescriptor;
  context: ModuleContext<QueryListRecord>;
  tree: boolean;
}

/**
 * Resolves the standard runtime descriptor once and owns all navigator source
 * contexts. The host only binds the returned state to visual surfaces; this
 * keeps list, detail and tree templates on the same navigator session.
 */
export function useNavigatorRuntime(context: ModuleContext<QueryListRecord>) {
  const formFields = ref<Map<string, RecordFormFieldDescriptor>>(resolveRecordFormFields(undefined));
  const detailDisplayFields = ref(resolveRecordDetailFields(undefined));
  const runtimePage = ref<ResolvedModulePageDescriptor>();
  const runtimeUiDescriptor = ref<ResolvedModuleUiDescriptor>();
  const treeModule = ref(false);
  const navigatorLevels = ref<NavigatorLevelRuntime[]>([]);
  const pageContextBindings = ref<ResolvedPageContextBindingDescriptor[]>([]);
  const selectedNavigatorRecords = ref<Record<string, QueryListRecord | undefined>>({});
  const navigatorSingleResultKeys = ref<string[]>([]);
  const navigatorDismissedSelectionKeys = ref<string[]>([]);

  async function loadRuntimeForm(
    isListPage: Ref<boolean>,
    hasBusinessRecordView: () => boolean,
    fail: (message: string) => void,
  ) {
    if (!isListPage.value) return;
    const runtimeContext = await context.runtime.ready;
    runtimeUiDescriptor.value = runtimeContext.uiDescriptor;
    runtimePage.value = runtimeContext.uiDescriptor?.page;
    treeModule.value = context.abilities.hasTree() === true;
    if (treeModule.value && hasBusinessRecordView()) {
      fail('模块页面增强的业务查看呈现仅支持普通列表模块，不支持树模块');
      return;
    }
    const descriptors = runtimePage.value?.navigator?.levels ?? [];
    pageContextBindings.value = runtimePage.value?.navigator?.contextBindings ?? [];
    selectedNavigatorRecords.value = {};
    navigatorSingleResultKeys.value = [];
    navigatorDismissedSelectionKeys.value = [];
    navigatorLevels.value = await Promise.all(
      descriptors.map(async (descriptor) => {
        const navigatorContext = createModuleContext<QueryListRecord>({
          http: context.http,
          moduleAlias: descriptor.sourceModuleAlias,
          runtimeAccess: 'REFERENCE',
        });
        await navigatorContext.runtime.ready;
        const requiredCapability = descriptor.kind === 'TREE' ? 'REFERENCE_TREE' : 'REFERENCE_QUERY';
        const sourceCapabilities = navigatorContext.runtime.snapshot()?.navigatorSourceCapabilities;
        if (sourceCapabilities !== undefined && !sourceCapabilities.includes(requiredCapability)) {
          throw new Error(
            `导航源能力不可用：层级 ${descriptor.key} 引用模块 ${descriptor.sourceModuleAlias}，缺少 ${requiredCapability}`,
          );
        }
        return {
          descriptor,
          context: navigatorContext,
          tree:
            descriptor.kind === 'TREE' &&
            (sourceCapabilities?.includes('REFERENCE_TREE') ?? navigatorContext.abilities.hasTree() === true),
        };
      }),
    );
    formFields.value = resolveRecordFormFields(runtimeContext.uiDescriptor);
    detailDisplayFields.value = resolveRecordDetailFields(runtimeContext.uiDescriptor);
  }

  return {
    formFields,
    detailDisplayFields,
    runtimePage,
    runtimeUiDescriptor,
    treeModule,
    navigatorLevels,
    pageContextBindings,
    selectedNavigatorRecords,
    navigatorSingleResultKeys,
    navigatorDismissedSelectionKeys,
    loadRuntimeForm,
  };
}
