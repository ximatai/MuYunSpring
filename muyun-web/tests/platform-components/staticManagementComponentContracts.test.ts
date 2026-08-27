import { assert, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

const root = resolve(import.meta.dirname, '../..');

it('record list explorer exposes visible secondary identity text', () => {
  const itemSource = readSource('src/vue-ui-antdv/components/UiRecordExplorerItem.vue');
  const listSource = readSource('src/platform-components/RecordListExplorer.vue');
  const crudListSource = readSource('src/platform-components/CrudRecordListExplorer.vue');
  const treeSource = readSource('src/platform-components/TreeRecordExplorer.vue');
  const itemModelSource = readSource('src/platform-components/recordExplorerItemModel.ts');
  const treeTypesSource = readSource('src/vue-ui-antdv/types.ts');
  const uiTreeSource = readSource('src/vue-ui-antdv/components/UiTree.vue');

  assert.match(itemModelSource, /interface RecordExplorerItemDescriptor/);
  assert.match(itemModelSource, /title: string/);
  assert.match(itemModelSource, /secondary\?: string/);
  assert.match(itemModelSource, /tag\?: string/);
  assert.match(itemModelSource, /actions\?: UiRecordInlineAction\[\]/);
  assert.match(itemSource, /secondary\?: string/);
  assert.match(itemSource, /class="ui-record-explorer-item-secondary"/);
  assert.match(itemSource, /\.ui-record-explorer-item:focus-within \.ui-record-explorer-item-actions/);
  assert.match(
    listSource,
    /itemOf\?: \(record: RecordListExplorerRecord\) => RecordExplorerItemDescriptor \| undefined/,
  );
  assert.match(listSource, /function recordSecondary/);
  assert.match(listSource, /props\.codeOf \? props\.codeOf\(record\)/);
  assert.match(listSource, /:secondary="recordSecondary\(record\)"/);
  assert.match(
    crudListSource,
    /itemOf\?: \(record: CrudRecordListBase\) => RecordExplorerItemDescriptor \| undefined/,
  );
  assert.match(crudListSource, /actionsOf\?: \(record: CrudRecordListBase\) => UiRecordInlineAction\[\]/);
  assert.match(crudListSource, /action: \[action: UiRecordInlineAction, record: CrudRecordListBase\]/);
  assert.match(crudListSource, /props\.subtitleOf[\s\S]*\? props\.subtitleOf\(record\)/);
  assert.match(crudListSource, /:item-of="\(record\) => itemOf\?\.\(record as CrudRecordListBase\)"/);
  assert.match(crudListSource, /:actions-of="\(record\) => recordActions\(record as CrudRecordListBase\)"/);
  assert.match(
    crudListSource,
    /@action="\(action, record\) => handleAction\(action, record as CrudRecordListBase\)"/,
  );
  assert.match(treeTypesSource, /secondary\?: string/);
  assert.match(treeSource, /secondaryOf\?: \(record: TreeRecordBase\) => string \| undefined/);
  assert.match(
    treeSource,
    /itemOf\?: \(record: TreeRecordBase\) => RecordExplorerItemDescriptor \| undefined/,
  );
  assert.match(treeSource, /const item = props\.itemOf\?\.\(record\)/);
  assert.match(treeSource, /secondary: item\?\.secondary \?\? props\.secondaryOf\?\.\(record\)/);
  assert.match(uiTreeSource, /#title="\{ key, title, secondary, tag, muted, actions \}"/);
  assert.match(uiTreeSource, /:secondary="secondary"/);
});

it('record explorer panel uses a single title contract', () => {
  const panelSource = readSource('src/platform-components/RecordExplorerPanel.vue');
  const detailPanelSource = readSource('src/platform-components/RecordDetailPanel.vue');
  const headerSource = readSource('src/platform-components/ManagementPanelHeader.vue');
  const statusSwitchSource = readSource('src/platform-components/RecordStatusSwitch.vue');
  const layoutSource = readSource('src/platform-components/StaticManagementLayout.vue');
  const workspaceSource = readSource('src/platform-components/ManagementWorkspace.vue');

  assert.notMatch(panelSource, /eyebrow/);
  assert.notMatch(layoutSource, /groupTitle/);
  assert.match(panelSource, /<ManagementPanelHeader/);
  assert.match(detailPanelSource, /<ManagementPanelHeader/);
  assert.match(headerSource, /--muyun-management-panel-header-height/);
  assert.match(
    headerSource,
    /<h2[\s\S]*?class="management-panel-header-title"[\s\S]*?<UiButton[\s\S]*?class="management-panel-header-title-action"/,
  );
  assert.match(headerSource, /management-panel-header-title-action-label/);
  assert.match(headerSource, /management-panel-header-title-label/);
  assert.match(headerSource, /\.management-panel-header-title-group \{[\s\S]*?gap: 4px/);
  assert.match(headerSource, /management-panel-header-title-copy--with-status/);
  assert.match(headerSource, /\.management-panel-header-title-copy--with-status \{\s*flex: 0 1 auto/);
  assert.match(headerSource, /text-overflow: ellipsis/);
  assert.match(headerSource, /\.management-panel-header-subtitle \{\s*margin: 1px 0 0;/);
  assert.match(headerSource, /management-panel-header-title--with-subtitle/);
  assert.match(headerSource, /max-width: 100%/);
  assert.match(
    headerSource,
    /\.management-panel-header-title \{\s*display: flex;[\s\S]*?align-items: center;/,
  );
  assert.notMatch(headerSource, /<h2 v-else/);
  assert.notMatch(headerSource, /record-status-switch-offset-y/);
  assert.notMatch(statusSwitchSource, /translateY\(/);
  assert.match(headerSource, /padding: 0 4px/);
  assert.match(headerSource, /margin-inline-start: -4px/);
  assert.match(headerSource, /background: var\(--muyun-hover\)/);
  assert.match(headerSource, /ant-btn-text:not\(:disabled\):focus-visible/);
  assert.match(headerSource, /width: 0/);
  assert.match(headerSource, /width: 14px/);
  assert.match(headerSource, /margin-inline-start: 6px/);
  assert.notMatch(headerSource, /management-panel-header-title-action::before/);
  assert.notMatch(headerSource, /position: absolute/);
  assert.match(layoutSource, /<RecordDetailPanel[\s\S]*<slot name="detail-status"/);
  assert.match(workspaceSource, /--muyun-management-panel-padding-block/);
});

it('content sections share one semantic heading language so dark skins preserve hierarchy', () => {
  const metaSource = readSource('src/platform-components/RecordMetaSection.vue');
  const extensionSource = readSource('src/platform-components/RecordDetailExtensionSection.vue');
  const formSource = readSource('src/platform-components/RecordFormFields.vue');
  const headingSource = readSource('src/platform-components/RecordContentSectionHeading.vue');
  const layoutSource = readSource('src/platform-components/RecordDetailLayout.vue');

  assert.match(metaSource, /<RecordContentSectionHeading title="系统信息"/);
  assert.match(extensionSource, /<RecordContentSectionHeading :title="title"/);
  assert.match(formSource, /<RecordContentSectionHeading[\s\S]*class="record-form-group-heading"/);
  assert.match(headingSource, /color: var\(--muyun-content-section-heading-color, var\(--muyun-text\)\)/);
  assert.match(layoutSource, /--muyun-content-section-heading-font-size: 14px/);
  assert.match(layoutSource, /--muyun-content-section-heading-font-weight: 600/);
  assert.match(layoutSource, /--muyun-content-section-heading-line-height: 20px/);
  assert.match(metaSource, /dt \{[\s\S]*color: var\(--muyun-support-text-muted\)/);
  assert.match(metaSource, /dd \{[\s\S]*color: var\(--muyun-support-text-body\)/);
  assert.notMatch(`${headingSource}\n${metaSource}`, /#334155|#64748b|#243447/);
});

it('detail sections share one internal and inter-section spacing rhythm', () => {
  const layoutSource = readSource('src/platform-components/RecordDetailLayout.vue');
  const extensionSource = readSource('src/platform-components/RecordDetailExtensionSection.vue');
  const metaSource = readSource('src/platform-components/RecordMetaSection.vue');

  assert.match(layoutSource, /--muyun-detail-section-inner-gap: 8px/);
  assert.match(layoutSource, /--muyun-detail-section-block-gap: 16px/);
  assert.match(extensionSource, /gap: var\(--muyun-detail-section-inner-gap, 8px\)/);
  assert.match(extensionSource, /margin-top: var\(--muyun-detail-section-block-gap, 16px\)/);
  assert.match(extensionSource, /padding-top: var\(--muyun-detail-section-inner-gap, 8px\)/);
  assert.match(metaSource, /gap: var\(--muyun-detail-section-inner-gap, 8px\)/);
  assert.match(metaSource, /margin-top: var\(--muyun-detail-section-block-gap, 16px\)/);
  assert.match(metaSource, /padding-top: var\(--muyun-detail-section-inner-gap, 8px\)/);
  assert.match(metaSource, /border-top: 1px solid var\(--muyun-border-subtle\)/);
});

it('reference summary tags keep visual rendering inside the UI adapter', () => {
  const tagListSource = readSource('src/platform-components/RecordTagList.vue');
  const adapterSource = readSource('src/vue-ui-antdv/components/UiTagList.vue');
  const adapterIndexSource = readSource('src/vue-ui-antdv/index.ts');

  assert.match(tagListSource, /import \{ UiTagList, type UiTagListItem \} from '@muyun\/vue-ui-antdv'/);
  assert.match(tagListSource, /<UiTagList :items="tags" :max-visible="maxVisible" \/>/);
  assert.notMatch(tagListSource, /<style/);
  assert.notMatch(tagListSource, /backgroundColor/);
  assert.match(adapterSource, /defineOptions\(\{ name: 'UiTagList', inheritAttrs: false \}\)/);
  assert.match(adapterSource, /import \{ Tag as ATag, Tooltip as ATooltip \} from 'ant-design-vue'/);
  assert.match(adapterSource, /<ATag v-for="item in visibleItems"/);
  assert.match(adapterSource, /<ATooltip v-if="overflowItems.length"/);
  assert.match(adapterSource, /Math\.max\(0, Math\.floor\(props\.maxVisible\)\)/);
  assert.match(adapterIndexSource, /export \{ default as UiTagList \}/);
});

it('workbench keeps the sidebar separator when the mega menu opens', () => {
  const workbenchMenuSource = readSource('src/platform-workbench/WorkbenchMenu.vue');

  assert.match(
    workbenchMenuSource,
    /border-right: var\(--workbench-menu-border-width\) solid var\(--workbench-menu-border\)/,
  );
  assert.notMatch(workbenchMenuSource, /border-right-color: transparent/);
});

it('workbench routes the skin toolbar entry to the shared preference dialog', () => {
  const appSource = readSource('src/App.vue');
  const workbenchSource = readSource('src/platform-workbench/Workbench.vue');

  assert.notMatch(workbenchSource, /aria-label="搜索"/);
  assert.match(workbenchSource, /aria-label="皮肤切换"[\s\S]*emit\('userCommand', 'themeSkin'\)/);
  assert.match(appSource, /command === 'themeSkin'/);
  assert.notMatch(workbenchSource, /key: 'settings'/);
  assert.match(appSource, /function openThemeSkinPreferences\(\)/);
  assert.match(appSource, /themeSkinPreferencesOpen\.value = true/);
  assert.match(appSource, /<ThemeSkinPreferencesDialog/);
});

it('workbench opens the signed-in user profile through the dedicated self-service contract', () => {
  const appSource = readSource('src/App.vue');
  const profileDialogSource = readSource('src/app/CurrentUserProfileDialog.vue');
  const clientSource = readSource('src/web-core/clients.ts');

  assert.match(appSource, /command === 'profile'/);
  assert.match(appSource, /function openCurrentUserProfile\(\)/);
  assert.match(appSource, /authClient\.currentProfile\(token\)/);
  assert.match(appSource, /authClient\.updateCurrentProfile\(value, token\)/);
  assert.match(appSource, /<CurrentUserProfileDialog/);
  assert.ok(
    appSource.indexOf('configureModuleContext({ httpFactory: createBackendHttpClient })') <
      appSource.indexOf("createModuleContext({ moduleAlias: 'iam.employee' })"),
  );
  assert.match(clientSource, /path: '\/iam\.auth\/profile'/);
  assert.match(profileDialogSource, /title="个人信息"/);
  assert.match(profileDialogSource, /保存联系方式/);
  assert.match(profileDialogSource, /机构 \/ 部门/);
  assert.match(profileDialogSource, /SingleImageFileReferenceField/);
  assert.match(profileDialogSource, /avatarAssetId/);
});

it('workbench presents realtime transport state through an application facade', () => {
  const appSource = readSource('src/App.vue');
  const appRealtimeSource = readSource('src/platform-admin-runtime/realtime.ts');
  const workbenchSource = readSource('src/platform-workbench/Workbench.vue');
  const menuSource = readSource('src/platform-workbench/WorkbenchMenu.vue');
  const workbenchStart = appSource.indexOf('<Workbench');
  const workbenchSourceInApp = appSource.slice(workbenchStart);

  assert.match(appSource, /function workbenchRealtimeStatusOf\(state: RealtimeConnectionState\)/);
  assert.match(workbenchSourceInApp, /:realtime-status="realtimeStatus"/);
  assert.match(appRealtimeSource, /options\.onStateChange\?\.\(state\)/);
  assert.match(workbenchSource, /:realtime-status="realtimeStatus"/);
  assert.match(menuSource, /presentWorkbenchRealtimeStatus\(props\.realtimeStatus\)/);
  assert.match(menuSource, /role="status"/);
  assert.notMatch(menuSource, /平台在线/);
});

it('record containers delegate chain errors to page feedback', () => {
  const treeSource = readSource('src/platform-components/TreeRecordExplorer.vue');
  const crudListSource = readSource('src/platform-components/CrudRecordListExplorer.vue');
  const layoutSource = readSource('src/platform-components/StaticManagementLayout.vue');

  assert.match(treeSource, /presentPlatformError/);
  assert.match(crudListSource, /presentPlatformError/);
  assert.match(treeSource, /tree\.value = \[\]/);
  assert.match(treeSource, /expandedKeys\.value = \[\]/);
  assert.match(treeSource, /emit\('loaded', \[\]\)/);
  assert.match(crudListSource, /records\.value = \[\]/);
  assert.match(crudListSource, /emit\('loaded', \[\]\)/);
  assert.notMatch(treeSource, /loadError/);
  assert.notMatch(crudListSource, /loadError/);
  assert.notMatch(treeSource, /UiError/);
  assert.notMatch(crudListSource, /UiError/);
  assert.notMatch(layoutSource, /actionError/);
  assert.notMatch(layoutSource, /message error/);
});

it('record mode drawer owns detail mode branch switching', () => {
  const drawerSource = readSource('src/platform-components/RecordModeDrawer.vue');
  const detailDrawerSource = readSource('src/platform-components/RecordDetailDrawer.vue');
  const detailPanelSource = readSource('src/platform-components/RecordDetailPanel.vue');
  const detailLayoutSource = readSource('src/platform-components/RecordDetailLayout.vue');
  const operationBarSource = readSource('src/platform-components/DrawerOperationBar.vue');
  const indexSource = readSource('src/platform-components/index.ts');
  const pageRealtimeSource = readSource('src/platform-admin-runtime/pageRealtime.ts');

  assert.match(indexSource, /export \{ default as RecordModeDrawer \}/);
  assert.match(indexSource, /export \{ default as RecordExternalChangeNotice \}/);
  assert.match(indexSource, /normalizeRecordDraft/);
  assert.match(drawerSource, /defineOptions\(\{ name: 'RecordModeDrawer' \}\)/);
  assert.match(drawerSource, /RecordExternalChangeNotice/);
  assert.match(drawerSource, /externallyChanged/);
  assert.match(drawerSource, /reloadExternalChange/);
  assert.match(drawerSource, /dismissExternalChange/);
  assert.match(drawerSource, /viewMode: 'view'/);
  assert.match(drawerSource, /formModes: \(\) => \['edit', 'create'\]/);
  assert.match(drawerSource, /viewOperation\(\): unknown/);
  assert.match(drawerSource, /<slot v-if="viewModeActive" name="viewOperation" \/>/);
  assert.match(
    detailLayoutSource,
    /\.record-detail-layout-header \{[\s\S]*?align-items: center;[\s\S]*?min-height: 32px;/,
  );
  assert.match(
    detailLayoutSource,
    /\.record-detail-layout-actions \{[\s\S]*?align-items: center;[\s\S]*?min-height: 32px;/,
  );
  assert.match(drawerSource, /editAvailable\?: boolean/);
  assert.match(drawerSource, /saveAvailable\?: boolean/);
  assert.match(drawerSource, /edit: \[\]/);
  assert.match(drawerSource, /save: \[\]/);
  assert.match(drawerSource, /<UiActionButton v-if="editAvailable"/);
  assert.match(drawerSource, /<UiActionButton[\s\S]*v-if="saveAvailable"/);
  assert.match(drawerSource, /const viewModeActive = computed\(\(\) => props\.mode === props\.viewMode\)/);
  assert.match(
    drawerSource,
    /const formModeActive = computed\(\(\) => props\.formModes\.includes\(props\.mode\)\)/,
  );
  assert.match(drawerSource, /props\.closeOnOutside \?\? viewModeActive\.value/);
  assert.match(drawerSource, /<template v-if="loading">/);
  assert.match(drawerSource, /<template v-else-if="loadFailed">/);
  assert.match(drawerSource, /<template v-else-if="viewModeActive">/);
  assert.match(drawerSource, /<template v-else-if="formModeActive">/);
  assert.notMatch(drawerSource, /<template v-else>\s*<slot name="form"/);
  assert.match(drawerSource, /<slot name="view" \/>/);
  assert.match(drawerSource, /<slot name="form" \/>/);
  assert.match(detailDrawerSource, /<RecordDetailLayout surface="drawer"[\s\S]*scrollable-content/);
  assert.match(detailDrawerSource, /subtitle\?: string/);
  assert.match(
    detailDrawerSource,
    /const inlineWidth = computed\([\s\S]*min\(\$\{requestedWidth\}, calc\(100% - 32px\)\)/,
  );
  assert.match(detailDrawerSource, /v-else-if="renderMode === 'inline'"[\s\S]*:width="inlineWidth"/);
  assert.notMatch(detailDrawerSource, /RecordDetailPanel/);
  assert.match(detailDrawerSource, /<slot name="operation" \/>/);
  assert.notMatch(detailDrawerSource, /<slot name="actions" \/>/);
  assert.match(detailPanelSource, /<RecordDetailLayout[\s\S]*surface="workspace"/);
  assert.match(detailPanelSource, /:subtitle="subtitle"/);
  assert.match(detailLayoutSource, /record-detail-layout-title-copy/);
  assert.match(detailLayoutSource, /<p v-if="subtitle">/);
  assert.match(detailLayoutSource, /surface\?: 'workspace' \| 'drawer'/);
  assert.match(detailLayoutSource, /record-detail-layout--workspace/);
  assert.match(detailLayoutSource, /record-detail-layout--drawer/);
  assert.match(
    detailLayoutSource,
    /\.record-detail-layout--drawer \.record-detail-layout-header[\s\S]*border-bottom: 1px solid var\(--muyun-border\)/,
  );
  assert.match(detailLayoutSource, /scrollableContent && Boolean\(\$slots\.operation\)/);
  assert.match(detailLayoutSource, /grid-template-rows: auto minmax\(0, 1fr\);/);
  assert.match(detailLayoutSource, /grid-template-rows: auto minmax\(0, 1fr\) auto;/);
  assert.match(detailLayoutSource, /record-detail-layout-operation/);
  assert.match(detailLayoutSource, /<DrawerOperationBar>/);
  assert.match(operationBarSource, /defineOptions\(\{ name: 'DrawerOperationBar' \}\)/);
  assert.match(operationBarSource, /\.ant-btn-primary/);
  assert.match(operationBarSource, /@media \(max-width: 480px\)/);
  assert.match(detailDrawerSource, /scope\?: UiSidePanelScope/);
  assert.match(detailDrawerSource, /:scope="scope"/);
  assert.match(drawerSource, /scope\?: UiSidePanelScope/);
  assert.match(drawerSource, /:scope="scope"/);
  assert.match(detailLayoutSource, /overflow: auto/);
  assert.match(pageRealtimeSource, /subscribeAppModuleDataChanges\(options\.moduleAlias\)/);

  const recordPickerSource = readSource('src/platform-components/RecordPicker.vue');
  assert.match(recordPickerSource, /if \(props\.mode === 'list'\)[\s\S]*await loadListRecords\(\)/);
  assert.match(recordPickerSource, /props\.context\.crud\.query/);
  assert.match(recordPickerSource, /record\.enabled === false \|\|\s*Boolean\(firstConstraintMessage/);
  const recordMultiPickerSource = readSource('src/platform-components/RecordMultiPicker.vue');
  assert.match(recordMultiPickerSource, /record\.enabled === false \|\|\s*Boolean\(firstConstraintMessage/);
});

it('standard module runner waits for a complete detail and action availability before enabling mutations', () => {
  const hostSource = readSource('src/dynamic-page-runtime/ModulePageHost.vue');
  const detailActionsSource = readSource('src/dynamic-page-runtime/ModuleRecordDetailActions.vue');
  const detailControllerSource = readSource('src/dynamic-page-runtime/recordDetailController.ts');
  const editingSessionSource = readSource('src/dynamic-page-runtime/composables/useRecordEditingSession.ts');

  assert.match(hostSource, /useRecordDetailController \} from '.\/recordDetailController'/);
  assert.match(hostSource, /const detail = useRecordDetailController<QueryListRecord>\(\)/);
  assert.match(hostSource, /loading: detailLoading/);
  assert.match(hostSource, /loadFailed: detailLoadFailed/);
  assert.match(hostSource, /useRecordEditingSession/);
  assert.match(hostSource, /invalidatePendingRequests\(\)/);
  assert.match(editingSessionSource, /const sequence = \+\+requestSequence/);
  assert.match(editingSessionSource, /detail\.beginLoad\(record, mode, options\)/);
  assert.match(editingSessionSource, /await context\.crud\.view\(id\)/);
  assert.match(editingSessionSource, /sequence !== requestSequence/);
  assert.match(editingSessionSource, /detail\.failLoad\(\)/);
  assert.match(hostSource, /function retryLoadDetail\(\)/);
  assert.match(hostSource, /:loading="detailLoading"/);
  assert.match(hostSource, /:load-failed="detailLoadFailed"/);
  assert.match(hostSource, /@retry="retryLoadDetail"/);
  assert.match(hostSource, /canMutateModuleDetail/);
  assert.match(hostSource, /<ModuleRecordDetailActions/);
  assert.match(hostSource, /:detail-loading="detailLoading"/);
  assert.match(hostSource, /:detail-load-failed="detailLoadFailed"/);
  assert.match(detailActionsSource, /const formActive = computed/);
  assert.match(detailActionsSource, /const saveAvailable = computed/);
  assert.match(detailActionsSource, /props\.mode === 'create' \? 'create' : 'update'/);
  assert.match(detailActionsSource, /<RecordPanelButton :disabled="saving" @click="emit\('cancel'\)">取消/);
  assert.match(detailActionsSource, /v-if="formActive"/);
  assert.match(detailActionsSource, /v-else-if="viewActionsActive"/);
  assert.match(hostSource, /@cancel="cancelDetailEditing"/);
  assert.match(hostSource, /function cancelDetailEditing\(\)/);
  assert.match(hostSource, /function cancelDetailEditing\(\)[\s\S]*detail\.cancelEdit\(\)/);
  assert.match(detailControllerSource, /function cancelEdit\(\)[\s\S]*if \(mode\.value === 'create'\)/);
  assert.match(detailControllerSource, /function cancelEdit\(\)[\s\S]*mode\.value = 'view';/);
});

it('page navigator renders levels through the standard module runner', () => {
  const hostSource = readSource('src/dynamic-page-runtime/ModulePageHost.vue');
  const navigatorRuntimeSource = readSource('src/dynamic-page-runtime/composables/useNavigatorRuntime.ts');

  assert.match(hostSource, /useNavigatorRuntime\(context, baseContext\.http\)/);
  assert.match(navigatorRuntimeSource, /navigatorLevels = ref<NavigatorLevelRuntime\[\]>/);
  assert.match(hostSource, /selectedNavigatorRecords/);
  assert.match(hostSource, /function selectNavigatorRecord/);
  assert.match(hostSource, /function navigatorExplorerQueryValues/);
  assert.match(hostSource, /v-for="level in visibleNavigatorLevels"/);
  assert.match(hostSource, /:external-query-values="navigatorExplorerQueryValues\(level\.descriptor\.key\)"/);
});

it('static edit draft normalizers preserve standard record fields', () => {
  const roleSource = readSource('src/views/RoleManagementView.vue');

  assert.match(roleSource, /function normalizedRoleDraft[\s\S]*normalizeRecordDraft<Role>\(draft,/);
});

it('record explorer panel focuses and closes search from keyboard', () => {
  const panelSource = readSource('src/platform-components/RecordExplorerPanel.vue');
  const treeSource = readSource('src/platform-components/TreeRecordExplorer.vue');
  const inputSource = readSource('src/vue-ui-antdv/components/UiInput.vue');

  assert.match(panelSource, /focusSearchInput/);
  assert.match(panelSource, /querySelector\('input'\)\?\.focus\(\)/);
  assert.match(panelSource, /const searchExpanded = ref\(props\.searchKeyword\.trim\(\)\.length > 0\)/);
  assert.match(
    panelSource,
    /const searchVisible = computed\(\(\) => props\.searchable && searchExpanded\.value\)/,
  );
  assert.match(panelSource, /if \(searchExpanded\.value\)/);
  assert.match(panelSource, /@keydown\.esc="handleSearchEscape"/);
  assert.match(panelSource, /:value="searchKeyword"\s+allow-clear/);
  assert.match(treeSource, /v-model:value="localKeyword"\s+allow-clear/);
  assert.match(inputSource, /keydown: \[event: KeyboardEvent\]/);
});

it('record explorer regions pass constrained height through to tree and list scroll owners', () => {
  const panelSource = readSource('src/platform-components/RecordExplorerPanel.vue');
  const treeSource = readSource('src/platform-components/TreeRecordExplorer.vue');
  const crudListSource = readSource('src/platform-components/CrudRecordListExplorer.vue');

  assert.match(
    panelSource,
    /\.record-explorer-panel-content \{[\s\S]*display: flex;[\s\S]*flex-direction: column;[\s\S]*min-height: 0;/,
  );
  assert.match(panelSource, /\.record-explorer-panel-content :slotted\(\*\) \{[\s\S]*flex: 1 1 auto;/);
  assert.match(treeSource, /\.tree-record-explorer \{[\s\S]*flex: 1 1 auto;/);
  assert.match(treeSource, /\.ant-tree\) \{[\s\S]*overflow: auto;/);
  assert.match(crudListSource, /\.crud-record-list-explorer \{[\s\S]*flex: 1 1 auto;/);
  assert.match(crudListSource, /:deep\(\.record-list-explorer\) \{[\s\S]*flex: 1 1 auto;/);
});

it('management workspace consumes the page layout contract for constrained desktop work areas', () => {
  const workspaceSource = readSource('src/platform-components/ManagementWorkspace.vue');
  const explorerColumnSource = readSource('src/platform-components/ManagementExplorerColumn.vue');
  const staticLayoutSource = readSource('src/platform-components/StaticManagementLayout.vue');
  const detailPanelSource = readSource('src/platform-components/RecordDetailPanel.vue');
  const indexSource = readSource('src/platform-components/index.ts');

  assert.match(workspaceSource, /explorerCount\?: number/);
  assert.match(workspaceSource, /--muyun-management-explorer-width: 280px/);
  assert.match(workspaceSource, /--muyun-management-list-min-width: 720px/);
  assert.match(workspaceSource, /--muyun-management-detail-min-width: 560px/);
  assert.match(workspaceSource, /overflow-x: auto/);
  assert.match(workspaceSource, /repeat\(var\(--muyun-management-explorer-count\)/);
  assert.match(workspaceSource, /align-items: start/);
  assert.match(workspaceSource, /usePageLayout/);
  assert.match(workspaceSource, /management-workspace--constrained/);
  assert.match(workspaceSource, /height: 100%;[\s\S]*min-height: 0;[\s\S]*align-items: stretch;/);
  assert.match(explorerColumnSource, /defineOptions\(\{ name: 'ManagementExplorerColumn' \}\)/);
  assert.match(explorerColumnSource, /align-self: stretch/);
  assert.match(explorerColumnSource, /:slotted\(\*\)/);
  assert.match(workspaceSource, /min-height: 100%/);
  assert.match(workspaceSource, /width: 100%/);
  assert.notMatch(workspaceSource, /width: max-content/);
  assert.notMatch(workspaceSource, /100vh - 116px/);
  assert.notMatch(workspaceSource, /@media \(max-width: 980px\)/);
  assert.match(workspaceSource, /min-width: 0/);
  assert.match(indexSource, /export \{ default as ManagementWorkspace \}/);
  assert.match(indexSource, /export \{ default as ManagementExplorerColumn \}/);
  assert.match(staticLayoutSource, /<ManagementWorkspace\s+class="static-management-page"/);
  assert.match(staticLayoutSource, /<ManagementExplorerColumn>/);
  assert.notMatch(staticLayoutSource, /scrollableContent\?: boolean/);
  assert.match(detailPanelSource, /const pageLayout = usePageLayout\(\)/);
  assert.match(detailPanelSource, /:scrollable-content="pageLayout === 'workspace'"/);
  assert.notMatch(detailPanelSource, /scrollableContent\?: boolean/);
});

it('user management is hosted by the constrained standard module workspace', () => {
  const hostSource = readSource('src/dynamic-page-runtime/ModulePageHost.vue');
  const enhancementSource = readSource('src/platform-admin-runtime/userModulePageEnhancement.ts');
  const routesSource = readSource('src/platform-admin-runtime/platformAdminRoutes.ts');

  assert.match(hostSource, /providePageLayout\([\s\S]*\? 'workspace'/);
  assert.match(enhancementSource, /target: \{ moduleAlias: 'iam\.user' \}/);
  assert.match(hostSource, /persistent-query-controls="persistentListQueryControls"/);
  assert.notMatch(routesSource, /moduleAlias: 'iam\.user'/);
});

it('record picker delegates single-value interaction to the standard select adapters', () => {
  const pickerSource = readSource('src/platform-components/RecordPicker.vue');
  const treeSelectSource = readSource('src/vue-ui-antdv/components/UiTreeSelect.vue');

  assert.match(pickerSource, /<UiTreeSelect[\s\S]*:allow-clear="allowClear"[\s\S]*:show-search="true"/);
  assert.match(pickerSource, /<UiSelect[\s\S]*:filter-option="false"/);
  assert.match(pickerSource, /@search="keyword = \$event"/);
  assert.match(pickerSource, /@update:value="updateValue"/);
  assert.notMatch(pickerSource, /document\.addEventListener|record-picker-clear|record-picker-panel/);
  assert.match(treeSelectSource, /:show-search="showSearch"/);
  assert.match(treeSelectSource, /:filter-tree-node="filterTreeNode"/);
});

it('role scope navigation uses the platform tree with deferred children', () => {
  const roleSource = readSource('src/views/RoleManagementView.vue');
  const treeSource = readSource('src/vue-ui-antdv/components/UiTree.vue');

  assert.match(roleSource, /<UiTree/);
  assert.match(roleSource, /:load-children="loadScopeTreeChildren"/);
  assert.match(roleSource, /tenantRootTreeNode/);
  assert.match(roleSource, /organizationTreeNode/);
  assert.match(roleSource, /createScopedTreeModuleContext/);
  assert.match(treeSource, /loadChildren\?: \(node: UiTreeNode\) => Promise<void>/);
  assert.match(treeSource, /event\?\.expanded/);
});

it('application scope switcher remains a platform component for legacy scoped pages', () => {
  const indexSource = readSource('src/platform-components/index.ts');
  const switcherSource = readSource('src/platform-components/ApplicationScopeSwitcher.vue');

  assert.match(indexSource, /ApplicationScopeSwitcher/);
  assert.match(indexSource, /createStaticTreeResourceModuleContext/);
  assert.match(switcherSource, /defineOptions\(\{ name: 'ApplicationScopeSwitcher' \}\)/);
  assert.match(switcherSource, /UiDropdown/);
  assert.match(switcherSource, /:selected-key="String\(value \?\? ''\)"/);
  assert.match(switcherSource, /align="start"/);
});

it('three-column management pages use the platform detail panel', () => {
  const indexSource = readSource('src/platform-components/index.ts');
  const panelSource = readSource('src/platform-components/RecordDetailPanel.vue');
  const layoutSource = readSource('src/platform-components/StaticManagementLayout.vue');

  assert.match(indexSource, /RecordDetailPanel/);
  assert.match(panelSource, /defineOptions\(\{ name: 'RecordDetailPanel', inheritAttrs: false \}\)/);
  assert.match(panelSource, /<slot name="status" \/>/);
  assert.match(panelSource, /<slot name="actions" \/>/);
  assert.match(panelSource, /<slot name="outside-top" \/>/);
  assert.match(panelSource, /<slot name="outside-bottom" \/>/);
  assert.match(layoutSource, /<RecordDetailPanel[\s\S]*:title="detailTitle"/);
  assert.match(layoutSource, /<slot name="detail-status" \/>/);
  assert.match(layoutSource, /explorerTitle: string/);
  assert.match(layoutSource, /detailTitle: string/);
  assert.notMatch(layoutSource, /sidebarTitle|cardTitle/);
  assert.match(layoutSource, /<slot name="explorer-actions" \/>/);
  assert.match(layoutSource, /<slot name="detail-actions" \/>/);
  assert.notMatch(layoutSource, /RecordStatusTag|card-header|title-line/);
  assert.notMatch(layoutSource, /actionMessage|message success|message\.success/);
});

it('role management enters the standard runner while keeping IAM scope and actions injected', () => {
  const roleViewSource = readSource('src/views/RoleManagementView.vue');
  const routesSource = readSource('src/platform-admin-runtime/platformAdminRoutes.ts');
  const roleScopeTreeSource = readSource('src/platform-admin-runtime/role/RoleScopeTree.vue');
  const roleEnumTitleCellSource = readSource('src/platform-admin-runtime/role/RoleEnumTitleCell.vue');
  const roleEnhancementSource = readSource('src/platform-admin-runtime/roleModulePageEnhancement.ts');
  const contractsSource = readSource('src/web-contracts/index.ts');
  const panelSource = readSource('src/platform-components/RecordQueryListPanel.vue');

  assert.notMatch(routesSource, /route: '\/iam\/role',/);
  assert.match(roleScopeTreeSource, /defineOptions\(\{ name: 'RoleScopeTree' \}\)/);
  assert.match(roleScopeTreeSource, /moduleAlias: 'iam\.tenant'/);
  assert.match(roleScopeTreeSource, /moduleAlias: 'iam\.organization'/);
  assert.match(roleScopeTreeSource, /<UiTree/);
  assert.match(roleScopeTreeSource, /loadChildren/);
  assert.match(roleScopeTreeSource, /clearSelection/);
  assert.notMatch(roleScopeTreeSource, /tenant-root:|租户本级角色/);
  assert.match(roleScopeTreeSource, /key: `tenant:\$\{tenant\.id \?\? ''\}`/);
  assert.match(roleEnhancementSource, /target: \{ moduleAlias: 'iam\.role' \}/);
  assert.match(roleEnhancementSource, /kind: 'roleScope'/);
  assert.match(roleEnhancementSource, /RoleAccountGrantDrawerSurface/);
  assert.match(roleEnhancementSource, /RoleEmploymentGrantDrawerSurface/);
  assert.match(roleEnhancementSource, /RoleAuthorizationDrawerSurface/);
  assert.match(roleEnhancementSource, /title: '角色授权', width: 820/);
  assert.match(
    roleEnhancementSource,
    /cellComponents:[\s\S]*assignmentType[\s\S]*roleKind[\s\S]*sharePolicy/,
  );
  assert.match(roleEnhancementSource, /recordActions: roleRecordActions/);
  assert.match(roleEnumTitleCellSource, /account: '账号角色'/);
  assert.match(roleEnumTitleCellSource, /standard: '标准角色'/);
  assert.match(roleViewSource, /tenantRootTreeNode/);
  assert.match(roleViewSource, /selectPlatformScope/);
  assert.match(roleViewSource, /title: '平台角色'/);
  assert.match(roleViewSource, /selectTenantRootScope/);
  assert.match(roleViewSource, /selectOrganizationScope/);
  assert.match(roleViewSource, /fieldName: 'ownerScopeType'/);
  assert.match(roleViewSource, /fieldName: 'ownerScopeId'/);
  assert.match(roleViewSource, /values: \[scope\.kind\]/);
  assert.match(roleViewSource, /values: \[scope\.id\]/);
  assert.match(roleViewSource, /createScopedTreeModuleContext/);
  assert.match(roleViewSource, /treePath: '\/iam\.organization\/tree'/);
  assert.match(roleViewSource, /scopeFieldName: 'tenantId'/);
  assert.match(roleViewSource, /onMounted\(\(\) => \{/);
  assert.match(roleViewSource, /void loadRoleFormDefinition\(\)/);
  assert.match(roleViewSource, /roleDetailWorkspaceView/);
  assert.match(roleViewSource, /scopeKind: scope\.kind/);
  assert.match(roleViewSource, /handOffRoleDetailWorkspaceSession/);
  assert.match(roleViewSource, /<RecordDetailPanel v-else/);
  assert.match(roleViewSource, /resolveRecordFormFields\(runtimeContext\.uiDescriptor\)/);
  assert.match(roleViewSource, /roleFormFieldDefinitions = ref\(resolveRecordFormFields\(undefined\)\)/);
  assert.match(roleViewSource, /:fields="roleFormFieldDefinitions"/);
  assert.match(roleViewSource, /:fallback="roleFormFieldFallback"/);
  assert.match(roleViewSource, /tenantId: scopeTenantId\(scope\)/);
  assert.match(roleViewSource, /function scopeTenantId\(scope: RoleScope \| undefined\)/);
  assert.match(roleViewSource, /scope\?\.kind === 'platform'/);
  assert.match(roleViewSource, /scope\?\.tenant\?\.id \?\? scope\?\.id/);
  assert.match(roleViewSource, /assignmentType: \{[\s\S]*controlType: 'select'/);
  assert.match(roleViewSource, /roleKind: \{[\s\S]*controlType: 'select'/);
  assert.match(roleViewSource, /sharePolicy: \{[\s\S]*options: sharePolicyOptions/);
  assert.match(roleViewSource, /roleDraft\.value\.roleKind === 'group'/);
  assert.match(roleViewSource, /fieldName === 'ownerScopeType' \|\| fieldName === 'ownerScopeId'/);
  assert.match(roleViewSource, /roleDetailMode\.value === 'edit' && \['assignmentType', 'roleKind'\]/);
  assert.match(roleViewSource, /selectedRole\.value\?\.systemManaged/);
  assert.match(roleViewSource, /target as Role\)\.systemManaged !== true/);
  assert.match(roleViewSource, /const roleListColumns = computed<RecordQueryListColumn\[\]>/);
  assert.match(roleViewSource, /:columns="roleListColumns"/);
  assert.match(roleViewSource, /function assignmentTypeTitle/);
  assert.match(roleViewSource, /function roleKindTitle/);
  assert.match(roleViewSource, /function sharePolicyTitle/);
  assert.match(roleViewSource, /commitRoleDetailRecord\(fullRecord, mode\)/);
  assert.match(roleViewSource, /nextMode === 'edit' && record\.systemManaged !== true \? 'edit' : 'view'/);
  assert.match(roleViewSource, /standard-crud-actions/);
  assert.match(roleViewSource, /standard-crud-row-actions/);
  assert.match(roleViewSource, /:extra-row-actions-of="roleExtraRowActionsOf"/);
  assert.match(roleViewSource, /key: 'bind'[\s\S]*title: '绑定'[\s\S]*after: 'edit'[\s\S]*pinned: true/);
  assert.match(panelSource, /primaryActions: ResolvedRecordActionItem\[\]/);
  assert.match(panelSource, /index === 0 \|\| action\.pinned === true/);
  assert.match(roleViewSource, /:row-action-state-of="roleRowActionStateOf"/);
  assert.match(roleViewSource, /const canToggleRole = computed\(/);
  assert.match(roleViewSource, /:disabled="savingRole \|\| !canToggleRole"/);
  assert.match(roleViewSource, /@change="toggleRoleEnabled\(selectedRole\)"/);
  assert.match(
    roleViewSource,
    /return record\?\.assignmentType === 'employment' \? 'employmentRoleGrants' : 'accountRoleGrants';/,
  );
  assert.match(roleViewSource, /<RoleAccountGrantDrawer/);
  assert.match(roleViewSource, /<RoleEmploymentGrantDrawer/);
  assert.match(
    roleViewSource,
    /if \(action\.key === 'bind' && selectedRole\.value\)[\s\S]*selectedRole\.value\.assignmentType === 'employment'/,
  );
  const roleEmploymentGrantDrawerSource = readSource('src/views/RoleEmploymentGrantDrawer.vue');
  const roleAuthorizationDrawerSurfaceForOperationSource = readSource(
    'src/platform-admin-runtime/role/RoleAuthorizationDrawerSurface.vue',
  );
  const roleAuthorizationSource = readSource('src/views/RoleAuthorizationView.vue');
  const employeeEmploymentTableSource = readSource('src/views/EmployeeEmploymentTable.vue');
  assert.notMatch(roleEmploymentGrantDrawerSource, /角色将在所选任职/);
  assert.match(roleEmploymentGrantDrawerSource, /:pagination="employmentTablePagination"/);
  assert.match(roleEmploymentGrantDrawerSource, /fill-height/);
  assert.match(
    roleEmploymentGrantDrawerSource,
    /<section v-if="selectedEmployments\.length > 0" class="role-employment-grant-selected">/,
  );
  assert.match(roleEmploymentGrantDrawerSource, /<strong>已选任职<\/strong>/);
  assert.match(roleEmploymentGrantDrawerSource, /@click="removeSelectedEmployment\(employment\.id\)"/);
  assert.match(roleEmploymentGrantDrawerSource, /function selectedEmploymentTitle/);
  assert.match(roleEmploymentGrantDrawerSource, /const selectionInitialized = ref\(false\)/);
  assert.match(
    roleEmploymentGrantDrawerSource,
    /:disabled="loading \|\| \(added\.length === 0 && removed\.length === 0\)"[\s\S]*确定/,
  );
  assert.match(employeeEmploymentTableSource, /:expanded-row-keys="expandedEmployeeKeys"/);
  assert.match(employeeEmploymentTableSource, /@row-dblclick="toggleEmployeeExpanded"/);
  assert.match(employeeEmploymentTableSource, /function toggleEmployeeExpanded/);
  assert.match(employeeEmploymentTableSource, /<RecordExpandedSubtable title="任职信息">/);
  assert.match(
    employeeEmploymentTableSource,
    /:selection="selectionOf\(record as unknown as EmployeeEmploymentGroup\)"/,
  );
  assert.match(employeeEmploymentTableSource, /function updateEmployeeSelectedIds/);
  assert.match(employeeEmploymentTableSource, /<UiDataTable[\s\S]*horizontal-scroll/);
  assert.match(employeeEmploymentTableSource, /:fill-height="fillHeight"/);
  assert.match(roleAuthorizationDrawerSurfaceForOperationSource, /:drawer-context="props\.context"/);
  assert.match(roleAuthorizationDrawerSurfaceForOperationSource, /role-authorization-drawer-surface/);
  assert.match(roleAuthorizationDrawerSurfaceForOperationSource, /height: 100%;[\s\S]*overflow: hidden;/);
  assert.match(roleAuthorizationSource, /const supportsDataScope = computed/);
  assert.match(roleAuthorizationSource, /v-model:search-keyword="moduleKeyword"/);
  assert.match(roleAuthorizationSource, /setOperation\(\{/);
  assert.match(roleAuthorizationSource, /setSubtitle\(`\$\{roleTitle\.value\} · \$\{scopeTitle/);
  assert.match(roleAuthorizationSource, /authorization-layout--compact-actions/);
  assert.match(roleAuthorizationSource, /var\(--muyun-management-explorer-width, 280px\)/);
  assert.match(
    roleAuthorizationSource,
    /\.action-panel :deep\(\.record-explorer-panel-content\) \{[\s\S]*overflow: hidden;/,
  );
  assert.match(roleAuthorizationSource, /\.action-panel :deep\(\.ui-data-table\) \{[\s\S]*flex: 1 1 auto;/);
  assert.match(roleAuthorizationSource, /\.module-panel :deep\(\.ant-tree\) \{[\s\S]*overflow-y: auto;/);
  assert.match(
    roleAuthorizationSource,
    /\.action-panel :deep\(\.ant-table-body\) \{[\s\S]*overscroll-behavior: contain;/,
  );
  assert.notMatch(roleViewSource, /account-grants/);
  assert.notMatch(roleViewSource, /employment-grants/);
  assert.notMatch(roleViewSource, /permissionMatrix/);
  assert.match(roleViewSource, /key: 'authorize'[\s\S]*actionCode: 'rolePermissions'[\s\S]*title: '授权'/);
  assert.match(roleViewSource, /authorizationDrawerOpen\.value = true/);
  assert.match(roleViewSource, /<RoleAuthorizationView[\s\S]*:role-id="authorizationRole\.id"[\s\S]*drawer/);
  assert.notMatch(roleViewSource, /createWorkspaceViewDescriptor\([\s\S]*roleAuthorizationWorkspaceView/);
  const roleAuthorizationDrawerSurfaceSource = readSource(
    'src/platform-admin-runtime/role/RoleAuthorizationDrawerSurface.vue',
  );
  const roleAuthorizationViewSource = readSource('src/views/RoleAuthorizationView.vue');
  const roleAuthorizationWorkspaceViewSource = readSource('src/views/roleAuthorizationWorkspaceView.ts');
  const workspaceDrawerSource = readSource('src/platform-admin-runtime/WorkspaceViewDrawer.vue');
  assert.match(roleAuthorizationViewSource, /角色组不独立授权/);
  assert.match(roleAuthorizationDrawerSurfaceSource, /:module-context="roleContext"/);
  assert.match(roleAuthorizationViewSource, /props\.moduleContext \?\? defaultRoleContext/);
  assert.match(roleAuthorizationViewSource, /标准动作的数据范围模板/);
  assert.match(roleAuthorizationViewSource, /dataScopePolicyCatalog/);
  assert.match(roleAuthorizationViewSource, /action\.dataScopePolicy = 'inheritDataGrant'/);
  assert.match(roleAuthorizationViewSource, /function normalizeEmploymentDataScope/);
  assert.match(roleAuthorizationViewSource, /action\.dataScopePolicy === 'none'/);
  assert.match(roleAuthorizationViewSource, /dataScopePolicy: 'inheritDataGrant'/);
  assert.match(
    roleAuthorizationViewSource,
    /record\.dataAuth && isEmploymentRole && Boolean\(record\.granted\)/,
  );
  assert.match(roleAuthorizationViewSource, /isEmploymentDataScopeColumn\(column, record\)/);
  assert.match(roleAuthorizationViewSource, /displayedDataScopePolicy/);
  assert.match(roleAuthorizationViewSource, /referenceDependencyOptions/);
  assert.match(roleAuthorizationViewSource, /handlePlatformActionSuccess\(result/);
  assert.notMatch(roleAuthorizationViewSource, /presentPlatformMessage\('授权已保存'/);
  assert.match(roleAuthorizationViewSource, /authorizationModules/);
  assert.match(roleAuthorizationViewSource, /permissionMatrix/);
  assert.match(roleAuthorizationViewSource, /WorkspaceViewDrawer/);
  assert.match(roleAuthorizationViewSource, /handOffRoleAuthorizationWorkspaceSession/);
  assert.match(roleAuthorizationViewSource, /registerRoleAuthorizationWorkspaceHandoffRecipient/);
  assert.match(roleAuthorizationViewSource, /onPromoted: dismissPromotedDrawer/);
  assert.match(roleAuthorizationViewSource, /onPromotionRejected/);
  assert.match(roleAuthorizationViewSource, /UiCheckbox/);
  assert.match(roleAuthorizationViewSource, /#header="\{ column \}"/);
  assert.match(roleAuthorizationViewSource, /updateAllActions/);
  assert.match(roleAuthorizationViewSource, /replacePermissionMatrix/);
  assert.match(roleAuthorizationViewSource, /确认/);
  assert.match(roleAuthorizationViewSource, /props\.drawer === true/);
  assert.match(roleAuthorizationWorkspaceViewSource, /drawerProfile: 'wide-work'/);
  assert.match(workspaceDrawerSource, /min\(600px, 100vw\)/);
  assert.match(panelSource, /record\[titleField \?\? `\$\{fieldName\}Title`\]/);
  assert.match(contractsSource, /export type RoleAssignmentType = 'account' \| 'employment'/);
  assert.match(contractsSource, /export type RoleOwnerScopeType = 'platform' \| 'tenant' \| 'organization'/);
  assert.match(
    contractsSource,
    /export type RoleSharePolicy = 'private' \| 'ownerAndChildren' \| 'tenant' \| 'platform'/,
  );
});

it('user management keeps account basics separate from employment binding and role authorization', () => {
  const userViewSource = [
    readSource('src/views/UserManagementView.vue'),
    readSource('src/views/UserManagementListView.vue'),
    readSource('src/views/UserDetailRouteView.vue'),
  ].join('\n');
  const userDetailContentSource = readSource('src/views/UserDetailContent.vue');
  const userSessionRowsSource = readSource('src/views/useUserSessionRows.ts');
  const userSessionExpandedSource = readSource('src/platform-components/UserSessionExpandedSubtable.vue');
  const routesSource = readSource('src/platform-admin-runtime/platformAdminRoutes.ts');
  const contractsSource = readSource('src/web-contracts/index.ts');
  const inputSource = readSource('src/vue-ui-antdv/components/UiInput.vue');
  const iconSource = readSource('src/vue-ui-antdv/components/UiIcon.vue');

  assert.notMatch(routesSource, /moduleAlias: 'iam\.user'/);
  assert.match(userViewSource, /defineOptions\(\{ name: 'UserManagementView' \}\)/);
  assert.match(userViewSource, /moduleAlias: 'iam\.tenant'/);
  assert.match(userViewSource, /moduleAlias: 'iam\.user'/);
  assert.match(userViewSource, /user-management-page/);
  assert.notMatch(userViewSource, /calc\(100vh|calc\(100dvh/);
  assert.match(userViewSource, /<CrudRecordListExplorer/);
  assert.match(userViewSource, /<RecordQueryListPanel/);
  assert.match(userViewSource, /X-MuYun-Page-Context/);
  assert.match(userViewSource, /JSON\.stringify\(\{ tenant: String\(tenant\.id\) \}\)/);
  assert.match(userViewSource, /useUserSessionRows\(\{ context: scopedUserContext/);
  assert.match(userViewSource, /:expanded-row-keys="expandedUserKeys"/);
  assert.match(userViewSource, /@row-expand="handleUserRowExpand"/);
  assert.match(userViewSource, /<template #expandedRow="\{ record \}">/);
  assert.match(userViewSource, /standard-crud-actions/);
  assert.match(userViewSource, /standard-crud-row-actions/);
  assert.match(userViewSource, /const userListColumns = computed<RecordQueryListColumn\[\]>/);
  assert.match(userViewSource, /key: 'onlineStatus'/);
  assert.match(userViewSource, /:columns="userListColumns"/);
  assert.match(userViewSource, /canBrowseTenants/);
  assert.match(userViewSource, /currentUserTenant/);
  assert.match(userViewSource, /initializeTenantUserScope/);
  assert.match(userViewSource, /fieldName: 'tenantId'/);
  assert.match(userViewSource, /createScopedUserModuleContext/);
  assert.match(userViewSource, /onMounted\(\(\) => \{/);
  assert.match(userViewSource, /void loadUserFormDefinition\(\)/);
  assert.match(userViewSource, /resolveRecordFormFields\(runtimeContext\.uiDescriptor\)/);
  assert.match(userViewSource, /userFormFieldDefinitions = ref\(resolveRecordFormFields\(undefined\)\)/);
  assert.match(userViewSource, /<UserDetailContent/);
  assert.match(userDetailContentSource, /<RecordFormFields/);
  assert.match(userDetailContentSource, /<form v-if="mode !== 'view'" class="user-form"/);
  assert.match(userViewSource, /:fields="userFormFieldDefinitions"/);
  assert.match(userViewSource, /:fallback="userFormFieldFallback"/);
  assert.match(userViewSource, /username: \{ label: '账号'/);
  assert.match(userViewSource, /enabled: \{ label: '允许登录'/);
  assert.match(userViewSource, /function normalizedUserDraft/);
  assert.match(userViewSource, /normalizeRecordDraft<UserAccount>\(draft,/);
  assert.match(userViewSource, /key: 'resetPassword'[\s\S]*actionCode: 'changePassword'/);
  assert.match(userViewSource, /key: 'resetGeneratedPassword'[\s\S]*actionCode: 'resetPassword'/);
  assert.match(userViewSource, /title: '修改密码'/);
  assert.match(userViewSource, /title: '重置密码'/);
  assert.match(userViewSource, /<UserSessionExpandedSubtable/);
  assert.match(userViewSource, /@revoke="revokeUserSession\(record, \$event\)"/);
  assert.match(userSessionExpandedSource, /defineOptions\(\{ name: 'UserSessionExpandedSubtable' \}\)/);
  assert.match(userSessionExpandedSource, /<RecordExpandedSubtable/);
  assert.match(userSessionExpandedSource, /user-session-expanded-main strong/);
  assert.match(userSessionExpandedSource, /text-overflow: ellipsis/);
  assert.match(userSessionExpandedSource, /@media \(max-width: 980px\)/);
  assert.match(
    userViewSource,
    /useUserSessionRows\(\{ context: scopedUserContext, source: 'user-management-list' \}\)/,
  );
  assert.match(userViewSource, /usePageBusinessEventHandler\(handleUserSessionBusinessEvent\)/);
  assert.match(userViewSource, /:cell-renderers="\{ onlineStatus: userOnlineStatusTitle \}"/);
  assert.match(userSessionRowsSource, /function handleUserListLoaded\(records: Array<\{ id\?: string \}>\)/);
  assert.match(userSessionRowsSource, /path: '\/iam\.user\/sessions\/status'/);
  assert.match(userSessionRowsSource, /function userOnlineStatusTitle\(record: \{ id\?: string \}\)/);
  assert.match(
    userSessionRowsSource,
    /function handleUserSessionBusinessEvent\(event: WebBusinessRealtimeEvent\)/,
  );
  assert.match(userSessionRowsSource, /event\.type !== userSessionCollectionChangedEventType/);
  assert.match(userSessionRowsSource, /visibleUserIds\.value\.includes\(userId\)/);
  assert.match(userSessionRowsSource, /expandedUserKeys\.value\.includes\(userId\)/);
  assert.match(userSessionRowsSource, /loadUserSessions/);
  assert.match(userSessionRowsSource, /loadUserSessionActions/);
  assert.match(userSessionRowsSource, /options\.context\.recordActions\(userId\)/);
  assert.match(userSessionRowsSource, /userSessionStates = ref<Record<string, UserSessionState>>/);
  assert.match(
    userSessionRowsSource,
    /function userSessionState\(userId: string \| undefined\): UserSessionState/,
  );
  assert.match(userViewSource, /revokeUserSession/);
  assert.match(userViewSource, /revokeAllUserSessions/);
  assert.match(userDetailContentSource, /temporaryPassword/);
  assert.match(
    userViewSource,
    /detailMode\.value === 'resetPassword'[\s\S]*const userId = selectedUser\.value\?\.id[\s\S]*userContext\.can\('changePassword', userId\)/,
  );
  assert.match(userViewSource, /:record-id="selectedUser\?\.id"/);
  assert.match(userViewSource, /path: `\/iam\.user\/changePassword\/\$\{encodeURIComponent\(user\.id!\)\}`/);
  assert.match(userViewSource, /path: `\/iam\.user\/resetPassword\/\$\{encodeURIComponent\(user\.id!\)\}`/);
  assert.match(userSessionRowsSource, /path: `\/iam\.user\/\$\{encodeURIComponent\(userId\)\}\/sessions`/);
  assert.match(userViewSource, /sessions\/\$\{encodeURIComponent\(session\.id\)\}\/revoke`/);
  assert.match(
    userViewSource,
    /path: `\/iam\.user\/\$\{encodeURIComponent\(user\.id!\)\}\/sessions\/revoke`/,
  );
  assert.match(userDetailContentSource, /type="password"/);
  assert.match(inputSource, /type\?: 'text' \| 'password'/);
  assert.match(iconSource, /LockOutlined/);
  assert.match(contractsSource, /export interface UserAccount extends StandardEnabledSortableEntity/);
  assert.match(
    contractsSource,
    /export type UserPasswordStatus = 'normal' \| 'initial' \| 'resetRequired' \| 'expired'/,
  );
  assert.match(contractsSource, /passwordStatusTitle\?: string/);
  assert.match(contractsSource, /export interface ResetPasswordResponse/);
  assert.match(contractsSource, /export interface UserSessionView/);
  assert.match(contractsSource, /export interface UserSessionStatusView/);
  assert.match(contractsSource, /terminalType\?: string/);
  assert.match(contractsSource, /terminalTypeTitle\?: string/);
  assert.match(contractsSource, /platformType\?: string/);
  assert.match(contractsSource, /platformTypeTitle\?: string/);
  assert.match(contractsSource, /username\?: string/);
  assert.match(contractsSource, /password\?: string/);
  assert.notMatch(contractsSource, /passwordHash/);
  assert.notMatch(userViewSource, /iam\.employee_account/);
  assert.match(userViewSource, /employee-binding/);
  assert.match(userViewSource, /UserEmployeeBindingView/);
  assert.match(userViewSource, /loadUserEmployeeBinding/);
  assert.notMatch(userViewSource, /user-employee-binding/);
  assert.notMatch(userViewSource, /绑定职员/);
  assert.notMatch(userViewSource, /iam\.role_assignment/);
  assert.notMatch(userViewSource, /moduleAlias: 'iam\.organization'/);
  assert.notMatch(userViewSource, /fieldName: 'organizationId'/);
  assert.notMatch(userViewSource, /系统账号/);
  assert.notMatch(userViewSource, /operator: 'NULL'/);
  assert.notMatch(userViewSource, /permissionMatrix/);
  assert.notMatch(userViewSource, /sessionAudit/);
  assert.notMatch(userViewSource, /forceLogout/);
});

it('password management uses the standard module runner with a source-owned card assistant', () => {
  const passwordPreviewSource = readSource('src/views/PasswordPolicyPreview.vue');
  const enhancementSource = readSource('src/platform-admin-runtime/passwordPolicyPageEnhancement.ts');
  const hostSource = readSource('src/dynamic-page-runtime/ModulePageHost.vue');
  const routesSource = readSource('src/platform-admin-runtime/platformAdminRoutes.ts');
  const contractsSource = readSource('src/web-contracts/index.ts');

  assert.notMatch(routesSource, /platformAdminDynamicModuleRoutes/);
  assert.match(enhancementSource, /moduleAlias: 'iam\.password_policy_rule'/);
  assert.match(enhancementSource, /card:\s*\{/);
  assert.match(enhancementSource, /component: PasswordPolicyPreview/);
  assert.match(enhancementSource, /boundary: 'outside'/);
  assert.match(enhancementSource, /position: 'bottom'/);
  assert.match(hostSource, /createReadonlyCardRecordSnapshot/);
  assert.match(hostSource, /module-card-assistant/);
  assert.match(passwordPreviewSource, /defineOptions\(\{ name: 'PasswordPolicyPreview' \}\)/);
  assert.match(passwordPreviewSource, /本规则/);
  assert.match(passwordPreviewSource, /全规则/);
  assert.match(passwordPreviewSource, /UiRadioGroup/);
  assert.match(passwordPreviewSource, /new RegExp/);
  assert.notMatch(passwordPreviewSource, /\/iam\.password_policy_rule\/preview/);
  assert.match(contractsSource, /export type PasswordPolicyScopeType = 'global' \| 'tenant'/);
  assert.match(contractsSource, /export interface PasswordPolicyRule extends StandardEnabledSortableEntity/);
  assert.notMatch(contractsSource, /ruleCode/);
});

it('workbench exposes own password change through auth boundary', () => {
  const appSource = readSource('src/App.vue');
  const realtimeSource = readSource('src/platform-admin-runtime/realtime.ts');
  const pageRealtimeSource = readSource('src/platform-admin-runtime/pageRealtime.ts');
  const workbenchSource = readSource('src/platform-workbench/Workbench.vue');
  const dialogSource = readSource('src/app/ChangeOwnPasswordDialog.vue');
  const authClientSource = readSource('src/web-core/clients.ts');

  assert.match(workbenchSource, /key: 'changePassword'/);
  assert.match(workbenchSource, /title: '修改密码'/);
  assert.match(workbenchSource, /\.workbench \{[\s\S]*height: 100dvh;[\s\S]*overflow: hidden;/);
  assert.match(workbenchSource, /\.app-main \{[\s\S]*min-height: 0;[\s\S]*overflow: hidden;/);
  assert.match(workbenchSource, /\.app-content \{[\s\S]*position: relative;[\s\S]*overflow: hidden;/);
  assert.match(
    workbenchSource,
    /@media \(max-width: 720px\) \{[\s\S]*\.workbench \{[\s\S]*height: auto;[\s\S]*overflow: visible;/,
  );
  assert.notMatch(
    workbenchSource,
    /@media \(max-width: 980px\) \{[\s\S]*\.workbench \{[\s\S]*overflow: visible;/,
  );
  assert.match(appSource, /command === 'changePassword'[\s\S]*openChangeOwnPasswordDialog\(\)/);
  assert.match(appSource, /authClient\.changeOwnPassword/);
  assert.match(appSource, /onUserNotification: handleSecurityNotification/);
  assert.match(pageRealtimeSource, /export interface PageRealtimeSubscription/);
  assert.match(pageRealtimeSource, /export function usePageRealtimeSubscription/);
  assert.match(pageRealtimeSource, /usePageModuleDataChanges\(moduleAlias: string\)/);
  assert.match(pageRealtimeSource, /usePageBusinessEventHandler/);
  assert.match(
    pageRealtimeSource,
    /usePageRealtimeSubscription\(\(\) => subscribeAppDataChanges\(handler\)\)/,
  );
  assert.match(
    pageRealtimeSource,
    /usePageRealtimeSubscription\(\(\) => subscribeAppBusinessEvents\(handler\)\)/,
  );
  assert.match(pageRealtimeSource, /onMounted\(\(\) => \{/);
  assert.match(pageRealtimeSource, /onUnmounted\(\(\) => \{/);
  assert.match(realtimeSource, /connectRealtimeBusinessEvents/);
  assert.match(realtimeSource, /subscribeAppBusinessEvents/);
  assert.match(realtimeSource, /subscribeAppModuleDataChanges\(moduleAlias: string\)/);
  assert.match(realtimeSource, /moduleDataChangeChannel\(moduleAlias\)/);
  assert.match(realtimeSource, /appDataChangeDispatcher\.dispatch\(changeSet\)/);
  assert.notMatch(realtimeSource, /moduleDataChangeChannel\('iam\.user'\)/);
  assert.match(appSource, /function handleSecurityNotification\(notification: WebUserNotification\)/);
  assert.match(appSource, /startSecurityLogoutCountdown\(5\)/);
  assert.match(appSource, /function forceLocalLogout\(\)/);
  assert.match(appSource, /v-if="securityNotification"/);
  assert.match(appSource, /立即重新登录/);
  assert.match(appSource, /effectiveAuthToken/);
  assert.match(appSource, /currentPassword: currentPassword\.value/);
  assert.match(appSource, /newPassword: newPassword\.value/);
  assert.match(dialogSource, /defineOptions\(\{ name: 'ChangeOwnPasswordDialog' \}\)/);
  assert.match(dialogSource, /UiModal/);
  assert.notMatch(dialogSource, /role="dialog"/);
  assert.match(dialogSource, /autocomplete="current-password"/);
  assert.match(dialogSource, /autocomplete="new-password"/);
  assert.match(authClientSource, /path: '\/iam\.auth\/changeOwnPassword'/);
  assert.notMatch(appSource, /iam\.user\/changePassword/);
  assert.notMatch(appSource, /iam\.user\/resetPassword/);
});

it('business views use page realtime lifecycle wrappers only', () => {
  for (const [file, source] of viewSources()) {
    assert.notMatch(
      source,
      /from ['"]\.\.\/app\/realtime['"]/,
      `${file} must not import app realtime directly`,
    );
    assert.notMatch(source, /subscribeApp[A-Z]/, `${file} must not call app realtime subscriptions directly`);
    assert.notMatch(source, /\.subscribe\(/, `${file} must not hold raw realtime subscriptions`);
  }
});

it('dynamic module host uses shared descriptor driven list and form runners', () => {
  const hostSource = readSource('src/dynamic-page-runtime/ModulePageHost.vue');
  const navigatorExplorerSource = readSource('src/dynamic-page-runtime/PageNavigatorExplorer.vue');
  const listPanelSource = readSource('src/platform-components/RecordQueryListPanel.vue');
  const bootstrapSource = readSource('src/dynamic-page-runtime/composables/useModulePageBootstrap.ts');
  const navigatorRuntimeSource = readSource('src/dynamic-page-runtime/composables/useNavigatorRuntime.ts');

  assert.match(hostSource, /useModuleContext<QueryListRecord>/);
  assert.match(hostSource, /<RecordQueryListPanel/);
  assert.match(hostSource, /<RecordModeDrawer/);
  assert.match(hostSource, /enhancementDetailActions/);
  assert.match(hostSource, /<template(?: v-if="[^"]+")? #operation>/);
  assert.match(hostSource, /<ModuleRecordDetailActions/);
  assert.match(hostSource, /<RecordDetailFields/);
  assert.match(hostSource, /<RecordFormFields/);
  assert.match(hostSource, /RecordStatusSwitch/);
  assert.match(hostSource, /<template #status>/);
  assert.match(hostSource, /context\.crud\.enable\(id, \{ version \}\)/);
  assert.match(hostSource, /context\.crud\.disable\(id, \{ version \}\)/);
  assert.match(hostSource, /:exclude-field-names="\['enabled'\]"/);
  assert.match(hostSource, /useNavigatorRuntime\(context, baseContext\.http\)/);
  assert.match(hostSource, /isListPage/);
  assert.match(hostSource, /listUiConfigId/);
  assert.match(navigatorRuntimeSource, /runtimePage\.value\?\.navigator\?\.levels/);
  assert.match(hostSource, /:ui-config-id="listUiConfigId"/);
  assert.match(hostSource, /useModulePageBootstrap/);
  assert.match(bootstrapSource, /createPageBootstrapClient\(context\.http\)\.byMenu\(entryMenuId\)/);
  assert.match(bootstrapSource, /bootstrap\.entry\.moduleAlias !== context\.moduleAlias/);
  assert.match(hostSource, /pageBootstrap\.value\?\.entry\.pageMode/);
  assert.match(hostSource, /v-else-if="!pageReady"/);
  assert.match(hostSource, /v-else-if="isListPage"/);
  assert.match(hostSource, /:query-template-id="listQueryTemplateId"/);
  assert.match(hostSource, /:ready="pageReady && navigatorListScopeReady"/);
  assert.match(hostSource, /\$\{pageMode\.value\}入口暂未接入模块页面运行器/);
  assert.match(navigatorRuntimeSource, /treeModule\.value = context\.abilities\.hasTree\(\) === true/);
  assert.match(hostSource, /:explorer-count="navigatorExplorerCount"/);
  assert.match(hostSource, /const workspaceElement = ref<HTMLElement>\(\)/);
  assert.match(hostSource, /listDetailWorkspaceMinWidth\(navigatorExplorerCount\.value\)/);
  assert.match(hostSource, /new ResizeObserver\(\(\) => updateDetailSurfaceForWorkspaceWidth\(\)\)/);
  assert.match(hostSource, /workspaceWidth < listDetailMinimumWidth\.value/);
  assert.equal(/max-width: 719px/.test(hostSource), false);
  assert.match(hostSource, /:navigator-count="navigatorExplorerCount"/);
  assert.match(hostSource, /<ManagementWorkspace[\s\S]*v-else-if="treeManagementPage \|\| treeModule"/);
  assert.match(hostSource, /<CrudRecordListExplorer/);
  assert.match(hostSource, /<PageNavigatorExplorer/);
  assert.match(hostSource, /const primaryNavigatorContext = computed/);
  assert.match(hostSource, /const navigatorCreateDefaults = computed/);
  assert.equal(/scopedListWorkspace/.test(hostSource), false);
  assert.equal(/selectedScopeRecord/.test(hostSource), false);
  assert.match(navigatorRuntimeSource, /sourceCapabilities\?\.includes\('REFERENCE_TREE'\)/);
  assert.match(navigatorExplorerSource, /<TreeRecordExplorer[\s\S]*v-if="ready !== false && level\.tree"/);
  assert.match(navigatorExplorerSource, /search-mode="none"/);
  assert.match(hostSource, /:external-query-values="navigatorListQueryValues"/);
  assert.match(hostSource, /:required-external-criteria-keys="navigatorListCriteriaKeys"/);
  assert.match(hostSource, /const navigatorListScopeReady = computed/);
  assert.match(hostSource, /<TreeRecordExplorer\s+v-if="mainTreeScopeReady"/);
  assert.match(hostSource, /for \(const descendantKey of navigatorDescendantKeys\(levelKey\)\)/);
  assert.match(listPanelSource, /queryTemplateId: props\.queryTemplateId/);
  assert.match(listPanelSource, /if \(!queryReady\.value\) \{\s*return;/);
  assert.match(listPanelSource, /uiDescriptor\?\.page\?\.list\?\.fields/);
  assert.match(listPanelSource, /props\.requiredExternalCriteriaKeys\.length > 0/);
  assert.match(hostSource, /resolvePageContextTargetValues\(pageContextBindings\.value, 'LIST_QUERY'/);
  assert.match(hostSource, /<TreeRecordExplorer/);
  assert.match(hostSource, /context\.crud\.update\(id, record\)/);
  assert.match(hostSource, /<RecordDetailPanel/);
  assert.match(hostSource, /<RecordMetaSection/);
  assert.match(hostSource, /<ModuleActionButton/);
  assert.match(hostSource, /<RecordPanelState/);
  assert.match(
    hostSource,
    /v-if="!persistentTreeDetail && !flatManagementPage && \(!listDetailCardPage \|\| detailSurfaceUsesDrawer\)"/,
  );
  assert.match(
    hostSource,
    /<ManagementWorkspace[\s\S]*v-else-if="listDetailCardPage"[\s\S]*:list-surface="detailSurfaceUsesDrawer"/,
  );
  assert.match(
    hostSource,
    /saveDetailSurfacePreference\(userPreferences, context\.moduleAlias, preference\)/,
  );
  assert.match(hostSource, /title="改为抽屉展示"/);
  assert.match(hostSource, /icon-name="pin-off"/);
  assert.match(hostSource, /icon-name="pin"/);
  assert.equal(matchCount(hostSource, /title="在新标签页打开"/g), 4);
  assert.match(hostSource, /<RecordDetailPanel[\s\S]*<template #title-prefix>/);
  assert.match(
    hostSource,
    /<RecordModeDrawer[\s\S]*<template v-if="listDetailCardPage && !narrowDetailSurface" #title-prefix>/,
  );
  assert.match(hostSource, /title="固定到右侧展示"/);
  assert.match(
    hostSource,
    /function selectListDetailRecord\(record: QueryListRecord\)[\s\S]*openRecordView\(record\)/,
  );
  assert.match(hostSource, /<StaticManagementLayout\s+v-if="flatManagementPage"/);
  assert.equal(matchCount(hostSource, /class="module-card-layout"/g), 0);
  assert.equal(matchCount(hostSource, /class="module-card-assistant"/g), 6);
  assert.equal(matchCount(hostSource, /module-card-assistant--outside/g), 6);
  assert.match(hostSource, /hasCardAssistantAt\('inside', 'bottom'\)/);
  assert.match(hostSource, /hasCardAssistantAt\('outside', 'bottom'\)/);
  assert.match(hostSource, /<div v-else class="module-form">[\s\S]*<RecordFormFields/);
  assert.match(hostSource, /\.module-form \{[\s\S]*column-gap: 12px;[\s\S]*row-gap: 16px;/);
  assert.match(hostSource, /\.module-form \{[\s\S]*--muyun-record-form-label-gap: 8px;/);
  const recordFormFieldsSource = readSource('src/platform-components/RecordFormFields.vue');
  const moduleActionsSource = readSource('src/dynamic-page-runtime/composables/useModulePageActions.ts');
  assert.match(recordFormFieldsSource, /gap: var\(--muyun-record-form-label-gap, 6px\)/);
  assert.match(hostSource, /useRecycleBinExplorerMode<QueryListRecord>/);
  assert.match(hostSource, /title: `删除\$\{recordLabel\.value\}`/);
  assert.match(hostSource, /useModulePageActions\(\)/);
  assert.match(moduleActionsSource, /handlePlatformActionSuccess\(result,/);
  assert.match(hostSource, /await presentModuleActionSuccess\(result, '保存成功'\)/);
  assert.match(hostSource, /await presentModuleActionSuccess\(result, '删除成功'\)/);
  assert.match(hostSource, /await presentModuleActionSuccess\(result, enabling \? '已启用' : '已停用'\)/);
  assert.match(hostSource, /presentPlatformError\(cause, \{ source: 'module-action', phase: 'action' \}\)/);
  assert.notMatch(hostSource, /formViewCode/);
  assert.match(hostSource, /:subtitle="mainTreeScopeContext"/);
  assert.notMatch(hostSource, /<button/);
  assert.notMatch(hostSource, /@muyun\/vue-ui-antdv/);
  assert.notMatch(hostSource, /等待接入页面 bootstrap 与列表查询/);
});

it('color picker prevents every mutation path while disabled', () => {
  const colorPickerSource = readSource('src/vue-ui-antdv/components/UiColorPicker.vue');

  assert.match(colorPickerSource, /if \(props\.disabled\) \{[\s\S]*return;/);
  assert.match(colorPickerSource, /:trigger="props\.disabled \? \[\] : 'click'"/);
  assert.match(colorPickerSource, /:disabled="props\.disabled"/);
});

it('consumer surface exposes basic adapter controls for business App composition', () => {
  const consumerSource = readSource('src/consumer/index.ts');

  assert.match(consumerSource, /UiButton,[\s\S]*UiDataTable,[\s\S]*UiSidePanel,[\s\S]*UiTree,/);
  assert.match(consumerSource, /export \{ default as FileTransferUploader \}/);
  assert.notMatch(consumerSource, /export \* from '\.\.\/platform-components\/index';/);
  assert.notMatch(consumerSource, /export \* from '\.\.\/dynamic-page-runtime\/index';/);
});

it('data table keeps platform typography when embedded by a consumer App', () => {
  const tableSource = readSource('src/vue-ui-antdv/components/UiDataTable.vue');

  assert.match(
    tableSource,
    /\.ui-data-table\.is-fill-height :deep\(\.ant-spin-container\),[\s\S]*?\.ui-data-table\.is-fill-height :deep\(\.ant-table\) \{[\s\S]*?display: flex;[\s\S]*?flex-direction: column;/,
  );
  assert.match(
    tableSource,
    /\.ui-data-table\.is-fill-height :deep\(\.ant-table\) \{[\s\S]*?flex: 1 1 auto;[\s\S]*?height: auto;/,
  );
  assert.match(tableSource, /\.ui-data-table :deep\(\.ant-table\)[\s\S]*font-size: 13px/);
  assert.match(
    tableSource,
    /\.ant-table-thead > tr > th\),[\s\S]*\.ant-table-tbody > tr > td\)[\s\S]*font-size: 13px/,
  );
  assert.match(
    tableSource,
    /\.ant-table-thead > tr > th\),[\s\S]*\.ant-table-tbody > tr > td\)[\s\S]*line-height: 1\.5714285714/,
  );
});

it('record query list panel forwards dynamic ui config and query template ids', () => {
  const panelSource = readSource('src/platform-components/RecordQueryListPanel.vue');

  assert.match(panelSource, /uiConfigId\?: string/);
  assert.match(panelSource, /queryTemplateId\?: string/);
  assert.match(
    panelSource,
    /querySchema\(\{\s*uiConfigId: props\.uiConfigId,\s*queryTemplateId: props\.queryTemplateId,\s*\}\)/,
  );
  assert.match(panelSource, /request\.uiConfigId = props\.uiConfigId/);
  assert.match(panelSource, /request\.queryTemplateId = props\.queryTemplateId/);
});

it('platform error feedback respects global error presentation slots', () => {
  const feedbackSource = readSource('src/platform-components/platformErrorFeedback.ts');
  const actionResultFeedbackSource = readSource('src/platform-components/platformActionResultFeedback.ts');
  const actionResultReactionsSource = readSource('src/platform-components/platformActionResultReactions.ts');
  const uiFeedbackSource = readSource('src/vue-ui-antdv/feedback.ts');
  const staticCrudStateSource = readSource('src/platform-components/staticCrudManagementState.ts');

  assert.match(feedbackSource, /resolveGlobalErrorPresentation/);
  assert.match(feedbackSource, /toErrorUiContext/);
  assert.match(feedbackSource, /presentation\.slot === 'silent'/);
  assert.match(feedbackSource, /presentation\.slot === 'redirect-login'/);
  assert.match(feedbackSource, /presentPlatformSuccess/);
  assert.match(feedbackSource, /showSuccessMessage\(message\)/);
  assert.match(actionResultFeedbackSource, /handlePlatformActionSuccess/);
  assert.match(actionResultFeedbackSource, /presentPlatformActionSuccess/);
  assert.match(actionResultReactionsSource, /resolvePlatformActionResultMessage/);
  assert.match(actionResultReactionsSource, /withPlatformActionResultReactions/);
  assert.match(actionResultReactionsSource, /platformActionResultReactionTypes/);
  const uiStylesSource = readSource('src/vue-ui-antdv/styles.css');
  assert.match(uiFeedbackSource, /import \{ notification \} from 'ant-design-vue'/);
  assert.match(uiFeedbackSource, /export function showFeedback/);
  assert.match(uiFeedbackSource, /UiFeedbackOptions/);
  assert.match(uiFeedbackSource, /notification\[options\.tone\]/);
  assert.match(
    uiFeedbackSource,
    /options\.tone === 'error' \|\| options\.tone === 'warning' \? 'top' : 'topRight'/,
  );
  assert.match(uiFeedbackSource, /const FEEDBACK_TOP_OFFSET = '80px'/);
  assert.match(uiFeedbackSource, /notification\.config\(\{ top: FEEDBACK_TOP_OFFSET \}\)/);
  assert.match(uiFeedbackSource, /muyun-feedback-notification-\$\{options\.tone\}/);
  assert.match(uiFeedbackSource, /width: 'fit-content'/);
  assert.match(uiFeedbackSource, /DEFAULT_DURATION_SECONDS/);
  assert.match(uiFeedbackSource, /muyun-feedback-timebar/);
  assert.match(uiFeedbackSource, /showFeedback\(\{ tone: 'error', content \}\)/);
  assert.match(uiFeedbackSource, /showFeedback\(\{ tone: 'success', content \}\)/);
  assert.match(uiFeedbackSource, /showFeedback\(\{ tone: 'info', content \}\)/);
  assert.match(uiFeedbackSource, /showFeedback\(\{ tone: 'warning', content \}\)/);
  assert.match(actionResultFeedbackSource, /messageType === 'WARNING'/);
  assert.match(actionResultFeedbackSource, /showWarningMessage\(message\)/);
  assert.match(uiStylesSource, /\.muyun-feedback-content/);
  assert.match(uiStylesSource, /\.ant-notification-notice\.muyun-feedback-notification/);
  assert.match(uiStylesSource, /transform-origin: right/);
  assert.match(uiStylesSource, /--muyun-danger-text/);
  assert.match(uiStylesSource, /--muyun-success-text/);
  assert.match(uiStylesSource, /muyun-feedback-notification-error \.ant-notification-notice-icon/);
  assert.match(uiStylesSource, /muyun-feedback-notification-success \.ant-notification-notice-icon/);
  assert.match(uiStylesSource, /muyun-feedback-notification-warning \.ant-notification-notice-icon/);
  assert.match(uiStylesSource, /muyun-feedback-notification-info \.ant-notification-notice-icon/);
  assert.match(uiStylesSource, /muyun-feedback-notification-warning \.muyun-feedback-timebar/);
  assert.match(uiStylesSource, /muyun-feedback-notification-info \.muyun-feedback-timebar/);
  assert.match(uiStylesSource, /inset-inline: 0/);
  assert.match(uiStylesSource, /muyun-feedback-notification \.ant-notification-notice-icon/);
  assert.match(uiStylesSource, /font-size: 16px/);
  assert.match(uiStylesSource, /align-items: center/);
  assert.match(uiStylesSource, /gap: 8px/);
  assert.match(uiStylesSource, /min-height: 24px/);
  assert.match(uiStylesSource, /top: 50%/);
  assert.match(uiStylesSource, /translateY\(-50%\)/);
  assert.match(uiStylesSource, /muyun-feedback-notification:hover \.muyun-feedback-timebar/);
  assert.match(uiStylesSource, /transform: scaleX\(1\)/);
  assert.match(uiStylesSource, /animation: none/);
  assert.match(uiStylesSource, /transition: transform 280ms ease-out/);
  assert.match(uiStylesSource, /font-size: 13px/);
  assert.match(uiStylesSource, /@keyframes muyun-feedback-countdown/);
  assert.match(staticCrudStateSource, /handlePlatformActionSuccess/);
});

it('production workbench delegates page lifetime to the Vue Router outlet', () => {
  const appSource = readSource('src/App.vue');
  const workbenchSource = readSource('src/platform-workbench/Workbench.vue');

  assert.match(workbenchSource, /const openedTabs = computed\(\(\) => props\.startup\?\.tabs \?\? \[\]\)/);
  assert.notMatch(workbenchSource, /UiSidePanelHost/);
  assert.notMatch(workbenchSource, /shouldKeepTabMounted|tabHostKey/);
  assert.notMatch(workbenchSource, /<template v-for="tab in openedTabs"/);
  assert.match(workbenchSource, /<div v-else-if="activeTab" class="tab-panel-host">[\s\S]*?<slot/);
  assert.match(workbenchSource, /\.tab-panel-host \{[\s\S]*height: 100%;[\s\S]*min-height: 0;/);
  assert.match(appSource, /<RouterView v-slot="\{ Component, route \}">/);
  assert.match(appSource, /<KeepAlive>[\s\S]*<StaticRoutePageHost/);
  assert.match(appSource, /:key="pageCacheKey\(route, activeTabKey\)"/);
  assert.match(appSource, /:refresh-revision="pageRefreshRevisionFor\(activeTabKey\)"/);
  assert.match(workbenchSource, /emit\('refreshPage', activeTabKey\.value\)/);
  assert.notMatch(workbenchSource, /activePageContentKey|pageRefreshRevision/);
  assert.notMatch(appSource, /PlatformAdminRouteOutlet|WorkbenchOutlet/);
});

it('pages own their drawer containers and fixed drawer action regions', () => {
  const uiIndexSource = readSource('src/vue-ui-antdv/index.ts');
  const sidePanelSource = readSource('src/vue-ui-antdv/components/UiSidePanel.vue');
  const sidePanelHostSource = readSource('src/vue-ui-antdv/components/UiSidePanelHost.vue');
  const dynamicWorkspaceDetailSource = readSource(
    'src/dynamic-page-runtime/DynamicModuleWorkspaceDetailView.vue',
  );
  const detailDrawerSource = readSource('src/platform-components/RecordDetailDrawer.vue');
  const workspaceViewOutletSource = readSource('src/platform-workbench/WorkspaceViewOutlet.vue');
  const workspaceViewContributionsSource = readSource(
    'src/platform-admin-runtime/workspaceViewContributions.ts',
  );
  const workspaceViewsSource = readSource('src/platform-workbench/workspaceViews.ts');
  const viewPromotionSource = readSource('src/platform-admin-runtime/useWorkspaceViewPromotion.ts');
  const userSource = [
    readSource('src/views/UserManagementView.vue'),
    readSource('src/views/UserManagementListView.vue'),
    readSource('src/views/UserDetailRouteView.vue'),
  ].join('\n');
  const userDetailContentSource = readSource('src/views/UserDetailContent.vue');
  const roleSource = readSource('src/views/RoleManagementView.vue');
  const roleAccountGrantSource = readSource('src/views/RoleAccountGrantDrawer.vue');
  const roleEmploymentGrantSource = readSource('src/views/RoleEmploymentGrantDrawer.vue');

  assert.match(uiIndexSource, /UiSidePanelHost/);
  assert.match(sidePanelSource, /scope: 'tab'/);
  assert.match(sidePanelSource, /props\.scope === 'viewport'/);
  assert.match(sidePanelSource, /sidePanelHost\?\.value \?\? false/);
  assert.match(sidePanelHostSource, /position: relative/);
  assert.match(dynamicWorkspaceDetailSource, /const workspaceElement = ref<HTMLElement>\(\)/);
  assert.match(dynamicWorkspaceDetailSource, /<section ref="workspaceElement"/);
  assert.match(dynamicWorkspaceDetailSource, /:container="workspaceElement \?\? null"/);
  assert.match(detailDrawerSource, /promotion\?: DrawerPromotion/);
  assert.match(detailDrawerSource, /promotion\.promote\(\)/);
  assert.match(detailDrawerSource, /<template #title-actions>/);
  assert.match(detailDrawerSource, /icon-name="export"/);
  assert.match(workspaceViewOutletSource, /resolveWorkspaceView/);
  assert.match(workspaceViewOutletSource, /provideWorkspaceViewHost/);
  assert.match(workspaceViewOutletSource, /dismissWorkspaceViewDescriptor/);
  assert.match(workspaceViewOutletSource, /tabKeyOf\(props\.descriptor\)/);
  assert.match(workspaceViewOutletSource, /navigation\.replacePage\(ownerPageKey\.value/);
  assert.match(workspaceViewsSource, /createWorkspaceViewDescriptor/);
  assert.match(workspaceViewsSource, /createWorkspaceViewRegistry/);
  assert.match(workspaceViewsSource, /重复的工作视图类型/);
  assert.match(workspaceViewsSource, /configureWorkspaceViewContributions/);
  assert.match(workspaceViewContributionsSource, /Application assembly for restorable workspace views/);
  assert.match(viewPromotionSource, /useWorkspaceViewPromotion/);
  assert.match(viewPromotionSource, /canPromoteWorkspaceView/);
  assert.match(viewPromotionSource, /hasStableIdentity/);
  assert.match(viewPromotionSource, /navigation\.openPage\([\s\S]*createWorkspaceViewDescriptor/);
  assert.match(viewPromotionSource, /onPromotionRejected/);
  assert.match(viewPromotionSource, /accepted === false/);
  assert.match(viewPromotionSource, /title\?: MaybeRefOrGetter<string \| undefined>/);
  assert.match(workspaceViewOutletSource, /setTitle\(title\)/);
  assert.match(
    readSource('src/platform-admin-runtime/PlatformAdminOutlet.vue'),
    /workspaceViewPresentation === 'drawer'/,
  );
  assert.match(userSource, /useWorkbenchNavigation/);
  assert.match(userSource, /navigation\?\.openRoute\('/);
  assert.match(userSource, /navigation\?\.replaceRoute\(`/);
  assert.match(userSource, /navigation\?\.closeCurrentTab\('/);
  assert.match(userSource, /const detailActions = computed<RecordActionItem\[\]>/);
  assert.match(userSource, /<UserDetailContent/);
  assert.match(userSource, /<RecordDetailPanel :title="userDetailTitle"/);
  assert.match(userDetailContentSource, /defineOptions\(\{ name: 'UserDetailContent' \}\)/);
  assert.notMatch(userSource, /userDetailHeaderActions/);
  assert.match(roleSource, /roleDetailOperationActions/);
  assert.notMatch(roleSource, /roleDetailHeaderActions/);
  assert.match(roleAccountGrantSource, /<template v-if="!embedded" #operation>/);
  assert.match(roleEmploymentGrantSource, /<template v-if="!embedded" #operation>/);
});

it('public management and drawer contracts use business roles instead of layout positions', () => {
  const layoutSource = readSource('src/platform-components/StaticManagementLayout.vue');
  const recordDetailDrawerSource = readSource('src/platform-components/RecordDetailDrawer.vue');
  const recordModeDrawerSource = readSource('src/platform-components/RecordModeDrawer.vue');
  const standardDrawerSources = [
    readSource('src/views/UserDetailRouteView.vue'),
    readSource('src/views/RoleManagementView.vue'),
    readSource('src/views/RoleAccountGrantDrawer.vue'),
    readSource('src/views/RoleEmploymentGrantDrawer.vue'),
  ];

  assert.match(layoutSource, /explorerTitle: string/);
  assert.match(layoutSource, /detailTitle: string/);
  assert.match(layoutSource, /update:explorerSearchKeyword/);
  assert.match(layoutSource, /<slot name="explorer-actions" \/>/);
  assert.match(layoutSource, /<slot name="detail-actions" \/>/);
  assert.notMatch(layoutSource, /sidebarTitle|cardTitle|sidebar-actions|card-actions|card-status/);
  assert.notMatch(recordDetailDrawerSource, /<slot name="actions" \/>/);
  assert.notMatch(recordModeDrawerSource, /<slot name="actions" \/>/);
  for (const source of standardDrawerSources) {
    assert.match(source, /<template(?: v-if="!embedded")? #operation>/);
  }
});

it('platform account-role binding selects a target tenant before loading or saving candidates', () => {
  const drawerSource = readSource('src/views/RoleAccountGrantDrawer.vue');
  const grantClientSource = readSource('src/views/roleGrantClient.ts');

  assert.match(
    drawerSource,
    /const needsTargetTenant = computed\(\(\) => props\.role\?\.ownerScopeType === 'platform'\)/,
  );
  assert.match(drawerSource, /if \(!bindingReady\.value\) \{[\s\S]*clearBindingData\(\);/);
  assert.match(drawerSource, /<RecordPicker[\s\S]*placeholder="请选择角色下发的目标租户"/);
  assert.match(drawerSource, /targetTenantId: targetTenantId\.value/);
  assert.match(
    grantClientSource,
    /\/iam\.role\/\$\{encodeURIComponent\(roleId\)\}\/account-role-candidates\/query/,
  );
  assert.notMatch(grantClientSource, /\/iam\.user\/account-role-candidates\/query/);
});

it('record lists reuse their existing region for recycle-bin data and lifecycle actions', () => {
  const panelSource = readSource('src/platform-components/RecordQueryListPanel.vue');
  const explorerSource = readSource('src/platform-components/CrudRecordListExplorer.vue');
  const recycleBinButtonSource = readSource('src/platform-components/RecycleBinModeButton.vue');
  const explorerItemSource = readSource('src/vue-ui-antdv/components/UiRecordExplorerItem.vue');
  const explorerPanelSource = readSource('src/platform-components/RecordExplorerPanel.vue');
  const staticLayoutSource = readSource('src/platform-components/StaticManagementLayout.vue');
  const hostSource = readSource('src/dynamic-page-runtime/ModulePageHost.vue');
  const listSessionSource = readSource('src/dynamic-page-runtime/composables/useModulePageListSession.ts');
  const recycleBinModeSource = readSource('src/platform-components/useRecycleBinExplorerMode.ts');
  const editingSessionSource = readSource('src/dynamic-page-runtime/composables/useRecordEditingSession.ts');
  const indexSource = readSource('src/platform-components/index.ts');

  assert.match(panelSource, /export type RecordQueryListMode = 'normal' \| 'recycleBin'/);
  assert.match(panelSource, /mode\?: RecordQueryListMode/);
  assert.match(panelSource, /hasRecycleBinAbility\(props\.context\)/);
  assert.match(
    panelSource,
    /canQueryRecycleBinAvailable = computed\(\(\) => canQueryRecycleBin\(props\.context\)\)/,
  );
  assert.match(panelSource, /refreshRecycleBinSummary\(\)/);
  assert.match(panelSource, /if \(canQueryRecycleBinAvailable\.value\)/);
  assert.match(panelSource, /<footer[\s\S]*class="record-query-list-pagination"[\s\S]*recycleBinEnabled/);
  assert.match(panelSource, /record-query-list-pagination-controls/);
  assert.match(panelSource, /RecycleBinModeButton/);
  assert.match(recycleBinButtonSource, /props\.hasRecords === true/);
  assert.match(recycleBinButtonSource, /count\?: number/);
  assert.match(recycleBinButtonSource, /class="recycle-bin-mode-badge"/);
  assert.match(recycleBinButtonSource, /visualState === 'expression' && props\.count > 0/);
  assert.match(recycleBinButtonSource, /border-color: var\(--muyun-border-subtle\)/);
  assert.match(recycleBinButtonSource, /'standard' \| 'expression' \| 'selected'/);
  assert.match(recycleBinButtonSource, /is-expression/);
  assert.match(recycleBinButtonSource, /is-selected/);
  assert.match(recycleBinButtonSource, /border-color: var\(--muyun-danger-border\)/);
  assert.match(recycleBinButtonSource, /min-width: 14px/);
  assert.match(recycleBinButtonSource, /font-size: 9px/);
  assert.match(recycleBinButtonSource, /:danger="visualState === 'selected'"/);
  assert.match(recycleBinButtonSource, /visualState === 'selected' \? 'reload' : 'delete'/);
  assert.notMatch(panelSource, /record-query-list-actions">[\s\S]{0,320}recycleBinEnabled/);
  assert.match(panelSource, /emit\('modeChange', mode === 'normal' \? 'recycleBin' : 'normal'\)/);
  assert.match(panelSource, /key: 'restore',[\s\S]*actionCode: 'recycleBinRestore'/);
  assert.match(panelSource, /item\.purgeable/);
  assert.match(panelSource, /key: 'purge', actionCode: 'recycleBinPurge'/);
  assert.match(panelSource, /function handleTableRowClick[\s\S]*emit\('select', row\.record\)/);
  assert.match(hostSource, /useModulePageListSession\(/);
  assert.match(listSessionSource, /const listMode = ref<RecordQueryListMode>\('normal'\)/);
  assert.match(hostSource, /:mode="listMode"/);
  assert.match(hostSource, /@mode-change="handleListModeChange"/);
  assert.match(hostSource, /@restored="handleRecycleBinRestore"/);
  assert.match(explorerSource, /export type CrudRecordListMode = 'normal' \| 'recycleBin'/);
  assert.match(explorerSource, /hasRecycleBinAbility\(props\.context\)/);
  assert.match(explorerSource, /if \(canQueryRecycleBin\(props\.context\)\)/);
  assert.match(explorerSource, /props\.mode === 'recycleBin'/);
  assert.match(explorerSource, /const requestSeq = \+\+recordsRequestSeq/);
  assert.match(explorerSource, /requestSeq !== recordsRequestSeq/);
  assert.match(explorerSource, /key: 'restore'/);
  assert.match(explorerSource, /showLabel: true/);
  assert.match(explorerSource, /disabledReason: recycleBinRestoreUnavailableReason\(item\)/);
  assert.match(explorerSource, /recycleBinState\.restore\(item, false\)/);
  assert.match(hostSource, /openRecycleBinRecord/);
  assert.match(editingSessionSource, /\/recycle-bin\/view\/\$\{encodeURIComponent\(id\)\}/);
  assert.match(hostSource, /const recycleBinDetailActive = computed/);
  assert.match(explorerItemSource, /action\.showLabel \? action\.title : actionFallbackLabel\(action\)/);
  assert.match(explorerItemSource, /action\.disabledReason \?\? action\.title/);
  assert.match(recycleBinModeSource, /hasRecycleBinAbility\(toValue\(options\.context\)\)/);
  assert.match(recycleBinModeSource, /canQueryRecycleBin\(toValue\(options\.context\)\)/);
  assert.match(recycleBinModeSource, /options\.resetSelection\?\.\(\)/);
  assert.match(explorerPanelSource, /<slot name="footer" \/>/);
  assert.match(staticLayoutSource, /<slot name="explorer-footer" \/>/);
  assert.notMatch(indexSource, /RecycleBinPanel/);
});

function readSource(path: string) {
  return readFileSync(resolve(root, path), 'utf8');
}

function viewSources() {
  const viewsDir = resolve(root, 'src/views');
  return readdirSync(viewsDir)
    .filter((file) => file.endsWith('.vue') || file.endsWith('.ts'))
    .map((file) => [file, readFileSync(join(viewsDir, file), 'utf8')] as const);
}

function matchCount(source: string, pattern: RegExp) {
  return source.match(pattern)?.length ?? 0;
}
