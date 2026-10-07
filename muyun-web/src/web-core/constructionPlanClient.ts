import type {
  ConstructionPlanContent,
  ReferenceTargetFieldCatalog,
  ConstructionTask,
  ConstructionAcceptancePreview,
  ConstructionAcceptanceReceipt,
  ConstructionDeliveryReceipt,
  ConstructionProgress,
  ConstructionFieldDescription,
  ConstructionFieldResult,
  ConstructionInitializationResult,
  ConstructionPlanSnapshot,
  ConstructionPlanSummary,
} from '@muyun/web-contracts';
import type { HttpClient } from './http';
export interface ConstructionPlanClient {
  designContract(): Promise<{
    recordName: { fieldName: string; columnName: string; fieldType: string };
    inheritedFields: string[];
    declarableCapabilities: {
      capabilities: string[];
      metadataFields: {
        fieldName: string;
        columnName: string;
        fieldSpecAlias: string;
        defaultKind: string;
        defaultDescription: string;
      }[];
    };
  }>;
  businessObjects(): Promise<
    {
      alias: string;
      title: string;
      applicationAlias: string;
      applicationTitle: string | null;
      kind: string;
      referenceReady: boolean;
      explanation: string;
    }[]
  >;
  referenceTarget(moduleAlias: string): Promise<ReferenceTargetFieldCatalog>;
  task(id: string): Promise<ConstructionTask>;
  previewAcceptance(id: string, objectKey: string): Promise<ConstructionAcceptancePreview>;
  confirmAcceptance(
    id: string,
    command: { requestId: string; objectKey: string; fingerprint: string },
  ): Promise<ConstructionAcceptanceReceipt>;
  acceptance(id: string, requestId: string): Promise<ConstructionAcceptanceReceipt | undefined>;
  delivery(id: string, requestId: string): Promise<ConstructionDeliveryReceipt | undefined>;
  progress(id: string, objectKey: string): Promise<ConstructionProgress>;
  describeFields(id: string, objectKey: string): Promise<ConstructionFieldDescription>;
  fieldChange(id: string, requestId: string): Promise<ConstructionFieldResult | undefined>;
  initialization(id: string, objectKey: string): Promise<ConstructionInitializationResult | undefined>;
  list(): Promise<ConstructionPlanSummary[]>;
  read(id: string): Promise<ConstructionPlanSnapshot>;
  history(id: string): Promise<ConstructionPlanSnapshot[]>;
  confirm(
    id: string,
    command: { requestId: string; expectedRevision: number; content: ConstructionPlanContent },
  ): Promise<ConstructionPlanSnapshot>;
  confirmation(id: string, requestId: string): Promise<ConstructionPlanSnapshot | undefined>;
}
export function createConstructionPlanClient(http: HttpClient): ConstructionPlanClient {
  const root = '/platform.application-construction-plans';
  const path = (id: string) => `${root}/${encodeURIComponent(id)}`;
  return {
    designContract: () => http.request({ path: `${root}/design-contract` }),
    businessObjects: () => http.request({ path: `${root}/business-objects` }),
    referenceTarget: (moduleAlias) =>
      http.request({ path: `${root}/reference-target`, query: { moduleAlias } }),
    task: (id) => http.request({ path: `${path(id)}/task` }),
    previewAcceptance: (id, objectKey) =>
      http.request({ path: `${path(id)}/objects/${encodeURIComponent(objectKey)}/acceptance-preview` }),
    confirmAcceptance: (id, command) =>
      http.request({ path: `${path(id)}/acceptances`, method: 'POST', body: command }),
    acceptance: (id, requestId) =>
      http.request({ path: `${path(id)}/acceptances/${encodeURIComponent(requestId)}` }),
    delivery: (id, requestId) =>
      http.request({ path: `${path(id)}/delivery/${encodeURIComponent(requestId)}` }),
    progress: (id, objectKey) =>
      http.request({ path: `${path(id)}/objects/${encodeURIComponent(objectKey)}/progress` }),
    describeFields: (id, objectKey) =>
      http.request({ path: `${path(id)}/objects/${encodeURIComponent(objectKey)}/fields` }),
    fieldChange: (id, requestId) =>
      http.request({ path: `${path(id)}/field-changes/${encodeURIComponent(requestId)}` }),
    initialization: (id, objectKey) =>
      http.request({ path: `${path(id)}/initializations/${encodeURIComponent(objectKey)}` }),
    list: () => http.request({ path: root }),
    read: (id) => http.request({ path: path(id) }),
    history: (id) => http.request({ path: `${path(id)}/revisions` }),
    confirm: (id, command) =>
      http.request({ path: `${path(id)}/confirmations`, method: 'POST', body: command }),
    confirmation: (id, requestId) =>
      http.request({ path: `${path(id)}/confirmations/${encodeURIComponent(requestId)}` }),
  };
}
