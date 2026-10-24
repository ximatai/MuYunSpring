import { nextTick, onBeforeUnmount, onMounted, ref, type Ref } from 'vue';

/** Keep cards on a continuous path when the bell changes their layout. */
export function useNotificationDeckMotion(host: Ref<HTMLElement | undefined>, expanded: Ref<boolean>) {
  const moving = ref(false);
  let revision = 0;
  let animations: Animation[] = [];
  let hiddenCards: HTMLElement[] = [];
  let preference: MediaQueryList | undefined;
  const duration = 300;
  const stagger = 35;
  const easing = 'cubic-bezier(0.22, 0.8, 0.2, 1)';
  const cards = () => [...(host.value?.querySelectorAll<HTMLElement>('[data-notification-id]') ?? [])];
  function stop() {
    revision++;
    for (const animation of animations) animation.cancel();
    animations = [];
    for (const card of hiddenCards) card.style.visibility = '';
    hiddenCards = [];
    moving.value = false;
  }
  async function arrange(open: boolean) {
    if (expanded.value === open) return false;
    // Read the current visual positions before cancellation, including a partly finished move.
    const viewport = host.value?.getBoundingClientRect();
    const interrupted = moving.value;
    const previousCards = cards();
    const before = new Map(
      previousCards
        .filter((card) => card.style.visibility !== 'hidden')
        .map((card) => {
          const style = getComputedStyle(card);
          return [
            card.dataset.notificationId!,
            {
              rect: card.getBoundingClientRect(),
              opacity: Number(style.opacity),
              clip: style.clipPath === 'none' ? 'inset(0)' : style.clipPath,
            },
          ] as const;
        }),
    );
    const origin = previousCards.find((card) =>
      card.classList.contains('business-notification-deck-card--top'),
    );
    const stack = origin?.getBoundingClientRect();
    const previousSurface = origin?.querySelector('.business-notification-card');
    const previousShadow = previousSurface ? getComputedStyle(previousSurface).boxShadow : 'none';
    stop();
    const current = revision;
    const animate = typeof host.value?.animate === 'function' && !preference?.matches;
    moving.value = animate;
    expanded.value = open;
    await nextTick();
    if (current !== revision) return false;
    if (!animate) return true;
    const visible = cards().filter((card) => !card.hidden && card.getBoundingClientRect().width > 0);
    const delayFor = (index: number) => Math.min(open ? index : visible.length - 1 - index, 5) * stagger;
    const lastDelay = Math.min(visible.length - 1, 5) * stagger;
    for (const [index, card] of visible.entries()) {
      const to = card.getBoundingClientRect();
      const previous = before.get(card.dataset.notificationId!);
      const from = previous?.rect.width ? previous.rect : stack;
      if (!from?.width || !to.width || !to.height) continue;
      // Cards outside the old scroll viewport must not reappear when clipping is removed.
      if (
        !open &&
        viewport &&
        !card.classList.contains('business-notification-deck-card--top') &&
        (from.bottom <= viewport.top || from.top >= viewport.bottom)
      ) {
        card.style.visibility = 'hidden';
        hiddenCards.push(card);
        continue;
      }
      const clip =
        interrupted && previous
          ? previous.clip
          : !open && viewport
            ? `inset(${Math.min(1, Math.max(0, viewport.top - from.top) / from.height) * 100}% 0 ${Math.min(1, Math.max(0, from.bottom - viewport.bottom) / from.height) * 100}% 0)`
            : 'inset(0)';
      const base = getComputedStyle(card).transform;
      const delay = delayFor(index);
      const alpha = previous?.opacity ?? 1;
      if (!card.classList.contains('business-notification-deck-card--top') && (!open || alpha < 1)) {
        // Keep the front card opaque; fade retiring cards only in the second half.
        animations.push(
          card.animate(
            open
              ? [{ opacity: alpha }, { opacity: 1 }]
              : [
                  { opacity: alpha, offset: 0 },
                  { opacity: alpha, offset: 0.5 },
                  { opacity: 0, offset: 1 },
                ],
            { duration, delay, easing: 'linear', fill: 'both' },
          ),
        );
      }
      animations.push(
        card.animate(
          [
            {
              clipPath: clip,
              transform: `translate(${from.left - to.left}px, ${from.top - to.top}px) ${base === 'none' ? '' : base} scale(${from.width / to.width}, ${from.height / to.height})`,
            },
            { transform: base, clipPath: 'inset(0)' },
          ],
          {
            duration,
            delay,
            easing,
            fill: 'both',
          },
        ),
      );
    }
    const front = host.value?.querySelector<HTMLElement>(
      '.business-notification-deck-card--top .business-notification-card',
    );
    if (front) {
      const shadow = getComputedStyle(front).boxShadow;
      animations.push(
        front.animate([{ boxShadow: previousShadow }, { boxShadow: shadow }], {
          duration,
          delay: open ? 0 : lastDelay,
          easing,
          fill: 'both',
        }),
      );
    }
    // Land on the actual destination edges instead of replacing faded cards at completion.
    for (const edge of host.value?.querySelectorAll<HTMLElement>('[data-notification-stack-edge]') ?? []) {
      animations.push(
        edge.animate([{ opacity: 0 }, { opacity: 1 }], {
          duration: duration / 2,
          delay: duration / 2 + lastDelay,
          easing: 'ease-out',
          fill: 'both',
        }),
      );
    }
    await Promise.all(animations.map((animation) => animation.finished.catch(() => undefined)));
    if (current !== revision) return false;
    stop();
    return true;
  }
  onMounted(() => {
    preference = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    preference?.addEventListener?.('change', stop);
    window.addEventListener('resize', stop);
  });
  onBeforeUnmount(() => {
    preference?.removeEventListener?.('change', stop);
    window.removeEventListener('resize', stop);
    stop();
  });
  return { moving, arrange, stop };
}
