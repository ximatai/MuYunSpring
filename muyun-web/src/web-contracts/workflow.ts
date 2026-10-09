/** Published workflow facts shared by static and metadata driven modules. */
export interface WorkflowDefinition {
  id: string;
  version: number;
  alias: string;
  title?: string;
  definitionStatus: string;
  currentVersionNo?: number;
  approvalEnabled: boolean;
  organizationId?: string;
  matchExpression?: string;
  matchPriority?: number;
  defaultDefinition?: boolean;
}
export interface WorkflowVersion {
  id: string;
  version: number;
  versionNo: number;
  publishStatus: string;
}
export interface WorkflowNode {
  id?: string;
  version?: number;
  nodeKey: string;
  title?: string;
  nodeTitle?: string;
  nodeType: string;
  nodeStatus?: string;
  approvalMode?: string;
  approvalRatio?: number;
  participantPolicyText?: string;
  milestoneType?: string;
  routeMode?: string;
  selectorNodeKey?: string;
  convergeNodeKey?: string;
  convergeMode?: string;
  convergeRatio?: number;
  requireManualSelectionReason?: boolean;
  allowReject?: boolean;
  autoApproveSameUser?: boolean;
  requireRejectReason?: boolean;
  allowRejectReturnToMe?: boolean;
  allowRollback?: boolean;
  requireRollbackReason?: boolean;
  allowAddSign?: boolean;
  warningDurationMinutes?: number;
  overtimeDurationMinutes?: number;
  taskDefinitionId?: string;
  nodeConfigText?: string;
}
export interface WorkflowRoute {
  id?: string;
  routeKey: string;
  sourceNodeKey: string;
  targetNodeKey: string;
  title?: string;
  conditionExpression?: string;
  defaultRoute?: boolean;
  routeStatus?: string;
  routeReason?: string;
  selectedReason?: string;
}
export interface WorkflowAddSignExplanation {
  dimension: 'NODE' | 'ROUTE';
  nodeKey?: string;
  nodeStatus?: string;
  routeKey?: string;
  routeSourceNodeKey?: string;
  routeTargetNodeKey?: string;
  addSignSourceNodeKey: string;
}
export interface WorkflowDesign {
  nodes: WorkflowNode[];
  links: WorkflowRoute[];
  layoutJson?: string;
}
export interface WorkflowInstance {
  id: string;
  moduleAlias: string;
  recordId: string;
  definitionTitle?: string;
  versionNo?: number;
  instanceStatus: string;
  approvalStatus?: string;
  currentNodeKeys?: string;
  startedAt?: string;
  startedBy?: string;
  completedAt?: string;
  semanticJson?: string;
  layoutJson?: string;
}
export interface WorkflowTask {
  id: string;
  nodeInstanceId?: string;
  nodeKey?: string;
  taskKind: string;
  taskStatus: string;
  assigneeId?: string;
  assigneeTitle?: string;
  originalAssigneeTitle?: string;
  actualProcessUserTitle?: string;
  processedByDelegation?: boolean;
  originalAssigneeId?: string;
  actualProcessorId?: string;
  assignmentKind?: string;
  decision?: string;
  completedAt?: string;
}
export interface WorkflowEvent {
  id: string;
  eventType: string;
  actionCode?: string;
  nodeKey?: string;
  operatorId?: string;
  operatorTitle?: string;
  reason?: string;
  message?: string;
  nodeInstanceId?: string;
  occurredAt?: string;
  eventText?: string;
  payloadText?: string;
}
export interface WorkflowAction {
  actionCode: string;
  title: string;
  taskId?: string;
  nodeKey?: string;
  nodeTitle?: string;
  reasonRequired: boolean;
  targetAssigneeRequired?: boolean;
  rejectReturnToMeSupported?: boolean;
  rejectResubmitModes?: string[];
  defaultRejectResubmitMode?: string;
}
export interface WorkflowStatus {
  canSubmit?: boolean;
  displayStatus: string;
  instanceId?: string;
  errorMessage?: string;
  definition?: { definitionTitle?: string; definitionAlias?: string };
}
export interface WorkflowRenderBundle {
  instance: WorkflowInstance;
  nodes: WorkflowNode[];
  routes: WorkflowRoute[];
  semanticJson?: string;
  layoutJson?: string;
}
export interface WorkflowBranch {
  branchNodeKey: string;
  branchTitle?: string;
  selectionPending?: boolean;
  selectorNodeKey?: string;
  requireManualSelectionReason?: boolean;
  candidates: Array<{
    routeKey: string;
    targetNodeKey: string;
    routeStatus: string;
    title?: string;
    targetNodeTitle?: string;
    defaultRoute?: boolean;
    conditionMatched?: boolean;
    recommended?: boolean;
  }>;
}
export interface WorkflowWorkbenchCard {
  business?: { title?: string; moduleTitle?: string; readable: boolean };
  instanceId: string;
  moduleAlias: string;
  recordId: string;
  taskId?: string;
  nodeTitle?: string;
  taskStatus?: string;
  approvalStatus?: string;
  instanceStatus: string;
  currentAssigneeTitles: string[];
  submitterUserTitle?: string;
  originalAssigneeTitle?: string;
  assignmentKind?: string;
  delegatedToUserTitle?: string;
  overtimeStatus?: string;
  startedAt?: string;
  receivedAt?: string;
  completedAt?: string;
}

export interface WorkflowHistoryInstance {
  moduleAlias: string;
  recordId: string;
  startedByTitle?: string;
  id: string;
  versionNo: number;
  instanceStatus: string;
  approvalStatus: string;
  startedAt?: string;
  archivedAt?: string;
  lastActionReason?: string;
}

export interface WorkflowWorkbenchFilters {
  moduleAlias?: string;
  submitterUserId?: string;
  overtimeStatus?: string;
  instanceStatus?: string;
  taskStatus?: string;
  receivedFrom?: string;
  receivedTo?: string;
  completedFrom?: string;
  completedTo?: string;
}

export interface WorkflowTaskPreparation {
  evaluation: {
    passed: boolean;
    failureMessage?: string;
    checkResults: Array<{ checkKey: string; passed: boolean; failureMessage?: string }>;
    guides: Array<{
      guideKey: string;
      title?: string;
      guideKind: string;
      guideConfigText?: string;
      targetModuleAlias?: string;
      targetActionCode?: string;
    }>;
  };
}
export interface WorkflowConfigurationCatalog {
  tasks: { id: string; title: string }[];
  queries: { id: string; title: string }[];
  generations: { id: string; title: string; targetModuleAlias: string }[];
  associations: { id: string; title: string }[];
}
export interface WorkflowDefinitionSelection {
  version: number;
  title: string;
  organizationId: string | null;
  matchExpression: string | null;
  matchPriority: number;
  defaultDefinition: boolean;
}
export interface WorkflowDefinitionCreate extends Omit<WorkflowDefinitionSelection, 'version'> {
  alias: string;
  approvalEnabled: boolean;
  enabled: boolean;
}
export interface WorkflowAdminInstance {
  instanceId: string;
  moduleAlias: string;
  recordId: string;
  versionNo: number;
  instanceStatus: string;
  approvalStatus: string;
  startedByTitle?: string;
  activeNodeTitles: string[];
  currentAssigneeTitles: string[];
  overtimeStatus?: string;
}
export interface WorkflowAdminTask {
  taskId: string;
  nodeTitle: string;
  assigneeTitle?: string;
  taskKind: string;
  canForceApprove: boolean;
}
export interface WorkflowAdminInstanceQuery {
  moduleAlias?: string;
  recordId?: string;
  starterId?: string;
  instanceStatus?: string;
  approvalStatus?: string;
  currentAssigneeId?: string;
  overtimeStatus?: string;
  page: { pageNum: number; pageSize: number };
}
export interface WorkflowAdminHistoryQuery {
  moduleAlias?: string;
  recordId?: string;
  startedBy?: string;
  page: { pageNum: number; pageSize: number };
}
