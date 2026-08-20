export interface ModuleDetailRequestState {
  activeRequestSequence: number;
  requestSequence: number;
}

export interface ModuleDetailMutationState {
  hasRecord: boolean;
  saving: boolean;
  loading: boolean;
  loadFailed: boolean;
}

export function shouldCommitModuleDetailRequest(state: ModuleDetailRequestState) {
  return state.activeRequestSequence === state.requestSequence;
}

export function canMutateModuleDetail(state: ModuleDetailMutationState) {
  return state.hasRecord && !state.saving && !state.loading && !state.loadFailed;
}
