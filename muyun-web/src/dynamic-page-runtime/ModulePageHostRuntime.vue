<script lang="ts">
import type { CrudRecordListBase, QueryListRecord } from '@muyun/platform-components';

import {
  RecordPermissionDialog,
  ManagementExplorerColumn,
  ManagementWorkspace,
  CrudRecordListExplorer,
  ModuleActionButton,
  RecordDetailPanel,
  RecordActionBar,
  RecordDetailExtensionSection,
  RecordDetailFields,
  RecordExplorerPanel,
  RecordFormFields,
  RecordMetaSection,
  RecordModeDrawer,
  RecordDetailDrawer,
  provideReferenceRecordDetailBrowser,
  DrawerTitleActions,
  RecordPanelButton,
  RecordPanelState,
  RecordQueryListPanel,
  RecycleBinModeButton,
  RecordStatusSwitch,
  StaticManagementLayout,
  TreeRecordExplorer,
  providePageLayout,
  UiModal,
} from '@muyun/platform-components';

import ModulePageBusinessState from './ModulePageBusinessState.vue';
import ModuleRecordDetailActions from './ModuleRecordDetailActions.vue';
import ModulePageDetailRelations from './ModulePageDetailRelations.vue';
import ModulePageListExpansionSurface from './ModulePageListExpansionSurface.vue';
import ModulePageRecordContent from './ModulePageRecordContent.vue';
import ModuleReferenceRecordDetailBrowser from './ModuleReferenceRecordDetailBrowser.vue';
import NavigatorManagementEditor from './NavigatorManagementEditor.vue';
import PageNavigatorExplorer from './PageNavigatorExplorer.vue';
import TenantScopeExplorer from './TenantScopeExplorer.vue';

import { computed, defineComponent, watch, type PropType } from 'vue';
import { sessionViewBindings } from './sessionViewBindings';
import type { ModulePageSessionView } from './useModulePageSession';
import type { TenantScopeController } from './useTenantScopeController';
export default defineComponent({
  name: 'ModulePageHostRuntime',
  components: {
    RecordPermissionDialog,
    ManagementExplorerColumn,
    ManagementWorkspace,
    CrudRecordListExplorer,
    ModuleActionButton,
    RecordDetailPanel,
    RecordActionBar,
    RecordDetailExtensionSection,
    RecordDetailFields,
    RecordExplorerPanel,
    RecordFormFields,
    RecordMetaSection,
    RecordModeDrawer,
    RecordDetailDrawer,
    DrawerTitleActions,
    RecordPanelButton,
    RecordPanelState,
    RecordQueryListPanel,
    RecycleBinModeButton,
    RecordStatusSwitch,
    StaticManagementLayout,
    TreeRecordExplorer,
    UiModal,
    ModulePageBusinessState,
    ModuleRecordDetailActions,
    ModulePageDetailRelations,
    ModulePageListExpansionSurface,
    ModulePageRecordContent,
    // Resolve lazily: the reference browser composes another public Host.
    get ModuleReferenceRecordDetailBrowser() {
      return ModuleReferenceRecordDetailBrowser;
    },
    NavigatorManagementEditor,
    PageNavigatorExplorer,
    TenantScopeExplorer,
  },
  props: {
    session: { type: Object as PropType<ModulePageSessionView>, required: true },
    tenantController: { type: Object as PropType<TenantScopeController>, required: true },
    pending: Boolean,
    businessError: String,
  },
  emits: ['retry'],
  setup(props, { emit }) {
    watch(
      () => props.session,
      (next, previous) => {
        next.workspaceElement = previous.workspaceElement;
      },
      { flush: 'sync' },
    );
    providePageLayout(() => props.session.pageLayout);
    provideReferenceRecordDetailBrowser({
      get revision() {
        return props.session.referenceRecordDetailBrowser.revision;
      },
      get active() {
        return props.session.referenceRecordDetailBrowser.active;
      },
      get activeContext() {
        return props.session.referenceRecordDetailBrowser.activeContext;
      },
      get fields() {
        return props.session.referenceRecordDetailBrowser.fields;
      },
      get title() {
        return props.session.referenceRecordDetailBrowser.title;
      },
      canBrowse: (...args) => props.session.referenceRecordDetailBrowser.canBrowse(...args),
      open: (...args) => props.session.referenceRecordDetailBrowser.open(...args),
      setBusy: (...args) => props.session.referenceRecordDetailBrowser.setBusy(...args),
      reportMutation: (...args) => props.session.referenceRecordDetailBrowser.reportMutation(...args),
      close: (...args) => props.session.referenceRecordDetailBrowser.close(...args),
      dispose: () => props.session.referenceRecordDetailBrowser.dispose(),
    });
    const businessVisible = computed(() => !props.pending && !props.businessError);
    return {
      businessVisible,
      retryBusinessSession: () => emit('retry'),
      ...sessionViewBindings(() => props.session),
      handleFlatManagementLoaded: (records: CrudRecordListBase[]) =>
        props.session.handleFlatManagementLoaded(records as QueryListRecord[]),
      openFlatManagementRecord: (record: CrudRecordListBase) =>
        props.session.openFlatManagementRecord(record as QueryListRecord),
      tenantScopeExplorerVisible: props.tenantController.tenantScopeExplorerVisible,
      tenantScopeExplorerCount: props.tenantController.tenantScopeExplorerCount,
      tenantScopeContext: props.tenantController.tenantScopeContext,
      tenantScopeId: props.tenantController.selectedId,
      tenantScopeReloadKey: props.tenantController.tenantScopeReloadKey,
      tenantSwitchBlocked: props.tenantController.blocked,
      handleTenantScopeLoaded: props.tenantController.handleTenantScopeLoaded,
      changeTenantScope: props.tenantController.changeTenantScope,
    };
  },
});
</script>

<template>
  <ManagementWorkspace
    v-if="session.pageBootstrapError && !props.recordOnly && tenantScopeExplorerVisible && tenantScopeContext"
    class="module-tenant-recovery-workspace"
    :explorer-count="tenantScopeExplorerCount"
  >
    <ManagementExplorerColumn collapsible title="租户" :has-selection="Boolean(tenantScopeId)">
      <TenantScopeExplorer
        :context="tenantScopeContext"
        :selected-id="tenantScopeId || undefined"
        :reload-key="tenantScopeReloadKey"
        :disabled="tenantSwitchBlocked"
        @refresh="tenantScopeReloadKey += 1"
        @loaded="handleTenantScopeLoaded"
        @select="changeTenantScope($event)"
        @deselect="changeTenantScope(undefined)"
      />
    </ManagementExplorerColumn>
    <ModulePageBusinessState :pending="pending" :error="businessError" @retry="retryBusinessSession" />
  </ManagementWorkspace>
  <section
    v-else-if="
      businessVisible &&
      !props.recordOnly &&
      props.requireConfiguredPage &&
      runtimePageResolved &&
      !runtimePage
    "
    class="module-unsupported"
  >
    <template v-if="businessVisible">
      <RecordPanelState description="当前模块尚未发布页面，请先发布页面配置。" />
    </template>
  </section>
  <section
    v-else-if="!props.recordOnly && pageBootstrapError && !(businessError && tenantScopeExplorerVisible)"
    class="module-unsupported"
  >
    <RecordPanelState class="module-bootstrap-error" :description="pageBootstrapError" />
  </section>
  <section v-else-if="businessVisible && !props.recordOnly && !pageReady" class="module-unsupported">
    <RecordPanelState loading loading-tip="加载页面入口" description="" />
  </section>
  <section
    v-else-if="!props.recordOnly && isListPage"
    ref="workspaceElement"
    class="module-workspace"
    :class="{
      'module-workspace--management': constrainedManagementPage,
    }"
  >
    <StaticManagementLayout
      v-if="flatManagementPage"
      class="module-flat-management-workspace"
      :refreshable="businessVisible && (!managedPageActions || flatManagementRecycleBin.active.value)"
      :explorer-title="
        businessVisible
          ? flatManagementRecycleBin.active.value
            ? '回收站'
            : (flatManagementContent?.explorerTitle ?? title)
          : '业务页面'
      "
      :refresh-title="`刷新${flatManagementRecycleBin.active.value ? '回收站' : (flatManagementContent?.explorerTitle ?? title)}`"
      :explorer-search-keyword="flatManagementSearchKeyword"
      :explorer-search-placeholder="flatManagementContent?.explorerSearchPlaceholder"
      :explorer-searchable="
        businessVisible &&
        !flatManagementRecycleBin.active.value &&
        runtimePage?.quickSearchFields?.length !== 0
      "
      :mode="editorMode"
      :detail-title="businessVisible ? detailTitle : ''"
      :navigator-count="navigatorExplorerCount + tenantScopeExplorerCount"
      @update:explorer-search-keyword="flatManagementSearchKeyword = $event"
      @refresh="businessVisible && flatManagementRecycleBin.refresh()"
    >
      <template #navigator="{ index }">
        <TenantScopeExplorer
          v-if="tenantScopeExplorerVisible && tenantScopeContext && index === 0"
          :context="tenantScopeContext"
          :selected-id="tenantScopeId || undefined"
          :reload-key="tenantScopeReloadKey"
          :disabled="tenantSwitchBlocked"
          @refresh="tenantScopeReloadKey += 1"
          @loaded="handleTenantScopeLoaded"
          @select="changeTenantScope($event)"
          @deselect="changeTenantScope(undefined)"
        />
        <template v-if="businessVisible">
          <component
            :is="navigatorExtension.component"
            v-if="navigatorExtension && index === tenantScopeExplorerCount"
            :context="navigatorExtensionContext"
          />
          <PageNavigatorExplorer
            v-if="
              !(navigatorExtension && index === tenantScopeExplorerCount) &&
              navigatorLevelAt(index - tenantScopeExplorerCount)
            "
            :level="navigatorLevelAt(index - tenantScopeExplorerCount)!"
            :selected-id="
              selectedNavigatorRecords[navigatorLevelAt(index - tenantScopeExplorerCount)!.descriptor.key]
                ?.id == null
                ? undefined
                : String(
                    selectedNavigatorRecords[
                      navigatorLevelAt(index - tenantScopeExplorerCount)!.descriptor.key
                    ]?.id,
                  )
            "
            :reload-key="
              navigatorReloadKey(navigatorLevelAt(index - tenantScopeExplorerCount)!.descriptor.key)
            "
            :keyword="scopeSearchKeyword"
            :external-query-values="
              navigatorExplorerQueryValues(navigatorLevelAt(index - tenantScopeExplorerCount)!.descriptor.key)
            "
            :navigator-host-module-alias="context.moduleAlias"
            :ready="navigatorManagementScopeReady(navigatorLevelAt(index - tenantScopeExplorerCount)!)"
            :create-disabled="
              !navigatorManagementScopeReady(navigatorLevelAt(index - tenantScopeExplorerCount)!)
            "
            :create-disabled-reason="
              navigatorManagementScopeDisabledReason(navigatorLevelAt(index - tenantScopeExplorerCount)!)
            "
            :scope-subtitle="
              navigatorPanelScopeContext(navigatorLevelAt(index - tenantScopeExplorerCount)!.descriptor.key)
            "
            :tree-parent-policy="
              navigatorTreeParentPolicy(navigatorLevelAt(index - tenantScopeExplorerCount)!)
            "
            :actions-of="
              (record) => navigatorInlineActions(navigatorLevelAt(index - tenantScopeExplorerCount)!, record)
            "
            :sort="navigatorSortState(navigatorLevelAt(index - tenantScopeExplorerCount)!)"
            @update:keyword="scopeSearchKeyword = $event"
            @refresh="scopeReloadKey += 1"
            @create="createNavigatorRecord(navigatorLevelAt(index - tenantScopeExplorerCount)!)"
            @loaded="handleNavigatorLoaded(navigatorLevelAt(index - tenantScopeExplorerCount)!, $event)"
            @select="
              selectNavigatorRecord(
                navigatorLevelAt(index - tenantScopeExplorerCount)!.descriptor.key,
                $event,
              )
            "
            @deselect="
              clearNavigatorRecord(navigatorLevelAt(index - tenantScopeExplorerCount)!.descriptor.key)
            "
            @action="
              (action, record) =>
                handleNavigatorInlineAction(
                  navigatorLevelAt(index - tenantScopeExplorerCount)!,
                  action,
                  record,
                )
            "
            @toggle-sorting="toggleNavigatorSorting(navigatorLevelAt(index - tenantScopeExplorerCount)!)"
          >
            <template #editor>
              <NavigatorManagementEditor
                :open="
                  navigatorManagementLevel?.descriptor.key ===
                    navigatorLevelAt(index - tenantScopeExplorerCount)!.descriptor.key &&
                  navigatorManagementDetail.open.value
                "
                :title="navigatorManagementTitle"
                :saving="navigatorManagementDetail.saving.value"
                :loading="navigatorManagementDetail.loading.value"
                :load-failed="navigatorManagementDetail.loadFailed.value"
                :draft="navigatorManagementDetail.draft.value"
                :fields="navigatorManagementFormFields"
                :mode="navigatorManagementDetail.mode.value"
                :form-session-key="navigatorManagementDetail.formSessionKey.value"
                :validation-request-key="navigatorManagementFormValidationRequestKey"
                :context="navigatorLevelAt(index - tenantScopeExplorerCount)!.context"
                :picker-configs="navigatorManagementPickerConfigs"
                :contributions="navigatorManagementFormContributions"
                :field-policies="navigatorManagementFormFieldPolicies"
                :show-enabled="navigatorManagementEnabledVisible"
                :enabled="navigatorManagementDetail.draft.value?.enabled !== false"
                :enabled-disabled="navigatorManagementEnabledDisabled"
                :enabled-disabled-reason="navigatorManagementEnabledDisabledReason"
                :enabled-loading="navigatorManagementTogglingEnabled"
                @close="closeNavigatorManagementEditor"
                @save="saveNavigatorRecord"
                @toggle-enabled="toggleNavigatorManagementEnabled"
                @update-field="updateNavigatorManagementDraft"
                @validity-change="navigatorManagementFormValid = $event.valid"
              />
            </template>
          </PageNavigatorExplorer>
        </template>
      </template>
      <template v-if="!flatManagementRecycleBin.active.value" #explorer-actions>
        <template v-if="businessVisible">
          <RecordActionBar
            v-if="placedPageActions.length"
            :context="context"
            :actions="placedPageActions"
            @action="handlePlacedPageAction"
          />
          <RecordPanelButton
            v-if="context.can('sort') === true"
            icon-name="swap-vertical"
            icon-only
            size="small"
            type="text"
            :selected="flatManagementSorting"
            :disabled="Boolean(flatManagementSearchKeyword.trim())"
            :title="
              flatManagementSearchKeyword.trim()
                ? '清空搜索后可调整排序'
                : flatManagementSorting
                  ? '结束排序'
                  : '调整排序'
            "
            :aria-label="flatManagementSorting ? '结束排序' : '调整排序'"
            @click="flatManagementSorting = !flatManagementSorting"
          />
          <ModuleActionButton
            v-if="!managedPageActions"
            class="record-panel-create-button"
            :context="context"
            action-code="create"
            icon-only
            :title="flatManagementContent?.createTitle"
            @click="createRootRecord"
          />
        </template>
      </template>
      <template #explorer>
        <template v-if="businessVisible">
          <CrudRecordListExplorer
            v-if="navigatorListScopeReady"
            :context="context"
            :selected-id="selectedRecord?.id == null ? undefined : String(selectedRecord.id)"
            :reload-key="flatManagementRecycleBin.reloadKey.value"
            :mode="flatManagementRecycleBin.mode.value"
            :sorting="flatManagementSorting"
            :keyword="flatManagementSearchKeyword"
            :query-quick-search-enabled="runtimePage?.quickSearchFields?.length !== 0"
            :external-query-values="navigatorListQueryValues"
            :empty-description="
              flatManagementRecycleBin.active.value ? '回收站为空' : flatManagementContent?.emptyDescription
            "
            :fallback-title="flatManagementContent?.fallbackTitle"
            :item-of="flatManagementItemOf"
            :filter-option="runtimePage?.quickSearchFields != null ? matchesPageQuickSearch : undefined"
            @recycle-bin-summary="flatManagementRecycleBin.updateSummary"
            @loaded="(records) => handleFlatManagementLoaded(records)"
            @update:keyword="flatManagementSearchKeyword = $event"
            @query-controller-change="bindListQueryController"
            @restored="refreshList"
            @select="(record) => openFlatManagementRecord(record)"
            @deselect="resetFlatManagementSelection"
          />
          <RecordPanelState v-else :description="navigatorScopeUnavailableDescription" />
        </template>
        <ModulePageBusinessState
          v-else
          :pending="pending"
          :error="businessError"
          @retry="retryBusinessSession"
        />
      </template>
      <template v-if="businessVisible && flatManagementRecycleBin.buttonVisible.value" #explorer-footer>
        <RecycleBinModeButton
          :active="flatManagementRecycleBin.active.value"
          :has-records="flatManagementRecycleBin.hasRecords.value"
          :count="flatManagementRecycleBin.total.value"
          @click="flatManagementRecycleBin.toggle"
        />
      </template>
      <template v-if="businessVisible && hasCardAssistantAt('outside', 'top')" #detail-outside-top>
        <aside class="module-card-assistant module-card-assistant--outside">
          <component :is="enhancementCardAssistant!.component" :context="cardAssistantContext" />
        </aside>
      </template>
      <template v-if="businessVisible && hasCardAssistantAt('inside', 'top')" #detail-content-top>
        <aside class="module-card-assistant">
          <component :is="enhancementCardAssistant!.component" :context="cardAssistantContext" />
        </aside>
      </template>
      <template v-if="businessVisible" #detail-actions>
        <RecordPanelButton
          v-if="detailWorkspaceAvailable"
          type="text"
          icon-name="open-in-new"
          title="在新标签页打开"
          aria-label="在新标签页打开"
          @click="openDetailWorkspaceView"
        />
        <RecordActionBar
          :context="context"
          :record-id="
            editorMode === 'create' || selectedRecord?.id == null ? undefined : String(selectedRecord.id)
          "
          :actions="flatManagementDetailActions"
          @action="handleFlatManagementAction"
        />
      </template>
      <template v-if="businessVisible" #detail-status>
        <RecordStatusSwitch
          v-if="
            context.abilities.hasEnable() === true &&
            !flatManagementRecycleBin.active.value &&
            editorMode !== 'view' &&
            editingRecord &&
            (editorMode === 'create' || typeof editingRecord.enabled === 'boolean')
          "
          :enabled="editingRecord.enabled !== false"
          :disabled="saving"
          :show-label="false"
          @change="updateDraftField('enabled', $event)"
        />
        <RecordStatusSwitch
          v-else-if="showStatusSwitch && !flatManagementRecycleBin.active.value && selectedRecord"
          :enabled="selectedRecord.enabled !== false"
          :disabled="!canToggleEnabled"
          :disabled-reason="toggleEnabledDisabledReason"
          :loading="togglingEnabled"
          :show-label="false"
          @change="toggleEnabled"
        />
      </template>
      <template v-if="businessVisible">
        <RecordPanelState
          v-if="!selectedRecord && editorMode === 'view'"
          :description="flatManagementContent?.detailEmptyDescription ?? detailEmptyDescription"
        />
        <RecordPanelState v-else-if="detailLoading" loading loading-tip="加载记录详情" description="" />
        <RecordPanelState v-else-if="detailLoadFailed" description="详情加载失败，请重新选择记录" />
        <ModulePageRecordContent
          v-else-if="editingRecord"
          :context="context"
          :cross-module-http="rawContext.http"
          :mode="editorMode"
          :record="editingRecord"
          :selected-record="selectedRecord"
          :detail-display-fields="detailDisplayFields"
          :form-fields="formFields"
          :form-session-key="formSessionKey"
          :validation-request-key="formValidationRequestKey"
          :picker-configs="referencePickerConfigs"
          :saving="saving"
          :ui-descriptor="runtimeUiDescriptor"
          :relations="executableDetailRelations"
          :relations-available="detailRelationsAvailable"
          :relation-reload-key="detailRelationReloadKey"
          :show-system-info="showDetailSystemInfo"
          :extension-sections="enhancementDetailSections"
          :detail-section-context="detailSectionContext"
          :form-contributions="formContributions"
          :form-field-policies="formFieldPolicies"
          @update:field="updateDraftField"
          @validity-change="updateMainFormValidity"
          @children-change="updateEmbeddedChildren"
          @relations-validity-change="updateRelationDraftValidity"
        />
      </template>
      <template v-if="businessVisible && hasCardAssistantAt('inside', 'bottom')" #detail-content-bottom>
        <aside class="module-card-assistant">
          <component :is="enhancementCardAssistant!.component" :context="cardAssistantContext" />
        </aside>
      </template>
      <template v-if="businessVisible && hasCardAssistantAt('outside', 'bottom')" #detail-outside-bottom>
        <aside class="module-card-assistant module-card-assistant--outside">
          <component :is="enhancementCardAssistant!.component" :context="cardAssistantContext" />
        </aside>
      </template>
    </StaticManagementLayout>

    <ManagementWorkspace
      v-else-if="listDetailCardPage"
      class="module-list-detail-workspace"
      :editing="editorMode !== 'view'"
      :explorer-count="navigatorExplorerCount + tenantScopeExplorerCount"
      :detail-surface="!detailSurfaceUsesDrawer"
      :list-surface="detailSurfaceUsesDrawer"
    >
      <ManagementExplorerColumn
        v-if="tenantScopeExplorerVisible && tenantScopeContext"
        :key="'tenant-scope'"
        collapsible
        title="租户"
        :has-selection="Boolean(tenantScopeId)"
      >
        <TenantScopeExplorer
          :context="tenantScopeContext"
          :selected-id="tenantScopeId || undefined"
          :reload-key="tenantScopeReloadKey"
          :disabled="tenantSwitchBlocked"
          @refresh="tenantScopeReloadKey += 1"
          @loaded="handleTenantScopeLoaded"
          @select="changeTenantScope($event)"
          @deselect="changeTenantScope(undefined)"
        />
      </ManagementExplorerColumn>
      <ManagementExplorerColumn v-if="navigatorExtension" :key="navigatorExtension.key">
        <template v-if="businessVisible">
          <component :is="navigatorExtension.component" :context="navigatorExtensionContext" />
        </template>
      </ManagementExplorerColumn>
      <ManagementExplorerColumn
        v-for="level in visibleNavigatorLevels"
        :key="level.descriptor.key"
        collapsible
        :title="businessVisible ? level.descriptor.title : ''"
        :has-selection="selectedNavigatorRecords[level.descriptor.key]?.id != null"
      >
        <template v-if="businessVisible">
          <PageNavigatorExplorer
            :level="level"
            :selected-id="
              selectedNavigatorRecords[level.descriptor.key]?.id == null
                ? undefined
                : String(selectedNavigatorRecords[level.descriptor.key]?.id)
            "
            :reload-key="navigatorReloadKey(level.descriptor.key)"
            :keyword="scopeSearchKeyword"
            :external-query-values="navigatorExplorerQueryValues(level.descriptor.key)"
            :navigator-host-module-alias="context.moduleAlias"
            :ready="navigatorManagementScopeReady(level)"
            :create-disabled="!navigatorManagementScopeReady(level)"
            :create-disabled-reason="navigatorManagementScopeDisabledReason(level)"
            :scope-subtitle="navigatorPanelScopeContext(level.descriptor.key)"
            :tree-parent-policy="navigatorTreeParentPolicy(level)"
            :actions-of="(record) => navigatorInlineActions(level, record)"
            :sort="navigatorSortState(level)"
            @update:keyword="scopeSearchKeyword = $event"
            @refresh="scopeReloadKey += 1"
            @create="createNavigatorRecord(level)"
            @loaded="handleNavigatorLoaded(level, $event)"
            @select="selectNavigatorRecord(level.descriptor.key, $event)"
            @deselect="clearNavigatorRecord(level.descriptor.key)"
            @action="(action, record) => handleNavigatorInlineAction(level, action, record)"
            @toggle-sorting="toggleNavigatorSorting(level)"
          >
            <template #editor>
              <NavigatorManagementEditor
                :open="
                  navigatorManagementLevel?.descriptor.key === level.descriptor.key &&
                  navigatorManagementDetail.open.value
                "
                :title="navigatorManagementTitle"
                :saving="navigatorManagementDetail.saving.value"
                :loading="navigatorManagementDetail.loading.value"
                :load-failed="navigatorManagementDetail.loadFailed.value"
                :draft="navigatorManagementDetail.draft.value"
                :fields="navigatorManagementFormFields"
                :mode="navigatorManagementDetail.mode.value"
                :form-session-key="navigatorManagementDetail.formSessionKey.value"
                :validation-request-key="navigatorManagementFormValidationRequestKey"
                :context="level.context"
                :picker-configs="navigatorManagementPickerConfigs"
                :contributions="navigatorManagementFormContributions"
                :field-policies="navigatorManagementFormFieldPolicies"
                :show-enabled="navigatorManagementEnabledVisible"
                :enabled="navigatorManagementDetail.draft.value?.enabled !== false"
                :enabled-disabled="navigatorManagementEnabledDisabled"
                :enabled-disabled-reason="navigatorManagementEnabledDisabledReason"
                :enabled-loading="navigatorManagementTogglingEnabled"
                @close="closeNavigatorManagementEditor"
                @save="saveNavigatorRecord"
                @toggle-enabled="toggleNavigatorManagementEnabled"
                @update-field="updateNavigatorManagementDraft"
                @validity-change="navigatorManagementFormValid = $event.valid"
              />
            </template>
          </PageNavigatorExplorer>
        </template>
      </ManagementExplorerColumn>
      <template v-if="businessVisible">
        <RecordQueryListPanel
          class="module-list"
          :class="{ 'module-list--row-expansion': listRowExpansionEnabled }"
          :context="context"
          :title="businessVisible ? modulePageTitle : ''"
          :subtitle="modulePageSubtitle"
          :selected-key="selectedRecord?.id"
          :expanded-row-keys="expandedListRowKeys"
          :reload-key="reloadKey"
          :refreshable="businessVisible && (!managedPageActions || listMode === 'recycleBin')"
          :standard-crud-actions="!managedPageActions"
          :standard-crud-row-actions="true"
          :standard-crud-row-action-keys="managedPageActions ? ['view'] : standardCrudRowActionKeys"
          :extra-actions="[...enhancementActions, ...placedPageActions]"
          :additional-columns="enhancementColumns"
          :cell-components="enhancementCellComponents"
          :extra-row-actions-of="enhancementRowActionsFor"
          :action-column-width="pageEnhancement?.list?.actionColumnWidth"
          :batch-actions="enhancementBatchActions"
          :ui-config-id="listUiConfigId"
          :query-template-id="listQueryTemplateId"
          :page-size="listPageSize"
          :ready="pageReady && navigatorListScopeReady"
          :sortable="true"
          :external-query-values="navigatorListQueryValues"
          :persistent-query-controls="persistentListQueryControls"
          :query-summaries="listQuerySummaries"
          :required-external-criteria-keys="navigatorListCriteriaKeys"
          :mode="listMode"
          :quick-search-placeholder="listSearchPlaceholder"
          :empty-description="listEmptyDescription"
          @loaded="handleLoaded"
          @query-controller-change="bindListQueryController"
          @mode-change="handleListModeChange"
          @page-size-change="setListPageSize"
          @restored="handleRecycleBinRestore"
          @select="selectListDetailRecord"
          @row-dblclick="openListRecord"
          @action="handleListAction"
          @row-action="handleRowAction"
          @row-expand="updateListRowExpansion"
          @batch-action="
            (action, records, _event, clearSelection) => handleBatchAction(action, records, clearSelection)
          "
        >
          <template v-if="listRowExpansionEnabled" #expandedRow="{ record }">
            <ModulePageListExpansionSurface
              :source-context="context"
              :cross-module-http="rawContext.http"
              :ui-descriptor="runtimeUiDescriptor!"
              :record="record"
              :relation-entries="listRelationExpansions"
              :extension="enhancementRowExpansion"
              :extension-context="enhancementRowExpansion ? listRowExpansionContext(record, true) : undefined"
            />
          </template>
        </RecordQueryListPanel>
      </template>
      <ModulePageBusinessState
        v-else
        :pending="pending"
        :error="businessError"
        @retry="retryBusinessSession"
      />

      <RecordDetailPanel
        v-if="!detailSurfaceUsesDrawer"
        class="module-list-detail-card"
        :title="businessVisible ? detailTitle : ''"
      >
        <template v-if="businessVisible && hasCardAssistantAt('outside', 'top')" #outside-top>
          <aside class="module-card-assistant module-card-assistant--outside">
            <component :is="enhancementCardAssistant!.component" :context="cardAssistantContext" />
          </aside>
        </template>
        <template v-if="businessVisible && hasCardAssistantAt('inside', 'top')" #content-top>
          <aside class="module-card-assistant">
            <component :is="enhancementCardAssistant!.component" :context="cardAssistantContext" />
          </aside>
        </template>
        <template v-if="businessVisible" #title-prefix>
          <RecordPanelButton
            class="detail-surface-mode-button"
            type="text"
            icon-name="pin-off"
            title="改为抽屉展示"
            aria-label="改为抽屉展示"
            @click="useDrawerDetailSurface"
          />
        </template>
        <template v-if="businessVisible" #actions>
          <ModuleRecordDetailActions
            :context="context"
            :record="selectedRecord"
            :mode="editorMode"
            :saving="detailActionBusy"
            :active-action-key="activeDetailActionKey"
            :detail-loading="detailLoading"
            :detail-load-failed="detailLoadFailed"
            :recycle-bin-active="recycleBinDetailActive"
            :actions="enhancementDetailActions"
            :configured-actions="[...detailPageActions, ...placedDetailButtons]"
            :form-actions="placedFormActions"
            :managed-actions="managedPageActions"
            :workspace-available="detailWorkspaceAvailable"
            @cancel="cancelDetailEditing"
            @save="saveRecord"
            @edit="selectedRecord && editRecord(selectedRecord, 'restore-view')"
            @delete="selectedRecord && deleteRecord(selectedRecord)"
            @open-workspace="openDetailWorkspaceView"
            @detail-action="
              editorMode === 'view' ? handleDetailAction($event) : handlePlacedFormAction($event)
            "
          />
        </template>
        <template v-if="businessVisible" #status>
          <RecordStatusSwitch
            v-if="showStatusSwitch && !recycleBinDetailActive && editorMode === 'view' && selectedRecord"
            :enabled="selectedRecord.enabled !== false"
            :disabled="!canToggleEnabled"
            :disabled-reason="toggleEnabledDisabledReason"
            :loading="togglingEnabled"
            :show-label="false"
            @change="toggleEnabled"
          />
        </template>

        <template v-if="businessVisible">
          <RecordPanelState
            v-if="!selectedRecord && editorMode === 'view'"
            :description="detailEmptyDescription"
          />
          <RecordPanelState v-else-if="detailLoading" loading loading-tip="加载记录详情" description="" />
          <RecordPanelState v-else-if="detailLoadFailed" description="详情加载失败，请重新选择记录" />
          <ModulePageRecordContent
            v-else-if="editingRecord"
            :context="context"
            :cross-module-http="rawContext.http"
            :mode="editorMode"
            :record="editingRecord"
            :selected-record="selectedRecord"
            :detail-display-fields="detailDisplayFields"
            :form-fields="formFields"
            :form-session-key="formSessionKey"
            :validation-request-key="formValidationRequestKey"
            :picker-configs="referencePickerConfigs"
            :saving="detailActionBusy"
            :ui-descriptor="runtimeUiDescriptor"
            :relations="executableDetailRelations"
            :relations-available="detailRelationsAvailable"
            :relation-reload-key="detailRelationReloadKey"
            :show-system-info="showDetailSystemInfo"
            :extension-sections="enhancementDetailSections"
            :detail-section-context="detailSectionContext"
            :form-contributions="formContributions"
            :form-field-policies="formFieldPolicies"
            @update:field="updateDraftField"
            @validity-change="updateMainFormValidity"
            @children-change="updateEmbeddedChildren"
            @relations-validity-change="updateRelationDraftValidity"
          />
        </template>
        <template v-if="businessVisible && hasCardAssistantAt('inside', 'bottom')" #content-bottom>
          <aside class="module-card-assistant">
            <component :is="enhancementCardAssistant!.component" :context="cardAssistantContext" />
          </aside>
        </template>
        <template v-if="businessVisible && hasCardAssistantAt('outside', 'bottom')" #outside-bottom>
          <aside class="module-card-assistant module-card-assistant--outside">
            <component :is="enhancementCardAssistant!.component" :context="cardAssistantContext" />
          </aside>
        </template>
      </RecordDetailPanel>
    </ManagementWorkspace>

    <ManagementWorkspace
      v-else-if="treeManagementPage || treeModule"
      class="module-tree-workspace"
      :editing="editorMode !== 'view'"
      :explorer-count="navigatorExplorerCount + tenantScopeExplorerCount + 1"
    >
      <ManagementExplorerColumn
        v-if="tenantScopeExplorerVisible && tenantScopeContext"
        :key="'tenant-scope'"
        collapsible
        title="租户"
        :has-selection="Boolean(tenantScopeId)"
      >
        <TenantScopeExplorer
          :context="tenantScopeContext"
          :selected-id="tenantScopeId || undefined"
          :reload-key="tenantScopeReloadKey"
          :disabled="tenantSwitchBlocked"
          @refresh="tenantScopeReloadKey += 1"
          @loaded="handleTenantScopeLoaded"
          @select="changeTenantScope($event)"
          @deselect="changeTenantScope(undefined)"
        />
      </ManagementExplorerColumn>
      <ManagementExplorerColumn v-if="navigatorExtension" :key="navigatorExtension.key">
        <template v-if="businessVisible">
          <component :is="navigatorExtension.component" :context="navigatorExtensionContext" />
        </template>
      </ManagementExplorerColumn>
      <ManagementExplorerColumn
        v-for="level in visibleNavigatorLevels"
        :key="level.descriptor.key"
        collapsible
        :title="businessVisible ? level.descriptor.title : ''"
        :has-selection="selectedNavigatorRecords[level.descriptor.key]?.id != null"
      >
        <template v-if="businessVisible">
          <PageNavigatorExplorer
            :level="level"
            :selected-id="
              selectedNavigatorRecords[level.descriptor.key]?.id == null
                ? undefined
                : String(selectedNavigatorRecords[level.descriptor.key]?.id)
            "
            :reload-key="navigatorReloadKey(level.descriptor.key)"
            :keyword="scopeSearchKeyword"
            :external-query-values="navigatorExplorerQueryValues(level.descriptor.key)"
            :navigator-host-module-alias="context.moduleAlias"
            :ready="navigatorManagementScopeReady(level)"
            :create-disabled="!navigatorManagementScopeReady(level)"
            :create-disabled-reason="navigatorManagementScopeDisabledReason(level)"
            :scope-subtitle="navigatorPanelScopeContext(level.descriptor.key)"
            :tree-parent-policy="navigatorTreeParentPolicy(level)"
            :actions-of="(record) => navigatorInlineActions(level, record)"
            :sort="navigatorSortState(level)"
            @update:keyword="scopeSearchKeyword = $event"
            @refresh="scopeReloadKey += 1"
            @create="createNavigatorRecord(level)"
            @loaded="handleNavigatorLoaded(level, $event)"
            @select="selectNavigatorRecord(level.descriptor.key, $event)"
            @deselect="clearNavigatorRecord(level.descriptor.key)"
            @action="(action, record) => handleNavigatorInlineAction(level, action, record)"
            @toggle-sorting="toggleNavigatorSorting(level)"
          >
            <template #editor>
              <NavigatorManagementEditor
                :open="
                  navigatorManagementLevel?.descriptor.key === level.descriptor.key &&
                  navigatorManagementDetail.open.value
                "
                :title="navigatorManagementTitle"
                :saving="navigatorManagementDetail.saving.value"
                :loading="navigatorManagementDetail.loading.value"
                :load-failed="navigatorManagementDetail.loadFailed.value"
                :draft="navigatorManagementDetail.draft.value"
                :fields="navigatorManagementFormFields"
                :mode="navigatorManagementDetail.mode.value"
                :form-session-key="navigatorManagementDetail.formSessionKey.value"
                :validation-request-key="navigatorManagementFormValidationRequestKey"
                :context="level.context"
                :picker-configs="navigatorManagementPickerConfigs"
                :contributions="navigatorManagementFormContributions"
                :field-policies="navigatorManagementFormFieldPolicies"
                :show-enabled="navigatorManagementEnabledVisible"
                :enabled="navigatorManagementDetail.draft.value?.enabled !== false"
                :enabled-disabled="navigatorManagementEnabledDisabled"
                :enabled-disabled-reason="navigatorManagementEnabledDisabledReason"
                :enabled-loading="navigatorManagementTogglingEnabled"
                @close="closeNavigatorManagementEditor"
                @save="saveNavigatorRecord"
                @toggle-enabled="toggleNavigatorManagementEnabled"
                @update-field="updateNavigatorManagementDraft"
                @validity-change="navigatorManagementFormValid = $event.valid"
              />
            </template>
          </PageNavigatorExplorer>
        </template>
      </ManagementExplorerColumn>
      <ManagementExplorerColumn>
        <RecordExplorerPanel
          :title="businessVisible ? treePanelTitle : '业务页面'"
          :refreshable="businessVisible && (!managedPageActions || Boolean(explorerRefreshAction))"
          :refresh-disabled="
            managedPageActions &&
            (saving ||
              !explorerRefreshAction ||
              explorerRefreshAction.disabled ||
              context.can('query') !== true)
          "
          :subtitle="businessVisible ? mainTreeScopeContext : ''"
          :refresh-title="`刷新${treePanelTitle}`"
          :searchable="businessVisible && runtimePage?.quickSearchFields?.length !== 0"
          :search-keyword="treeSearchKeyword"
          :search-placeholder="listSearchPlaceholder"
          @update:search-keyword="treeSearchKeyword = $event"
          @refresh="
            businessVisible &&
            (explorerRefreshAction ? handlePlacedPageAction(explorerRefreshAction) : (treeReloadKey += 1))
          "
        >
          <template #actions>
            <template v-if="businessVisible">
              <RecordActionBar
                v-if="explorerExtraActions.length"
                :context="context"
                :actions="explorerExtraActions"
                @action="handlePlacedPageAction"
              />
              <RecordPanelButton
                v-if="mainTreeScopeReady && context.can('sort') === true"
                icon-name="swap-vertical"
                icon-only
                size="small"
                type="text"
                :selected="mainTreeSorting"
                :disabled="Boolean(treeSearchKeyword.trim())"
                :title="
                  treeSearchKeyword.trim()
                    ? '清空搜索后可调整排序'
                    : mainTreeSorting
                      ? '结束排序'
                      : '调整排序'
                "
                :aria-label="mainTreeSorting ? '结束排序' : '调整排序'"
                @click="mainTreeSorting = !mainTreeSorting"
              />
              <ModuleActionButton
                v-if="mainTreeScopeReady && explorerCreateAction"
                :context="context"
                action-code="create"
                icon-only
                :title="explorerCreateAction.title"
                :disabled="saving || explorerCreateAction.disabled"
                @click="handlePlacedPageAction(explorerCreateAction)"
              />
              <ModuleActionButton
                v-if="mainTreeScopeReady && !managedPageActions"
                class="record-panel-create-button"
                :context="context"
                action-code="create"
                icon-only
                :title="runtimePage?.treeResource?.createTitle ?? `新建${treeRootTitle}`"
                @click="createRootRecord"
              />
            </template>
          </template>
          <template v-if="businessVisible">
            <TreeRecordExplorer
              v-if="mainTreeScopeReady"
              :context="context"
              :selected-id="selectedTreeRecord?.id == null ? undefined : String(selectedTreeRecord.id)"
              :reload-key="treeReloadKey"
              :keyword="treeSearchKeyword"
              :title-of="runtimePage?.explorer ? mainTreeTitle : undefined"
              :secondary-of="runtimePage?.explorer ? mainTreeSecondary : undefined"
              :filter-option="
                runtimePage?.explorer || runtimePage?.quickSearchFields != null
                  ? matchesPageQuickSearch
                  : undefined
              "
              :sorting="mainTreeSorting"
              :sort-partition-fields="
                runtimePage?.treeResource
                  ? runtimePage.treeResource.sortPartitionFields
                  : context.runtime.snapshot()?.sortPartitionFields
              "
              :external-query-values="runtimePage?.treeResource ? undefined : navigatorListQueryValues"
              search-mode="none"
              search-trigger="external"
              :empty-description="listEmptyDescription"
              @select="selectTreeRecord"
              @deselect="clearTreeRecordSelection"
              @loaded="handleTreeLoaded"
              @sorted="handleTreeSorted"
            />
            <RecordPanelState v-else :description="mainTreeScopeDescription" />
          </template>
          <ModulePageBusinessState
            v-else
            :pending="pending"
            :error="businessError"
            @retry="retryBusinessSession"
          />
        </RecordExplorerPanel>
      </ManagementExplorerColumn>

      <RecordDetailPanel class="module-tree-card" :title="businessVisible ? detailTitle : ''">
        <template v-if="businessVisible && hasCardAssistantAt('outside', 'top')" #outside-top>
          <aside class="module-card-assistant module-card-assistant--outside">
            <component :is="enhancementCardAssistant!.component" :context="cardAssistantContext" />
          </aside>
        </template>
        <template v-if="businessVisible && hasCardAssistantAt('inside', 'top')" #content-top>
          <aside class="module-card-assistant">
            <component :is="enhancementCardAssistant!.component" :context="cardAssistantContext" />
          </aside>
        </template>
        <template v-if="businessVisible" #actions>
          <ModuleRecordDetailActions
            :context="context"
            :record="selectedRecord"
            :mode="editorMode"
            :saving="detailActionBusy"
            :active-action-key="activeDetailActionKey"
            :detail-loading="detailLoading"
            :detail-load-failed="detailLoadFailed"
            :actions="enhancementDetailActions"
            :configured-actions="[...detailPageActions, ...placedDetailButtons]"
            :form-actions="placedFormActions"
            :managed-actions="managedPageActions"
            :workspace-available="detailWorkspaceAvailable"
            :create-child-available="!managedPageActions"
            :create-child-disabled="!selectedRecord || context.can('create') !== true"
            @cancel="closeTreeCardEditor"
            @save="saveRecord"
            @edit="selectedRecord && editRecord(selectedRecord, 'restore-view')"
            @delete="selectedRecord && deleteRecord(selectedRecord)"
            @open-workspace="openDetailWorkspaceView"
            @create-child="createChildRecord"
            @detail-action="
              editorMode === 'view' ? handleDetailAction($event) : handlePlacedFormAction($event)
            "
          />
        </template>
        <template v-if="businessVisible" #status>
          <RecordStatusSwitch
            v-if="showStatusSwitch && editorMode === 'view' && selectedRecord"
            :enabled="selectedRecord.enabled !== false"
            :disabled="!canToggleEnabled"
            :disabled-reason="toggleEnabledDisabledReason"
            :loading="togglingEnabled"
            :show-label="false"
            @change="toggleEnabled"
          />
        </template>

        <template v-if="businessVisible">
          <RecordPanelState
            v-if="!selectedRecord && editorMode === 'view'"
            :description="detailEmptyDescription"
          />
          <RecordPanelState v-else-if="detailLoading" loading loading-tip="加载记录详情" description="" />
          <RecordPanelState v-else-if="detailLoadFailed" description="详情加载失败，请重新选择记录" />
          <template v-else-if="editingRecord">
            <template v-if="editorMode === 'view'">
              <RecordDetailFields
                :record="editingRecord"
                :fields="detailDisplayFields"
                :option-context="context"
                :file-transfer-context="context"
                :exclude-field-names="['enabled']"
              />
              <RecordDetailExtensionSection
                v-for="section in enhancementDetailSections"
                :key="section.key"
                :title="section.title"
              >
                <component :is="section.component" :context="detailSectionContext(editingRecord)" />
              </RecordDetailExtensionSection>
            </template>
            <div v-else class="module-form">
              <RecordFormFields
                :record="editingRecord"
                :fields="formFields"
                :form-session-key="formSessionKey"
                :validation-request-key="formValidationRequestKey"
                :option-context="context"
                :file-transfer-context="context"
                :picker-configs="referencePickerConfigs"
                :exclude-field-names="['enabled']"
                @update:field="updateDraftField"
                @validity-change="updateMainFormValidity"
              />
            </div>
            <ModulePageDetailRelations
              v-if="runtimeUiDescriptor && detailRelationsAvailable"
              :source-context="context"
              :cross-module-http="rawContext.http"
              :ui-descriptor="runtimeUiDescriptor"
              :relations="executableDetailRelations"
              :parent-record="(editorMode === 'view' ? selectedRecord : editingRecord)!"
              :mutation-enabled="editorMode !== 'view'"
              :reload-key="detailRelationReloadKey"
              :validation-request-key="formValidationRequestKey"
              @children-change="updateEmbeddedChildren"
              @validity-change="updateRelationDraftValidity"
            />
            <RecordMetaSection
              v-if="editorMode !== 'create' && showDetailSystemInfo"
              :record="editingRecord"
              show-sort-order
            />
          </template>
        </template>
        <template v-if="businessVisible && hasCardAssistantAt('inside', 'bottom')" #content-bottom>
          <aside class="module-card-assistant">
            <component :is="enhancementCardAssistant!.component" :context="cardAssistantContext" />
          </aside>
        </template>
        <template v-if="businessVisible && hasCardAssistantAt('outside', 'bottom')" #outside-bottom>
          <aside class="module-card-assistant module-card-assistant--outside">
            <component :is="enhancementCardAssistant!.component" :context="cardAssistantContext" />
          </aside>
        </template>
      </RecordDetailPanel>
    </ManagementWorkspace>

    <template v-else>
      <template v-if="businessVisible">
        <RecordQueryListPanel
          class="module-list"
          :class="{ 'module-list--row-expansion': listRowExpansionEnabled }"
          :context="context"
          :title="businessVisible ? modulePageTitle : ''"
          :subtitle="businessVisible ? modulePageSubtitle : undefined"
          :selected-key="selectedRecord?.id"
          :expanded-row-keys="expandedListRowKeys"
          :reload-key="reloadKey"
          :refreshable="!managedPageActions || listMode === 'recycleBin'"
          :standard-crud-actions="!managedPageActions"
          :standard-crud-row-actions="true"
          :standard-crud-row-action-keys="managedPageActions ? ['view'] : standardCrudRowActionKeys"
          :extra-actions="[...enhancementActions, ...placedPageActions]"
          :additional-columns="enhancementColumns"
          :cell-components="enhancementCellComponents"
          :extra-row-actions-of="enhancementRowActionsFor"
          :action-column-width="pageEnhancement?.list?.actionColumnWidth"
          :batch-actions="enhancementBatchActions"
          :ui-config-id="listUiConfigId"
          :query-template-id="listQueryTemplateId"
          :page-size="listPageSize"
          :ready="pageReady && navigatorListScopeReady"
          :sortable="true"
          :external-query-values="navigatorListQueryValues"
          :required-external-criteria-keys="navigatorListCriteriaKeys"
          :mode="listMode"
          :persistent-query-controls="persistentListQueryControls"
          :query-summaries="listQuerySummaries"
          :quick-search-placeholder="listSearchPlaceholder"
          :empty-description="listEmptyDescription"
          @loaded="handleLoaded"
          @query-controller-change="bindListQueryController"
          @mode-change="handleListModeChange"
          @page-size-change="setListPageSize"
          @restored="handleRecycleBinRestore"
          @select="selectStandaloneListRecord"
          @row-dblclick="openListRecord"
          @action="handleListAction"
          @row-action="handleRowAction"
          @row-expand="updateListRowExpansion"
          @batch-action="
            (action, records, _event, clearSelection) => handleBatchAction(action, records, clearSelection)
          "
        >
          <template v-if="listRowExpansionEnabled" #expandedRow="{ record }">
            <ModulePageListExpansionSurface
              :source-context="context"
              :cross-module-http="rawContext.http"
              :ui-descriptor="runtimeUiDescriptor!"
              :record="record"
              :relation-entries="listRelationExpansions"
              :extension="enhancementRowExpansion"
              :extension-context="enhancementRowExpansion ? listRowExpansionContext(record, true) : undefined"
            />
          </template>
        </RecordQueryListPanel>
      </template>
      <ModulePageBusinessState
        v-else
        :pending="pending"
        :error="businessError"
        @retry="retryBusinessSession"
      />
    </template>
  </section>
  <section v-else-if="!props.recordOnly" class="module-unsupported">
    <h2>{{ title }}</h2>
    <p>{{ unsupportedPageModeText }}</p>
  </section>

  <template v-if="businessVisible">
    <Teleport :disabled="Boolean(props.recordOnly) || !workspaceElement" :to="workspaceElement ?? 'body'">
      <RecordModeDrawer
        v-if="
          props.recordOnly ||
          (!persistentTreeDetail && !flatManagementPage && (!listDetailCardPage || detailSurfaceUsesDrawer))
        "
        :open="detailOpen"
        :title="businessVisible ? detailTitle : ''"
        :render-mode="props.recordOnly?.renderMode ?? 'inline'"
        :scope="props.recordOnly?.scope ?? 'tab'"
        :width="enhancementDetailDrawer?.width"
        :mode="editorMode"
        :loading="detailLoading"
        :load-failed="detailLoadFailed"
        :dismissal="editorMode === 'view' ? 'dismissible' : 'guarded'"
        :before-close="confirmDetailDrawerClose"
        @close="props.recordOnly ? closeRecordOnlyDetail() : closeDetail()"
        @after-close="finishRecordOnlyDetailClose"
        @retry="retryLoadDetail"
      >
        <template v-if="!props.recordOnly && detailWorkspaceAvailable" #header-actions>
          <RecordPanelButton
            type="text"
            icon-name="open-in-new"
            title="在新标签页打开"
            aria-label="在新标签页打开"
            @click="openDetailWorkspaceView"
          />
        </template>
        <template v-if="!props.recordOnly && listDetailCardPage && !narrowDetailSurface" #title-prefix>
          <RecordPanelButton
            class="detail-surface-mode-button"
            type="text"
            icon-name="pin"
            title="固定到右侧展示"
            aria-label="固定到右侧展示"
            @click="usePinnedDetailSurface"
          />
        </template>
        <template #status>
          <RecordStatusSwitch
            v-if="
              showStatusSwitch &&
              !recycleBinDetailActive &&
              !enhancementDetailDrawer &&
              editorMode === 'view' &&
              selectedRecord
            "
            :enabled="selectedRecord.enabled !== false"
            :disabled="!canToggleEnabled"
            :disabled-reason="toggleEnabledDisabledReason"
            :loading="togglingEnabled"
            :show-label="false"
            @change="toggleEnabled"
          />
        </template>
        <template v-if="!enhancementDetailDrawer || enhancementDetailActions.length > 0" #operation>
          <ModuleRecordDetailActions
            :context="context"
            :record="selectedRecord"
            :mode="editorMode"
            :saving="detailActionBusy"
            :active-action-key="activeDetailActionKey"
            :detail-loading="detailLoading"
            :detail-load-failed="detailLoadFailed"
            :recycle-bin-active="recycleBinDetailActive"
            :actions="enhancementDetailActions"
            :configured-actions="[...detailPageActions, ...placedDetailButtons]"
            :form-actions="placedFormActions"
            :managed-actions="managedPageActions"
            :show-standard-view-actions="!enhancementDetailDrawer"
            @cancel="cancelDetailEditing"
            @save="saveRecord"
            @edit="selectedRecord && editRecord(selectedRecord, 'restore-view')"
            @delete="selectedRecord && deleteRecord(selectedRecord)"
            @detail-action="
              editorMode === 'view' ? handleDetailAction($event) : handlePlacedFormAction($event)
            "
          />
        </template>
        <template #view>
          <template v-if="editingRecord">
            <component
              :is="enhancementDetailDrawer.component"
              v-if="enhancementDetailDrawer"
              :context="recordViewContext(editingRecord)"
            />
            <ModulePageRecordContent
              v-else
              :context="context"
              :cross-module-http="rawContext.http"
              :mode="editorMode"
              :record="editingRecord"
              :selected-record="selectedRecord"
              :detail-display-fields="detailDisplayFields"
              :form-fields="formFields"
              :form-session-key="formSessionKey"
              :validation-request-key="formValidationRequestKey"
              :picker-configs="referencePickerConfigs"
              :saving="saving"
              :ui-descriptor="runtimeUiDescriptor"
              :relations="executableDetailRelations"
              :relations-available="detailRelationsAvailable"
              :relation-reload-key="detailRelationReloadKey"
              :show-system-info="showDetailSystemInfo"
              :extension-sections="enhancementDetailSections"
              :detail-section-context="detailSectionContext"
              :form-contributions="formContributions"
              :form-field-policies="formFieldPolicies"
              @update:field="updateDraftField"
              @validity-change="updateMainFormValidity"
              @children-change="updateEmbeddedChildren"
              @relations-validity-change="updateRelationDraftValidity"
            />
          </template>
        </template>
        <template #form>
          <ModulePageRecordContent
            v-if="editingRecord"
            :context="context"
            :cross-module-http="rawContext.http"
            :mode="editorMode"
            :record="editingRecord"
            :selected-record="selectedRecord"
            :detail-display-fields="detailDisplayFields"
            :form-fields="formFields"
            :form-session-key="formSessionKey"
            :validation-request-key="formValidationRequestKey"
            :picker-configs="referencePickerConfigs"
            :saving="saving"
            :ui-descriptor="runtimeUiDescriptor"
            :relations="executableDetailRelations"
            :relations-available="detailRelationsAvailable"
            :relation-reload-key="detailRelationReloadKey"
            :show-system-info="showDetailSystemInfo"
            :extension-sections="enhancementDetailSections"
            :detail-section-context="detailSectionContext"
            :form-contributions="formContributions"
            :form-field-policies="formFieldPolicies"
            @update:field="updateDraftField"
            @validity-change="updateMainFormValidity"
            @children-change="updateEmbeddedChildren"
            @relations-validity-change="updateRelationDraftValidity"
          />
        </template>
      </RecordModeDrawer>
      <ModuleReferenceRecordDetailBrowser
        :browser="referenceRecordDetailBrowser"
        :render-mode="props.recordOnly?.renderMode ?? 'inline'"
        :scope="props.recordOnly?.scope ?? 'tab'"
        @record-change="handleReferenceRecordChange"
        @interaction-state-change="updateReferenceRecordDetailInteraction($event)"
      />
    </Teleport>
    <RecordPermissionDialog
      v-if="permissionsOpen && selectedRecord?.id != null"
      :open="permissionsOpen"
      :context="context"
      :record-id="String(selectedRecord.id)"
      @close="permissionsOpen = false"
      @changed="permissionsChanged"
    />
    <RecordDetailDrawer
      v-if="enhancementDrawer"
      :open="enhancementDrawerOpen"
      :title="enhancementDrawer.definition.title"
      :subtitle="enhancementDrawer.subtitle"
      render-mode="inline"
      :width="enhancementDrawer.definition.width"
      @close="closeEnhancementDrawer"
      @after-close="disposeEnhancementDrawer"
    >
      <template v-if="enhancementDrawer.titleActions.length" #title-actions>
        <DrawerTitleActions :actions="enhancementDrawer.titleActions" />
      </template>
      <template v-if="enhancementDrawer.operation?.summary" #operation-summary>
        {{ enhancementDrawer.operation.summary }}
      </template>
      <template v-if="enhancementDrawer.operation" #operation>
        <DrawerTitleActions :actions="enhancementDrawer.operation.actions" />
      </template>
      <component :is="enhancementDrawer.definition.component" :context="enhancementDrawer.context" />
    </RecordDetailDrawer>
    <UiModal
      :open="localEditOpen"
      :title="localEditBlock?.title ?? '局部编辑'"
      confirm-text="保存"
      :width="localEditBlock?.width ?? 640"
      :confirm-loading="localEditSaving"
      @confirm="submitLocalEdit"
      @cancel="dismissLocalEdit"
    >
      <RecordFormFields
        v-if="localEditDraft"
        :record="localEditDraft"
        :fields="localEditFields"
        :option-context="context"
        :file-transfer-context="context"
        :disabled="localEditSaving"
        @update:field="(fieldName, value) => (localEditDraft![fieldName] = value)"
        @validity-change="updateLocalEditFormValidity"
      />
    </UiModal>
  </template>
</template>

<style scoped>
.module-workspace {
  position: relative;
  min-width: 0;
  min-height: calc(100vh - 116px);
}

/* All desktop management templates share one fixed workbench boundary. */
.module-workspace--management {
  height: 100%;
  min-height: 0;
}

.module-list {
  min-width: 0;
}

/* The platform owns the expanded-row table boundary; its shared surface owns visual hierarchy. */
.module-list--row-expansion :deep(.ant-table-tbody > tr.ant-table-expanded-row > td) {
  padding: 0 !important;
  background: var(--muyun-surface) !important;
  border-bottom-color: var(--muyun-border-subtle);
}

/* Ant Design adds fixed-row compensation around expanded content. The platform
 * surface owns spacing instead, so relation and extension content align alike. */
.module-list--row-expansion :deep(.ant-table-expanded-row-fixed) {
  margin: 0 !important;
  padding: 0 !important;
}

.module-list--row-expansion :deep(.ant-table-tbody > tr.ant-table-expanded-row:hover > td) {
  background: var(--muyun-surface) !important;
}

.module-tree-workspace {
  height: 100%;
  min-height: 0;
}

.module-list-detail-card {
  min-width: var(--muyun-management-detail-min-width);
}

/* Title-level layout toggles are compact controls, not primary panel actions. */
.detail-surface-mode-button {
  width: 24px;
  min-width: 24px;
  height: 24px;
  padding: 0;
}

.detail-surface-mode-button :deep(.ui-icon) {
  font-size: 16px;
}

.module-tree-card {
  min-width: 0;
}

.module-tree-workspace :deep(.record-panel-create-button) {
  width: 28px;
  height: 28px;
  padding: 0;
  border-radius: 999px;
}

.module-scope-editor-panel {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 3;
  display: grid;
  align-content: start;
  gap: 12px;
  max-height: min(420px, 68%);
  min-height: 0;
  padding: 12px;
  border: 1px solid var(--muyun-border);
  border-top-color: var(--muyun-border-subtle);
  border-radius: 8px 8px 0 0;
  background: var(--muyun-surface);
  box-shadow:
    0 -1px 0 rgb(15 23 42 / 4%),
    0 -12px 28px rgb(15 23 42 / 12%);
  overflow: auto;
}

.module-scope-editor-drawer-enter-active,
.module-scope-editor-drawer-leave-active {
  transition:
    transform 0.18s ease,
    opacity 0.18s ease;
}

.module-scope-editor-drawer-enter-from,
.module-scope-editor-drawer-leave-to {
  opacity: 0;
  transform: translateY(100%);
}

.module-scope-editor-header,
.module-scope-editor-actions {
  display: flex;
  align-items: center;
}

.module-scope-editor-header {
  justify-content: space-between;
  gap: 10px;
}

.module-scope-editor-header h3 {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  color: var(--muyun-text);
  font-size: 14px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.module-scope-editor-actions {
  flex: 0 0 auto;
  gap: 8px;
}

.module-scope-editor-form {
  grid-template-columns: minmax(0, 1fr);
}

.module-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 12px;
  row-gap: 16px;
  --muyun-record-form-label-gap: 8px;
}

.module-card-assistant {
  min-width: 0;
}

.module-unsupported {
  display: grid;
  align-content: center;
  justify-items: center;
  min-height: calc(100vh - 116px);
  color: var(--muyun-text-muted);
  text-align: center;
}

.module-unsupported h2 {
  margin: 0 0 8px;
  color: var(--muyun-text);
  font-size: 18px;
  font-weight: 600;
}

.module-unsupported p {
  margin: 0;
  font-size: 13px;
}

@media (max-width: 720px) {
  .module-workspace--management {
    height: calc(100vh - 116px);
    min-height: calc(100vh - 116px);
  }

  .module-tree-workspace {
    height: auto;
    min-height: 0;
  }

  .module-form {
    grid-template-columns: 1fr;
  }
}
</style>
