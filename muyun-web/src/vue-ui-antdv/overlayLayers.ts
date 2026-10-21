/** Notifications remain above page overlays; their own confirmations must remain actionable. */
export const uiOverlayLayers = {
  notification: 1200,
  notificationConfirmation: 1210,
} as const;
