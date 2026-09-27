/** Configuration directory shared by standard editors and application construction. */
export interface ReferenceTargetFieldCandidate {
  fieldName: string;
  title: string;
  defaultField: boolean;
  selectable: boolean;
}
export interface ReferenceTargetFieldCatalog {
  targetModuleAlias: string;
  targetMetadataId: string | null;
  keyFields: ReferenceTargetFieldCandidate[];
  labelFields: ReferenceTargetFieldCandidate[];
}
