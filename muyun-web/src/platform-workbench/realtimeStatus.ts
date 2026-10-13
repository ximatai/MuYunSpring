import { platformLanguageContext, type LanguageContext } from '@muyun/web-core';
export type WorkbenchRealtimeStatus = 'unavailable' | 'connecting' | 'connected' | 'disconnected';

export interface WorkbenchRealtimeStatusPresentation {
  label: string;
  title: string;
  tone: Exclude<WorkbenchRealtimeStatus, 'unavailable'>;
}

export function presentWorkbenchRealtimeStatus(
  status: WorkbenchRealtimeStatus,
  language: LanguageContext = platformLanguageContext,
): WorkbenchRealtimeStatusPresentation | undefined {
  if (status === 'connected') {
    return {
      label: language.t('platform.screen.realtimeStatus.live-connection-active', '实时连接正常'),
      title: language.t(
        'platform.screen.realtimeStatus.connected-to-the-platform-live-service',
        '与平台实时服务连接正常',
      ),
      tone: status,
    };
  }
  if (status === 'connecting') {
    return {
      label: language.t('platform.screen.realtimeStatus.connecting', '实时连接中'),
      title: language.t(
        'platform.screen.realtimeStatus.connecting-to-the-platform-live-service',
        '正在连接平台实时服务',
      ),
      tone: status,
    };
  }
  if (status === 'disconnected') {
    return {
      label: language.t('platform.screen.realtimeStatus.live-connection-disconnected', '实时连接已断开'),
      title: language.t(
        'platform.screen.realtimeStatus.disconnected-from-the-platform-live-service-waiting-to',
        '与平台实时服务的连接已断开，正在等待恢复',
      ),
      tone: status,
    };
  }
  return undefined;
}
