/**
 * Public App-consumer surface. It deliberately exposes the workbench shell
 * and its runtime primitives, including first-party platform administration
 * pages.  It deliberately excludes pages owned by a consuming business App.
 *
 * Consumers must import `@ximatai/muyun-web-app/style.css` explicitly.
 */

import '../styles.css';

export * from '../web-contracts/index';
export * from '../web-core/index';
export * from '../platform-workbench/index';
export * from '../platform-admin-runtime/index';
export {
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
  ModulePageBatchActionContext,
  ModulePageBatchActionContribution,
  ModulePageColumnContribution,
  ModulePageDetailSection,
  ModulePageDetailSectionContext,
  ModulePageDrawer,
  ModulePageDrawerContext,
  ModulePageEnhancement,
  ModulePageEnhancementTarget,
  ModulePageRecordActionContext,
  ModulePageRecordActionContribution,
  ModulePageWorkspaceView,
  ModulePageWorkspaceViewInput,
} from '../dynamic-page-runtime/modulePageEnhancements';
// The standard module runner and its list/drawer/form components are shipped
// behind PlatformAdminOutlet.  Keeping them out of this entry preserves their
// freedom to evolve without making each implementation detail an App contract.
export {
  UiButton,
  confirmAction,
  UiDataTable,
  UiInput,
  UiSidePanel,
  UiSwitch,
  UiTextArea,
  UiTree,
} from '../vue-ui-antdv/index';
export { default as PlatformAdminOutlet } from './PlatformAdminOutlet.vue';
export { default as DateTimeText } from '../platform-components/DateTimeText.vue';
export { default as FileSizeText } from '../platform-components/FileSizeText.vue';
export { default as FileTransferUploader } from '../platform-components/FileTransferUploader.vue';
export {
  presentPlatformError,
  presentPlatformInfo,
  presentPlatformMessage,
  presentPlatformSuccess,
} from '../platform-components/platformErrorFeedback';
export {
  createBrowserFileTransferUpload,
  unwrapResponsePayload,
} from '../platform-components/fileTransferUpload';
export type {
  FileTransferUploadAccess,
  FileTransferUploadReceipt,
  FileTransferUploadTask,
} from '../platform-components/fileTransferUpload';
