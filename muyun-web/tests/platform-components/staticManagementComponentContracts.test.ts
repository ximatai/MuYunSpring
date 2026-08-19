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
    /\.management-panel-header-title,[\s\S]*?\.management-panel-header-title-action \{[\s\S]*?display: inline-flex;[\s\S]*?align-items: center;/,
  );
  assert.notMatch(headerSource, /record-status-switch-offset-y/);
  assert.notMatch(statusSwitchSource, /translateY\(/);
  assert.match(layoutSource, /<RecordDetailPanel[\s\S]*<slot name="detail-status"/);
  assert.match(workspaceSource, /--muyun-management-panel-padding-block/);
});

it('record metadata uses semantic text colors so dark skins preserve hierarchy', () => {
  const metaSource = readSource('src/platform-components/RecordMetaSection.vue');

  assert.match(metaSource, /\.record-meta h3[\s\S]*color: var\(--muyun-support-text\)/);
  assert.match(metaSource, /dt \{[\s\S]*color: var\(--muyun-support-text-muted\)/);
  assert.match(metaSource, /dd \{[\s\S]*color: var\(--muyun-support-text-body\)/);
  assert.notMatch(metaSource, /#334155|#64748b|#243447/);
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
  const employeeDetailContentSource = readSource('src/views/EmployeeDetailContent.vue');

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
  assert.match(employeeDetailContentSource, /RecordExternalChangeNotice/);
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

  const systemUserSource = readSource('src/views/SystemUserManagementView.vue');
  const employmentContractsSource = readSource('src/web-contracts/index.ts');
  assert.match(systemUserSource, /usePageRecordExternalChange\(\{\s*moduleAlias: 'iam\.user'/);
  assert.match(systemUserSource, /:externally-changed="userExternalChange\.externallyChanged\.value"/);
  assert.match(systemUserSource, /@reload-external-change="reloadExternalUserChange"/);
  assert.match(systemUserSource, /@dismiss-external-change="userExternalChange\.clearExternalChanged"/);
  assert.match(
    systemUserSource,
    /usePageRecordExternalChange\(\{[\s\S]*recordId: \(\) => selectedUser\.value\?\.id[\s\S]*editing: \(\) => detailMode\.value === 'edit'[\s\S]*saving: \(\) => savingUser\.value/,
  );
  assert.match(systemUserSource, /code: platformErrorCodes\.conflictVersion/);
  assert.match(systemUserSource, /userExternalChange\.markExternalRecordChanged\(record\.id\)/);

  const employeeSource = [
    readSource('src/views/EmployeeManagementView.vue'),
    readSource('src/views/EmployeeDetailContent.vue'),
  ].join('\n');
  assert.match(employeeSource, /RecordExternalChangeNotice/);
  assert.match(employeeSource, /usePageRecordExternalChange\(\{\s*moduleAlias: 'iam\.employee'/);
  assert.match(employeeSource, /v-if="props\.externallyChanged"/);
  assert.match(employeeSource, /@reload-external="reloadExternalEmployeeChange"/);
  assert.match(employeeSource, /@dismiss-external="employeeExternalChange\.clearExternalChanged"/);
  assert.match(
    employeeSource,
    /usePageRecordExternalChange\(\{[\s\S]*recordId: \(\) => selectedEmployee\.value\?\.id[\s\S]*editing: \(\) => employeeDetailMode\.value === 'edit'[\s\S]*saving: \(\) => savingEmployee\.value/,
  );
  assert.match(employeeSource, /code: platformErrorCodes\.conflictVersion/);
  assert.match(employeeSource, /employeeExternalChange\.markExternalRecordChanged\(record\.id\)/);
  assert.match(employeeSource, /useRealtimeRefreshQueue<string>\(\{/);
  assert.match(employeeSource, /usePageDataChange\(\{\s*moduleAlias: 'iam\.employee'/);
  assert.match(employeeSource, /employeeRealtimeRefreshQueue\.enqueue/);
  assert.match(employeeSource, /employeeReloadKey\.value \+= 1/);
  const viewTemplateStart = employeeSource.indexOf(`<template v-if="employeeDetailMode === 'view'">`);
  const editTemplateStart = employeeSource.indexOf('<template v-else>', viewTemplateStart);
  const viewTemplate = employeeSource.slice(viewTemplateStart, editTemplateStart);
  const editTemplate = employeeSource.slice(editTemplateStart);
  assert.match(employeeSource, /import EmployeeEmploymentDrawer from '.\/EmployeeEmploymentDrawer\.vue'/);
  assert.match(employeeSource, /:extra-row-actions-of="employeeExtraRowActionsOf"/);
  assert.match(employeeSource, /key: 'employment'[\s\S]*title: '任职'/);
  assert.match(employeeSource, /<EmployeeEmploymentDrawer[\s\S]*@saved="handleEmployeeEmploymentSaved"/);
  assert.notMatch(editTemplate, /任职管理/);
  const employmentDrawerSource = readSource('src/views/EmployeeEmploymentDrawer.vue');
  assert.match(employmentDrawerSource, /function updateOrganization[\s\S]*const changed =/);
  assert.match(employmentDrawerSource, /departmentId: changed \? undefined : draft\.value\.departmentId/);
  assert.match(
    employmentDrawerSource,
    /const departmentContext = computed\([\s\S]*scopeFieldName: 'organizationId'/,
  );
  assert.match(employmentDrawerSource, /<RecordPicker[\s\S]*:context="organizationContext"/);
  assert.match(employmentDrawerSource, /<RecordPicker[\s\S]*:context="departmentContext"/);
  assert.match(employmentDrawerSource, /<RecordPicker[\s\S]*:context="positionContext"[\s\S]*mode="list"/);
  assert.match(employmentDrawerSource, /:constraints="\[enabledOnly\(\)\]"/);
  assert.match(employmentDrawerSource, /function updatePrimary[\s\S]*主岗必须与职员主机构、主部门一致/);
  assert.match(employmentDrawerSource, /draft\.value = \{ \.\.\.row \}/);
  assert.match(
    employmentContractsSource,
    /export interface EmploymentSelectorItem \{\s*id: string;\s*version\?: number;/,
  );
  assert.match(employmentDrawerSource, /<UiDataTable[\s\S]*horizontal-scroll[\s\S]*show-action-column/);
  assert.match(employmentDrawerSource, /action-column-width="92"/);
  assert.match(employmentDrawerSource, /<UiDropdown[\s\S]*trigger="hover"/);
  assert.notMatch(employmentDrawerSource, /<ReferenceSelect/);
  const recordPickerSource = readSource('src/platform-components/RecordPicker.vue');
  assert.match(recordPickerSource, /if \(props\.mode === 'list'\)[\s\S]*await loadListRecords\(\)/);
  assert.match(recordPickerSource, /props\.context\.crud\.query/);
  assert.match(recordPickerSource, /record\.enabled === false \|\|\s*Boolean\(firstConstraintMessage/);
  const recordMultiPickerSource = readSource('src/platform-components/RecordMultiPicker.vue');
  assert.match(recordMultiPickerSource, /record\.enabled === false \|\|\s*Boolean\(firstConstraintMessage/);
  assert.notMatch(viewTemplate, /任职管理/);
});

it('standard module runner waits for a complete detail and action availability before enabling mutations', () => {
  const hostSource = readSource('src/dynamic-page-runtime/DynamicModuleHost.vue');
  const detailActionsSource = readSource('src/dynamic-page-runtime/DynamicRecordDetailActions.vue');
  const detailControllerSource = readSource('src/dynamic-page-runtime/recordDetailController.ts');

  assert.match(hostSource, /import \{ useRecordDetailController \} from '.\/recordDetailController'/);
  assert.match(hostSource, /const detail = useRecordDetailController<QueryListRecord>\(\)/);
  assert.match(hostSource, /loading: detailLoading/);
  assert.match(hostSource, /loadFailed: detailLoadFailed/);
  assert.match(hostSource, /const requestSequence = \+\+detailLoadSequence/);
  assert.match(hostSource, /detail\.beginLoad\(record, mode\)/);
  assert.match(hostSource, /await context\.crud\.view\(id\)/);
  assert.match(hostSource, /shouldCommitDynamicModuleDetailRequest/);
  assert.match(hostSource, /detail\.failLoad\(\)/);
  assert.match(hostSource, /function retryLoadDetail\(\)/);
  assert.match(hostSource, /:loading="detailLoading"/);
  assert.match(hostSource, /:load-failed="detailLoadFailed"/);
  assert.match(hostSource, /@retry="retryLoadDetail"/);
  assert.match(hostSource, /canMutateDynamicModuleDetail/);
  assert.match(hostSource, /<DynamicRecordDetailActions/);
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
  const hostSource = readSource('src/dynamic-page-runtime/DynamicModuleHost.vue');

  assert.match(hostSource, /navigatorLevels = ref<NavigatorLevelRuntime\[\]>/);
  assert.match(hostSource, /selectedNavigatorRecords/);
  assert.match(hostSource, /function selectNavigatorRecord/);
  assert.match(hostSource, /function navigatorExplorerQueryValues/);
  assert.match(hostSource, /v-for="level in visibleNavigatorLevels"/);
  assert.match(hostSource, /:external-query-values="navigatorExplorerQueryValues\(level\.descriptor\.key\)"/);
});

it('static edit draft normalizers preserve standard record fields', () => {
  const userSource = readSource('src/views/UserManagementView.vue');
  const systemUserSource = readSource('src/views/SystemUserManagementView.vue');
  const employeeSource = readSource('src/views/EmployeeManagementView.vue');
  const roleSource = readSource('src/views/RoleManagementView.vue');
  const tenantStateSource = readSource('src/views/tenantManagementState.ts');
  const menuStateSource = readSource('src/views/menuManagementState.ts');
  const positionStateSource = readSource('src/views/positionManagementState.ts');
  const dictionaryStateSource = readSource('src/views/dictionaryManagementState.ts');

  assert.match(userSource, /function normalizedUserDraft[\s\S]*normalizeRecordDraft<UserAccount>\(draft,/);
  assert.match(
    systemUserSource,
    /function normalizedSystemUserDraft[\s\S]*normalizeRecordDraft<UserAccount>\(draft,/,
  );
  assert.match(
    employeeSource,
    /function normalizedEmployeeDraft[\s\S]*normalizeRecordDraft<Employee>\(draft,/,
  );
  assert.match(roleSource, /function normalizedRoleDraft[\s\S]*normalizeRecordDraft<Role>\(draft,/);
  assert.match(tenantStateSource, /function normalizedDraft[\s\S]*return \{\s*\.\.\.record,/);
  assert.match(menuStateSource, /function normalizeSchemeDraft[\s\S]*return \{\s*\.\.\.record,/);
  assert.match(
    menuStateSource,
    /function normalizeMenuDraft[\s\S]*const normalized: MenuRecord = \{\s*\.\.\.record,/,
  );
  assert.match(positionStateSource, /function normalizePositionDraft[\s\S]*return \{\s*\.\.\.record,/);
  assert.match(positionStateSource, /function normalizeCategoryDraft[\s\S]*return \{\s*\.\.\.record,/);
  assert.match(
    dictionaryStateSource,
    /function normalizeDictionaryCategoryDraft[\s\S]*return \{\s*\.\.\.record,/,
  );
  assert.match(
    dictionaryStateSource,
    /function normalizeDictionaryItemDraft[\s\S]*return \{\s*\.\.\.record,/,
  );
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
  const positionViewSource = readSource('src/views/PositionManagementView.vue');
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
  assert.match(positionViewSource, /<ManagementWorkspace[\s\S]*:explorer-count="canBrowseTenants \? 3 : 2"/);
  const employeeViewSource = [
    readSource('src/views/EmployeeManagementView.vue'),
    readSource('src/views/EmployeeDetailContent.vue'),
  ].join('\n');
  assert.match(
    employeeViewSource,
    /<ManagementWorkspace[\s\S]*v-if="!isWorkspaceView \|\| isDrawerWorkspaceView"[\s\S]*class="employee-management-page"/,
  );
  assert.match(employeeViewSource, /<ManagementExplorerColumn>[\s\S]*employee-scope-panel/);
  assert.notMatch(employeeViewSource, /grid-template-columns: minmax\(260px, 320px\)/);
  assert.notMatch(positionViewSource, /management-workspace-explorer/);
  assert.notMatch(positionViewSource, /position-workspace-system/);
});

it('system user management fills the constrained work area and leaves scrolling to its list panel', () => {
  const systemUserSource = readSource('src/views/SystemUserManagementView.vue');
  const routesSource = readSource('src/platform-admin-runtime/platformAdminRoutes.ts');

  assert.match(
    systemUserSource,
    /\.system-user-management-page \{[\s\S]*height: 100%;[\s\S]*min-height: 0;[\s\S]*overflow: hidden;/,
  );
  assert.match(
    systemUserSource,
    /@media \(max-width: 980px\) \{[\s\S]*\.system-user-management-page \{[\s\S]*height: auto;[\s\S]*overflow: visible;/,
  );
  assert.match(routesSource, /route: '\/iam\/system-users'[\s\S]*layout: 'workspace'/);
});

it('user management fills the constrained work area and leaves scrolling to its scope and list panels', () => {
  const userSource = readSource('src/views/UserManagementView.vue');
  const routesSource = readSource('src/platform-admin-runtime/platformAdminRoutes.ts');

  assert.match(
    userSource,
    /\.user-management-page \{[\s\S]*height: 100%;[\s\S]*min-height: 0;[\s\S]*overflow: hidden;/,
  );
  assert.match(
    userSource,
    /@media \(max-width: 980px\) \{[\s\S]*\.user-management-page \{[\s\S]*height: auto;[\s\S]*overflow: visible;/,
  );
  assert.match(routesSource, /route: '\/iam\/users'[\s\S]*layout: 'workspace'/);
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

it('menu management keeps scheme actions inline and delegates search to panel', () => {
  const menuViewSource = readSource('src/views/MenuManagementView.vue');
  const contractsSource = readSource('src/web-contracts/index.ts');
  const schemePanelStart = menuViewSource.indexOf('title="菜单方案"');
  const menuTreePanelStart = menuViewSource.indexOf('title="菜单树"');
  const schemePanelSource = menuViewSource.slice(schemePanelStart, menuTreePanelStart);
  const menuTreePanelSource = menuViewSource.slice(menuTreePanelStart);

  assert.match(menuViewSource, /function schemeActionsOf/);
  assert.match(menuViewSource, /function schemeItemOf/);
  assert.match(menuViewSource, /function handleSchemeInlineAction/);
  assert.match(schemePanelSource, /:item-of="schemeItemOf"/);
  assert.match(schemePanelSource, /@action="handleSchemeInlineAction"/);
  assert.match(schemePanelSource, /:filter-option="schemeFilterOption"/);
  assert.notMatch(schemePanelSource, /title="编辑菜单方案"/);
  assert.notMatch(schemePanelSource, /title="删除菜单方案"/);
  assert.match(menuTreePanelSource, /search-mode="none"/);
  assert.match(menuTreePanelSource, /search-trigger="external"/);
  assert.match(contractsSource, /export interface MenuRecord extends StandardEnabledTreeEntity/);
});

it('static management explorers use unified item descriptors', () => {
  const explorerViews = [
    'TenantManagementView.vue',
    'PositionManagementView.vue',
    'DictionaryManagementView.vue',
    'MenuManagementView.vue',
    'EmployeeManagementView.vue',
    'UserManagementView.vue',
    'RoleManagementView.vue',
  ];

  for (const fileName of explorerViews) {
    const source = readSource(`src/views/${fileName}`);
    assert.match(source, /RecordExplorerItemDescriptor/, fileName);
    assert.match(source, /:item-of=/, fileName);
  }

  const dictionarySource = readSource('src/views/DictionaryManagementView.vue');
  const menuSource = readSource('src/views/MenuManagementView.vue');
  const positionSource = readSource('src/views/PositionManagementView.vue');

  assert.notMatch(dictionarySource, /:tag-of=|:actions-of=|:muted-of=/);
  assert.notMatch(menuSource, /:tag-of=|:actions-of=/);
  assert.notMatch(positionSource, /:actions-of=/);
});

it('tree explorer editor is explicit edit mode instead of selected record presence', () => {
  const positionViewSource = readSource('src/views/PositionManagementView.vue');
  const dictionaryViewSource = readSource('src/views/DictionaryManagementView.vue');

  assert.match(
    positionViewSource,
    /categoryEditorVisible = computed\(\(\) => categoryMode\.value !== 'view'\)/,
  );
  assert.match(
    dictionaryViewSource,
    /categoryEditorVisible = computed\(\(\) => categoryMode\.value !== 'view'\)/,
  );
  assert.notMatch(positionViewSource, /categoryEditorVisible[\s\S]*Boolean\(selectedCategory/);
  assert.notMatch(dictionaryViewSource, /categoryEditorVisible[\s\S]*Boolean\(selectedCategory/);
});

it('menu entry low-code fields are only exposed for dynamic module entries', () => {
  const menuViewSource = readSource('src/views/MenuManagementView.vue');

  assert.match(menuViewSource, /<label v-if="isDynamicModuleEntry"[\s\S]*页面模式/);
  assert.match(menuViewSource, /<label v-if="isDynamicModuleEntry"[\s\S]*默认 UI 配置/);
  assert.match(menuViewSource, /<label v-if="isDynamicModuleEntry"[\s\S]*默认查询模板/);
  assert.match(menuViewSource, /<label v-if="isDynamicModuleEntry" class="full-row"[\s\S]*入口参数 JSON/);
  assert.notMatch(menuViewSource, /<label v-if="hasModuleEntry" class="full-row"[\s\S]*入口参数 JSON/);
});

it('application scope switcher is a platform component for scoped management pages', () => {
  const indexSource = readSource('src/platform-components/index.ts');
  const switcherSource = readSource('src/platform-components/ApplicationScopeSwitcher.vue');
  const dictionaryViewSource = readSource('src/views/DictionaryManagementView.vue');

  assert.match(indexSource, /ApplicationScopeSwitcher/);
  assert.match(indexSource, /createStaticTreeResourceModuleContext/);
  assert.match(switcherSource, /defineOptions\(\{ name: 'ApplicationScopeSwitcher' \}\)/);
  assert.match(switcherSource, /UiDropdown/);
  assert.match(switcherSource, /:selected-key="String\(value \?\? ''\)"/);
  assert.match(switcherSource, /align="start"/);
  assert.match(dictionaryViewSource, /<ApplicationScopeSwitcher/);
  assert.match(dictionaryViewSource, /createStaticTreeResourceModuleContext/);
  assert.notMatch(dictionaryViewSource, /function fallbackCategoryClient/);
  assert.notMatch(dictionaryViewSource, /function fallbackItemClient/);
  assert.notMatch(dictionaryViewSource, /class="application-scope-select"/);
});

it('dictionary item explorer uses tree explorer for tree-backed items', () => {
  const dictionaryViewSource = readSource('src/views/DictionaryManagementView.vue');

  assert.equal(matchCount(dictionaryViewSource, /<TreeRecordExplorer/g), 2);
  assert.notMatch(dictionaryViewSource, /RecordListExplorer/);
  assert.match(dictionaryViewSource, /v-else-if="!canTreeItem"/);
  assert.match(dictionaryViewSource, /function itemTreeActionsOf/);
  assert.match(dictionaryViewSource, /startCreateChildItem\(record\)/);
});

it('dictionary item parent selector uses tree-aware record picker', () => {
  const dictionaryViewSource = readSource('src/views/DictionaryManagementView.vue');
  const pickerSource = readSource('src/platform-components/RecordPicker.vue');

  assert.match(dictionaryViewSource, /:context="itemExplorerContext"/);
  assert.match(dictionaryViewSource, /:reload-key="itemReloadKey"/);
  assert.match(dictionaryViewSource, /parentRecordConstraints\(itemDraft\.value\.id\)/);
  assert.match(dictionaryViewSource, /itemFormPickerConfigs/);
  assert.match(dictionaryViewSource, /:picker-configs="itemFormPickerConfigs"/);
  assert.notMatch(dictionaryViewSource, /itemParentOptions/);
  assert.notMatch(dictionaryViewSource, /<RecordPicker[\s\S]*v-model:value="itemDraft\.parentId"/);
  assert.match(pickerSource, /reloadKey\?: number/);
  assert.match(pickerSource, /props\.context, props\.mode, props\.reloadKey/);
  assert.match(pickerSource, /\(\) => void loadRecords\(\)/);
});

it('dictionary management uses record form fields for category and item forms', () => {
  const dictionaryViewSource = readSource('src/views/DictionaryManagementView.vue');

  assert.equal(matchCount(dictionaryViewSource, /<RecordFormFields/g), 2);
  assert.match(dictionaryViewSource, /onMounted\(loadDictionaryFormDefinitions\)/);
  assert.match(dictionaryViewSource, /resolveRecordFormFields\(runtimeContext\.uiDescriptor\)/);
  assert.match(
    dictionaryViewSource,
    /resolveRecordFormFields\(\s*runtimeContext\.uiDescriptor,\s*ITEM_RESOURCE,?\s*\)/,
  );
  assert.match(dictionaryViewSource, /const ITEM_RESOURCE = 'item'/);
  assert.match(dictionaryViewSource, /categoryFormFieldDefinitions/);
  assert.match(dictionaryViewSource, /itemFormFieldDefinitions/);
  assert.match(dictionaryViewSource, /:field-names="itemFormFieldNames"/);
  assert.match(dictionaryViewSource, /:fields="itemFormFieldDefinitions"/);
  assert.match(dictionaryViewSource, /categoryKind: \{[\s\S]*controlType: 'select'/);
  assert.match(dictionaryViewSource, /enabled: \{[\s\S]*controlType: 'enabledStatus'/);
  assert.match(dictionaryViewSource, /itemFormFieldFallback/);
  assert.match(dictionaryViewSource, /parentId: \{[\s\S]*controlType: 'recordPicker'/);
  assert.match(dictionaryViewSource, /:disabled-of="itemFormFieldDisabled"/);
  assert.notMatch(dictionaryViewSource, /<UiInput[\s\S]*v-model:value="categoryDraft\.alias"/);
  assert.notMatch(dictionaryViewSource, /<UiSelect[\s\S]*v-model:value="categoryDraft\.categoryKind"/);
  assert.notMatch(dictionaryViewSource, /<UiInput[\s\S]*v-model:value="itemDraft\.code"/);
});

it('position management uses child resource form descriptor for position form', () => {
  const positionViewSource = readSource('src/views/PositionManagementView.vue');

  assert.match(positionViewSource, /const currentUserTenant = computed<Tenant \| undefined>/);
  assert.match(
    positionViewSource,
    /watch\(currentUserTenant, initializeTenantUserScope, \{ immediate: true \}\)/,
  );
  assert.match(positionViewSource, /function initializeTenantUserScope\(tenant = currentUserTenant\.value\)/);
  assert.match(positionViewSource, /onMounted\(loadPositionFormDefinition\)/);
  assert.match(
    positionViewSource,
    /resolveRecordFormFields\(\s*runtimeContext\.uiDescriptor,\s*childResourceDefaultFormViewCode\(POSITION_RESOURCE\),?\s*\)/,
  );
  assert.match(positionViewSource, /const POSITION_RESOURCE = 'position'/);
  assert.match(positionViewSource, /childResourceDefaultFormViewCode/);
  assert.match(
    positionViewSource,
    /positionFormFieldDefinitions = ref\(resolveRecordFormFields\(undefined\)\)/,
  );
  assert.match(positionViewSource, /<RecordFormFields/);
  assert.match(positionViewSource, /:field-names="positionFormFieldNames"/);
  assert.match(positionViewSource, /:fields="positionFormFieldDefinitions"/);
  assert.match(positionViewSource, /:fallback="positionFormFieldFallback"/);
  assert.match(positionViewSource, /@update:field="updatePositionDraftField"/);
  assert.match(positionViewSource, /categoryId: \{[\s\S]*controlType: 'select'/);
  assert.match(positionViewSource, /options: categoryOptions\.value/);
  assert.notMatch(positionViewSource, /<UiSelect[\s\S]*v-model:value="positionDraft\.categoryId"/);
  assert.notMatch(positionViewSource, /<UiInput[\s\S]*v-model:value="positionDraft\.code"/);
  assert.notMatch(positionViewSource, /<UiInput[\s\S]*v-model:value="positionDraft\.title"/);
  assert.notMatch(positionViewSource, /<UiInput[\s\S]*v-model:value="positionDraft\.description"/);
});

it('three-column management pages use the platform detail panel', () => {
  const indexSource = readSource('src/platform-components/index.ts');
  const panelSource = readSource('src/platform-components/RecordDetailPanel.vue');
  const layoutSource = readSource('src/platform-components/StaticManagementLayout.vue');
  const tenantViewSource = readSource('src/views/TenantManagementView.vue');
  const positionViewSource = readSource('src/views/PositionManagementView.vue');
  const dictionaryViewSource = readSource('src/views/DictionaryManagementView.vue');
  const menuViewSource = readSource('src/views/MenuManagementView.vue');
  const dictionaryDetailSource = dictionaryViewSource.slice(
    dictionaryViewSource.indexOf('<RecordDetailPanel class="dictionary-column"'),
  );

  assert.match(indexSource, /RecordDetailPanel/);
  assert.match(panelSource, /defineOptions\(\{ name: 'RecordDetailPanel' \}\)/);
  assert.match(panelSource, /<slot name="status" \/>/);
  assert.match(panelSource, /<slot name="actions" \/>/);
  assert.match(layoutSource, /<RecordDetailPanel[\s\S]*:title="detailTitle"/);
  assert.match(layoutSource, /<slot name="detail-status" \/>/);
  assert.match(layoutSource, /explorerTitle: string/);
  assert.match(layoutSource, /detailTitle: string/);
  assert.notMatch(layoutSource, /sidebarTitle|cardTitle/);
  assert.match(layoutSource, /<slot name="explorer-actions" \/>/);
  assert.match(layoutSource, /<slot name="detail-actions" \/>/);
  assert.notMatch(layoutSource, /RecordStatusTag|card-header|title-line/);
  for (const source of [tenantViewSource]) {
    assert.match(source, /<template #detail-status>/);
    assert.match(source, /<RecordStatusSwitch/);
    assert.notMatch(source, /EnabledSelect|启用状态|toggle-enabled|show-status/);
  }
  assert.equal(matchCount(positionViewSource, /<RecordDetailPanel/g), 1);
  assert.equal(matchCount(dictionaryViewSource, /<RecordDetailPanel/g), 1);
  assert.equal(matchCount(menuViewSource, /<RecordDetailPanel/g), 1);
  assert.match(positionViewSource, /v-if="positionMode !== 'view'"[\s\S]*:enabled="positionDraft\.enabled"/);
  assert.match(dictionaryViewSource, /v-if="itemMode !== 'view'"[\s\S]*:enabled="itemDraft\.enabled"/);
  assert.notMatch(positionViewSource, /v-if="positionMode === 'create'"/);
  assert.notMatch(dictionaryViewSource, /v-if="itemMode === 'create'"/);
  assert.match(menuViewSource, /<template #editor>[\s\S]*scheme-editor-panel/);
  assert.match(menuViewSource, /<RecordDetailPanel class="menu-detail-column"[\s\S]*:title="menuCardTitle"/);
  assert.notMatch(menuViewSource, /<RecordDetailPanel[\s\S]*:title="schemeCardTitle"/);
  assert.match(
    positionViewSource,
    /:enabled="categoryDraft\.enabled"[\s\S]*@change="categoryDraft\.enabled = \$event"/,
  );
  assert.match(
    dictionaryViewSource,
    /:enabled="categoryDraft\.enabled"[\s\S]*@change="categoryDraft\.enabled = \$event"/,
  );
  assert.notMatch(positionViewSource, /detail-column|detail-title-group|detail-header-actions/);
  assert.notMatch(dictionaryViewSource, /detail-column|detail-title-group|detail-header-actions/);
  assert.notMatch(dictionaryDetailSource, /EnabledSelect|启用状态/);
  assert.notMatch(layoutSource, /actionMessage|message success|message\.success/);
  assert.notMatch(positionViewSource, /message success|message\.success/);
  assert.notMatch(dictionaryViewSource, /message success|message\.success/);
});

it('employee management uses organization scope and platform query list panel', () => {
  const indexSource = readSource('src/platform-components/index.ts');
  const uiIndexSource = readSource('src/vue-ui-antdv/index.ts');
  const searchInputSource = readSource('src/vue-ui-antdv/components/UiSearchInput.vue');
  const drawerSource = readSource('src/platform-components/RecordDetailDrawer.vue');
  const sidePanelSource = readSource('src/vue-ui-antdv/components/UiSidePanel.vue');
  const panelSource = readSource('src/platform-components/RecordQueryListPanel.vue');
  const dataTableSource = readSource('src/vue-ui-antdv/components/UiDataTable.vue');
  const uiTypesSource = readSource('src/vue-ui-antdv/types.ts');
  const uiTableSource = readSource('src/vue-ui-antdv/components/UiTable.vue');
  const formFieldsSource = readSource('src/platform-components/RecordFormFields.vue');
  const formFieldModelSource = readSource('src/platform-components/recordFormFieldModel.ts');
  const runtimeContextSource = readSource('src/web-core/module/runtimeContext.ts');
  const dropdownSource = readSource('src/vue-ui-antdv/components/UiDropdown.vue');
  const employeeViewSource = [
    readSource('src/views/EmployeeManagementView.vue'),
    readSource('src/views/EmployeeDetailContent.vue'),
  ].join('\n');
  const contractsSource = readSource('src/web-contracts/index.ts');

  assert.match(indexSource, /RecordQueryListPanel/);
  assert.match(indexSource, /RecordFormFields/);
  assert.match(uiIndexSource, /UiDataTable/);
  assert.match(uiIndexSource, /UiSearchInput/);
  assert.match(searchInputSource, /InputSearch as AInputSearch/);
  assert.match(searchInputSource, /:enter-button="searchText \?\? false"/);
  assert.match(searchInputSource, /allow-clear/);
  assert.match(searchInputSource, /if \(event\.key !== 'Escape'\) return/);
  assert.match(searchInputSource, /emit\('search', ''\)/);
  assert.match(uiIndexSource, /UiDataTableColumn/);
  assert.match(indexSource, /resolveRecordFormFieldNames/);
  assert.match(indexSource, /resolveRecordFormFieldState/);
  assert.match(formFieldsSource, /RecordStatusSwitch/);
  assert.match(formFieldsSource, /record-form-field-full-row/);
  assert.match(formFieldsSource, /RecordPicker/);
  assert.match(formFieldsSource, /pickerConfigs\?: Record<string, RecordFormFieldPickerConfig>/);
  assert.match(formFieldsSource, /fieldNames\?: string\[\]/);
  assert.match(formFieldsSource, /excludeFieldNames\?: string\[\]/);
  assert.match(formFieldsSource, /resolveRecordFormFieldNames/);
  assert.match(formFieldsSource, /resolveRecordFormFieldState/);
  assert.match(formFieldModelSource, /field\?\.uiType === 'enabledStatus'/);
  assert.match(formFieldModelSource, /field\?\.uiType === 'recordPicker'/);
  assert.match(formFieldModelSource, /fallback\?\.controlType \?\? 'input'/);
  assert.match(formFieldsSource, /booleanFieldValue/);
  assert.match(formFieldsSource, /field\.controlType === 'recordPicker' && field\.pickerConfig/);
  assert.match(
    formFieldsSource,
    /field\.controlType === 'select' && \(field\.hasOption \|\| optionFieldOptions\(field\)\.length > 0\)/,
  );
  assert.match(
    formFieldsSource,
    /disabledOf\?: \(fieldName: string, field: RecordFormFieldState\) => boolean/,
  );
  assert.match(formFieldsSource, /'update:field': \[fieldName: string, value: RecordFormFieldValue\]/);
  assert.match(formFieldsSource, /const optionFieldErrors = ref<Record<string, string>>/);
  assert.match(formFieldsSource, /catch \{[\s\S]*选项加载失败，请重试/);
  assert.match(formFieldsSource, /await loadOptionField\(field\)/);
  assert.match(formFieldsSource, /retryOptionField/);
  assert.match(panelSource, /defineOptions\(\{ name: 'RecordQueryListPanel' \}\)/);
  assert.match(dataTableSource, /defineOptions\(\{ name: 'UiDataTable', inheritAttrs: false \}\)/);
  assert.match(dataTableSource, /Table as ATable/);
  assert.match(dataTableSource, /clickableRows\?: boolean/);
  assert.match(dataTableSource, /fillHeight\?: boolean/);
  assert.match(dataTableSource, /horizontalScroll\?: boolean/);
  assert.match(dataTableSource, /rowMuted\?: \(record: UiDataTableRecord\) => boolean/);
  assert.notMatch(dataTableSource, /record\.muted/);
  assert.notMatch(dataTableSource, /pagination\?: false \| TablePaginationConfig/);
  assert.notMatch(dataTableSource, /rowSelection\?: TableProps/);
  assert.notMatch(dataTableSource, /scroll\?: TableProps/);
  assert.match(uiTypesSource, /interface UiDataTablePagination/);
  assert.match(uiTypesSource, /interface UiDataTableSelection/);
  assert.match(dataTableSource, /if \(!props\.clickableRows\)/);
  assert.match(
    dataTableSource,
    /onClick: \(event: MouseEvent\) => \{\s*if \(isExpandTriggerEvent\(event\)\)/,
  );
  assert.match(dataTableSource, /showActionColumn\?: boolean/);
  assert.match(dataTableSource, /fixed: 'right' as const/);
  assert.match(dataTableSource, /className: 'ui-data-table-action-cell'/);
  assert.match(dataTableSource, /th\.ui-data-table-action-cell/);
  assert.match(dataTableSource, /expandedRowRender/);
  assert.match(dataTableSource, /resolveUiDataTableScroll/);
  assert.match(dataTableSource, /\.ant-table-body/);
  assert.match(dataTableSource, /\.ant-table-expanded-row-fixed/);
  assert.match(uiTableSource, /<UiDataTable/);
  assert.notMatch(uiTableSource, /Table as ATable/);
  assert.match(panelSource, /<UiDataTable/);
  assert.notMatch(panelSource, /<table/);
  assert.match(
    panelSource,
    /querySchema\(\{\s*uiConfigId: props\.uiConfigId,\s*queryTemplateId: props\.queryTemplateId,\s*\}\)/,
  );
  assert.match(panelSource, /emptyQuerySchema/);
  assert.match(panelSource, /isUnsupportedQuerySchemaError/);
  assert.match(panelSource, /query schema is not supported by/);
  assert.match(panelSource, /externalQueryValues/);
  assert.match(panelSource, /actions\?: RecordActionItem\[\]/);
  assert.match(panelSource, /standardCrudActions\?: boolean/);
  assert.match(panelSource, /standardCrudRowActions\?: boolean/);
  assert.match(panelSource, /function standardCrudRowActionsOf/);
  assert.match(
    panelSource,
    /key: 'view', actionCode: props\.standardCrudRowActionCodes\.view \?\? 'view', title: '查看'/,
  );
  assert.match(panelSource, /rowActionsOf\?: \(record: QueryListRecord\) => RecordActionItem\[\]/);
  assert.match(panelSource, /extraRowActionsOf\?: \(record: QueryListRecord\) => RecordActionItem\[\]/);
  assert.match(panelSource, /rowActionStateOf\?:/);
  assert.match(panelSource, /mergeRecordActions/);
  assert.match(panelSource, /type\?: 'text' \| 'enabledStatus'/);
  assert.match(panelSource, /interface QueryListRow/);
  assert.match(panelSource, /const rows = computed<QueryListRow/);
  assert.match(panelSource, /function resolveRow/);
  assert.match(panelSource, /resolveRecordActions\(props\.context, configuredActions, false, recordId\)/);
  assert.notMatch(panelSource, /resolveRecordActions\(props\.context, configuredActions\)/);
  assert.match(panelSource, /<RecordActionBar/);
  assert.match(panelSource, /<RecordStatusTag/);
  assert.match(panelSource, /<UiDropdown/);
  assert.match(panelSource, /emit\('action', action, event\)/);
  assert.match(panelSource, /function handleTableRowDblclick/);
  assert.match(panelSource, /emit\('rowDblclick', row\.record, event\)/);
  assert.match(panelSource, /emit\('rowAction', action, row\.record/);
  assert.notMatch(panelSource, /primaryRowAction\(record\)/);
  assert.match(panelSource, /<UiSearchInput/);
  assert.match(panelSource, /@search="submitQuickSearch"/);
  assert.match(panelSource, /conditionsDisabled/);
  assert.notMatch(panelSource, />清除</);
  assert.match(dropdownSource, /Dropdown as ADropdown/);
  assert.match(dropdownSource, /Menu as AMenu/);
  assert.match(dropdownSource, /overlay-class-name/);
  assert.match(dropdownSource, /handleOpenChange/);
  assert.notMatch(dropdownSource, /Teleport/);
  assert.notMatch(dropdownSource, /document\.addEventListener/);
  assert.match(panelSource, /ready\?: boolean/);
  assert.match(panelSource, /waitingDescription\?: string/);
  assert.match(panelSource, /\(\) => props\.ready/);
  assert.match(panelSource, /runtimeListView = ref<ResolvedViewDescriptor>/);
  assert.match(panelSource, /runtimeListView\.value = await loadRuntimeListView\(\)/);
  assert.match(panelSource, /async function loadRuntimeListView/);
  assert.match(panelSource, /if \(props\.columns && props\.columns\.length > 0\)/);
  assert.match(panelSource, /descriptorLoadError = ref\(false\)/);
  assert.match(panelSource, /descriptorLoadError\.value = true/);
  assert.match(panelSource, /列表声明加载失败，请稍后重试/);
  assert.notMatch(panelSource, /catch \{\s*return \[\];\s*\}/);
  assert.match(
    panelSource,
    /presentPlatformError\(cause, \{ source: 'record-query-list-panel', phase: 'load' \}\)/,
  );
  assert.match(panelSource, /tableColumns = computed<RecordQueryListColumn\[\]>/);
  assert.match(panelSource, /dataTableColumns = computed<UiDataTableColumn\[\]>/);
  assert.match(panelSource, /clickable-rows/);
  assert.match(panelSource, /fill-height/);
  assert.match(panelSource, /horizontal-scroll/);
  assert.match(panelSource, /:row-muted=/);
  assert.match(panelSource, /cellRenderers\?: Record<string, \(record: QueryListRecord\) => string>/);
  assert.match(panelSource, /props\.cellRenderers\[column\.key\]/);
  assert.match(panelSource, /@dblclick\.stop/);
  assert.match(
    panelSource,
    /class="record-query-list-row-actions"[\s\S]{0,180}@click\.stop[\s\S]{0,80}@dblclick\.stop/,
  );
  assert.match(panelSource, /show-action-column="hasRowActions"/);
  assert.match(panelSource, /@row-expand="\(row, expanded\) => handleTableRowExpand/);
  assert.match(panelSource, /record-query-list-primary-actions/);
  assert.match(panelSource, /position: absolute/);
  assert.match(panelSource, /right: 0/);
  assert.match(panelSource, /width: 100%/);
  assert.match(panelSource, /justify-content: center/);
  assert.match(panelSource, /columnsFromRuntimeListView/);
  assert.match(panelSource, /field\.fieldRef\.fieldName/);
  assert.match(panelSource, /field\.uiType === 'enabledStatus'/);
  assert.match(contractsSource, /optionTitleField\?: string/);
  assert.match(panelSource, /titleField\?: string/);
  assert.match(panelSource, /titleField: field\.option\?\.titleField \?\? queryField\?\.optionTitleField/);
  assert.match(panelSource, /record\[titleField \?\? `\$\{fieldName\}Title`\]/);
  assert.match(panelSource, /return value \? '是' : '否'/);
  assert.match(panelSource, /emit\('loaded', \[\]\)/);
  assert.match(panelSource, /recordsRequestSeq/);
  assert.match(panelSource, /if \(!queryReady\.value\)/);
  assert.match(panelSource, /activeConditions\.value = \[\]/);
  assert.match(panelSource, /validateConditionDrafts/);
  assert.match(
    panelSource,
    /operator === 'BETWEEN'[\s\S]*valuesOfDraft\(field, operator, draft\)\.length !== 2/,
  );
  assert.match(panelSource, /quickSearchFields/);
  assert.match(panelSource, /conditions: activeConditions\.value/);
  assert.match(panelSource, /page: \{ pageNum: pageNum\.value, pageSize: pageSize\.value \}/);
  assert.match(employeeViewSource, /moduleAlias: 'iam\.organization'/);
  assert.match(employeeViewSource, /moduleAlias: 'iam\.employee'/);
  assert.match(employeeViewSource, /<TreeRecordExplorer/);
  assert.match(employeeViewSource, /<RecordQueryListPanel/);
  assert.match(employeeViewSource, /:expanded-row-keys="expandedEmployeeKeys"/);
  assert.match(employeeViewSource, /@row-expand="handleEmployeeRowExpand"/);
  assert.match(employeeViewSource, /employee-row-employment-list/);
  assert.match(
    employeeViewSource,
    /<span>岗位<\/span>[\s\S]*<span>机构<\/span>[\s\S]*<span>部门<\/span>[\s\S]*<span>主岗位<\/span>/,
  );
  const employmentRowsSource = readSource('src/views/useEmployeeEmploymentRows.ts');
  assert.match(employmentRowsSource, /expandedEmployeeKeys/);
  assert.match(employmentRowsSource, /\/employment-view/);
  assert.match(employmentRowsSource, /pathOf\?: \(employeeId: string\) => string/);
  assert.match(employeeViewSource, /\/recycle-bin\/\$\{encodeURIComponent\(employeeId\)\}\/employment-view/);
  assert.notMatch(panelSource, /props\.mode === 'normal' && \(props\.expandedRowKeys/);
  assert.match(employmentRowsSource, /handleEmployeeRowExpand/);
  assert.match(employeeViewSource, /<RecordExpandedSubtable[\s\S]*title="任职信息"/);
  const userViewSource = readSource('src/views/UserManagementView.vue');
  assert.match(userViewSource, /<UserSessionExpandedSubtable/);
  const expandedSubtableSource = readSource('src/platform-components/RecordExpandedSubtable.vue');
  assert.match(expandedSubtableSource, /defineOptions\(\{ name: 'RecordExpandedSubtable' \}\)/);
  assert.match(expandedSubtableSource, /record-expanded-subtable-header/);
  assert.match(employeeViewSource, /standard-crud-actions/);
  assert.notMatch(employeeViewSource, /create-title=/);
  assert.match(panelSource, /title: '新建'/);
  assert.match(panelSource, /icon-name="filter"/);
  assert.match(panelSource, /:class="\{ 'is-selected': conditionsExpanded \}"/);
  assert.match(panelSource, /background: var\(--muyun-selected\)/);
  assert.match(panelSource, /border: 1px solid var\(--muyun-theme-border\)/);
  assert.match(panelSource, /:deep\(\.record-query-list-advanced\.is-selected\.ant-btn\)/);
  assert.match(employeeViewSource, /@action="handleEmployeeListAction"/);
  assert.match(indexSource, /RecordDetailDrawer/);
  assert.match(uiIndexSource, /UiSidePanel/);
  assert.match(drawerSource, /UiSidePanel/);
  assert.match(sidePanelSource, /Drawer as ADrawer/);
  assert.match(sidePanelSource, /:get-container="container"/);
  assert.match(sidePanelSource, /scope: 'tab'/);
  assert.notMatch(drawerSource, /document\.addEventListener/);
  assert.match(indexSource, /RecordDetailFields/);
  assert.match(drawerSource, /closeOnOutside\?: boolean/);
  assert.notMatch(drawerSource, /handleDocumentPointerDown/);
  assert.match(employeeViewSource, /<RecordDetailDrawer/);
  assert.match(employeeViewSource, /:close-on-outside="employeeDetailMode === 'view'"/);
  assert.match(employeeViewSource, /standard-crud-row-actions/);
  assert.notMatch(employeeViewSource, /employeeRowActionsOf/);
  assert.match(employeeViewSource, /@row-action="handleEmployeeRowAction"/);
  assert.match(employeeViewSource, /@row-dblclick="handleEmployeeRowDblclick"/);
  assert.notMatch(employeeViewSource, /employeeColumns/);
  assert.notMatch(employeeViewSource, /:columns="employeeColumns"/);
  assert.notMatch(employeeViewSource, /type: 'enabledStatus'/);
  assert.match(employeeViewSource, /void loadEmployeeFormDefinition\(\)/);
  assert.match(employeeViewSource, /useWorkspaceViewPromotion/);
  assert.match(employeeViewSource, /EmployeeDetailContent/);
  assert.match(employeeViewSource, /resolveRecordFormFields\(runtimeContext\.uiDescriptor\)/);
  assert.match(
    employeeViewSource,
    /employeeFormFieldDefinitions = ref\(resolveRecordFormFields\(undefined\)\)/,
  );
  assert.match(employeeViewSource, /<RecordFormFields/);
  assert.match(employeeViewSource, /<RecordDetailFields/);
  assert.match(employeeViewSource, /v-if="props\.mode === 'view'"/);
  assert.match(employeeViewSource, /<template v-else>/);
  assert.match(employeeViewSource, /<form class="employee-form"/);
  assert.match(employeeViewSource, /:display-of="employeeDetailDisplayValue"/);
  assert.match(employeeViewSource, /function employeeDetailDisplayValue/);
  assert.match(employeeViewSource, /resolveRecordFormFieldState/);
  assert.match(employeeViewSource, /:exclude-field-names="\['organizationId'\]"/);
  assert.notMatch(employeeViewSource, /const employeeStandardFormFields = computed/);
  assert.notMatch(employeeViewSource, /Array\.from\(employeeFormFieldDefinitions\.value\.keys\(\)\)/);
  assert.match(employeeViewSource, /placeholder: '请输入性别'/);
  assert.match(employeeViewSource, /gender: draft\.gender\?\.trim\(\) \|\| undefined/);
  assert.match(employeeViewSource, /gender: \{ label: '性别'/);
  assert.match(employeeViewSource, /function defaultAccountUsername/);
  assert.match(employeeViewSource, /employeeNo \?\? employee\?\.mobile/);
  assert.match(employeeViewSource, /\.trim\(\)\s*\.toLowerCase\(\)/);
  assert.match(employeeViewSource, /移除账户/);
  assert.match(employeeViewSource, /该用户账号会同步删除/);
  assert.notMatch(employeeViewSource, /解绑/);
  assert.match(
    employeeViewSource,
    /handlePlatformActionSuccess\(result,[\s\S]*source: 'employee-management'/,
  );
  assert.notMatch(employeeViewSource, /presentPlatformSuccess\('账号已创建并绑定职员'/);
  assert.notMatch(employeeViewSource, /presentPlatformSuccess\('账户已移除'/);
  assert.match(employeeViewSource, /departmentId: \{[\s\S]*controlType: 'recordPicker'/);
  assert.match(employeeViewSource, /enabled: \{[\s\S]*controlType: 'enabledStatus'/);
  assert.match(employeeViewSource, /employeeFormPickerConfigs/);
  assert.match(
    employeeViewSource,
    /context: scopedDepartmentContext\.value as unknown as ModuleContext<RecordPickerRecord>/,
  );
  assert.match(employeeViewSource, /:picker-configs="employeeFormPickerConfigs"/);
  assert.match(employeeViewSource, /@update-field="updateEmployeeDraftField"/);
  assert.match(employeeViewSource, /employeeDetailMode === 'view' && selectedEmployee/);
  assert.notMatch(employeeViewSource, /employeeDetailMode !== 'view'[\s\S]*employeeDraft\.enabled/);
  assert.notMatch(employeeViewSource, /<RecordPicker[\s\S]*v-model:value="employeeDraft\.departmentId"/);
  assert.notMatch(employeeViewSource, /employeeFormLabel\('employeeNo'\)/);
  assert.notMatch(employeeViewSource, /employeeFormRequired\('employeeNo'\)/);
  assert.match(employeeViewSource, /label: '所属部门'/);
  assert.notMatch(employeeViewSource, /function employeeFormFieldDisabled/);
  assert.notMatch(employeeViewSource, /<span>所属部门<\/span>/);
  assert.notMatch(employeeViewSource, /<span>职员编号<\/span>/);
  assert.match(employeeViewSource, /const employeeFormDisabled = computed/);
  assert.match(employeeViewSource, /const loadingEmployeeDetail = ref\(false\)/);
  assert.match(employeeViewSource, /const employeeDetailLoadFailed = ref\(false\)/);
  assert.match(employeeViewSource, /const employeeDetailRequestSeq = ref\(0\)/);
  assert.match(employeeViewSource, /isEmployeeFormDisabled/);
  assert.match(employeeViewSource, /shouldShowEmployeeDetailContent/);
  assert.match(employeeViewSource, /shouldCloseEmployeeDetailOnCancel/);
  assert.match(employeeViewSource, /canSwitchEmployeeDetailContext/);
  assert.match(employeeViewSource, /function canLeaveEmployeeDetailContext\(\)/);
  assert.match(employeeViewSource, /canSwitchEmployeeDetailContext\(\{ saving: savingEmployee\.value \}\)/);
  assert.match(employeeViewSource, /if \(!canLeaveEmployeeDetailContext\(\)\) \{\s*return;\s*\}/);
  assert.match(employeeViewSource, /key: 'edit'[\s\S]*disabled: savingEmployee\.value/);
  assert.match(employeeViewSource, /key: 'delete'[\s\S]*disabled: savingEmployee\.value/);
  assert.match(employeeViewSource, /selectedEmployeeId: selectedEmployee\.value\?\.id/);
  assert.match(
    employeeViewSource,
    /const currentDetailId = String\(selectedEmployee\.value\?\.id \?\? employeeDraft\.value\.id \?\? ''\)/,
  );
  assert.match(employeeViewSource, /employeeDetailOpen\.value && currentDetailId !== nextKey/);
  assert.match(employeeViewSource, /employeeDetailOpen\.value = false/);
  assert.match(employeeViewSource, /const canSaveEmployee = computed/);
  assert.match(employeeViewSource, /if \(loadingEmployeeDetail\.value\) \{\s*return false;\s*\}/);
  assert.match(employeeViewSource, /const canToggleEmployee = computed/);
  assert.match(employeeViewSource, /loadingEmployeeDetail\.value \|\| !selectedEmployee\.value\?\.id/);
  assert.match(employeeViewSource, /function employeeToggleActionCode/);
  assert.match(
    employeeViewSource,
    /selectedEmployee\.value = undefined;\s*employeeDraft\.value = copyEmployee/,
  );
  assert.notMatch(employeeViewSource, /selectedEmployee\.value = record as Employee/);
  assert.match(employeeViewSource, /const requestSeq = employeeDetailRequestSeq\.value \+ 1/);
  assert.match(employeeViewSource, /employeeDetailRequestSeq\.value = requestSeq/);
  assert.match(employeeViewSource, /shouldCommitEmployeeDetailRequest/);
  assert.match(employeeViewSource, /const canCommitRequest = \(\) =>/);
  assert.match(employeeViewSource, /if \(!canCommitRequest\(\)\)/);
  assert.match(employeeViewSource, /if \(canCommitRequest\(\)\) \{\s*employeeDetailLoadFailed\.value = true/);
  assert.match(employeeViewSource, /loadingEmployeeDetail\.value = false/);
  assert.match(employeeViewSource, /async function loadEmployeeDetailDepartment\([\s\S]*requestSeq/);
  assert.match(employeeViewSource, /function canCommitEmployeeDetailSideEffect/);
  assert.match(employeeViewSource, /activeRequestSeq: employeeDetailRequestSeq\.value/);
  assert.match(employeeViewSource, /selectedEmployeeKey: selectedEmployeeKey\.value/);
  assert.match(employeeViewSource, /if \(canCommitEmployeeDetailSideEffect\(employeeId, requestSeq\)\)/);
  assert.match(employeeViewSource, /function cancelEmployeeDetail\(\)/);
  assert.match(
    employeeViewSource,
    /function cancelEmployeeDetail[\s\S]*shouldCloseEmployeeDetailOnCancel[\s\S]*closeEmployeeDetail\(\)/,
  );
  assert.match(
    employeeViewSource,
    /function cancelEmployeeDetail[\s\S]*employeeDraft\.value = copyEmployee\(selectedEmployee\.value!\)[\s\S]*employeeDetailMode\.value = 'view'/,
  );
  assert.match(
    employeeViewSource,
    /if \(action\.key === 'cancel'\) \{\s*cancelEmployeeDetail\(\);\s*return;\s*\}/,
  );
  assert.match(
    employeeViewSource,
    /function handleEmployeeDetailAction[\s\S]*if \(!canLeaveEmployeeDetailContext\(\)\) \{\s*return;\s*\}[\s\S]*if \(action\.key === 'edit'\)/,
  );
  assert.match(employeeViewSource, /if \(!selectedEmployee\.value \|\| loadingEmployeeDetail\.value\)/);
  assert.match(employeeViewSource, /function retryEmployeeDetail/);
  assert.match(employeeViewSource, /<UiSpin v-if="props\.loading"/);
  assert.match(employeeViewSource, /v-else-if="props\.loadFailed"/);
  assert.match(employeeViewSource, /<UiError title="详情加载失败"/);
  assert.match(employeeViewSource, /@click="emit\('retry'\)"/);
  assert.match(employeeViewSource, /v-else-if="props\.showContent"/);
  assert.match(employeeViewSource, /executeStaticFormSave<Employee>/);
  assert.match(employeeViewSource, /executeStaticRecordAction/);
  assert.match(
    employeeViewSource,
    /validateContext: \(\) => \(selectedOrganizationId\.value \? undefined : '请先选择机构'\)/,
  );
  assert.match(employeeViewSource, /canSave: \(\) => canSaveEmployee\.value/);
  assert.match(employeeViewSource, /validateRecord: validateEmployeeDraft/);
  assert.match(employeeViewSource, /function validateEmployeeDraft\(draft: Employee\)/);
  assert.match(employeeViewSource, /validateEmployeeRequiredFormFields/);
  assert.match(employeeViewSource, /const employeeRequiredFormFieldNames = \[/);
  assert.notMatch(employeeViewSource, /draft\.departmentId && draft\.employeeNo && draft\.title/);
  assert.match(
    employeeViewSource,
    /onSaved: \(\{ record \}\) => \{\s*const requestSeq = commitEmployeeDetailRecord\(record\)[\s\S]*void loadEmployeeDetailDepartment\(record, requestSeq\)/,
  );
  assert.match(employeeViewSource, /当前用户无权保存职员/);
  assert.match(employeeViewSource, /当前用户无权变更职员启停状态/);
  assert.match(employeeViewSource, /canExecute: \(\) => canToggleEmployee\.value/);
  assert.match(
    employeeViewSource,
    /employeeContext\.crud\.enable\(employee\.id!, \{ version: employee\.version! \}\)/,
  );
  assert.match(
    employeeViewSource,
    /employeeContext\.crud\.disable\(employee\.id!, \{ version: employee\.version! \}\)/,
  );
  assert.match(
    employeeViewSource,
    /const refreshed = await employeeContext\.crud\.view\(employee\.id!\);\s*const requestSeq = commitEmployeeDetailRecord\(refreshed\);\s*await loadEmployeeDetailDepartment\(refreshed, requestSeq\)/,
  );
  assert.match(employeeViewSource, /confirm: \(target\) =>[\s\S]*title: '删除职员'/);
  assert.match(employeeViewSource, /content: `确认删除职员/);
  assert.match(
    employeeViewSource,
    /employeeContext\.crud\.delete\(String\(target\.id\), \{ version: \(target as \{ version: number \}\)\.version \}\)/,
  );
  assert.match(employeeViewSource, /:disabled="savingEmployee \|\| !canToggleEmployee"/);
  assert.notMatch(employeeViewSource, /:disabled="employeeFormFieldDisabled\('employeeNo'\)"/);
  assert.notMatch(
    employeeViewSource,
    /render: \(record\) => \(record\.enabled === false \? '停用' : '启用'\)/,
  );
  assert.match(employeeViewSource, /employeeContext\.crud\.insert/);
  assert.match(employeeViewSource, /employeeContext\.crud\.update/);
  assert.match(employeeViewSource, /employeeContext\.crud\.delete/);
  assert.notMatch(employeeViewSource, /presentPlatformMessage\(result\.message \?\? '操作成功'/);
  assert.match(employeeViewSource, /employeeReloadKey\.value \+= 1/);
  assert.match(indexSource, /createScopedTreeModuleContext/);
  assert.match(employeeViewSource, /createScopedTreeModuleContext/);
  assert.match(employeeViewSource, /treePath: '\/iam\.department\/tree'/);
  assert.match(employeeViewSource, /sortPath: '\/iam\.department\/sort'/);
  assert.notMatch(employeeViewSource, /createOrganizationScopedDepartmentContext/);
  assert.match(employeeViewSource, /organizationReloadKey/);
  assert.match(employeeViewSource, /@refresh="refreshOrganizations"/);
  assert.match(employeeViewSource, /:reload-key="organizationReloadKey"/);
  assert.match(employeeViewSource, /:ready="Boolean\(selectedOrganization\?\.id\)"/);
  assert.match(employeeViewSource, /departmentScope/);
  assert.match(contractsSource, /export interface QuerySchema/);
  assert.match(contractsSource, /export interface ResolvedModulePageDescriptor/);
  assert.match(contractsSource, /export interface ViewDefinition/);
  assert.match(contractsSource, /export interface ViewFieldDefinition/);
  assert.match(contractsSource, /export type ViewFieldValueType/);
  assert.match(contractsSource, /valueType\?: ViewFieldValueType/);
  assert.match(contractsSource, /export type FieldValuePresentation = 'FILE_SIZE'/);
  assert.match(contractsSource, /valuePresentation\?: FieldValuePresentation/);
  assert.match(panelSource, /field\.valuePresentation === 'FILE_SIZE'/);
  assert.match(formFieldsSource, /field\.valuePresentation === 'FILE_SIZE'/);
  assert.match(formFieldsSource, /<FileSizeText/);
  assert.match(panelSource, /field\.valueType \?\? queryField\?\.valueType/);
  assert.match(
    panelSource,
    /valueType === 'TIMESTAMP' \|\| valueType === 'ZONED_TIMESTAMP' \|\| valueType === 'INSTANT'/,
  );
  assert.match(contractsSource, /gender\?: string/);
  assert.match(contractsSource, /schemaVersion: string/);
  assert.notMatch(runtimeContextSource, /uiDefinition\?:/);
  assert.notMatch(panelSource, /uiDefinition/);
  assert.notMatch(employeeViewSource, /uiDefinition/);
  assert.match(contractsSource, /externalQueryValues\?: Record<string, unknown>/);
});

it('role management keeps basic scope management separate from binding and authorization', () => {
  const roleViewSource = readSource('src/views/RoleManagementView.vue');
  const routesSource = readSource('src/platform-admin-runtime/platformAdminRoutes.ts');
  const contractsSource = readSource('src/web-contracts/index.ts');
  const panelSource = readSource('src/platform-components/RecordQueryListPanel.vue');

  assert.match(routesSource, /moduleAlias: 'iam\.role'/);
  assert.match(routesSource, /route: '\/iam\/roles'/);
  assert.match(roleViewSource, /defineOptions\(\{ name: 'RoleManagementView' \}\)/);
  assert.match(roleViewSource, /moduleAlias: 'iam\.tenant'/);
  assert.match(roleViewSource, /moduleAlias: 'iam\.organization'/);
  assert.match(roleViewSource, /moduleAlias: 'iam\.role'/);
  assert.match(roleViewSource, /role-management-page/);
  assert.match(roleViewSource, /height: 100%;[\s\S]*min-height: 0;[\s\S]*overflow: hidden;/);
  assert.match(roleViewSource, /@media \(max-width: 980px\)[\s\S]*height: auto;[\s\S]*overflow: visible;/);
  assert.notMatch(roleViewSource, /calc\(100vh|calc\(100dvh/);
  assert.match(roleViewSource, /<CrudRecordListExplorer/);
  assert.match(roleViewSource, /<TreeRecordExplorer/);
  assert.match(roleViewSource, /<RecordQueryListPanel/);
  assert.match(roleViewSource, /selectedScope/);
  assert.match(roleViewSource, /canBrowseTenants/);
  assert.match(roleViewSource, /currentUserTenant/);
  assert.match(roleViewSource, /initializeTenantUserScope/);
  assert.match(roleViewSource, /secondary="当前租户"/);
  assert.match(roleViewSource, /v-if="!canBrowseTenants && currentUserTenant"/);
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
  const employeeEmploymentTableSource = readSource('src/views/EmployeeEmploymentTable.vue');
  assert.match(roleEmploymentGrantDrawerSource, /展开职员后选择其具体任职/);
  assert.match(roleEmploymentGrantDrawerSource, /当前页涉及 \{\{ selectedEmployeeCount \}\} 名职员/);
  assert.match(roleEmploymentGrantDrawerSource, /<section class="role-employment-grant-selected">/);
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
  assert.notMatch(roleViewSource, /account-grants/);
  assert.notMatch(roleViewSource, /employment-grants/);
  assert.notMatch(roleViewSource, /permissionMatrix/);
  assert.match(roleViewSource, /key: 'authorize'[\s\S]*actionCode: 'rolePermissions'[\s\S]*title: '授权'/);
  assert.match(roleViewSource, /authorizationDrawerOpen\.value = true/);
  assert.match(roleViewSource, /<RoleAuthorizationView[\s\S]*:role-id="authorizationRole\.id"[\s\S]*drawer/);
  assert.notMatch(roleViewSource, /createWorkspaceViewDescriptor\([\s\S]*roleAuthorizationWorkspaceView/);
  const roleAuthorizationViewSource = readSource('src/views/RoleAuthorizationView.vue');
  const roleAuthorizationWorkspaceViewSource = readSource('src/views/roleAuthorizationWorkspaceView.ts');
  const workspaceDrawerSource = readSource('src/platform-admin-runtime/WorkspaceViewDrawer.vue');
  assert.match(roleAuthorizationViewSource, /角色组不独立授权/);
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
  const userViewSource = readSource('src/views/UserManagementView.vue');
  const userDetailContentSource = readSource('src/views/UserDetailContent.vue');
  const userSessionRowsSource = readSource('src/views/useUserSessionRows.ts');
  const userSessionExpandedSource = readSource('src/platform-components/UserSessionExpandedSubtable.vue');
  const routesSource = readSource('src/platform-admin-runtime/platformAdminRoutes.ts');
  const contractsSource = readSource('src/web-contracts/index.ts');
  const inputSource = readSource('src/vue-ui-antdv/components/UiInput.vue');
  const iconSource = readSource('src/vue-ui-antdv/components/UiIcon.vue');

  assert.match(routesSource, /moduleAlias: 'iam\.user'/);
  assert.match(routesSource, /route: '\/iam\/users'/);
  assert.match(userViewSource, /defineOptions\(\{ name: 'UserManagementView' \}\)/);
  assert.match(userViewSource, /moduleAlias: 'iam\.tenant'/);
  assert.match(userViewSource, /moduleAlias: 'iam\.user'/);
  assert.match(userViewSource, /user-management-page/);
  assert.notMatch(userViewSource, /calc\(100vh|calc\(100dvh/);
  assert.match(userViewSource, /<CrudRecordListExplorer/);
  assert.match(userViewSource, /<RecordQueryListPanel/);
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
  assert.match(userViewSource, /useUserSessionRows\(\{ context: userContext, source: 'user-management' \}\)/);
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
    /userDetailMode\.value === 'resetPassword'[\s\S]*const userId = selectedUser\.value\?\.id[\s\S]*userContext\.can\('changePassword', userId\)/,
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

it('system user management is a separate root account entry', () => {
  const systemUserViewSource = readSource('src/views/SystemUserManagementView.vue');
  const userSessionRowsSource = readSource('src/views/useUserSessionRows.ts');
  const userViewSource = readSource('src/views/UserManagementView.vue');
  const routesSource = readSource('src/platform-admin-runtime/platformAdminRoutes.ts');

  assert.match(routesSource, /moduleAlias: 'iam\.system_user'/);
  assert.match(routesSource, /route: '\/iam\/system-users'/);
  assert.match(systemUserViewSource, /defineOptions\(\{ name: 'SystemUserManagementView' \}\)/);
  assert.match(systemUserViewSource, /moduleAlias: 'iam\.user'/);
  assert.match(systemUserViewSource, /system-user-management-page/);
  assert.notMatch(systemUserViewSource, /100vh|100dvh/);
  assert.match(systemUserViewSource, /<RecordQueryListPanel/);
  assert.match(systemUserViewSource, /:expanded-row-keys="expandedUserKeys"/);
  assert.match(systemUserViewSource, /@row-expand="handleUserRowExpand"/);
  assert.match(systemUserViewSource, /<template #expandedRow="\{ record \}">/);
  assert.match(systemUserViewSource, /<RecordModeDrawer/);
  assert.match(systemUserViewSource, /:mode="detailMode"/);
  assert.match(systemUserViewSource, /:form-modes="\['edit', 'resetPassword'\]"/);
  assert.match(systemUserViewSource, /:externally-changed="userExternalChange\.externallyChanged\.value"/);
  assert.match(systemUserViewSource, /@reload-external-change="reloadExternalUserChange"/);
  assert.match(systemUserViewSource, /@dismiss-external-change="userExternalChange\.clearExternalChanged"/);
  assert.match(systemUserViewSource, /<template #view>/);
  assert.match(systemUserViewSource, /<template #form>/);
  assert.match(systemUserViewSource, /<RecordDetailFields/);
  assert.match(systemUserViewSource, /<RecordFormFields/);
  assert.match(systemUserViewSource, /function normalizedSystemUserDraft/);
  assert.match(systemUserViewSource, /normalizeRecordDraft<UserAccount>\(draft,/);
  assert.match(systemUserViewSource, /<RecordStatusSwitch/);
  assert.match(systemUserViewSource, /<RecordActionBar/);
  assert.match(systemUserViewSource, /fieldName: 'tenantId'/);
  assert.match(systemUserViewSource, /operator: 'NULL'/);
  assert.match(systemUserViewSource, /title="系统账号"/);
  assert.match(systemUserViewSource, /function rowActionsOf/);
  assert.match(systemUserViewSource, /actionCode: 'view'/);
  assert.match(systemUserViewSource, /actionCode: 'update'/);
  assert.match(systemUserViewSource, /actionCode: 'changePassword'/);
  assert.match(systemUserViewSource, /actionCode: 'resetPassword'/);
  assert.match(systemUserViewSource, /:record-id="selectedUser\?\.id"/);
  assert.match(systemUserViewSource, /title: '修改密码'/);
  assert.match(systemUserViewSource, /title: '重置密码'/);
  assert.match(systemUserViewSource, /<UserSessionExpandedSubtable/);
  assert.match(systemUserViewSource, /@revoke="revokeUserSession\(record, \$event\)"/);
  assert.match(systemUserViewSource, /key: 'onlineStatus'/);
  assert.match(
    systemUserViewSource,
    /useUserSessionRows\(\{ context: userContext, source: 'system-user-management' \}\)/,
  );
  assert.match(systemUserViewSource, /usePageBusinessEventHandler\(handleUserSessionBusinessEvent\)/);
  assert.match(systemUserViewSource, /:cell-renderers="\{ onlineStatus: userOnlineStatusTitle \}"/);
  assert.match(userSessionRowsSource, /function handleUserListLoaded\(records: Array<\{ id\?: string \}>\)/);
  assert.match(userSessionRowsSource, /path: '\/iam\.user\/sessions\/status'/);
  assert.match(userSessionRowsSource, /loadUserSessions/);
  assert.match(userSessionRowsSource, /loadUserSessionActions/);
  assert.match(userSessionRowsSource, /options\.context\.recordActions\(userId\)/);
  assert.match(userSessionRowsSource, /userSessionStates = ref<Record<string, UserSessionState>>/);
  assert.match(
    userSessionRowsSource,
    /function userSessionState\(userId: string \| undefined\): UserSessionState/,
  );
  assert.match(systemUserViewSource, /revokeUserSession/);
  assert.match(systemUserViewSource, /revokeAllUserSessions/);
  assert.match(systemUserViewSource, /temporaryPassword/);
  assert.match(
    systemUserViewSource,
    /path: `\/iam\.user\/changePassword\/\$\{encodeURIComponent\(user\.id!\)\}`/,
  );
  assert.match(
    systemUserViewSource,
    /path: `\/iam\.user\/resetPassword\/\$\{encodeURIComponent\(user\.id!\)\}`/,
  );
  assert.match(userSessionRowsSource, /path: `\/iam\.user\/\$\{encodeURIComponent\(userId\)\}\/sessions`/);
  assert.match(systemUserViewSource, /sessions\/\$\{encodeURIComponent\(session\.id\)\}\/revoke`/);
  assert.match(
    systemUserViewSource,
    /path: `\/iam\.user\/\$\{encodeURIComponent\(user\.id!\)\}\/sessions\/revoke`/,
  );
  assert.match(systemUserViewSource, /tenantId: undefined/);
  assert.match(systemUserViewSource, /enabled: \{ label: '允许登录'/);
  assert.match(systemUserViewSource, /systemUserFormFieldDisabled/);
  assert.notMatch(systemUserViewSource, /<CrudRecordListExplorer/);
  assert.notMatch(systemUserViewSource, /<TreeRecordExplorer/);
  assert.notMatch(systemUserViewSource, /standard-crud-actions/);
  assert.notMatch(systemUserViewSource, /standard-crud-row-actions/);
  assert.notMatch(systemUserViewSource, /actionCode: 'create'/);
  assert.notMatch(systemUserViewSource, /actionCode: 'delete'/);
  assert.notMatch(systemUserViewSource, /forceLogout/);
  assert.notMatch(userViewSource, /iam\.system_user/);
});

it('ordinary management pages do not infer their height from the workbench chrome', () => {
  for (const viewPath of ['src/views/DictionaryManagementView.vue', 'src/views/MenuManagementView.vue']) {
    assert.notMatch(readSource(viewPath), /calc\(100vh|calc\(100dvh/);
  }
});

it('password management is a dedicated security settings page', () => {
  const passwordViewSource = readSource('src/views/PasswordManagementView.vue');
  const routesSource = readSource('src/platform-admin-runtime/platformAdminRoutes.ts');
  const startupSource = readSource('src/app/appWorkbenchStartup.ts');
  const contractsSource = readSource('src/web-contracts/index.ts');

  assert.match(routesSource, /moduleAlias: 'iam\.password_policy_rule'/);
  assert.match(routesSource, /route: '\/platform\/security\/passwords'/);
  assert.match(startupSource, /platformAdminModuleRoutes/);
  assert.match(startupSource, /platformAdminRoutePrefixes/);
  assert.match(passwordViewSource, /defineOptions\(\{ name: 'PasswordManagementView' \}\)/);
  assert.match(passwordViewSource, /moduleAlias: 'iam\.password_policy_rule'/);
  assert.match(passwordViewSource, /<StaticManagementLayout/);
  assert.match(passwordViewSource, /<CrudRecordListExplorer/);
  assert.match(passwordViewSource, /<RecordActionBar/);
  assert.match(passwordViewSource, /<RecordStatusSwitch/);
  assert.match(passwordViewSource, /密码试算/);
  assert.match(passwordViewSource, /new RegExp\(rule\.pattern/);
  assert.match(passwordViewSource, /scopeType: 'global'/);
  assert.notMatch(passwordViewSource, /ruleCode/);
  assert.notMatch(passwordViewSource, /规则编码/);
  assert.match(passwordViewSource, /pattern/);
  assert.match(passwordViewSource, /message/);
  assert.match(passwordViewSource, /description/);
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
  const hostSource = readSource('src/dynamic-page-runtime/DynamicModuleHost.vue');
  const navigatorExplorerSource = readSource('src/dynamic-page-runtime/PageNavigatorExplorer.vue');
  const listPanelSource = readSource('src/platform-components/RecordQueryListPanel.vue');

  assert.match(hostSource, /useModuleContext<QueryListRecord>/);
  assert.match(hostSource, /<RecordQueryListPanel/);
  assert.match(hostSource, /<RecordModeDrawer/);
  assert.match(hostSource, /enhancementDetailActions/);
  assert.match(hostSource, /<template(?: v-if="[^"]+")? #operation>/);
  assert.match(hostSource, /<DynamicRecordDetailActions/);
  assert.match(hostSource, /<RecordDetailFields/);
  assert.match(hostSource, /<RecordFormFields/);
  assert.match(hostSource, /RecordStatusSwitch/);
  assert.match(hostSource, /<template #status>/);
  assert.match(hostSource, /context\.crud\.enable\(id, \{ version \}\)/);
  assert.match(hostSource, /context\.crud\.disable\(id, \{ version \}\)/);
  assert.match(hostSource, /:exclude-field-names="\['enabled'\]"/);
  assert.match(hostSource, /resolveRecordFormFields\(runtimeContext\.uiDescriptor\)/);
  assert.match(hostSource, /isListPage/);
  assert.match(hostSource, /listUiConfigId/);
  assert.match(hostSource, /runtimePage\.value\?\.navigator\?\.levels/);
  assert.match(hostSource, /:ui-config-id="listUiConfigId"/);
  assert.match(hostSource, /createPageBootstrapClient\(context\.http\)\.byMenu\(menuId\)/);
  assert.match(hostSource, /bootstrap\.entry\.moduleAlias !== context\.moduleAlias/);
  assert.match(hostSource, /pageBootstrap\.value\?\.entry\.pageMode/);
  assert.match(hostSource, /v-else-if="!pageReady"/);
  assert.match(hostSource, /v-else-if="isListPage"/);
  assert.match(hostSource, /:query-template-id="listQueryTemplateId"/);
  assert.match(hostSource, /:ready="pageReady"/);
  assert.match(hostSource, /动态\$\{pageMode\.value\}入口暂未接入运行器/);
  assert.match(hostSource, /treeModule\.value = context\.abilities\.hasTree\(\) === true/);
  assert.match(hostSource, /:explorer-count="visibleNavigatorLevels\.length"/);
  assert.match(hostSource, /const workspaceElement = ref<HTMLElement>\(\)/);
  assert.match(hostSource, /listDetailWorkspaceMinWidth\(navigatorLevels\.value\.length\)/);
  assert.match(hostSource, /new ResizeObserver\(\(\) => updateDetailSurfaceForWorkspaceWidth\(\)\)/);
  assert.match(hostSource, /workspaceWidth < listDetailMinimumWidth\.value/);
  assert.equal(/max-width: 719px/.test(hostSource), false);
  assert.match(hostSource, /:navigator-count="visibleNavigatorLevels\.length"/);
  assert.match(hostSource, /<ManagementWorkspace[\s\S]*v-else-if="treeManagementPage \|\| treeModule"/);
  assert.match(hostSource, /<CrudRecordListExplorer/);
  assert.match(hostSource, /<PageNavigatorExplorer/);
  assert.match(hostSource, /const primaryNavigatorContext = computed/);
  assert.match(hostSource, /const navigatorCreateDefaults = computed/);
  assert.equal(/scopedListWorkspace/.test(hostSource), false);
  assert.equal(/selectedScopeRecord/.test(hostSource), false);
  assert.match(hostSource, /sourceCapabilities\?\.includes\('REFERENCE_TREE'\)/);
  assert.match(navigatorExplorerSource, /<TreeRecordExplorer[\s\S]*v-if="level\.tree"/);
  assert.match(navigatorExplorerSource, /search-mode="none"/);
  assert.match(hostSource, /:external-query-values="navigatorListQueryValues"/);
  assert.match(hostSource, /:required-external-criteria-keys="navigatorListCriteriaKeys"/);
  assert.match(hostSource, /const navigatorListScopeReady = computed/);
  assert.match(hostSource, /<TreeRecordExplorer\s+v-if="navigatorListScopeReady"/);
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
    /v-if="!treeModule && !flatManagementPage && \(!listDetailCardPage \|\| detailSurfaceUsesDrawer\)"/,
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
  assert.equal(matchCount(hostSource, /<div v-else class="dynamic-form">[\s\S]*?<RecordFormFields/g), 3);
  assert.match(
    hostSource,
    /<div v-if="editingRecord" class="dynamic-form">[\s\S]*?<RecordFormFields[\s\S]*?@update:field="updateDraftField"/,
  );
  assert.match(hostSource, /<div v-else class="dynamic-form">[\s\S]*<RecordFormFields/);
  assert.match(hostSource, /\.dynamic-form \{[\s\S]*column-gap: 12px;[\s\S]*row-gap: 16px;/);
  assert.match(hostSource, /\.dynamic-form \{[\s\S]*--muyun-record-form-label-gap: 8px;/);
  const recordFormFieldsSource = readSource('src/platform-components/RecordFormFields.vue');
  assert.match(recordFormFieldsSource, /gap: var\(--muyun-record-form-label-gap, 6px\)/);
  assert.match(hostSource, /useRecycleBinExplorerMode<QueryListRecord>/);
  assert.match(hostSource, /title: `删除\$\{recordLabel\.value\}`/);
  assert.match(
    hostSource,
    /function presentDynamicModuleActionSuccess\([\s\S]*handlePlatformActionSuccess\(result,[\s\S]*source,[\s\S]*fallbackMessage/,
  );
  assert.match(hostSource, /await presentDynamicModuleActionSuccess\(result, '保存成功'\)/);
  assert.match(hostSource, /await presentDynamicModuleActionSuccess\(result, '删除成功'\)/);
  assert.match(
    hostSource,
    /await presentDynamicModuleActionSuccess\(result, enabling \? '已启用' : '已停用'\)/,
  );
  assert.match(
    hostSource,
    /presentPlatformError\(cause, \{ source: 'dynamic-module-action', phase: 'action' \}\)/,
  );
  assert.notMatch(hostSource, /formViewCode/);
  assert.notMatch(hostSource, /:subtitle=/);
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
  const positionStateSource = readSource('src/views/positionManagementState.ts');
  const dictionaryStateSource = readSource('src/views/dictionaryManagementState.ts');

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
  assert.match(positionStateSource, /handlePlatformActionSuccess/);
  assert.match(dictionaryStateSource, /handlePlatformActionSuccess/);
});

it('workbench keeps cacheable tab pages mounted behind their stable tab keys', () => {
  const workbenchSource = readSource('src/platform-workbench/Workbench.vue');

  assert.match(workbenchSource, /const openedTabs = computed\(\(\) => props\.startup\?\.tabs \?\? \[\]\)/);
  assert.match(workbenchSource, /function shouldKeepTabMounted\(tab: MenuTab\)/);
  assert.match(workbenchSource, /pageDescriptorOf\(tab\)\?\.tabPolicy\.cacheable !== false/);
  assert.match(workbenchSource, /<template v-for="tab in openedTabs" :key="tab\.key">/);
  assert.match(
    workbenchSource,
    /<UiSidePanelHost[\s\S]*v-if="shouldKeepTabMounted\(tab\)"[\s\S]*v-show="tab\.key === activeTabKey"/,
  );
  assert.match(workbenchSource, /:active-tab="tab"[\s\S]*:page-descriptor="pageDescriptorOf\(tab\)"/);
  assert.match(workbenchSource, /\.tab-panel-host \{[\s\S]*height: 100%;[\s\S]*min-height: 0;/);
  assert.match(workbenchSource, /\.tab-page \{[\s\S]*padding: 10px;[\s\S]*overflow: auto;/);
  assert.match(workbenchSource, /tab-page--workspace/);
  assert.match(workbenchSource, /\.tab-page--workspace \{\s*overflow-x: auto;\s*overflow-y: hidden;/);
});

it('tenant management governs application entitlements as tenant child records', () => {
  const tenantSource = readSource('src/views/TenantManagementView.vue');

  assert.match(tenantSource, /TenantApplication/);
  assert.match(tenantSource, /tenantApplicationsPath\(tenantId\)\}\/query/);
  assert.match(tenantSource, /tenantApplicationsPath\(tenantId\)\}\/configure/);
  assert.match(tenantSource, /RecordDetailDrawer/);
  assert.match(tenantSource, /配置应用/);
  assert.match(tenantSource, /configuredApplicationAliases/);
  assert.match(tenantSource, /已开通应用/);
  assert.notMatch(tenantSource, /draft\.applicationAliases/);
  assert.notMatch(tenantSource, /enabledApplicationAliases/);
  assert.notMatch(tenantSource, /toggleTenantApplication/);
  assert.notMatch(tenantSource, /tenantApplicationsSaving/);
  assert.match(tenantSource, /\(\) => selected\.value\?\.id/);
  assert.match(tenantSource, /tenantApplications\.value = \[\]/);
  assert.match(tenantSource, /tenantApplicationsLoadVersion/);
  assert.match(tenantSource, /selected\.value\?\.id === tenantId/);
  assert.match(tenantSource, /'iam',/);
  assert.match(tenantSource, /record\.alias === 'iam'/);
});

it('tenant form statically owns its mode-dependent branding experience while reusing governed image transfer', () => {
  const tenantSource = readSource('src/views/TenantManagementView.vue');

  assert.match(tenantSource, /tenantFormFields = ref\(resolveRecordFormFields\(undefined\)\)/);
  assert.match(tenantSource, /<SingleImageFileReferenceField/);
  assert.match(tenantSource, /:upload-validation="validateTenantLogo"/);
  assert.match(tenantSource, /Logo \+ 标题模式仅支持正方形图片/);
  assert.match(tenantSource, /v-model:value="workbenchBrandMode"/);
  assert.match(tenantSource, /v-if="logoWithTitle"/);
  assert.match(tenantSource, /\.static-record-form > \.tenant-branding[\s\S]*grid-column: 1 \/ -1/);
  assert.notMatch(tenantSource, /\/branding/);
  assert.notMatch(tenantSource, /saveTenant/);
});

it('side panels use an explicit tab host and fixed drawer action regions', () => {
  const uiIndexSource = readSource('src/vue-ui-antdv/index.ts');
  const sidePanelSource = readSource('src/vue-ui-antdv/components/UiSidePanel.vue');
  const sidePanelHostSource = readSource('src/vue-ui-antdv/components/UiSidePanelHost.vue');
  const workbenchSource = readSource('src/platform-workbench/Workbench.vue');
  const detailDrawerSource = readSource('src/platform-components/RecordDetailDrawer.vue');
  const workspaceViewOutletSource = readSource('src/platform-workbench/WorkspaceViewOutlet.vue');
  const workspaceViewContributionsSource = readSource(
    'src/platform-admin-runtime/workspaceViewContributions.ts',
  );
  const workspaceViewsSource = readSource('src/platform-workbench/workspaceViews.ts');
  const viewPromotionSource = readSource('src/platform-admin-runtime/useWorkspaceViewPromotion.ts');
  const tenantSource = readSource('src/views/TenantManagementView.vue');
  const userSource = readSource('src/views/UserManagementView.vue');
  const userDetailContentSource = readSource('src/views/UserDetailContent.vue');
  const roleSource = readSource('src/views/RoleManagementView.vue');
  const employeeSource = readSource('src/views/EmployeeManagementView.vue');
  const systemUserSource = readSource('src/views/SystemUserManagementView.vue');
  const roleAccountGrantSource = readSource('src/views/RoleAccountGrantDrawer.vue');
  const roleEmploymentGrantSource = readSource('src/views/RoleEmploymentGrantDrawer.vue');

  assert.match(uiIndexSource, /UiSidePanelHost/);
  assert.match(sidePanelSource, /scope: 'tab'/);
  assert.match(sidePanelSource, /props\.scope === 'viewport'/);
  assert.match(sidePanelSource, /sidePanelHost\?\.value \?\? false/);
  assert.match(sidePanelHostSource, /position: relative/);
  assert.match(workbenchSource, /<UiSidePanelHost[\s\S]*class="tab-panel-host"/);
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
  assert.match(userSource, /useWorkspaceViewHost/);
  assert.match(userSource, /isDrawerWorkspaceTask/);
  assert.match(employeeSource, /useWorkspaceViewHost/);
  assert.match(employeeSource, /isDrawerWorkspaceView/);
  assert.match(tenantSource, /<template #operation>/);
  assert.notMatch(tenantSource, /useWorkspaceViewPromotion/);
  assert.notMatch(tenantSource, /:promotion=/);
  assert.match(tenantSource, />\s*确认\s*<\/UiButton/);
  assert.notMatch(tenantSource, /确认配置/);
  assert.match(userSource, /userDetailOperationActions/);
  assert.match(userSource, /userDetailPromotion/);
  assert.match(userSource, /<UserDetailContent/);
  assert.match(userSource, /<RecordDetailPanel v-else/);
  assert.match(userDetailContentSource, /defineOptions\(\{ name: 'UserDetailContent' \}\)/);
  assert.notMatch(userSource, /userDetailHeaderActions/);
  assert.match(roleSource, /roleDetailOperationActions/);
  assert.notMatch(roleSource, /roleDetailHeaderActions/);
  assert.match(employeeSource, /employeeDetailOperationActions/);
  assert.notMatch(employeeSource, /employeeDetailHeaderActions/);
  assert.match(systemUserSource, /detailOperationActions/);
  assert.notMatch(systemUserSource, /detailHeaderActions/);
  assert.match(roleAccountGrantSource, /<template #operation>/);
  assert.match(roleEmploymentGrantSource, /<template #operation>/);
});

it('public management and drawer contracts use business roles instead of layout positions', () => {
  const layoutSource = readSource('src/platform-components/StaticManagementLayout.vue');
  const recordDetailDrawerSource = readSource('src/platform-components/RecordDetailDrawer.vue');
  const recordModeDrawerSource = readSource('src/platform-components/RecordModeDrawer.vue');
  const managementPageSources = [
    readSource('src/views/PasswordManagementView.vue'),
    readSource('src/views/TenantManagementView.vue'),
  ];
  const standardDrawerSources = [
    readSource('src/views/UserManagementView.vue'),
    readSource('src/views/RoleManagementView.vue'),
    readSource('src/views/EmployeeManagementView.vue'),
    readSource('src/views/SystemUserManagementView.vue'),
    readSource('src/views/EmployeeEmploymentDrawer.vue'),
    readSource('src/views/RoleAccountGrantDrawer.vue'),
    readSource('src/views/RoleEmploymentGrantDrawer.vue'),
    readSource('src/views/TenantManagementView.vue'),
  ];

  assert.match(layoutSource, /explorerTitle: string/);
  assert.match(layoutSource, /detailTitle: string/);
  assert.match(layoutSource, /update:explorerSearchKeyword/);
  assert.match(layoutSource, /<slot name="explorer-actions" \/>/);
  assert.match(layoutSource, /<slot name="detail-actions" \/>/);
  assert.notMatch(layoutSource, /sidebarTitle|cardTitle|sidebar-actions|card-actions|card-status/);
  for (const source of managementPageSources) {
    assert.match(source, /v-model:explorer-search-keyword/);
    assert.match(source, /explorer-title=/);
    assert.match(source, /:detail-title=/);
    assert.notMatch(
      source,
      /sidebar-search|sidebar-title|card-title|sidebar-actions|card-actions|card-status/,
    );
  }

  assert.notMatch(recordDetailDrawerSource, /<slot name="actions" \/>/);
  assert.notMatch(recordModeDrawerSource, /<slot name="actions" \/>/);
  for (const source of standardDrawerSources) {
    assert.match(source, /<template #operation>/);
  }
});

it('record lists reuse their existing region for recycle-bin data and lifecycle actions', () => {
  const panelSource = readSource('src/platform-components/RecordQueryListPanel.vue');
  const explorerSource = readSource('src/platform-components/CrudRecordListExplorer.vue');
  const recycleBinButtonSource = readSource('src/platform-components/RecycleBinModeButton.vue');
  const explorerItemSource = readSource('src/vue-ui-antdv/components/UiRecordExplorerItem.vue');
  const explorerPanelSource = readSource('src/platform-components/RecordExplorerPanel.vue');
  const staticLayoutSource = readSource('src/platform-components/StaticManagementLayout.vue');
  const hostSource = readSource('src/dynamic-page-runtime/DynamicModuleHost.vue');
  const employeeSource = readSource('src/views/EmployeeManagementView.vue');
  const tenantSource = readSource('src/views/TenantManagementView.vue');
  const recycleBinModeSource = readSource('src/platform-components/useRecycleBinExplorerMode.ts');
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
  assert.match(panelSource, /<footer class="record-query-list-pagination">[\s\S]*recycleBinEnabled/);
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
  assert.match(hostSource, /const listMode = ref<RecordQueryListMode>\('normal'\)/);
  assert.match(hostSource, /:mode="listMode"/);
  assert.match(hostSource, /@mode-change="handleListModeChange"/);
  assert.match(hostSource, /@restored="handleRecycleBinRestore"/);
  assert.match(employeeSource, /<RecordQueryListPanel/);
  assert.match(employeeSource, /useRecycleBinExplorerMode/);
  assert.match(employeeSource, /:mode="employeeRecycleBinExplorer\.mode\.value"/);
  assert.match(employeeSource, /@mode-change="changeEmployeeListMode"/);
  assert.match(employeeSource, /createSoftDeletedConflictErrorHandler/);
  assert.notMatch(employeeSource, /<RecycleBinPanel/);
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
  assert.match(hostSource, /function openRecycleBinRecord/);
  assert.match(hostSource, /\/recycle-bin\/view\/\$\{encodeURIComponent\(id\)\}/);
  assert.match(hostSource, /const recycleBinDetailActive = computed/);
  assert.match(tenantSource, /<CrudRecordListExplorer/);
  assert.match(explorerItemSource, /action\.showLabel \? action\.title : actionFallbackLabel\(action\)/);
  assert.match(explorerItemSource, /action\.disabledReason \?\? action\.title/);
  assert.match(recycleBinModeSource, /hasRecycleBinAbility\(toValue\(options\.context\)\)/);
  assert.match(recycleBinModeSource, /canQueryRecycleBin\(toValue\(options\.context\)\)/);
  assert.match(recycleBinModeSource, /options\.resetSelection\?\.\(\)/);
  assert.match(tenantSource, /useRecycleBinExplorerMode/);
  assert.match(tenantSource, /recycleBinExplorer\.buttonVisible\.value/);
  assert.match(tenantSource, /<template #explorer-footer>[\s\S]*回收站/);
  assert.match(tenantSource, /RecycleBinModeButton/);
  assert.match(tenantSource, /@recycle-bin-summary/);
  assert.match(tenantSource, /:count="recycleBinExplorer\.total\.value"/);
  assert.notMatch(tenantSource, /已删除/);
  assert.notMatch(tenantSource, /<template #explorer-actions>[\s\S]{0,320}recycleBinQuery/);
  assert.match(explorerPanelSource, /<slot name="footer" \/>/);
  assert.match(staticLayoutSource, /<slot name="explorer-footer" \/>/);
  assert.match(tenantSource, /:mode="recycleBinExplorer\.mode\.value"/);
  assert.match(tenantSource, /handleReadonlyListLoaded\(tenants\)/);
  assert.match(employeeSource, /employeeRecycleBinExplorer\.enter\(\)/);
  assert.match(tenantSource, /recycleBinExplorer\.active\.value \|\| readonly/);
  assert.notMatch(tenantSource, /<RecycleBinPanel/);
  assert.notMatch(tenantSource, /recycle-bin-detail-hint/);
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
