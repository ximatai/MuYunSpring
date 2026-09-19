import { notification } from 'ant-design-vue';
import { h } from 'vue';

export type UiFeedbackTone = 'error' | 'success' | 'info' | 'warning';

export interface UiFeedbackOptions {
  tone: UiFeedbackTone;
  content: string;
}

const DEFAULT_DURATION_SECONDS = 2.6;
// Align feedback with the workbench tab strip near the 62px header boundary,
// keeping short-lived notices away from the current page action row.
const FEEDBACK_TOP_OFFSET = '60px';

// Ant Design Vue positions the shared notification container, rather than each
// notice. Configure it before the first notice is created so every top placement
// starts below the workbench header.
notification.config({ top: FEEDBACK_TOP_OFFSET });

export function showFeedback(options: UiFeedbackOptions) {
  if (typeof document === 'undefined') {
    return;
  }
  notification[options.tone]({
    message: () => feedbackContent(options.content, options.tone),
    duration: DEFAULT_DURATION_SECONDS,
    placement: options.tone === 'error' || options.tone === 'warning' ? 'top' : 'topRight',
    class: `muyun-feedback-notification muyun-feedback-notification-${options.tone}`,
    style: compactFeedbackStyle,
  });
}

const compactFeedbackStyle = {
  width: 'fit-content',
  maxWidth: 'calc(100vw - 40px)',
  minHeight: '36px',
  padding: '6px 36px 6px 12px',
  marginBottom: '8px',
};

export function showErrorMessage(content: string) {
  showFeedback({ tone: 'error', content });
}

export function showSuccessMessage(content: string) {
  showFeedback({ tone: 'success', content });
}

export function showInfoMessage(content: string) {
  showFeedback({ tone: 'info', content });
}

export function showWarningMessage(content: string) {
  showFeedback({ tone: 'warning', content });
}

function feedbackContent(content: string, tone: UiFeedbackTone) {
  return h('span', { class: `muyun-feedback-content muyun-feedback-${tone}` }, [
    h('span', { class: 'muyun-feedback-text' }, content),
    h('span', {
      class: 'muyun-feedback-timebar',
      'aria-hidden': 'true',
      style: { '--muyun-feedback-duration': `${DEFAULT_DURATION_SECONDS}s` },
    }),
  ]);
}
