/** Record reading without UI registration, styles or browser globals; Vue remains a peer. */
export { useRecordReadSession } from '../web-core/useRecordReadSession';
export { resolveRecordReadEntry } from '../web-core/recordReadEntry';
export type { RecordReadEntry } from '../web-core/recordReadEntry';
export type { RecordReadQuery, RecordReadRequestOptions, RecordReadView } from '../web-core/recordReadView';
export type {
  PageDescriptor,
  QuerySchema,
  ResolvedModuleUiDescriptor,
  WebPageResponse,
  WebQueryRequest,
} from '../web-contracts/index';
