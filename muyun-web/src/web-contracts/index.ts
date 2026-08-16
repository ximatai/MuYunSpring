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
  };
  openApiPath: string;
}

export interface MenuRecord extends StandardEnabledTreeEntity {
  id: string;
  title: string;
  schemeId: string;
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

export type DynamicModulePageDescriptor = PageDescriptorBase<
  'dynamic-module',
  'dynamic-runner',
  'dynamic-module-host',
  DynamicModulePageTarget
>;

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
  | DynamicModulePageDescriptor
  | RemoteUrlPageDescriptor
  | ExternalLinkPageDescriptor;

export interface MenuTab {
  key: string;
  title: string;
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

/**
 * A platform-owned portable Boolean predicate evaluated against the current record draft.
 * The current grammar supports `PRESENT({fieldName})`, `!()` negation, and `&&` conjunction of PRESENT terms.
 * It is deliberately smaller than the server FormulaEngine because this contract must run in every Web client.
 */
export interface UiFormula {
  expression: string;
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

export interface ResolvedViewFieldDescriptor {
  fieldRef: ViewFieldRef;
  label?: string;
  visible?: UiRule<boolean>;
  required?: UiRule<boolean>;
  readOnly?: UiRule<boolean>;
  uiType?: string;
  valueType?: ViewFieldValueType;
  valuePresentation?: FieldValuePresentation;
  width?: string;
  columnSpan?: number;
  align?: 'left' | 'center' | 'right' | string;
  fixed?: boolean;
  booleanStatus?: BooleanStatusPresentation;
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
  /** Read-side title projection for this scalar reference, when supplied by the server. */
  titleField?: string;
}

export interface OptionBindingDescriptor {
  sourceType: string;
  source: string;
}

export interface ResolvedOptionFieldDescriptor {
  binding: OptionBindingDescriptor;
  selectionMode: 'SINGLE' | 'MULTIPLE';
  titleField?: string;
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

export interface ResolvedViewDescriptor {
  viewCode: string;
  viewKind: ModuleViewKind;
  clientType?: ModuleUiClientType;
  title?: string;
  fields: ResolvedViewFieldDescriptor[];
  /** Dynamic-page provenance used to select the view configured by a menu entry. */
  sourceUiConfigId?: string;
  formGroups?: FormGroupDescriptor[];
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
}

export interface ResolvedPageNavigatorLevelDescriptor {
  key: string;
  kind: 'TREE' | 'MICRO_LIST';
  sourceModuleAlias: string;
  title: string;
  searchPlaceholder: string;
  queryBindings: ResolvedPageNavigatorQueryBindingDescriptor[];
  childBindings: ResolvedPageNavigatorChildBindingDescriptor[];
  /** Presentation policy applied after the source has authoritatively loaded. */
  singleResultPolicy?: 'NONE' | 'AUTO_SELECT' | 'AUTO_SELECT_AND_HIDE';
  /** When present, the navigator source exposes its own standard CRUD affordances in place. */
  management?: ResolvedPageNavigatorManagementDescriptor;
}

export interface ResolvedPageNavigatorManagementDescriptor {
  /** Optional named source-module form; the default editor is used when omitted. */
  editorSurface?: string;
}

export interface ResolvedPageNavigatorQueryBindingDescriptor {
  field: string;
  queryCriteriaKey: string;
}

export interface ResolvedPageNavigatorChildBindingDescriptor {
  childLevelKey: string;
  childQueryCriteriaKey: string;
}

export interface ResolvedPageListDescriptor {
  searchPlaceholder: string;
  fields: ResolvedViewDescriptor;
}

export interface ResolvedPageDetailDescriptor {
  emptyDescription: string;
  createTitle: string;
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
