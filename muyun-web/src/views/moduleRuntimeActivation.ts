import type { InjectionKey } from 'vue';
import type { HttpClient } from '@muyun/web-core';
import type { DynamicRuntimeActivationStatus, WebActionMessage } from '@muyun/web-contracts';

export interface ModuleActivationFeedback {
  message: WebActionMessage;
}

/** Recheck committed module configuration without coupling editors to its presentation. */
export const moduleRuntimeActivationRefreshKey: InjectionKey<
  (moduleAlias: string) => Promise<ModuleActivationFeedback | undefined>
> = Symbol('module-runtime-activation-refresh');

/** Same committed-state feedback for visible governance pages and headless editors. */
export function moduleActivationFeedback(current: DynamicRuntimeActivationStatus): ModuleActivationFeedback {
  const waiting = current.status === 'PENDING' || current.installedRevision !== current.desiredRevision;
  const text =
    current.status === 'UNTRACKED'
      ? '尚无模块运行配置生效记录'
      : current.status === 'FAILED'
        ? '模块运行配置已保存，生效失败'
        : current.status === 'PENDING'
          ? '模块运行配置已保存，正在生效'
          : current.installedRevision !== current.desiredRevision
            ? '模块运行配置已保存，当前节点尚未确认生效'
            : current.status === 'INACTIVE'
              ? '模块运行配置已生效，业务运行入口已停用'
              : '模块运行配置已生效';
  return {
    message: {
      text,
      type:
        current.status === 'FAILED'
          ? 'WARNING'
          : waiting || current.status === 'UNTRACKED'
            ? 'INFO'
            : 'SUCCESS',
    },
  };
}

export async function readModuleActivationFeedback(http: HttpClient, moduleAlias: string) {
  return moduleActivationFeedback(
    await http.request<DynamicRuntimeActivationStatus>({
      method: 'GET',
      path: `/platform.module/${encodeURIComponent(moduleAlias)}/runtime/activation`,
    }),
  );
}
