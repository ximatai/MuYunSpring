export type UserDetailMode = 'view' | 'create' | 'edit' | 'resetPassword';

export interface UserDetailRequestState {
  activeRequestSeq: number;
  requestSeq: number;
  selectedUserKey?: string;
  recordId: string;
}

/** 只有仍属于当前用户和当前请求的结果才能写入页面。 */
export function shouldCommitUserDetailRequest(state: UserDetailRequestState) {
  return state.activeRequestSeq === state.requestSeq && state.selectedUserKey === state.recordId;
}
