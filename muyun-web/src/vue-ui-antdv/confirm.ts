import { Input, Modal } from 'ant-design-vue';
import { h, watch } from 'vue';
import { platformLanguageContext, type LanguageContext } from '@muyun/web-core';
import type { UiConfirmOptions } from './types';
import { registerUiBlockingOverlay } from './blockingOverlays';

export const confirmAction = createConfirmAction(Modal.confirm);

export function createConfirmAction(confirm: typeof Modal.confirm) {
  return function confirmAction(
    options: UiConfirmOptions,
    language: LanguageContext = platformLanguageContext,
  ): Promise<boolean> {
    return new Promise((resolve, reject) => {
      const overlay = registerUiBlockingOverlay();
      overlay.setVisible(true);
      const requiredText = options.requiredText;
      let typedText = '';
      let modal: ReturnType<typeof confirm>;
      let stopLanguageWatch: (() => void) | undefined;
      try {
        modal = confirm({
          title: options.title,
          content:
            requiredText || options.details
              ? () =>
                  h('div', [
                    options.content
                      ? h('p', { style: { whiteSpace: 'pre-line' } }, options.content)
                      : undefined,
                    requiredText
                      ? h(
                          'p',
                          language.t('platform.confirm.required-text', '请输入「{text}」以确认此操作。', {
                            text: requiredText,
                          }),
                        )
                      : undefined,
                    requiredText
                      ? h(Input, {
                          value: typedText,
                          placeholder: requiredText,
                          autofocus: true,
                          'onUpdate:value': (value: string) => {
                            typedText = value;
                            modal.update({
                              okButtonProps: {
                                danger: options.danger,
                                disabled: !matchesRequiredText(requiredText, typedText),
                              },
                            });
                          },
                        })
                      : undefined,
                    options.details
                      ? h('details', [
                          h('summary', options.details.title),
                          h(
                            'div',
                            { style: { whiteSpace: 'pre-line', maxHeight: '45vh', overflow: 'auto' } },
                            options.details.lines.join('\n'),
                          ),
                        ])
                      : undefined,
                  ])
              : options.content,
          okText: options.okText ?? language.t('platform.confirm.confirm', '确认'),
          cancelText: options.cancelText ?? language.t('platform.confirm.cancel', '取消'),
          okButtonProps: {
            danger: options.danger,
            disabled: Boolean(requiredText),
          },
          onOk: () => {
            if (!matchesRequiredText(requiredText, typedText)) {
              return Promise.reject(new Error('Confirmation text does not match'));
            }
            resolve(true);
          },
          onCancel: () => resolve(false),
          afterClose: () => {
            stopLanguageWatch?.();
            overlay.release();
          },
        });
        stopLanguageWatch = watch(language.revision, () =>
          modal.update({
            okText: options.okText ?? language.t('platform.confirm.confirm', '确认'),
            cancelText: options.cancelText ?? language.t('platform.confirm.cancel', '取消'),
          }),
        );
      } catch (cause) {
        stopLanguageWatch?.();
        overlay.release();
        reject(cause);
      }
    });
  };
}

export function matchesRequiredText(requiredText: string | undefined, typedText: string) {
  return requiredText === undefined || typedText === requiredText;
}
