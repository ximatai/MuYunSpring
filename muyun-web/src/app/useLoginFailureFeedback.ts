import { computed, onScopeDispose, ref } from 'vue';
import { AppError, platformErrorCodes, useLanguageContext } from '@muyun/web-core';

/** Standard password-login feedback. Countdown expiry restores the form; it never retries credentials. */
export function useLoginFailureFeedback() {
  const language = useLanguageContext();
  const lock = ref<{ deadline: number; until: number; triggered: boolean }>();
  const remainingAttempts = ref<number>();
  const tick = ref(0);
  let timer: ReturnType<typeof setInterval> | undefined;
  let disposed = false;
  const locked = computed(() => Boolean(lock.value));

  function clear() {
    if (timer !== undefined) clearInterval(timer);
    timer = undefined;
    lock.value = undefined;
    remainingAttempts.value = undefined;
  }
  onScopeDispose(() => {
    disposed = true;
    clear();
  });

  const message = computed(() => {
    if (lock.value) {
      const seconds = Math.max(0, Math.ceil((lock.value.deadline - tick.value) / 1000));
      const remaining = `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
      return lock.value.triggered
        ? language.t(
            'platform.screen.LoginView.lock-triggered',
            '连续登录失败，账号已临时锁定。{remaining} 后可重试。',
            { remaining },
          )
        : language.t(
            'platform.screen.LoginView.account-locked',
            '账号暂时锁定，{remaining} 后可重试。急需登录请联系管理员。',
            { remaining },
          );
    }
    if (remainingAttempts.value !== undefined) {
      return language.t(
        'platform.screen.LoginView.attempts-remaining',
        '用户名或密码错误。还可尝试 {count} 次，之后将临时锁定。',
        { count: remainingAttempts.value },
      );
    }
    return undefined;
  });
  const unlockTime = computed(() =>
    lock.value
      ? language.t('platform.screen.LoginView.unlock-time', '可重试时间：{time}', {
          time: new Date(lock.value.until).toLocaleString(language.languageTag.value, { hour12: false }),
        })
      : undefined,
  );

  function accept(cause: unknown): boolean {
    if (disposed) return true;
    clear();
    if (!(cause instanceof AppError) || cause.code !== platformErrorCodes.loginBadCredentials) return false;
    const feedback = cause.details?.loginFailure;
    if (!feedback || typeof feedback !== 'object' || Array.isArray(feedback)) return false;
    const facts = feedback as Record<string, unknown>;
    if (typeof facts.lockedUntil === 'string' && typeof facts.serverTime === 'string') {
      const until = Date.parse(facts.lockedUntil);
      const serverTime = Date.parse(facts.serverTime);
      if (!Number.isFinite(until) || !Number.isFinite(serverTime)) return false;
      const duration = until - serverTime;
      // A response whose lock has already expired does not need an error notice.
      if (duration <= 0) return true;
      tick.value = performance.now();
      lock.value = { deadline: tick.value + duration, until, triggered: facts.lockTriggered === true };
      timer = setInterval(() => {
        tick.value = performance.now();
        if (lock.value && tick.value >= lock.value.deadline) clear();
      }, 250);
      return true;
    }
    if (
      typeof facts.remainingAttempts === 'number' &&
      Number.isSafeInteger(facts.remainingAttempts) &&
      facts.remainingAttempts > 0
    ) {
      remainingAttempts.value = facts.remainingAttempts;
      return true;
    }
    return false;
  }
  return { locked, message, unlockTime, accept, clear };
}
