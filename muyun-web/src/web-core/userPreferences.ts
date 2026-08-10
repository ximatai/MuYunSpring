export type UserPreferencePersistence = 'automatic' | 'backend';

export interface UserPreferenceSaveOptions {
  /**
   * `automatic` lets the platform choose the active persistence route.
   * `backend` reserves the preference for a user-account-backed route and never silently falls back.
   */
  persistence?: UserPreferencePersistence;
}

export interface UserPreferenceStore {
  get<T>(key: string, fallback: T): T;
  restore<T>(key: string, fallback: T, options?: UserPreferenceRestoreOptions): Promise<T>;
  set<T>(key: string, value: T, options?: UserPreferenceSaveOptions): Promise<void>;
  remove(key: string, options?: UserPreferenceSaveOptions): Promise<void>;
}

export interface UserPreferenceRestoreOptions {
  persistence?: UserPreferencePersistence;
}

/** Internal application-composition bridge; business code only uses userPreferences. */
export interface UserPreferenceBackend {
  load(key: string): Promise<string | undefined>;
  save(key: string, valueJson: string): Promise<void>;
  remove(key: string): Promise<void>;
}

const PREFERENCE_KEY_PREFIX = 'muyun.preference.';
let backend: UserPreferenceBackend | undefined;

export function configureUserPreferenceBackend(value: UserPreferenceBackend | undefined) {
  backend = value;
}

class DefaultUserPreferenceStore implements UserPreferenceStore {
  get<T>(key: string, fallback: T): T {
    try {
      const rawValue = window.localStorage.getItem(storageKeyOf(key));
      return rawValue === null ? fallback : (JSON.parse(rawValue) as T);
    } catch {
      return fallback;
    }
  }

  async restore<T>(key: string, fallback: T, options: UserPreferenceRestoreOptions = {}): Promise<T> {
    if (options.persistence !== 'backend') {
      return this.get(key, fallback);
    }
    if (!backend) {
      throw new Error(`Backend user preference persistence is not configured: ${key}`);
    }
    const valueJson = await backend.load(key);
    if (valueJson === undefined) {
      return fallback;
    }
    try {
      const value = JSON.parse(valueJson) as T;
      window.localStorage.setItem(storageKeyOf(key), valueJson);
      return value;
    } catch {
      return fallback;
    }
  }

  async set<T>(key: string, value: T, options: UserPreferenceSaveOptions = {}): Promise<void> {
    if (options.persistence === 'backend') {
      if (!backend) {
        throw new Error(`Backend user preference persistence is not configured: ${key}`);
      }
      const valueJson = JSON.stringify(value);
      await backend.save(key, valueJson);
      this.writeLocal(key, valueJson);
      return;
    }

    this.writeLocal(key, JSON.stringify(value));
  }

  async remove(key: string, options: UserPreferenceSaveOptions = {}): Promise<void> {
    if (options.persistence === 'backend') {
      if (!backend) {
        throw new Error(`Backend user preference persistence is not configured: ${key}`);
      }
      await backend.remove(key);
    }
    try {
      window.localStorage.removeItem(storageKeyOf(key));
    } catch {
      // Preference persistence is optional: callers retain their in-memory value when it is unavailable.
    }
  }

  private writeLocal(key: string, valueJson: string) {
    try {
      window.localStorage.setItem(storageKeyOf(key), valueJson);
    } catch {
      // Preference persistence is optional: callers retain their in-memory value when it is unavailable.
    }
  }
}

function storageKeyOf(key: string): string {
  return `${PREFERENCE_KEY_PREFIX}${key}`;
}

/**
 * Source-neutral user preference facade. Platform code owns the active persistence route.
 */
export const userPreferences: UserPreferenceStore = new DefaultUserPreferenceStore();
