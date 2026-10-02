/** Requirements consensus; never an executable schema or publication approval. */
export interface ConstructionPlanContent {
  title: string;
  goal: string;
  inScope: string[];
  outOfScope: string[];
  objects: { key: string; name: string; purpose: string; moduleAlias?: string | null }[];
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
  constructionStatus: 'NOT_STARTED' | 'LINKED' | 'INITIALIZED' | 'PARTIALLY_DELIVERED' | 'DELIVERED';
  deliveredObjectKeys: string[];
  /** Current explicit associations; old snapshots may only carry historical initialization receipts. */
  moduleBindings?: { objectKey: string; moduleAlias: string }[];
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
  titleField?: boolean;
  reference?: {
    targetModuleAlias: string;
    targetMetadataId: string | null;
    targetKeyField: string;
    targetLabelField: string;
    cardinality: 'ONE';
    targetUnavailablePolicy: 'PRESERVE_HISTORY';
    requireEnabled: boolean;
    projectionMappings: string[];
  } | null;
}
export interface ConstructionFieldDescription {
  children?: Record<
    string,
    {
      relation: import('./index').ModuleMetadataRelation;
      metadataVersion: number;
      fields: import('./index').MetadataField[];
      references: Record<string, NonNullable<ConstructionField['reference']>>;
    }
  >;
  calculationRules?: { alias: string; targetField?: string | null; expression: string; version?: number }[];
  moduleAlias: string;
  planRevision: number;
  metadataVersion: number;
  references?: Record<string, NonNullable<ConstructionField['reference']>>;
  fields: import('./index').MetadataField[];
  specs: {
    alias: string;
    title: string;
    type: string;
    length: number | null;
    precision: number | null;
    scale: number | null;
  }[];
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
  childFields?: Record<string, string[]>;
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
  /** Configuration progress is not an observation of tenant business records. */
  businessDataStatus: 'NOT_QUERIED';
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
  mode: 'FIELD' | 'REQUIRED' | 'UNIQUE' | 'REFERENCE' | 'CHILD' | 'CALCULATION' | 'MANUAL' | 'UNSUPPORTED';
  fieldName: string;
  explanation: string;
  reference?: { objectKey: string; moduleAlias: string } | null;
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
    | 'MANUAL_RESPONSIBILITY';
  explanation: string;
}
export interface ConstructionTask {
  planRevision: number;
  objects: {
    objectKey: string;
    title: string;
    complete: boolean;
    options: {
      action:
        | 'REVIEW_CURRENT_CONFIGURATION'
        | 'REVIEW_REQUIREMENTS'
        | 'INITIALIZE'
        | 'VERIFY_RUNTIME'
        | 'CONFIGURE_FIELDS'
        | 'REVIEW_CONFIGURATION'
        | 'PUBLISH_PAGE'
        | 'CREATE_ENTRY'
        | 'VERIFY_BUSINESS';
      explanation: string;
    }[];
    requirements: ConstructionRequirementEvidence[];
    progress?: ConstructionProgress | null;
  }[];
}
