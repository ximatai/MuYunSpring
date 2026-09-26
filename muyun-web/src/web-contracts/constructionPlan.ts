/** Requirements consensus; never an executable schema or publication approval. */
export interface ConstructionPlanContent {
  title: string;
  goal: string;
  inScope: string[];
  outOfScope: string[];
  objects: { key: string; name: string; purpose: string }[];
  relationships: string[];
  rules: string[];
  questions: string[];
  assumptions: string[];
  decisions: { statement: string; source: 'USER_REQUIREMENT' | 'RECOMMENDATION' }[];
  acceptanceExamples: string[];
  requirements?: ConstructionRequirement[];
}
export interface ConstructionPlanSnapshot {
  planId: string;
  revision: number;
  content: ConstructionPlanContent;
  confirmedAt: string;
  constructionStatus: 'NOT_STARTED' | 'INITIALIZED';
  initializations: ConstructionInitialization[];
  fieldChanges: ConstructionFieldReceipt[];
  deliveries: ConstructionDeliveryReceipt[];
}
export interface ConstructionPlanSummary {
  planId: string;
  title: string;
  revision: number;
  updatedAt: string;
}

export interface ConstructionInitialization {
  objectKey: string;
  planRevision: number;
  moduleAlias: string;
  metadataId: string;
  relationId: string;
  requestId: string;
}
export interface ConstructionInitializationProposal {
  planRevision: number;
  objectKey: string;
  applicationAlias: string;
  applicationTitle: string;
  moduleName: string;
}
export interface ConstructionInitializationPreview {
  proposal: ConstructionInitializationProposal;
  applicationTitle: string;
  createsApplication: boolean;
  applicationVersion: number | null;
  moduleAlias: string;
  moduleTitle: string;
  schemaName: string;
  tableName: string;
  remainingWork: string[];
  fingerprint: string;
}
export interface ConstructionInitializationResult {
  receipt: ConstructionInitialization;
  runtime: import('./index').DynamicRuntimeActivationStatus | null;
}

export interface ConstructionField {
  name: string;
  title: string;
  specAlias: string;
  required: boolean;
  unique: boolean;
  indexed: boolean;
}
export interface ConstructionFieldProposal {
  planRevision: number;
  objectKey: string;
  expectedMetadataVersion: number;
  fields: ConstructionField[];
}
export interface ConstructionFieldDescription {
  moduleAlias: string;
  planRevision: number;
  metadataVersion: number;
  fields: ConstructionField[];
  specs: {
    alias: string;
    title: string;
    type: string;
    length: number | null;
    precision: number | null;
    scale: number | null;
  }[];
}
export interface ConstructionFieldPreview {
  proposal: ConstructionFieldProposal;
  moduleAlias: string;
  fieldImpacts: unknown[];
  schemaImpacts: unknown[];
  warnings: { code: string; message: string }[];
  errors: { code: string; message: string }[];
  fingerprint: string;
}
export interface ConstructionFieldReceipt {
  requestId: string;
  objectKey: string;
  planRevision: number;
  moduleAlias: string;
  fields: ConstructionField[];
}
export interface ConstructionFieldResult {
  receipt: ConstructionFieldReceipt;
  runtime: import('./index').DynamicRuntimeActivationStatus | null;
}

export interface ConstructionDeliveryProposal {
  planRevision: number;
  objectKey: string;
  kind: 'PAGE' | 'ENTRY';
  title: string;
  listFields: string[];
  formFields: string[];
  searchFields: string[];
}
export interface ConstructionDeliveryPreview {
  proposal: ConstructionDeliveryProposal;
  moduleAlias: string;
  lines: string[];
  fingerprint: string;
}
export interface ConstructionDeliveryReceipt {
  requestId: string;
  objectKey: string;
  planRevision: number;
  kind: 'PAGE' | 'ENTRY';
  moduleAlias: string;
  pageId: string;
  variantId: string;
  revisionId: string;
  menuId: string | null;
  metadataVersion: number;
}
export interface ConstructionProgress {
  objectKey: string;
  moduleAlias: string;
  runtimeStatus: string;
  pagePublished: boolean;
  entryVisible: boolean;
  menuId: string | null;
  needsReview: boolean;
  acceptanceConfirmed: boolean;
  remainingWork: string[];
  receipts: ConstructionDeliveryReceipt[];
  requirements?: ConstructionRequirementEvidence[];
}

export interface ConstructionAcceptancePreview {
  objectKey: string;
  planRevision: number;
  checks: string[];
  fingerprint: string;
}
export interface ConstructionAcceptanceReceipt {
  requestId: string;
  objectKey: string;
  planRevision: number;
  baseline: string;
}

export interface ConstructionRequirement {
  section: 'SCOPE' | 'RULE' | 'RELATION';
  index: number;
  objectKey: string;
  mode: 'FIELD' | 'REQUIRED' | 'UNIQUE' | 'MANUAL' | 'UNSUPPORTED';
  fieldName: string;
  explanation: string;
}
export interface ConstructionRequirementEvidence {
  section: ConstructionRequirement['section'];
  index: number;
  statement: string;
  objectKey: string;
  fieldName: string;
  status:
    | 'UNMAPPED'
    | 'UNSUPPORTED'
    | 'CONFIGURATION_MISSING'
    | 'CONFIGURATION_MATCHED'
    | 'MANUAL_CHECK_REQUIRED';
  explanation: string;
}
export interface ConstructionTask {
  planRevision: number;
  objects: {
    objectKey: string;
    title: string;
    stage:
      | 'REVIEW_REQUIREMENTS'
      | 'INITIALIZE'
      | 'VERIFY_RUNTIME'
      | 'CONFIGURE_FIELDS'
      | 'REVIEW_CONFIGURATION'
      | 'PUBLISH_PAGE'
      | 'CREATE_ENTRY'
      | 'VERIFY_BUSINESS'
      | 'COMPLETE';
    nextAction: string;
    requirements: ConstructionRequirementEvidence[];
  }[];
}
