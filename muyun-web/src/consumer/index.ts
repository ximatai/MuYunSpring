/**
 * Public App-consumer surface. It deliberately exposes the workbench shell
 * and its runtime primitives, including first-party platform administration
 * pages.  It deliberately excludes pages owned by a consuming business App.
 *
 * Consumers must import `@ximatai/muyun-web-app/style.css` explicitly.
 */

// The public stylesheet must include the adapter's global feedback overrides.
// Consumers only import `@ximatai/muyun-web-app/style.css`, so importing the
// app baseline alone would leave notification feedback on Ant Design Vue defaults.
import '../vue-ui-antdv/styles.css';
import '../styles.css';

export * from '../web-contracts/index';
export * from '../web-core/index';
export * from '../platform-workbench/index';
export * from '../platform-admin-runtime/index';
export {
  activeTabUrlOf,
  arrangeLockedMenuTabs,
  removeLockedMenuTabs,
  restoreWorkbenchStartupStateFromUrl,
  restoreLockedWorkbenchTabs,
  updateLockedMenuTabs,
} from '../app/workbenchStartup';
export { restoreLockedTabPreference, saveLockedTabPreference } from '../app/lockedTabPreference';
export {
  configureModulePageEnhancementContributions,
  configureModulePageEnhancements,
  createModulePageEnhancementRegistry,
  modulePageWorkspaceViews,
} from '../dynamic-page-runtime/modulePageEnhancements';
export { refreshModulePageList } from '../dynamic-page-runtime/modulePageListRefresh';
export type {
  ModuleListEnhancement,
  ModuleDetailEnhancement,
  ModulePageActionContext,
  ModulePageActionContribution,
  ModulePageActionStateContext,
  ModulePageBatchActionContext,
  ModulePageBatchActionContribution,
  ModulePageColumnContribution,
  ModulePageDetailDrawer,
  ModulePageDetailSection,
  ModulePageDetailSectionContext,
  ModulePageDrawer,
  ModulePageDrawerContext,
  ModulePageRecordViewContext,
  ModulePageEnhancement,
  ModulePageEnhancementTarget,
  ModulePageLockedNavigatorEntry,
  ModulePageNavigatorEnhancement,
  ModulePageNavigatorExtension,
  ModulePageNavigatorExtensionContext,
  ModulePageRecordActionContext,
  ModulePageRecordActionContribution,
  ModulePageScopeContext,
  ModulePageWorkspaceView,
  ModulePageWorkspaceViewInput,
} from '../dynamic-page-runtime/modulePageEnhancements';
// Full module orchestration and mutation sessions stay behind PlatformAdminOutlet.
// The read-only workspace and supported presentation compositors below are separate App contracts.
export {
  UiButton,
  UiDropdown,
  UiIcon,
  confirmAction,
  UiDataTable,
  UiSidePanel,
  UiTree,
  UiTreeSelect,
  UiInput,
  UiDatePicker,
  UiSwitch,
  UiTextArea,
} from '../vue-ui-antdv/index';
export type {
  UiDropdownItem,
  UiIconName,
  UiDatePickerMode,
  UiDatePickerPrecision,
} from '../vue-ui-antdv/index';
export { default as PlatformAdminOutlet } from './PlatformAdminOutlet.vue';
export { default as AppWorkbenchShell } from './AppWorkbenchShell.vue';
export { default as ExternalProvisioningConfirmation } from '../app/ExternalProvisioningConfirmation.vue';
export { default as LoginView } from '../app/LoginView.vue';
export type { AppWorkbenchNavigation } from './workbenchNavigation';
export { configureUserPreferencePersistence } from './userPreferencePersistence';
export { default as ChangeOwnPasswordDialog } from '../app/ChangeOwnPasswordDialog.vue';
export { default as ThemeSkinPreferencesDialog } from '../app/ThemeSkinPreferencesDialog.vue';
export {
  restoreThemeSkinPreference,
  saveThemeSkinPreference,
  themeSkinPreferenceKey,
} from '../app/themeSkinPreference';
export { defaultUiThemeSkinId, UiThemeProvider, uiThemeSkinById, uiThemeSkins } from '../vue-ui-antdv/index';
export type { UiThemeSkin, UiThemeSkinId } from '../vue-ui-antdv/index';
export { default as DateTimeText } from '../platform-components/DateTimeText.vue';
export { default as BusinessNotificationPanel } from '../platform-components/BusinessNotificationPanel.vue';
export { default as FileSizeText } from '../platform-components/FileSizeText.vue';
export { default as DrawerOperationBar } from '../platform-components/DrawerOperationBar.vue';
export { default as RecordDetailDrawer } from '../platform-components/RecordDetailDrawer.vue';
export { default as DynamicModuleWorkspaceDetailView } from '../dynamic-page-runtime/DynamicModuleWorkspaceDetailView.vue';
export { default as FileTransferUploader } from '../platform-components/FileTransferUploader.vue';
export { default as SingleImageFileReferenceField } from '../platform-components/SingleImageFileReferenceField.vue';
export { uploadedFileId } from '../platform-components/fileReferenceTransfer';
export {
  presentPlatformError,
  presentPlatformInfo,
  presentPlatformMessage,
  presentPlatformSuccess,
} from '../platform-components/platformErrorFeedback';
export {
  createBrowserFileTransferUpload,
  FileTransferUploadCancelledError,
  unwrapResponsePayload,
} from '../platform-components/fileTransferUpload';
export type {
  FileTransferUploadAccess,
  FileTransferUploadReceipt,
  FileTransferUploadTask,
} from '../platform-components/fileTransferUpload';

// Reference interaction is a supported business-App extension point; the standard form runner
// remains internal. Business sources supply their own authorized candidate provider.
export { default as ReferencePicker } from '../platform-components/ReferencePicker.vue';
export { default as ReferenceRecordDetailBrowser } from '../platform-components/ReferenceRecordDetailBrowser.vue';
export { default as ModuleReferenceRecordDetailBrowser } from '../dynamic-page-runtime/ModuleReferenceRecordDetailBrowser.vue';
export { default as ReadonlyReferenceValue } from '../platform-components/ReadonlyReferenceValue.vue';
export { ReferencePickerReadError } from '../platform-components/referencePickerReadError';
export type { ReferencePickerReadErrorKind } from '../platform-components/referencePickerReadError';
export type {
  ReferencePickerAxisSelection,
  ReferencePickerBrowseScope,
  ReferencePickerCandidate,
  ReferencePickerColumn,
  ReferencePickerConfig,
  ReferencePickerId,
  ReferencePickerNavigationAxis,
  ReferencePickerNavigationItem,
  ReferencePickerPage,
  ReferencePickerPageRequest,
  ReferencePickerProvider,
  ReferencePickerSelectionSummary,
  ReferencePickerSourceIdentity,
  ReferencePickerTreeNode,
  ReferencePickerValidity,
} from '../platform-components/referencePickerModel';

export { UiLanguageProvider, registerUiLanguageLocales } from '../vue-ui-antdv';
export { default as LanguageSelector } from '../platform-workbench/LanguageSelector.vue';

// Specialized business pages compose the same surfaces as the standard module runner.
export {
  ManagementWorkspace,
  ManagementExplorerColumn,
  TenantScopeExplorer,
  RecordQueryListSurface,
  RecordQueryListCell,
  RecordDetailPanel,
  RecordDetailFields,
  RecordDetailExtensionSection,
  useWorkspaceDetailSurface,
  listDetailWorkspaceMinWidth,
  resolveRecordQueryListColumns,
  resolveRecordDetailFields,
} from '../platform-components/index';
export type { RecordFormFieldFallback } from '../platform-components/index';
export type {
  QueryListRecord,
  RecordQueryListColumn,
  RecordFormFieldDescriptor,
} from '../platform-components/index';
export type { UiDataTableColumn, UiDataTableRecord, UiDataTableSelection } from '../vue-ui-antdv/index';
export { UiSelect, UiEmpty, UiError } from '../vue-ui-antdv/index';

export { default as ReadonlyRecordWorkspace } from '../platform-components/ReadonlyRecordWorkspace.vue';

// Cached business pages read their own restored route/descriptor, independently of the active tab.
export { usePageDescriptor, usePageRoute } from '../app/pageRouteContext';
export type { PageRoute } from '../app/pageRouteContext';

export { useExternalLoginHost } from '../app/useExternalLoginHost';
export type { ExternalLoginHost, ExternalLoginHostOptions } from '../app/useExternalLoginHost';
export { default as CurrentUserProfileDialog } from '../app/CurrentUserProfileDialog.vue';
export { default as ExternalIdentityBindings } from '../app/ExternalIdentityBindings.vue';
