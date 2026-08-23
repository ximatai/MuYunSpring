export type Primitive = string | number | boolean | null | undefined;

export type OptionValue = string | number;

export type OptionValueList = OptionValue[];

export interface Option {
  label: string;
  value: OptionValue;
  disabled?: boolean;
}

export type RouteQueryPrimitive = string | number | boolean | null | undefined;

export type RouteQueryValue = RouteQueryPrimitive | RouteQueryPrimitive[];

export interface WebListResponse<T> {
  records: T[];
}

export interface WebPageResponse<T> {
  records: T[];
  total: number;
  pageNum: number;
  pageSize: number;
  pages: number;
  totalKnown: boolean;
  navigation?: unknown;
}

export const webDataChangeTypes = {
  recordCreated: 'record-created',
  recordUpdated: 'record-updated',
  recordDeleted: 'record-deleted',
  collectionChanged: 'collection-changed',
} as const;

export type WebDataChangeType = (typeof webDataChangeTypes)[keyof typeof webDataChangeTypes];

export interface WebDataChange {
  type: string;
  moduleAlias: string;
  recordId?: string;
  resourceKey?: string;
  scope?: string;
  [key: string]: unknown;
}

export type WebActionMessageType = 'SUCCESS' | 'INFO' | 'WARNING' | 'ERROR' | string;

export interface WebActionMessage {
  code?: string;
  text?: string;
  type?: WebActionMessageType;
  messageArgs?: Record<string, unknown>;
}

export interface WebActionResultFacts {
  message?: string | WebActionMessage;
  resultType?: string;
  changes?: WebDataChange[];
  changeSetId?: string;
}

export type WebActionResult<TFacts extends Record<string, unknown> = Record<string, unknown>> = TFacts &
  WebActionResultFacts;

export interface WebActionResultEnvelope<TData = unknown> extends WebActionResultFacts {
  data: TData;
}

export interface WebCommittedChangeSet {
  changeSetId: string;
  changes: WebDataChange[];
}

export interface WebRealtimeEnvelope<TPayload = unknown> {
  id: string;
  type: string;
  occurredAt: string;
  traceId?: string;
  payload: TPayload;
}

export interface WebUserNotification {
  code: string;
  message: string;
  logoutRequired?: boolean;
  targetSessionId?: string;
}

/** Ephemeral business reminder. It is not a persisted inbox or an acknowledgement record. */
export interface WebBusinessNotification {
  id: string;
  code: string;
  title: string;
  subtitle?: string;
  content: string;
  dismissible: boolean;
  actions: WebBusinessNotificationAction[];
}

export type WebBusinessNotificationAction =
  | WebBusinessNotificationNavigateAction
  | WebBusinessNotificationRecordAction;

export interface WebBusinessNotificationNavigateAction {
  kind: 'navigate';
  key: string;
  label: string;
  moduleAlias: string;
  recordId?: string;
  pageMode?: 'LIST' | 'FORM' | 'DETAIL';
  query?: Record<string, string>;
  placement?: 'leading' | 'trailing';
  dismissOnSuccess: boolean;
}

/** A standard record action implemented by the owning business module. */
export interface WebBusinessNotificationRecordAction {
  kind: 'record';
  key: string;
  label: string;
  moduleAlias: string;
  recordId: string;
  actionCode: string;
  arguments?: Record<string, unknown>;
  danger?: boolean;
  confirmation?: string;
  placement?: 'leading' | 'trailing';
  dismissOnSuccess: boolean;
}

export interface WebBusinessRealtimeEvent {
  type: string;
  moduleAlias: string;
  recordId: string;
  reason?: string;
  sensitivity?: string;
}

export interface WebTreeNode<T> {
  record: T;
  children: WebTreeNode<T>[];
}

/**
 * A semantic inline action rendered beside a record in an explorer or tree.
 *
 * The icon names are platform presentation tokens. Individual UI adapters map
 * them to their own icon implementations.
 */
export interface RecordInlineAction {
  key: string;
  /** Standard record action whose availability is resolved for the rendered record. */
  actionCode?: string;
  title: string;
  iconName?: RecordInlineActionIconName;
  showLabel?: boolean;
  disabled?: boolean;
  /** Explains why a visible inline action is unavailable. */
  disabledReason?: string;
  danger?: boolean;
}

export type RecordInlineActionIconName =
  | 'app'
  | 'check'
  | 'close'
  | 'delete'
  | 'down'
  | 'edit'
  | 'export'
  | 'filter'
  | 'help'
  | 'lock'
  | 'notification'
  | 'plus'
  | 'power'
  | 'reload'
  | 'save'
  | 'search'
  | 'settings';

export interface CurrentUser {
  userId: string;
  username?: string;
  tenantId?: string;
  organizationId?: string;
  system: boolean;
  passwordChangeRequired?: boolean;
  timeZone?: string;
}

export interface CurrentUserProfilePosition {
  id?: string;
  title?: string;
  primary?: boolean;
}

export interface CurrentUserEmployeeProfile {
  id?: string;
  employeeNo?: string;
  title?: string;
  avatarAssetId?: string;
  mobile?: string;
  email?: string;
  organizationId?: string;
  organizationTitle?: string;
  departmentId?: string;
  departmentTitle?: string;
  contactEditable?: boolean;
  positions?: CurrentUserProfilePosition[];
}

export interface CurrentUserProfile {
  username?: string;
  timeZone?: string;
  employee?: CurrentUserEmployeeProfile;
}

export interface UpdateCurrentUserProfileRequest {
  mobile?: string;
  email?: string;
  avatarAssetId?: string;
}

export interface SessionContext {
  currentUser: CurrentUser;
  tenantBranding?: TenantBranding;
}

export interface TenantBranding {
  lightLogo?: string;
  darkLogo?: string;
  mode?: 'logoOnly' | 'logoWithTitle';
  title?: string;
  subtitle?: string;
}

/** Public branding facts for a tenant locked by the unauthenticated login URL. */
export interface TenantLoginContext {
  tenantId: string;
  branding?: TenantBranding;
}

export interface LoginRequest {
  tenantId?: string;
  username: string;
  password: string;
}

export interface LoginResult {
  token: string;
  tokenType: 'Bearer' | string;
  sessionId?: string;
  issuedAt: string;
  currentUser: CurrentUser;
  passwordChangeRequired?: boolean;
  passwordStatus?: UserPasswordStatus;
  passwordExpiresAt?: string;
}

export interface ChangeOwnPasswordRequest {
  currentPassword: string;
  newPassword: string;
}

// Matches current Spring/Jackson code output from menu page mode fields.
export type MenuPageMode = 'LIST' | 'FORM' | 'DETAIL';

export type MenuOpenMode = 'tab' | 'window';

/** Server-resolved facts for one menu-driven module page entry. */
export interface PageBootstrapEntry {
  menuId?: string;
  moduleAlias: string;
  pageMode: MenuPageMode;
  defaultUiConfigId?: string;
  defaultQueryTemplateId?: string;
  entryParamsJson?: string;
}

/**
 * The narrow frontend projection of `/platform.menu/{menuId}/entry`.
 *
 * The module descriptor remains owned by the module runtime context until the
 * standard runner consumes descriptor fields directly.  This projection keeps
 * the menu entry and resolved page configuration available without creating a
 * second frontend descriptor model.
 */
export interface PageBootstrap {
  entry: PageBootstrapEntry;
  clientType: 'WEB' | 'APP';
  mainEntityAlias: string;
  resolvedConfig: {
    uiFields: unknown[];
    queryItems: unknown[];
    /**
     * Permission-scoped detail interaction blocks. The standard runner only
     * executes the explicitly supported block kinds; it never resolves a
     * backend block to an arbitrary client component or handler.
     */
    actionBlocks?: PageBootstrapActionBlock[];
    /** Server-resolved detail relations. Only relation.queryContract is executable by a Web runner. */
    associationBlocks?: PageBootstrapAssociationBlock[];
  };
  openApiPath: string;
}

/** A server-resolved action entry declared by a published detail UI config. */
export interface PageBootstrapActionBlock {
  uiConfigId?: string;
  type: 'action' | 'dialog' | 'localEdit';
  key?: string;
  actionCode: string;
  title?: string;
  position?: string;
  targetUiConfigId?: string;
  submitPath?: string;
  refreshStrategy?: {
    list?: boolean;
    detail?: boolean;
  };
  width?: number;
  height?: number;
  localEditForm?: PageBootstrapLocalEditForm;
}

export interface PageBootstrapLocalEditForm {
  uiConfigId: string;
  fields: PageBootstrapLocalEditField[];
  /**
   * Authoritative controls resolved from the published target FORM config.
   * Local edit must consume these facts rather than falling back to the
   * field's legacy alias, otherwise a control can drift from the page that
   * was actually published.
   */
  fieldUiControls: ResolvedFieldControlDescriptor[];
  submitContract: {
    recordRequired: true;
    recordVersionRequired: true;
    fieldNamesRequired: true;
    uiConfigIdPayloadKey: 'uiConfigId';
  };
}

export interface PageBootstrapLocalEditField {
  relationAlias?: string;
  fieldName: string;
  fieldTitle?: string;
  fieldUiControlAlias?: string;
  /** Authoritative storage type of this resolved field; editors use it for lossless codecs. */
  valueType?: ViewFieldValueType;
  visible?: boolean;
  readOnly?: boolean;
  requiredOverride?: boolean;
  placeholder?: string;
  columnSpan?: number;
}

export interface PageBootstrapAssociationBlock {
  title?: string;
  relation?: ResolvedDetailRelationDescriptor;
}

/** Source-neutral detail-relation descriptor. A declaration without queryContract is intentionally inert. */
export interface ResolvedDetailRelationDescriptor {
  code: string;
  title?: string;
  readOnly: boolean;
  sourceModuleAlias: string;
  sourceEntityAlias: string;
  targetModuleAlias: string;
  targetEntityAlias: string;
  parentBinding: string;
  queryContract?: ResolvedDetailRelationQueryContract;
  /** Read access never implies these operations; the server must explicitly compile them. */
  mutationContract?: ResolvedDetailRelationMutationContract;
  /** Optional client-visible applicability fact; the server remains authoritative. */
  parentConstraint?: ResolvedDetailRelationParentConstraint;
  /** Parent entity field carrying the complete child collection for standard CRUD. */
  embeddedField?: string;
  /** Shared column projection for gateway-backed and embedded relations. */
  listProjection?: ResolvedDetailRelationListProjection;
  /** Immediate server-compiled calculations for rows in this embedded child relation. */
  formComputeRules?: ResolvedDetailRelationFormComputeRuleDescriptor[];
  /** Server-compiled visibility rule; clients execute only its FormulaProgram. */
  visible?: UiRule<boolean>;
  editing?: ResolvedDetailRelationEditing;
  refreshOnDetailReload: boolean;
}

export interface ResolvedDetailRelationEditing {
  mode: 'DIALOG' | 'INLINE';
  saveMode: 'INDEPENDENT' | 'AGGREGATE_DRAFT';
  recycleBinEnabled?: boolean;
}

export interface ResolvedDetailRelationFormComputeRuleDescriptor {
  code: string;
  program: FormulaProgram;
  targetField: string;
  targetValueType: ViewFieldValueType;
  triggerFields: string[];
}

export interface ResolvedDetailRelationParentConstraint {
  fieldName: string;
  expectedValue: string;
}

export interface ResolvedDetailRelationMutationContract {
  createAllowed: boolean;
  updateAllowed: boolean;
  deleteAllowed: boolean;
  createActionCode?: string;
  updateActionCode?: string;
  deleteActionCode?: string;
}

export interface ResolvedDetailRelationQueryContract {
  /** Dynamic/legacy relation route. Managed relations deliberately omit it. */
  queryPath?: string;
  /** Uses the platform parent-association gateway derived from module + relation facts. */
  managedGateway?: boolean;
  /** Compiled action identity in the source/parent module permission catalog. */
  actionCode?: string;
  targetUiConfigId?: string;
  queryTemplateId?: string;
  pageable: boolean;
  /** Initial page size; absent when the relation explicitly loads its complete result. */
  pageSize?: number;
  /** Server-approved choices. Empty for a non-pageable relation. */
  pageSizeOptions?: number[];
  queryable: boolean;
  listProjection?: ResolvedDetailRelationListProjection;
  /** Source-owned query schema; the relation runner must not load target-module schema directly. */
  querySchema?: QuerySchema;
}

/** Server-issued list columns, not a raw target UI config or a client-side inferred view. */
export interface ResolvedDetailRelationListProjection {
  uiConfigId?: string;
  fields: ResolvedDetailRelationListField[];
}

export interface ResolvedDetailRelationListField {
  fieldName: string;
  title?: string;
  fieldForm?: string;
  fieldUiControlAlias?: string;
  width?: number;
  align?: 'left' | 'center' | 'right' | string;
  maxDisplayLines?: number;
}

export function hasExecutableDetailRelationQueryContract(
  relation: ResolvedDetailRelationDescriptor | undefined,
): relation is ResolvedDetailRelationDescriptor & {
  queryContract: ResolvedDetailRelationQueryContract & {
    listProjection: ResolvedDetailRelationListProjection;
  };
} {
  return (
    ((relation?.queryContract?.managedGateway === true && Boolean(relation.queryContract.actionCode)) ||
      (relation?.queryContract?.queryPath != null && relation.queryContract.queryPath.trim().length > 0)) &&
    relation.queryContract.listProjection != null &&
    relation.queryContract.querySchema != null
  );
}

export function hasExecutableDetailRelationMutationContract(
  relation: ResolvedDetailRelationDescriptor | undefined,
): relation is ResolvedDetailRelationDescriptor & {
  mutationContract: ResolvedDetailRelationMutationContract;
} {
  const mutation = relation?.mutationContract;
  return (
    relation?.readOnly === false &&
    mutation != null &&
    ((mutation.createAllowed && Boolean(mutation.createActionCode)) ||
      (mutation.updateAllowed && Boolean(mutation.updateActionCode)) ||
      (mutation.deleteAllowed && Boolean(mutation.deleteActionCode)))
  );
}

export type MenuEntryType = 'module' | 'route' | 'link';

export interface MenuRecord extends StandardEnabledTreeEntity {
  id: string;
  title: string;
  schemeId: string;
  entryType?: MenuEntryType;
  openMode?: MenuOpenMode;
  moduleAlias?: string;
  route?: string;
  externalUrl?: string;
  pageMode?: MenuPageMode;
  defaultUiConfigId?: string;
  defaultQueryTemplateId?: string;
  entryParamsJson?: string;
}

export type MenuTreeNode = WebTreeNode<MenuRecord>;

export type MenuMineResponse = WebListResponse<MenuTreeNode>;

export type MenuScopeType = 'system' | 'tenant' | 'organization';

export interface MenuScheme extends StandardEnabledSortableEntity {
  alias?: string;
  scopeType?: MenuScopeType;
  scopeId?: string;
}

export interface ModuleMenuTarget {
  menuId: string;
  menuType: 'module';
  openMode: MenuOpenMode;
  moduleAlias: string;
  pageMode?: MenuPageMode;
  defaultUiConfigId?: string;
  defaultQueryTemplateId?: string;
  entryParamsJson?: string;
  query?: Record<string, RouteQueryValue>;
}

export interface RouteMenuTarget {
  menuId: string;
  menuType: 'route';
  openMode: MenuOpenMode;
  route: string;
  moduleAlias?: string;
  entryParamsJson?: string;
  query?: Record<string, RouteQueryValue>;
}

export interface ExternalLinkMenuTarget {
  menuId: string;
  menuType: 'link';
  openMode: MenuOpenMode;
  externalUrl: string;
  moduleAlias?: string;
  entryParamsJson?: string;
}

export type MenuNavigationTarget = ModuleMenuTarget | RouteMenuTarget | ExternalLinkMenuTarget;

export type MenuNavigationType = MenuNavigationTarget['menuType'];

export type PageType =
  | 'platform-route'
  | 'business-route'
  | 'dynamic-module'
  | 'remote-url'
  | 'external-link';

export type OpenMode = 'workbench-route' | 'dynamic-runner' | 'iframe' | 'new-window';

export type PageHostType =
  | 'platform-route-host'
  | 'business-route-host'
  /** Source-neutral standard CRUD page host. */
  | 'module-page-host'
  /** Persisted compatibility identifier for the standard module page host. */
  | 'dynamic-module-host'
  | 'external-page-host';

export type TabIdentityStrategy = 'by-menu' | 'by-target' | 'by-params';

/**
 * Declares which layer owns a page's vertical scroll boundary.
 *
 * Flow pages scroll in their workbench tab. Workspace pages consume the
 * constrained desktop work area and manage their resource/detail panes.
 */
export type PageLayoutMode = 'flow' | 'workspace';

export interface TabPolicy {
  identity: TabIdentityStrategy;
  closable?: boolean;
  cacheable?: boolean;
}

export interface TabRestoreState {
  url?: string;
  snapshot?: unknown;
}

export interface PageDescriptorBase<
  TPageType extends PageType,
  TOpenMode extends OpenMode,
  THostType extends PageHostType,
  TTarget,
> {
  pageType: TPageType;
  openMode: TOpenMode;
  hostType: THostType;
  title?: string;
  /** Defaults to flow when omitted, preserving ordinary route behaviour. */
  layout?: PageLayoutMode;
  menuId?: string;
  target: TTarget;
  params?: Record<string, RouteQueryValue>;
  entryParamsJson?: string;
  tabPolicy: TabPolicy;
  restoreState?: TabRestoreState;
}

export interface RoutePageTarget {
  route?: string;
  routeName?: string;
  pageKey?: string;
  moduleAlias?: string;
  query?: Record<string, RouteQueryValue>;
}

export type PlatformRoutePageDescriptor = PageDescriptorBase<
  'platform-route',
  'workbench-route',
  'platform-route-host',
  RoutePageTarget
>;

export type BusinessRoutePageDescriptor = PageDescriptorBase<
  'business-route',
  'workbench-route',
  'business-route-host',
  RoutePageTarget
>;

export interface DynamicModulePageTarget {
  moduleAlias: string;
  pageMode?: MenuPageMode;
  defaultUiConfigId?: string;
  defaultQueryTemplateId?: string;
}

export type ModulePageDescriptor = PageDescriptorBase<
  'dynamic-module',
  'dynamic-runner',
  'module-page-host',
  DynamicModulePageTarget
>;

/** Compatibility descriptor accepted when restoring existing workbench state. */
export type DynamicModulePageDescriptor = PageDescriptorBase<
  'dynamic-module',
  'dynamic-runner',
  'dynamic-module-host',
  DynamicModulePageTarget
>;

/** Source-neutral descriptor accepted by the standard module page runner. */
export type StandardModulePageDescriptor = ModulePageDescriptor | DynamicModulePageDescriptor;

export interface UrlPageTarget {
  url: string;
  moduleAlias?: string;
}

export type RemoteUrlPageDescriptor = PageDescriptorBase<
  'remote-url',
  'iframe' | 'new-window',
  'external-page-host',
  UrlPageTarget
>;

export type ExternalLinkPageDescriptor = PageDescriptorBase<
  'external-link',
  'new-window',
  'external-page-host',
  UrlPageTarget
>;

export type PageDescriptor =
  | PlatformRoutePageDescriptor
  | BusinessRoutePageDescriptor
  | ModulePageDescriptor
  | DynamicModulePageDescriptor
  | RemoteUrlPageDescriptor
  | ExternalLinkPageDescriptor;

export interface MenuTab {
  /** Immutable page-instance key used by route restoration and cache isolation. */
  instanceKey?: string;
  key: string;
  title: string;
  /** Exact browser address used to restore this tab. */
  fullPath?: string;
  target?: MenuNavigationTarget;
  pageDescriptor?: PageDescriptor;
  restoreState?: TabRestoreState;
  closable?: boolean;
}

export interface WorkbenchStartupState {
  session: SessionContext;
  menus: MenuTreeNode[];
  tabs?: MenuTab[];
  activeTabKey?: string;
}

export interface WebPageRequest {
  pageNum: number;
  pageSize: number;
}

export interface WebQueryCondition {
  fieldName: string;
  operator?: string;
  values?: unknown[];
  timeZone?: string;
}

export interface WebSort {
  field: string;
  desc?: boolean;
}

export interface WebQueryRequest {
  page?: WebPageRequest;
  unpaged?: boolean;
  conditions?: WebQueryCondition[];
  criteria?: unknown;
  queryForm?: Record<string, unknown>;
  sorts?: WebSort[];
  uiConfigId?: string;
  queryTemplateId?: string;
  quickSearch?: string;
  quickSearchFields?: string[];
  externalQueryValues?: Record<string, unknown>;
}

/** Shared field-reference delivery contract for static and metadata-backed modules. */
export type WebReferenceResolveMode = 'QUERY' | 'TREE' | 'TRANSLATE';
export type WebReferenceMatchMode = 'KEY' | 'LABEL' | 'AUTO';
export type WebReferenceResolveStatus = 'OK' | 'RESOLVED' | 'NOT_FOUND' | 'AMBIGUOUS' | 'PARTIAL';

/** Persisted aggregate record owning a reference interaction; never use form values to provide its tenant. */
export interface WebReferenceSource {
  recordId?: string;
}

export interface WebReferenceResolveRequest {
  mode?: WebReferenceResolveMode;
  matchMode?: WebReferenceMatchMode;
  fuzzy?: string;
  values?: unknown[];
  conditions?: WebQueryCondition[];
  criteria?: unknown;
  page?: WebPageRequest;
  includeProjections?: boolean;
  formValues?: Record<string, unknown>;
  source?: WebReferenceSource;
  sourceUiConfigId?: string;
  uiConfigId?: string;
  queryTemplateId?: string;
  externalQueryValues?: Record<string, unknown>;
}

export interface WebReferenceResolveItem {
  id: string;
  title?: string;
  matchedBy?: WebReferenceMatchMode;
  projections?: Record<string, unknown>;
  affectPatch?: Record<string, unknown>;
}

export interface WebReferenceResolveResult {
  input: unknown;
  status: WebReferenceResolveStatus;
  matchedBy?: WebReferenceMatchMode;
  item?: WebReferenceResolveItem;
  candidates: WebReferenceResolveItem[];
}

export interface WebReferenceResolveResponse {
  status: WebReferenceResolveStatus;
  mode: WebReferenceResolveMode;
  options: WebReferenceResolveItem[];
  results: WebReferenceResolveResult[];
  offset: number;
  limit: number;
  total: number;
  tree?: WebTreeNode<WebReferenceResolveItem>[];
}

export type QueryValueType =
  | 'STRING'
  | 'TEXT'
  | 'BOOLEAN'
  | 'INTEGER'
  | 'LONG'
  | 'DECIMAL'
  | 'INSTANT'
  | 'DATE'
  | 'JSON';

export type QueryOperator =
  | 'EQ'
  | 'NOT_EQUAL'
  | 'LIKE'
  | 'IN'
  | 'NOT_IN'
  | 'GT'
  | 'GTE'
  | 'LT'
  | 'LTE'
  | 'BETWEEN'
  | 'NULL'
  | 'NOT_NULL';

export interface QuerySchemaField {
  name: string;
  title?: string;
  valueType: QueryValueType;
  operators: QueryOperator[];
  defaultOperator?: QueryOperator;
  quickSearch?: boolean;
  sortable?: boolean;
  optionTitleField?: string;
}

export interface QuerySchemaQuickSearch {
  enabled: boolean;
  fields: string[];
  fieldSchemas: QuerySchemaField[];
}

export interface QuerySchemaExternalCriteria {
  key: string;
  valueType?: string;
  providedBy?: string;
}

export interface QuerySchemaDefaultSort {
  field: string;
  desc?: boolean;
}

export interface QuerySchema {
  scopeName: string;
  entityAlias?: string;
  quickSearch: QuerySchemaQuickSearch;
  fields: QuerySchemaField[];
  externalCriteria: QuerySchemaExternalCriteria[];
  defaultSorts: QuerySchemaDefaultSort[];
}

export type ModuleViewKind = 'LIST' | 'FORM' | 'DETAIL';

export type ModuleUiClientType = 'WEB';

export interface UiRule<T> {
  constant?: T;
  formula?: UiFormula;
  disabledHint?: string;
}

/** A FormulaEngine expression together with its server-issued WEB_UI AST. */
export interface UiFormula {
  expression: string;
  program?: FormulaProgram;
}

/** Versioned program compiled by FormulaEngine. The browser executes it locally and never parses expression. */
export interface FormulaProgram {
  schemaVersion: number;
  profile: 'WEB_UI' | 'FORM_COMPUTE';
  root: FormulaNode;
  referencedFields: string[];
}

/** Source-neutral FormulaEngine AST node. Profiles decide which nodes can execute. */
export interface FormulaNode {
  kind: 'VALUE' | 'FIELD' | 'OTHERS' | 'UNARY' | 'BINARY' | 'FUNCTION' | 'ASSIGN';
  operator?: string;
  field?: string;
  value?: string | number | boolean | null;
  arguments: FormulaNode[];
}

export interface ViewFieldRef {
  relationCode?: string;
  fieldName: string;
  fieldId?: string;
}

export interface ViewFieldDefinition {
  fieldRef: ViewFieldRef;
  label?: string;
  visible?: UiRule<boolean>;
  required?: UiRule<boolean>;
  readOnly?: UiRule<boolean>;
  uiType?: string;
  valuePresentation?: FieldValuePresentation;
  width?: string;
  columnSpan?: number;
  align?: 'left' | 'center' | 'right' | string;
  fixed?: boolean;
  booleanStatus?: BooleanStatusPresentation;
  /** Maximum visible lines for text cells in standard list views. Omitted uses the platform default of one line. */
  maxDisplayLines?: number;
  /** User-facing title for the standard tree root sentinel in a detail field. */
  treeRootTitle?: string;
}

/** Semantic group that owns a contiguous set of standard-form fields. */
export interface FormGroupDescriptor {
  groupCode: string;
  title: string;
  subtitle?: string;
  fields: ViewFieldRef[];
}

export interface BooleanStatusPresentation {
  trueLabel: string;
  falseLabel: string;
  trueTone?: BooleanStatusTone;
  falseTone?: BooleanStatusTone;
}

export type BooleanStatusTone = 'SUCCESS' | 'NEUTRAL' | 'WARNING' | 'DANGER';

export type FieldValuePresentation = 'FILE_SIZE';

export type ViewFieldValueType =
  | 'STRING'
  | 'TEXT'
  | 'INTEGER'
  | 'LONG'
  | 'BOOLEAN'
  | 'TIMESTAMP'
  | 'ZONED_TIMESTAMP'
  | 'DATE'
  | 'DECIMAL'
  | 'JSON';

/**
 * Source-neutral field-control fact compiled by the platform. `renderer` is a platform semantic
 * renderer type, never a Vue component name or front-end module path.
 */
export interface ResolvedFieldControlDescriptor {
  alias: string;
  rendererType: string;
  valueShape: 'SCALAR' | 'COLLECTION' | 'COMPOSITE';
  properties?: Record<string, string>;
  bindings?: ResolvedFieldControlBindingDescriptor[];
}

export interface ResolvedFieldControlBindingDescriptor {
  key: string;
  valueType: string;
}

export interface ResolvedViewFieldDescriptor {
  fieldRef: ViewFieldRef;
  label?: string;
  visible?: UiRule<boolean>;
  required?: UiRule<boolean>;
  readOnly?: UiRule<boolean>;
  uiType?: string;
  /** Optional while older descriptors still publish only `uiType`. When present it is authoritative. */
  fieldControl?: ResolvedFieldControlDescriptor;
  valueType?: ViewFieldValueType;
  valuePresentation?: FieldValuePresentation;
  width?: string;
  columnSpan?: number;
  align?: 'left' | 'center' | 'right' | string;
  fixed?: boolean;
  booleanStatus?: BooleanStatusPresentation;
  treeRootTitle?: string;
  option?: ResolvedOptionFieldDescriptor;
  reference?: ResolvedReferenceFieldDescriptor;
  referenceSummary?: ResolvedReferenceSummaryFieldDescriptor;
  /** Maximum visible lines for text cells. Omitted uses the platform default of one line. */
  maxDisplayLines?: number;
}

/** Structured summary facts resolved from a reference projection. Every item also contains `id`. */
export interface ResolvedReferenceSummaryFieldDescriptor {
  sourceField: string;
  targetModuleAlias: string;
  cardinality: 'ONE' | 'MANY';
  fields: string[];
}

export interface ResolvedReferenceFieldDescriptor {
  targetModuleAlias: string;
  cardinality: 'ONE' | 'MANY';
  /** Server-resolved standard picker transport. AUTO exists only for descriptors issued before an explicit mode. */
  pickerMode?: ReferencePickerMode;
  /** Standardized candidate transport chosen by the compiled source-field contract. */
  candidateDelivery?: 'TARGET_NAVIGATOR' | 'SOURCE_FIELD';
  resolvePath?: string;
  /** Server-enforced dependencies that constrain candidates from values of the current form row. */
  candidateDependencies?: ReferenceCandidateDependency[];
  /** Read-side title projection for this scalar reference, when supplied by the server. */
  titleField?: string;
}

export interface ReferenceCandidateDependency {
  sourceField: string;
  targetField: string;
  required: boolean;
}

export type ReferencePickerMode = 'LIST' | 'TREE' | 'AUTO';

export interface OptionBindingDescriptor {
  sourceType: string;
  source: string;
}

export interface ResolvedOptionFieldDescriptor {
  binding: OptionBindingDescriptor;
  selectionMode: 'SINGLE' | 'MULTIPLE';
  titleField?: string;
  /** Immutable option facts (currently CodeTitleEnum); scope-sensitive dictionaries stay runtime data. */
  inlineItems?: OptionItemDescriptor[];
}

export interface OptionItemDescriptor {
  code: string;
  title: string;
  enabled: boolean;
  sortOrder?: number;
  parentCode?: string;
}

export interface ViewDefinition {
  viewCode: string;
  viewKind: ModuleViewKind;
  clientType?: ModuleUiClientType;
  title?: string;
  fields: ViewFieldDefinition[];
  formGroups?: FormGroupDescriptor[];
}

/** Server-issued deterministic main-form calculation. The coordinator is introduced separately. */
export interface ResolvedFormComputeRuleDescriptor {
  code: string;
  program: FormulaProgram;
  targetField: string;
  targetValueType: ViewFieldValueType;
  triggerFields: string[];
  writePolicy: 'ALWAYS';
}

export interface ResolvedViewDescriptor {
  viewCode: string;
  viewKind: ModuleViewKind;
  clientType?: ModuleUiClientType;
  title?: string;
  fields: ResolvedViewFieldDescriptor[];
  /** Dynamic-page provenance used to select the view configured by a menu entry. */
  sourceUiConfigId?: string;
  formGroups?: FormGroupDescriptor[];
  formComputeRules?: ResolvedFormComputeRuleDescriptor[];
}

export type ModulePageTemplate = 'FLAT_MANAGEMENT' | 'LIST_DETAIL_CARD' | 'TREE_MANAGEMENT';

export interface ResolvedPageExplorerDescriptor {
  title: string;
  searchPlaceholder: string;
  emptyDescription: string;
  recordLabel: string;
  fallbackTitle: string;
  titleField: string;
  secondaryField?: string;
  mutedWhenDisabled: boolean;
}

export interface ResolvedPageNavigatorDescriptor {
  levels: ResolvedPageNavigatorLevelDescriptor[];
  contextBindings: ResolvedPageContextBindingDescriptor[];
}

export interface ResolvedPageNavigatorLevelDescriptor {
  key: string;
  kind: 'TREE' | 'MICRO_LIST';
  sourceModuleAlias: string;
  title: string;
  searchPlaceholder: string;
  /** Presentation policy applied after the source has authoritatively loaded. */
  singleResultPolicy?: 'NONE' | 'AUTO_SELECT' | 'AUTO_SELECT_AND_HIDE';
  /** Explicit initial selection policy; omitted means the navigator starts unselected. */
  initialSelectionPolicy?: 'NONE' | 'FIRST_RECORD';
  /** Session-derived source scope, enforced by the source reference transport. */
  sourceScope?: 'NONE' | 'CURRENT_TENANT';
  /** When present, the navigator source exposes its own standard CRUD affordances in place. */
  management?: ResolvedPageNavigatorManagementDescriptor;
}

export interface ResolvedPageContextBindingDescriptor {
  source: 'SESSION' | 'NAVIGATOR';
  sourceKey: string;
  target: 'LIST_QUERY' | 'NAVIGATOR_QUERY' | 'FORM_DEFAULT' | 'PICKER_QUERY' | 'MUTATION_CONSTRAINT';
  targetKey: string;
  targetNavigatorLevelKey?: string;
  /** Field name of the record picker which receives this query context. */
  targetPickerFieldKey?: string;
}

export interface ResolvedPageNavigatorManagementDescriptor {
  /** Optional named source-module form; the default editor is used when omitted. */
  editorSurface?: string;
  /**
   * Presentation allow-list for standard in-place source management. Omitted
   * descriptors retain the legacy create, update and delete affordances.
   */
  actions?: Array<'CREATE' | 'UPDATE' | 'DELETE'>;
}

export interface ResolvedPageListDescriptor {
  searchPlaceholder: string;
  fields: ResolvedViewDescriptor;
  /** Read-only placements of declared aggregate relations beneath an expanded list row. */
  relationExpansions?: ResolvedPageListRelationExpansionDescriptor[];
}

export interface ResolvedPageListRelationExpansionDescriptor {
  relationCode: string;
  fields: string[];
}

export interface ResolvedPageDetailDescriptor {
  emptyDescription: string;
  createTitle: string;
  /** Whether the standard immutable system metadata section is displayed. */
  showSystemInfo?: boolean;
  display?: ResolvedViewDescriptor;
  /** Omitted by an editorless page; standard mutation actions remain governed by the runtime action contract. */
  editor?: ResolvedViewDescriptor;
  /** Stable client registration key for an independently restorable detail workbench view. */
  workspaceView?: ResolvedPageDetailWorkspaceViewDescriptor;
}

export interface ResolvedPageDetailWorkspaceViewDescriptor {
  type: string;
}

export interface ResolvedModulePageDescriptor {
  template: ModulePageTemplate;
  explorer?: ResolvedPageExplorerDescriptor;
  navigator?: ResolvedPageNavigatorDescriptor;
  list?: ResolvedPageListDescriptor;
  detail: ResolvedPageDetailDescriptor;
  traits: ('STANDARD_CRUD' | 'ENABLED_STATUS' | 'RECYCLE_BIN' | 'RESPONSIVE_DETAIL_SURFACE')[];
}

export interface ResolvedUiActionConfirmationDescriptor {
  mode: 'typedText';
  requiredField: string;
}

export interface ResolvedUiActionDescriptor {
  actionCode: string;
  confirmation?: ResolvedUiActionConfirmationDescriptor;
}

/** Source-neutral runtime fact for one persisted MuYunFileServer field. */
export interface ResolvedFileReferenceFieldDescriptor {
  fieldRef: ViewFieldRef;
  allowedMediaTypes: string[];
  maxFileSizeBytes?: number;
  maxFiles: number;
  storagePolicy: 'MUYUN_FILE_SERVER' | 'DATABASE_INLINE';
  uploadAvailable: boolean;
  readAvailable: boolean;
}

export interface ResolvedModuleUiDescriptor {
  schemaVersion: string;
  moduleAlias: string;
  moduleKind?: 'STATIC' | 'DYNAMIC';
  title?: string;
  actions?: ResolvedUiActionDescriptor[];
  recordLabelField?: string;
  fileReferences?: ResolvedFileReferenceFieldDescriptor[];
  page?: ResolvedModulePageDescriptor;
  defaultEditor?: ResolvedViewDescriptor;
  editorSurfaces?: ResolvedEditorSurfaceDescriptor[];
  editorContributions?: ResolvedPageDetailEditorContribution[];
  detailRelations?: ResolvedDetailRelationDescriptor[];
}

export interface ResolvedEditorSurfaceDescriptor {
  key: string;
  editor: ResolvedViewDescriptor;
}

export interface ResolvedPageDetailEditorContribution {
  resource: string;
  editor: ResolvedViewDescriptor;
}

export interface StandardEntity {
  id?: string;
  tenantId?: string;
  version?: number;
  deleted?: boolean;
  deletedAt?: string;
  createdBy?: string;
  createdAt?: string;
  updatedBy?: string;
  updatedAt?: string;
}

export interface StandardTitledEntity extends StandardEntity {
  title?: string;
}

export interface StandardSortableEntity extends StandardTitledEntity {
  sortOrder?: number;
}

export interface StandardEnabledSortableEntity extends StandardSortableEntity {
  enabled?: boolean;
}

export interface StandardTreeEntity extends StandardSortableEntity {
  parentId?: string;
}

export interface StandardEnabledTreeEntity extends StandardTreeEntity {
  enabled?: boolean;
}

export interface Organization extends StandardEnabledTreeEntity {
  code?: string;
}

export interface Department extends StandardEnabledTreeEntity {
  organizationId?: string;
  code?: string;
}

export interface Employee extends StandardEnabledSortableEntity {
  organizationId?: string;
  departmentId?: string;
  employeeNo?: string;
  gender?: string;
  genderTitle?: string;
  mobile?: string;
  email?: string;
}

export interface EmployeeAccount extends StandardEntity {
  employeeId?: string;
  userId?: string;
}

export interface EmployeeAccountProvisionResponse {
  user?: UserAccount;
  binding?: EmployeeAccount;
}

export interface UserAccount extends StandardEnabledSortableEntity {
  username?: string;
  /** Read projection of the employee currently bound to this account. */
  employeeId?: string;
  employeeNo?: string;
  employeeTitle?: string;
  password?: string;
  passwordStatus?: UserPasswordStatus;
  passwordStatusTitle?: string;
  passwordChangedAt?: string;
  passwordExpiresAt?: string;
  lastLoginAt?: string;
  lastLoginIp?: string;
  lastLoginUserAgent?: string;
  lastFailedLoginAt?: string;
  failedLoginCount?: number;
  lockedUntil?: string;
}

export interface UserEmployeeBindingView {
  bindingId?: string;
  employeeId?: string;
  employeeNo?: string;
  employeeTitle?: string;
  organizationId?: string;
  organizationTitle?: string;
  departmentId?: string;
  departmentTitle?: string;
}

export interface UserSessionView {
  id: string;
  userId: string;
  username?: string;
  tenantId?: string;
  organizationId?: string;
  issuedAt: string;
  expiresAt: string;
  maxExpiresAt?: string;
  lastSeenAt?: string;
  passwordChangeRequired?: boolean;
  loginIp?: string;
  loginUserAgent?: string;
  terminalType?: string;
  terminalTypeTitle?: string;
  platformType?: string;
  platformTypeTitle?: string;
  current: boolean;
  present?: boolean;
  presenceStatus?: string;
  presenceStatusTitle?: string;
  connectionCount?: number;
  lastConnectedAt?: string;
  lastObservedAt?: string;
}

export interface UserSessionStatusView {
  userId: string;
  online: boolean;
  activeSessionCount: number;
  present?: boolean;
  presentSessionCount?: number;
  idleSessionCount?: number;
}

export type UserPasswordStatus = 'normal' | 'initial' | 'resetRequired' | 'expired';

export interface ResetPasswordResponse {
  count: number;
  temporaryPassword?: string;
  expiresAt?: string;
}

export interface UserSelectorItem {
  id: string;
  username?: string;
  employeeId?: string;
  employeeNo?: string;
  employeeTitle?: string;
  organizationId?: string;
  organizationTitle?: string;
  departmentId?: string;
  departmentTitle?: string;
}

export type RoleAssignmentType = 'account' | 'employment';

export type RoleKind = 'standard' | 'group' | 'dataGrant' | 'system';

export type RoleOwnerScopeType = 'platform' | 'tenant' | 'organization';

export type RoleSharePolicy = 'private' | 'ownerAndChildren' | 'tenant' | 'platform';

export type DataScopePolicy =
  | 'none'
  | 'inheritDataGrant'
  | 'all'
  | 'owner'
  | 'assignee'
  | 'member'
  | 'organization'
  | 'organizationAndChildren'
  | 'department'
  | 'departmentAndChildren'
  | 'custom'
  | 'referenceDependency';

export type ManagementScopeType = 'platform' | 'tenant' | 'organization';

export interface Role extends StandardEnabledSortableEntity {
  assignmentType?: RoleAssignmentType;
  assignmentTypeTitle?: string;
  roleKind?: RoleKind;
  roleKindTitle?: string;
  memberRoleIds?: string;
  ownerScopeType?: RoleOwnerScopeType;
  ownerScopeTypeTitle?: string;
  ownerScopeId?: string;
  ownerScopeKey?: string;
  sharePolicy?: RoleSharePolicy;
  sharePolicyTitle?: string;
  builtIn?: boolean;
  systemManaged?: boolean;
  description?: string;
}

export interface RoleAuthorizationModule {
  moduleAlias: string;
  title: string;
  applicationAlias?: string;
  parentId?: string;
}

export interface RolePermissionAction {
  moduleAlias: string;
  actionCode: string;
  permissionActionCode?: string;
  title?: string;
  titleKey?: string;
  actionAuth?: boolean;
  dataAuth?: boolean;
  granted?: boolean;
  dataScopePolicy?: DataScopePolicy;
  referenceFieldId?: string;
  referenceActionCode?: string;
}

export interface RolePermissionMatrixModule {
  moduleAlias: string;
  actions: RolePermissionAction[];
}

export interface RolePermissionMatrix {
  roleId: string;
  modules: RolePermissionMatrixModule[];
}

export interface RoleDataGrantActionMatrix {
  roleId: string;
  actions: Array<{
    actionCode: string;
    title?: string;
    configured: boolean;
    dataScopePolicy?: DataScopePolicy;
  }>;
}

export interface RoleDataScopePolicyCatalog {
  roleId: string;
  options: Array<{
    code: DataScopePolicy;
    title: string;
  }>;
  referenceDependencies: Array<{
    referenceFieldId: string;
    title: string;
    targetModuleAlias: string;
    targetModuleTitle: string;
    referenceActionCode: string;
    referenceActionTitle: string;
  }>;
}

export interface AccountRoleGrant extends StandardEntity {
  roleId?: string;
  /** User account primary key, not username. */
  userId?: string;
  managementScopeType?: ManagementScopeType;
  managementScopeId?: string;
  enabled?: boolean;
}

export interface EmploymentRoleGrant extends StandardEntity {
  roleId?: string;
  employeePositionId?: string;
  enabled?: boolean;
}

export interface EmploymentSelectorItem {
  id: string;
  version?: number;
  employeeId?: string;
  employeeNo?: string;
  employeeTitle?: string;
  organizationId?: string;
  organizationTitle?: string;
  departmentId?: string;
  departmentTitle?: string;
  positionId?: string;
  positionTitle?: string;
  primaryPosition?: boolean;
  enabled?: boolean;
  username?: string;
}

export interface EmployeePosition extends StandardEntity {
  employeeId?: string;
  organizationId?: string;
  departmentId?: string;
  positionId?: string;
  primaryPosition?: boolean;
  enabled?: boolean;
}

export type PasswordPolicyScopeType = 'global' | 'tenant';

export interface PasswordPolicyRule extends StandardEnabledSortableEntity {
  scopeType?: PasswordPolicyScopeType;
  scopeTypeTitle?: string;
  scopeId?: string;
  scopeKey?: string;
  pattern?: string;
  message?: string;
  description?: string;
}

export interface Application extends StandardEnabledSortableEntity {
  alias?: string;
}

export type ModuleKind = 'static' | 'dynamic';

export type ModuleEntryType = 'module' | 'route' | 'link';

export interface PlatformModule extends StandardEnabledTreeEntity {
  alias?: string;
  applicationAlias?: string;
  moduleKind?: ModuleKind;
  entryType?: ModuleEntryType;
  entryRoute?: string;
  entryExternalUrl?: string;
  systemManaged?: boolean;
}

/** A governed operation exposed by one platform module. */
export interface PlatformModuleAction extends StandardEnabledSortableEntity {
  moduleAlias?: string;
  actionCode?: string;
  entityAlias?: string;
  permissionActionCode?: string;
  category?: 'STANDARD' | 'CUSTOM' | 'DIALOG' | 'WORKFLOW' | 'GENERATE';
  actionLevel?: 'LIST' | 'RECORD' | 'BATCH' | 'ANY';
  accessMode?: 'AUTH_REQUIRED' | 'LOGIN_REQUIRED' | 'ANONYMOUS_ALLOWED';
  actionAuth?: boolean;
  dataAuth?: boolean;
  defaultGrantPolicy?: 'NONE' | 'ANY_LOGIN_USER' | 'OWNER' | 'ASSIGNEE' | 'MEMBER';
  /** Governance-only overrides. Code declarations remain the default action fact. */
  accessModeOverride?: 'AUTH_REQUIRED' | 'LOGIN_REQUIRED' | 'ANONYMOUS_ALLOWED';
  actionAuthOverride?: boolean;
  dataAuthOverride?: boolean;
  defaultGrantPolicyOverride?: 'NONE' | 'ANY_LOGIN_USER' | 'OWNER' | 'ASSIGNEE' | 'MEMBER';
  availableExpression?: string;
  unavailableMessage?: string;
  executorType?: 'STANDARD' | 'SERVICE' | 'DIALOG' | 'WORKFLOW' | 'GENERATE';
  executorKey?: string;
  sourceType?: string;
  sourceId?: string;
  sourceVersionId?: string;
  bindingType?: string;
  bindingId?: string;
  bindingAlias?: string;
  systemManaged?: boolean;
}

export interface Metadata extends StandardEnabledSortableEntity {
  applicationAlias?: string;
  alias?: string;
  schemaName?: string;
  tableName?: string;
  dataScopeEnabled?: boolean;
  sortPartitionFields?: string[];
}

export interface MetadataField extends StandardEnabledSortableEntity {
  metadataId?: string;
  fieldName?: string;
  columnName?: string;
  fieldSpecAlias?: string;
  fieldOwnership?: string;
  fieldForm?: string;
  ownerFieldId?: string;
  fieldRole?: string;
  systemManaged?: boolean;
  required?: boolean;
  uniqueField?: boolean;
  indexed?: boolean;
  sortableField?: boolean;
  titleField?: boolean;
}

export interface ModuleMetadataRelation extends StandardSortableEntity {
  moduleAlias?: string;
  metadataId?: string;
  relationAlias?: string;
  relationRole?: 'MAIN' | 'CHILD';
  parentMetadataId?: string;
  foreignKey?: string;
  autoPopulate?: boolean;
}

export interface Tenant extends StandardEnabledSortableEntity {
  alias?: string;
  lightLogoAssetId?: string;
  darkLogoAssetId?: string;
  workbenchBrandMode?: 'logoOnly' | 'logoWithTitle';
  workbenchTitle?: string;
  workbenchSubtitle?: string;
}

export interface FieldSpec extends StandardEnabledSortableEntity {
  alias?: string;
  fieldType?: string;
  defaultLength?: number;
  defaultPrecision?: number;
  defaultScale?: number;
  defaultQueryOperator?: string;
  queryOperators?: string[];
  defaultUiControlAlias?: string;
  uiControlAliases?: string[];
}

export interface FieldUiControl extends StandardEnabledSortableEntity {
  alias?: string;
  defaultFieldSpecAlias?: string;
  valueShape?: 'SCALAR' | 'COLLECTION' | 'COMPOSITE';
  primaryValueKey?: string;
  queryMode?: 'DEFAULT' | 'BETWEEN';
  rendererType?: string;
  icon?: string;
}

export interface FieldUiControlProperty extends StandardSortableEntity {
  fieldUiControlAlias?: string;
  attributeAlias?: string;
  valueFieldSpecAlias?: string;
  defaultValue?: string;
}

export interface FieldUiControlBinding extends StandardSortableEntity {
  fieldUiControlAlias?: string;
  valueKey?: string;
  valueFieldSpecAlias?: string;
}

export interface TenantApplication extends StandardTitledEntity {
  tenantId?: string;
  applicationAlias?: string;
}

export interface PositionCategory extends StandardEnabledTreeEntity {
  code?: string;
  description?: string;
}

export interface Position extends StandardEnabledSortableEntity {
  categoryId?: string;
  code?: string;
  description?: string;
}

export type DictionaryCategoryKind = 'FOLDER' | 'DICTIONARY' | 'folder' | 'dictionary';

export interface DictionaryCategory extends StandardEnabledTreeEntity {
  applicationAlias?: string;
  alias?: string;
  categoryKind?: DictionaryCategoryKind;
}

export interface DictionaryItem extends StandardEnabledTreeEntity {
  categoryId?: string;
  categoryAlias?: string;
  code?: string;
}

export interface TreeSortRequest {
  previousId?: string | null;
  nextId?: string | null;
  parentId?: string | null;
}

export type FieldKind = 'input' | 'select' | 'dictionary-select' | 'reference-select';

export interface FieldCondition {
  field: string;
  equals?: Primitive;
  notEquals?: Primitive;
}

export interface ReferenceContract {
  targetModuleAlias: string;
  keyField: string;
  labelField: string;
  fillBack?: Record<string, string>;
}

export interface FieldContract {
  name: string;
  label: string;
  kind: FieldKind;
  placeholder?: string;
  required?: boolean;
  disabled?: boolean;
  options?: Option[];
  dictionaryAlias?: string;
  reference?: ReferenceContract;
  visibleWhen?: FieldCondition;
  readonlyWhen?: FieldCondition;
  requiredWhen?: FieldCondition;
}

export interface FormContract {
  title?: string;
  fields: FieldContract[];
}

export interface TableColumn {
  key: string;
  title: string;
  width?: number;
  dictionaryAlias?: string;
}

export interface TableContract {
  rowKey?: string;
  columns: TableColumn[];
}

export interface ActionContract {
  actionCode: string;
  title: string;
  level?: 'primary' | 'default' | 'danger';
  disabled?: boolean;
  disabledReason?: string;
}

export type RecordData = Record<string, Primitive>;

export interface DynamicPageDescriptor {
  moduleAlias: string;
  title: string;
  form: FormContract;
  list: TableContract;
  actions: ActionContract[];
  initialRecord: RecordData;
  records: RecordData[];
}

// --- Recycle Bin ---

export interface RecycleBinItem<T = Record<string, unknown>> {
  record: T;
  sourceDeleteOperationId: string | null;
  deletedAt: string;
  restorable: boolean;
  purgeable: boolean;
  unavailableReason?: string;
}

export type RestoreEntryStatus = 'RESTORED' | 'SKIPPED' | 'FAILED';

export interface RestoreEntryResult {
  sourceEntryId: string;
  moduleAlias: string;
  entityAlias: string;
  recordId: string;
  status: RestoreEntryStatus;
  message?: string;
}

export interface RestoreReport {
  sourceOperationId: string;
  restoreOperationId: string;
  entries: RestoreEntryResult[];
}

export type PurgeEntryStatus = 'PURGED' | 'SKIPPED' | 'FAILED';

export interface PurgeEntryResult {
  sourceEntryId: string;
  moduleAlias: string;
  entityAlias: string;
  recordId: string;
  status: PurgeEntryStatus;
  message?: string;
}

export interface PurgeReport {
  sourceOperationId: string;
  purgeOperationId: string;
  entries: PurgeEntryResult[];
}
