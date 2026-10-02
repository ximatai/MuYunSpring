<script setup lang="ts">
import { onActivated, onDeactivated } from 'vue';
import {
  ManagementExplorerColumn,
  ManagementPanelHeader,
  ManagementWorkspace,
  RecordDetailDrawer,
  RecordDetailPanel,
  RecordExplorerPanel,
  ManagementTabs,
} from '@muyun/platform-components';

import { useWorkspaceViewUnsavedState } from '@muyun/platform-workbench';
import { pageActionEntryDescription, pageActionEntryTitle } from '@muyun/web-core';
import {
  UiButton,
  UiTree,
  UiEmpty,
  UiInput,
  UiSelect,
  UiSwitch,
  UiRadioGroup,
  type UiTreeNode,
} from '@muyun/vue-ui-antdv';

import { orderedFormItems } from './pageCompositionDraftState';
import { actionButtonMembers } from './pageCompositionMode';

import PageCompositionDescriptorPreview from './PageCompositionDescriptorPreview.vue';
import PageQuerySummaryEditor from './PageQuerySummaryEditor.vue';
import MetadataSourceTree from './MetadataSourceTree.vue';

import PageCompositionTree from './PageCompositionTree.vue';
import { PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE } from './pageCompositionDragPayload';

import type { PageCompositionSession } from './pageCompositionSession';
const props = withDefaults(defineProps<{ session: PageCompositionSession; retained?: boolean }>(), {
  retained: false,
});
const {
  state,
  paletteMode,
  pendingChildren,
  componentNameInvalid,
  childInvalid,
  componentCatalog,
  componentCatalogLoading,
  componentNodes,
  selectedPendingComponent,
  selectedFieldUsage,
  duplicateComponentFields,
  updateComponentRequired,
  loadComponentCatalog,
  updateComponentTitle,
  catalogueRefreshPending,
  loading,
  metadataLoadFailed,
  saving,
  publishing,
  relation,
  dictionaryRadioMaxOptions,
  metadataTreeReloadKey,
  revision,
  publishedRevision,
  compositionMode,
  configuredMode,
  explorerTitleField,
  quickSearchFields,
  searchableFields,
  explorerSecondaryField,
  moduleActions,
  actionPlacements,
  actionFormMode,
  selectedActionEntry,
  actionIssues,
  editorMode,
  editorModeOptions,
  skeleton,
  fieldKeyword,
  showSystemFields,
  selectedMetadataTreeKey,
  metadataExpandedKeys,
  propertyDrawerOpen,
  propertyDraft,
  groupTitleDraft,
  groupSubtitleDraft,
  quickSearchPlaceholderDraft,
  previewDescriptor,
  previewStructure,
  previewLoading,
  previewError,
  summaryCatalog,
  summaryCatalogLoading,
  summaryCatalogError,
  summaryDrawerOpen,
  selectedSummaryKey,
  summaryFocusRequest,
  draftParseError,
  draftConflict,
  supportsQuerySummaries,
  summarySources,
  hasCatalogDependentSummary,
  summaryCatalogBlocksMutation,
  summaryEditorIssues,
  summaryIssueEntries,
  hasSummaryIssues,
  summaryDescriptions,
  summaryTreeIssues,
  effectivePreviewMode,
  previewModes,
  removedDraft,
  propertyIssues,
  visibleFields,
  allMetadataFields,
  selectedField,
  selectedRelation,
  selectedRelationField,
  selectedDirectReferenceFormField,
  selectedDirectDictionaryFormField,
  referencePickerPresentationValue,
  referencePickerPresentationOptions,
  dictionaryPresentationValue,
  selectedDictionaryRadioIssue,
  dictionaryPresentationOptions,
  selectedGroup,
  selectedQuickSearch,
  propertyDrawerTitle,
  selectedPreviewFieldName,
  hasUnsavedChanges,
  hasPendingChanges,
  isMutating,
  metadataLoadProblem,
  unavailableSources,
  dictionaryRadioIssues,
  propertyValidationMessage,
  selectedUiTreeKey,
  composerTitle,
  paletteTitle,
  structureTitle,
  compositionSubtitle,
  metadataTreeNodes,
  applyConfiguredMode,
  loadMetadataTree,
  loadSummaryCatalog,
  openSummaryEditor,
  closeSummaryEditor,
  addQuerySummary,
  updateQuerySummary,
  removeQuerySummary,
  moveQuerySummary,
  loadReferenceChildren,
  reloadComposition,
  retryPreviewDescriptor,
  initializeComposition,
  saveAndApply,
  discardUnsavedChanges,
  selectMetadataNode,
  addMetadataNode,
  canDragMetadataNode,
  handleUiTreeDoubleClick,
  handlePreviewActionDrop,
  handlePreviewPlacement,
  metadataDragPayload,
  selectDescriptorPreviewField,
  configurePreviewRelationField,
  selectPreviewMode,
  handleNodeAction,
  undoRemoval,
  updateFieldProperty,
  updateReferencePickerPresentation,
  updateDictionaryPresentation,
  updateQuickSearch,
  updateGroup,
  fieldDisplayTitle,
  treeLayouts,
  mergeLayoutOpen,
  mergeKeep,
  confirmMergeLayout,
  layoutHandlers,
  candidateChanges,
} = props.session;
useWorkspaceViewUnsavedState(
  '页面配置',
  () => !props.retained && props.session.hasUnsavedChanges.value,
  () => props.session.isMutating.value,
);
onActivated(props.session.activate);
onDeactivated(props.session.deactivate);
</script>

<template>
  <section class="page-composition-workspace">
    <ManagementPanelHeader :title="composerTitle" :subtitle="compositionSubtitle">
      <template #title-suffix>
        <UiRadioGroup
          v-model:value="editorMode"
          :options="editorModeOptions"
          size="small"
          :disabled="isMutating"
        />
      </template>
      <template #actions>
        <div class="page-composition-actions">
          <UiButton v-if="removedDraft" :disabled="isMutating" @click="undoRemoval">撤销移除</UiButton>
          <UiButton
            v-if="!revision"
            :loading="saving"
            :disabled="isMutating || !relation"
            type="primary"
            @click="initializeComposition"
          >
            {{ publishedRevision ? '继续编辑' : '配置页面' }}
          </UiButton>
          <template v-else>
            <UiButton v-if="hasUnsavedChanges" :disabled="isMutating" @click="discardUnsavedChanges">
              放弃本次更改
            </UiButton>
            <UiButton
              type="primary"
              :loading="publishing"
              :disabled="
                isMutating ||
                catalogueRefreshPending ||
                metadataLoadFailed ||
                !hasPendingChanges ||
                draftConflict ||
                componentNameInvalid ||
                childInvalid ||
                propertyIssues.length > 0 ||
                dictionaryRadioIssues.length > 0 ||
                actionIssues.length > 0 ||
                unavailableSources.length > 0 ||
                hasSummaryIssues ||
                summaryCatalogBlocksMutation ||
                (summarySources.length > 0 && !supportsQuerySummaries) ||
                Boolean(draftParseError)
              "
              @click="saveAndApply"
            >
              保存并生效
            </UiButton>
          </template>
        </div>
      </template>
    </ManagementPanelHeader>
    <p v-if="metadataLoadProblem" role="alert">{{ metadataLoadProblem }}</p>
    <details
      v-if="hasUnsavedChanges"
      class="page-composition-candidate"
      data-testid="page-composition-candidate"
    >
      <summary>配置变更（尚未保存）</summary>
      <ul>
        <li v-for="(change, index) in candidateChanges" :key="index">{{ change }}</li>
      </ul>
      <p>预览使用当前草稿。点击“保存并生效”后才会发布；可继续手工修改或放弃本次更改。</p>
    </details>
    <div v-if="configuredMode !== compositionMode" class="page-composition-mode">
      <UiButton :disabled="isMutating" @click="applyConfiguredMode">采用概览中的呈现方式</UiButton>
    </div>
    <ManagementWorkspace class="page-composition-workspace__body" layout="composer" :explorer-count="2">
      <ManagementExplorerColumn collapsible :title="editorMode === 'fields' ? '字段来源' : paletteTitle">
        <div class="page-composition-palette">
          <RecordExplorerPanel
            v-if="editorMode === 'fields' && paletteMode === 'components'"
            title="组件库"
            :searchable="false"
            :refresh-disabled="isMutating || componentCatalogLoading"
            @refresh="loadComponentCatalog"
          >
            <template #title>
              <ManagementTabs
                v-model:active-key="paletteMode"
                :tabs="[
                  { key: 'fields', title: '元数据' },
                  { key: 'components', title: '组件库' },
                ]"
                :disabled="isMutating"
                label="字段来源"
                appearance="header"
              />
            </template>

            <div class="page-composition-component-list">
              <p class="page-composition-component-help">
                拖入组件新建数据项；同一数据在多处展示，请复用已有字段。
              </p>
              <UiTree
                v-if="componentCatalog"
                :nodes="componentNodes"
                :draggable="!isMutating"
                :drag-operations="['copy']"
                :drag-payload-type="PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE"
                :drag-payload-of="
                  (node: UiTreeNode) =>
                    node.key === 'child' ? { kind: 'child' } : { kind: 'component', component: node.key }
                "
                :allow-drop="() => false"
                data-testid="page-composer-component-library"
              />
              <UiEmpty v-else :description="componentCatalogLoading ? '加载组件库' : '当前无法新增数据项'" />
            </div>
          </RecordExplorerPanel>
          <MetadataSourceTree
            v-else
            v-model:search-keyword="fieldKeyword"
            v-model:show-system-fields="showSystemFields"
            v-model:expanded-keys="metadataExpandedKeys"
            :title="editorMode === 'fields' ? '已有字段' : paletteTitle"
            :utility-placement="editorMode === 'fields' ? 'toolbar' : 'header'"
            :searchable="editorMode === 'fields'"
            :refresh-disabled="isMutating"
            :loading="loading && !relation"
            :unavailable="!relation"
            unavailable-description="页面编排仅面向已发布主元数据；当前模块暂无可编排主实体"
            :nodes="metadataTreeNodes"
            :load-children="loadReferenceChildren"
            :reload-key="metadataTreeReloadKey"
            :selected-key="selectedMetadataTreeKey"
            :draggable="!isMutating"
            :drag-payload-type="PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE"
            :drag-payload-of="metadataDragPayload"
            :can-drag="canDragMetadataNode"
            data-testid="page-composer-metadata-tree"
            @refresh="loadMetadataTree"
            @select="selectMetadataNode"
            @action="addMetadataNode"
          >
            <template v-if="editorMode === 'fields'" #title>
              <ManagementTabs
                v-model:active-key="paletteMode"
                :tabs="[
                  { key: 'fields', title: '元数据' },
                  { key: 'components', title: '组件库' },
                ]"
                :disabled="isMutating"
                label="字段来源"
                appearance="header"
              />
            </template>
            <UiEmpty v-if="editorMode === 'actions' && !moduleActions.length" description="暂无模块动作" />
            <UiEmpty v-if="editorMode === 'fields' && !visibleFields.length" description="暂无可编排字段" />
          </MetadataSourceTree>
        </div>
      </ManagementExplorerColumn>

      <ManagementExplorerColumn collapsible :title="structureTitle">
        <RecordExplorerPanel
          :title="structureTitle"
          :searchable="false"
          :refresh-disabled="isMutating"
          @refresh="reloadComposition"
        >
          <div class="page-composition-structure" data-testid="page-composer-ui-tree">
            <PageCompositionTree
              v-for="layout in treeLayouts"
              :key="layout"
              :layout-title="state.separateDetail.value ? (layout === 'detail' ? '详情' : '表单') : undefined"
              :hide-list="state.separateDetail.value && layout === 'form'"
              :separate-detail="state.separateDetail.value"
              :skeleton="skeleton"
              :searchable-field-ids="
                allMetadataFields
                  .filter((field) => searchableFields.includes(field.fieldName))
                  .map((field) => field.id)
              "
              :quick-search-fields="
                quickSearchFields.map((fieldName) => ({
                  fieldName,
                  title: allMetadataFields.find((field) => field.fieldName === fieldName)?.title ?? fieldName,
                }))
              "
              :query-summaries="summarySources"
              :summaries-supported="supportsQuerySummaries"
              :summary-descriptions="summaryDescriptions"
              :summary-issues="summaryTreeIssues"
              :explorer-title="
                allMetadataFields.find((field) => field.fieldName === explorerTitleField)?.title ??
                explorerTitleField
              "
              :explorer-secondary="
                allMetadataFields.find((field) => field.fieldName === explorerSecondaryField)?.title ??
                explorerSecondaryField
              "
              :list-fields="state.listFields.value"
              :form-fields="state.layouts.value[layout].fields"
              :form-groups="state.layouts.value[layout].groups"
              :form-order="
                orderedFormItems(
                  state.layouts.value[layout].fields,
                  state.layouts.value[layout].groups,
                  state.layouts.value[layout].order,
                )
              "
              :form-relations="layout === 'form' ? state.formRelations.value : []"
              :selected-key="state.activeLayout.value === layout ? selectedUiTreeKey : undefined"
              :disabled="isMutating"
              :action-placements="actionPlacements"
              :module-actions="moduleActions"
              :editor-mode="editorMode"
              v-on="layoutHandlers(layout)"
            />
          </div>
        </RecordExplorerPanel>
      </ManagementExplorerColumn>

      <RecordDetailPanel title="预览">
        <template #actions>
          <UiRadioGroup
            :value="effectivePreviewMode"
            :options="previewModes"
            :disabled="!previewDescriptor || isMutating"
            @update:value="selectPreviewMode"
          />
        </template>
        <div v-if="actionIssues.length" role="alert" class="page-composition-source-error">
          {{ actionIssues.join('；') }}。请修正入口后发布。
        </div>
        <div v-if="editorMode === 'actions'" class="page-composition-action-help">
          标准按钮可隐藏或排序，自定义动作可拖入对应区域。
        </div>
        <UiRadioGroup
          v-if="editorMode === 'actions' && state.previewMode.value === 'edit'"
          v-model:value="actionFormMode"
          :options="[
            { value: 'create', label: '新建状态' },
            { value: 'edit', label: '编辑状态' },
          ]"
        />
        <div v-if="componentNameInvalid || childInvalid" role="alert" class="page-composition-source-error">
          请填写数据项和明细表名称（最多 128 字），明细表至少添加一个字段。
        </div>
        <div v-if="propertyIssues.length" role="alert" class="page-composition-source-error">
          列宽格式有误，请修正后保存：
          <UiButton
            v-for="field in propertyIssues"
            :key="field.id"
            size="small"
            @click="handleNodeAction('configure', `ui:field:list:${field.id}`)"
          >
            {{ fieldDisplayTitle(field) }}
          </UiButton>
        </div>
        <div v-if="draftConflict" class="page-composition-conflict" role="alert">
          <span>页面配置已被其他会话更新，本地修改已保留。请加载最新配置后继续编辑。</span>
          <UiButton :disabled="isMutating" @click="reloadComposition">加载最新配置</UiButton>
        </div>
        <div
          v-if="summarySources.length && !supportsQuerySummaries"
          class="page-composition-source-error"
          role="alert"
        >
          汇总统计仅支持列表卡片布局；当前配置已保留，切回列表卡片后可继续编辑。
          <UiButton size="small" :disabled="isMutating" @click="compositionMode = 'LIST_CARD'">
            切回列表卡片
          </UiButton>
          <UiButton size="small" :disabled="isMutating" @click="state.replaceQuerySummaries([])">
            清空汇总
          </UiButton>
        </div>
        <div v-if="hasSummaryIssues" class="page-composition-source-error" role="alert">
          <span>汇总配置需修正：</span>
          <UiButton
            v-for="entry in summaryIssueEntries"
            :key="entry.key"
            type="link"
            :disabled="isMutating"
            @click="openSummaryEditor(entry.key)"
          >
            {{ entry.title }}：{{ entry.messages.join('、') }}
          </UiButton>
        </div>
        <p
          v-if="
            unavailableSources.length ||
            draftParseError ||
            dictionaryRadioIssues.length ||
            (hasCatalogDependentSummary && summaryCatalogError)
          "
          class="page-composition-source-error"
          role="alert"
        >
          {{
            draftParseError ??
            (dictionaryRadioIssues.length
              ? `字典 radio 配置需修正：${dictionaryRadioIssues.join('；')}。`
              : undefined) ??
            (hasCatalogDependentSummary ? summaryCatalogError : undefined) ??
            `来源失效：${[...new Set(unavailableSources)].join('、')}。配置已保留，请在编排树中移除标记节点并重新选择；修正后才能发布。`
          }}
        </p>
        <div
          v-if="previewError"
          class="page-composition-preview-status page-composition-preview-status--error"
          aria-live="polite"
        >
          <span>草稿解析失败：{{ previewError }}</span>
          <span v-if="previewDescriptor">当前展示的是上一次成功解析结果，不代表当前草稿。</span>
          <UiButton size="small" :disabled="previewLoading" @click="retryPreviewDescriptor">
            重新解析
          </UiButton>
        </div>
        <PageCompositionDescriptorPreview
          v-if="previewDescriptor"
          :descriptor="previewDescriptor"
          :module-alias="props.session.moduleAlias"
          :mode="effectivePreviewMode"
          :selected-field-name="selectedPreviewFieldName"
          :accept-external-drop="true"
          :placement-disabled="isMutating || previewLoading || Boolean(previewError)"
          :placement-commit-failed="Boolean(previewError)"
          :structure="previewStructure"
          :action-form-mode="actionFormMode"
          :action-placements="actionPlacements"
          :module-actions="moduleActions"
          :query-summaries="supportsQuerySummaries ? summarySources : []"
          @select-field="(slot, fieldName) => selectDescriptorPreviewField(slot, fieldName)"
          @configure-field="(slot, fieldName) => selectDescriptorPreviewField(slot, fieldName, true)"
          @configure-relation-field="configurePreviewRelationField"
          @configure-action="(anchor, code) => handleUiTreeDoubleClick(`ui:action:${anchor}:${code}`)"
          @configure-summaries="openSummaryEditor"
          @placement-drop="handlePreviewPlacement"
          @action-drop="(source, target) => handlePreviewActionDrop(source, target, true)"
        />
        <UiEmpty
          v-else-if="revision && !previewLoading && !previewError"
          class="page-composition-preview-empty"
          :description="
            previewError
              ? '当前草稿未能解析；可重新解析，或修正页面结构后自动重试。'
              : '正在等待草稿解析结果。'
          "
        />
        <UiEmpty
          v-else-if="!revision"
          class="page-composition-preview-empty"
          description="初始化页面草稿后，即可查看页面预览。"
        />
      </RecordDetailPanel>

      <RecordDetailDrawer
        :open="mergeLayoutOpen"
        title="恢复共用布局"
        render-mode="inline"
        width="compact"
        @close="mergeLayoutOpen = false"
      >
        <div class="component-property-drawer">
          <fieldset class="page-composition-layout-choice">
            <legend>共用哪套布局</legend>
            <UiRadioGroup
              v-model:value="mergeKeep"
              :disabled="isMutating"
              :options="[
                { value: 'form', label: '表单布局' },
                { value: 'detail', label: '详情布局' },
              ]"
            />
          </fieldset>
          <p>
            {{
              mergeKeep === 'form'
                ? '详情将采用当前表单的字段、分组和顺序，原详情编排将被覆盖。'
                : '表单将采用当前详情的字段、分组和顺序，原表单编排将被覆盖。'
            }}
          </p>
        </div>
        <template #operation>
          <UiButton :disabled="isMutating" @click="mergeLayoutOpen = false">取消</UiButton>
          <UiButton type="primary" :disabled="isMutating" @click="confirmMergeLayout">恢复共用</UiButton>
        </template>
      </RecordDetailDrawer>
      <RecordDetailDrawer
        :open="propertyDrawerOpen"
        render-mode="inline"
        :title="propertyDrawerTitle"
        width="compact"
        @close="propertyDrawerOpen = false"
      >
        <div v-if="selectedQuickSearch" class="component-property-drawer">
          <label>
            <span>搜索占位提示</span>
            <UiInput
              :value="quickSearchPlaceholderDraft"
              :disabled="isMutating"
              placeholder="例如：搜索名称、编码或 ID"
              @update:value="updateQuickSearch"
            />
          </label>
        </div>
        <div v-if="selectedActionEntry" class="component-property-drawer">
          <label
            ><span>按钮标题</span
            ><UiInput
              :value="selectedActionEntry.title ?? ''"
              :placeholder="pageActionEntryTitle({ ...selectedActionEntry, title: undefined })"
              @update:value="
                (value) => {
                  if (selectedActionEntry)
                    actionButtonMembers(actionPlacements, selectedActionEntry).forEach((entry) => {
                      entry.title = value.trim() || undefined;
                    });
                }
              "
          /></label>
          <p>业务动作：{{ selectedActionEntry.actionCode }}</p>
          <p>交互：{{ pageActionEntryDescription(selectedActionEntry) }}</p>
        </div>
        <div v-else-if="selectedField" class="component-property-drawer">
          <h3>数据项</h3>
          <p v-if="!selectedPendingComponent">
            {{ selectedField.title }} · {{ selectedField.required ? '必填' : '选填' }}
          </p>
          <p v-if="selectedFieldUsage.length">已用于：{{ selectedFieldUsage.join('、') }}</p>
          <label v-if="selectedPendingComponent">
            <span>数据项名称</span>
            <UiInput
              :value="selectedPendingComponent.title"
              :disabled="isMutating"
              :maxlength="128"
              placeholder="例如：供应商名称"
              @update:value="updateComponentTitle"
            />
          </label>
          <label v-if="selectedPendingComponent" class="component-property-drawer__switch">
            <span>必填</span>
            <UiSwitch
              :checked="selectedPendingComponent.required ?? false"
              :disabled="isMutating"
              @update:checked="updateComponentRequired"
            />
          </label>
          <small v-if="selectedPendingComponent">名称和必填规则适用于所有使用位置。</small>
          <div v-if="duplicateComponentFields.length" role="status">
            <p>
              已有同名数据项“{{
                selectedPendingComponent?.title.trim()
              }}”。若要展示同一份数据，请移除此新增组件并复用已有字段。
            </p>
            <UiButton
              :disabled="isMutating"
              @click="
                paletteMode = 'fields';
                fieldKeyword = selectedPendingComponent?.title.trim() ?? '';
                propertyDrawerOpen = false;
              "
            >
              查找同名字段
            </UiButton>
          </div>
          <h3>当前展示位置</h3>
          <small>以下设置仅影响当前区域，不修改数据项。</small>
          <label>
            <span>展示标题</span>
            <UiInput
              :value="propertyDraft.label"
              :disabled="isMutating"
              :placeholder="selectedField.title"
              @update:value="updateFieldProperty('label', $event)"
            />
          </label>
          <template v-if="state.selectedNode.value?.slot === 'list' || selectedRelationField">
            <label>
              <span>列宽</span>
              <span v-if="selectedRelationField" class="component-property-drawer__width-input">
                <UiInput
                  :value="propertyDraft.width?.replace(/px$/, '')"
                  type="number"
                  :step="1"
                  :disabled="isMutating"
                  placeholder="默认 160"
                  @update:value="
                    updateFieldProperty('width', $event.trim() ? `${$event.trim()}px` : undefined)
                  "
                />
                <span>px</span>
              </span>
              <UiInput
                v-else
                :value="propertyDraft.width"
                :disabled="isMutating"
                placeholder="例如 160px 或 25%"
                @update:value="updateFieldProperty('width', $event)"
              />
              <small v-if="propertyValidationMessage" class="component-property-drawer__error">
                {{ propertyValidationMessage }}
              </small>
            </label>
            <label>
              <span>对齐</span>
              <UiSelect
                :value="propertyDraft.align"
                :disabled="isMutating"
                :options="[
                  { label: '左对齐', value: 'left' },
                  { label: '居中', value: 'center' },
                  { label: '右对齐', value: 'right' },
                ]"
                placeholder="遵循平台默认"
                @update:value="
                  updateFieldProperty(
                    'align',
                    $event === 'left' || $event === 'center' || $event === 'right' ? $event : undefined,
                  )
                "
              />
            </label>
          </template>
          <template v-else>
            <label v-if="selectedDirectReferenceFormField">
              <span>引用选择形式</span>
              <UiSelect
                :value="referencePickerPresentationValue"
                :options="referencePickerPresentationOptions"
                :disabled="isMutating"
                :allow-clear="false"
                @update:value="updateReferencePickerPresentation"
              />
            </label>
            <label v-if="selectedDirectDictionaryFormField">
              <span>字典展示形式</span>
              <UiSelect
                :value="dictionaryPresentationValue"
                :options="dictionaryPresentationOptions"
                :disabled="isMutating"
                :allow-clear="false"
                @update:value="updateDictionaryPresentation"
              />
              <small v-if="selectedDirectDictionaryFormField.optionSelectionMode === 'SINGLE'">
                {{
                  selectedDictionaryRadioIssue ??
                  `radio 可用：不超过 ${dictionaryRadioMaxOptions} 个启用候选且没有层级。`
                }}
              </small>
            </label>
            <label>
              <span>表单列宽度</span>
              <UiSelect
                :value="propertyDraft.columnSpan"
                :disabled="isMutating"
                :options="[
                  { label: '半行（1 列）', value: 1 },
                  { label: '整行（2 列）', value: 2 },
                ]"
                placeholder="遵循平台默认"
                @update:value="
                  updateFieldProperty('columnSpan', $event === 1 || $event === 2 ? $event : undefined)
                "
              />
            </label>
            <label class="component-property-drawer__switch">
              <span>只读展示</span>
              <UiSwitch
                :checked="selectedField.platformReadOnly || propertyDraft.readOnly"
                :disabled="isMutating || selectedField.platformReadOnly"
                @update:checked="updateFieldProperty('readOnly', $event)"
              />
            </label>
          </template>
        </div>
        <div v-else-if="selectedRelation" class="component-property-drawer">
          <label
            ><span>明细表名称</span
            ><UiInput
              :value="selectedRelation.title"
              :maxlength="128"
              :disabled="isMutating"
              @update:value="selectedRelation.title = $event"
          /></label>
          <p v-if="pendingChildren.some((child) => child.key === selectedRelation?.id)">
            将基础组件拖入明细表，添加明细字段。
          </p>
          <p>明细随主表一起编辑和保存。</p>
        </div>
        <div v-else-if="selectedGroup" class="component-property-drawer">
          <label>
            <span>分组标题</span>
            <UiInput
              :value="groupTitleDraft"
              :disabled="isMutating"
              placeholder="例如：基本信息"
              @update:value="updateGroup($event, groupSubtitleDraft)"
            />
            <small v-if="!groupTitleDraft.trim()" class="component-property-drawer__error"
              >标题不能为空，当前保留原名称。</small
            >
          </label>
          <label>
            <span>辅助说明</span>
            <UiInput
              :value="groupSubtitleDraft"
              :disabled="isMutating"
              placeholder="可选，例如：填写考试基础资料"
              @update:value="updateGroup(groupTitleDraft, $event)"
            />
          </label>
        </div>
      </RecordDetailDrawer>
      <RecordDetailDrawer
        :open="summaryDrawerOpen"
        render-mode="inline"
        title="汇总统计"
        width="narrow"
        @close="closeSummaryEditor"
      >
        <PageQuerySummaryEditor
          :summaries="summarySources"
          :catalog="summaryCatalog"
          :loading="summaryCatalogLoading"
          :error="summaryCatalogError"
          :disabled="isMutating"
          :selected-key="selectedSummaryKey"
          :focus-request="summaryFocusRequest"
          :issues="summaryEditorIssues"
          :descriptions="summaryDescriptions"
          @add="addQuerySummary"
          @update="updateQuerySummary"
          @remove="removeQuerySummary"
          @move="moveQuerySummary"
          @retry="loadSummaryCatalog(true)"
        />
      </RecordDetailDrawer>
    </ManagementWorkspace>
  </section>
</template>

<style scoped>
.page-composition-palette,
.page-composition-component-list {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
}
.page-composition-component-list {
  gap: 8px;
}
.page-composition-palette > :deep(.record-explorer-panel) {
  flex: 1 1 auto;
}
.page-composition-component-list > .page-composition-component-help {
  flex: 0 0 auto;
  color: var(--muyun-text-muted);
  margin: 0 0 12px;
  font-size: 13px;
}
.page-composition-mode {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 12px;
  color: var(--muyun-text-muted);
}
.page-composition-mode :deep(.ant-select) {
  min-width: 140px;
}

.page-composition-conflict {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  color: var(--muyun-danger-base);
}
.page-composition-source-error {
  color: var(--muyun-danger-base);
}
.page-composition-candidate {
  padding: 8px 16px;
  max-height: 180px;
  overflow: auto;
  overflow-wrap: anywhere;
  flex-shrink: 0;
}
.page-composition-candidate summary {
  cursor: pointer;
}

.page-composition-workspace {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  gap: 10px;
}
.page-composition-workspace__body {
  flex: 1 1 auto;
  min-height: 0;
}
.page-composition-workspace > :deep(.management-panel-header) {
  flex: 0 0 auto;
}

.page-composition-structure {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-height: 0;
  overflow: hidden;
}
.page-composition-structure :deep(.ant-tree) {
  flex: 0 0 auto;
  min-height: 0;
  overflow: visible;
}
/* Keep node names legible beside stable inline action slots in the compact composer. */
.page-composition-structure :deep(.ant-tree-indent-unit) {
  width: 16px;
}
.page-composition-structure :deep(.ui-record-explorer-item-title) {
  flex-shrink: 0;
}
.page-composition-structure :deep(.ui-record-explorer-item-secondary) {
  flex-shrink: 4;
}
.page-composition-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}
.page-composition-preview-status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin: 10px 0 0;
  color: var(--muyun-text-muted);
  font-size: 12px;
  line-height: 1.5;
}
.page-composition-preview-status--error {
  color: var(--muyun-danger-base);
}
.page-composition-preview-empty {
  min-height: 280px;
  margin-top: 12px;
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
}
.page-composition-layout-choice {
  margin: 0;
  padding: 0;
  border: 0;
}
.page-composition-layout-choice legend {
  margin-bottom: 8px;
  color: var(--muyun-text-muted);
  font-size: 13px;
}
.component-property-drawer {
  display: grid;
  gap: 16px;
}
.component-property-drawer h3 {
  margin: 0;
  font-size: 14px;
}
.component-property-drawer small {
  color: var(--muyun-text-muted);
}
.component-property-drawer label {
  display: grid;
  gap: 6px;
  color: var(--muyun-text-muted);
  font-size: 13px;
}
.component-property-drawer__switch {
  grid-template-columns: 1fr auto;
  align-items: center;
}
.component-property-drawer p {
  margin: 0;
  color: var(--muyun-text-muted);
  font-size: 13px;
  line-height: 1.55;
}
.component-property-drawer__error {
  color: var(--muyun-danger-base);
  font-size: 12px;
  line-height: 1.4;
}
.component-property-drawer__width-input {
  display: flex;
  align-items: center;
  gap: 8px;
}
.component-property-drawer__width-input > :first-child {
  flex: 1;
  min-width: 0;
}
</style>
