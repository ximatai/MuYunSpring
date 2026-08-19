/**
 * @deprecated Standard module pages use `moduleDetailStateModel`. Remove after
 * extensions built before the rename have migrated their state-model imports.
 */
export {
  canMutateModuleDetail as canMutateDynamicModuleDetail,
  shouldCommitModuleDetailRequest as shouldCommitDynamicModuleDetailRequest,
  type ModuleDetailMutationState as DynamicModuleDetailMutationState,
  type ModuleDetailRequestState as DynamicModuleDetailRequestState,
} from './moduleDetailStateModel';
