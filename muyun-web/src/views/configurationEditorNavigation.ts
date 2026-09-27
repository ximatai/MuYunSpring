import { nextTick, watch } from 'vue';

/** Lazy page loading may finish after the router commits; resume only once the shared editor is mounted. */
export async function waitForConfigurationEditor(visible: () => boolean, signal?: AbortSignal) {
  if (signal?.aborted) throw new DOMException('Navigation cancelled', 'AbortError');
  if (!visible()) {
    await new Promise<void>((resolve, reject) => {
      const finish = (error?: Error) => {
        stop();
        clearTimeout(timeout);
        signal?.removeEventListener('abort', abort);
        if (error) reject(error);
        else resolve();
      };
      const abort = () => finish(new DOMException('Navigation cancelled', 'AbortError'));
      const stop = watch(
        visible,
        (ready) => {
          if (ready) finish();
        },
        { flush: 'post' },
      );
      const timeout = setTimeout(() => finish(new Error('配置页面尚未就绪，请重新打开后继续。')), 15_000);
      signal?.addEventListener('abort', abort, { once: true });
    });
  }
  await nextTick();
  if (signal?.aborted) throw new DOMException('Navigation cancelled', 'AbortError');
}
