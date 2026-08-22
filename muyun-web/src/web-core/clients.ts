import type {
  ChangeOwnPasswordRequest,
  CurrentUserProfile,
  CurrentUser,
  LoginRequest,
  LoginResult,
  UpdateCurrentUserProfileRequest,
  MenuMineResponse,
  MenuOpenMode,
  MenuTreeNode,
  PageBootstrap,
  TenantBranding,
  TenantLoginContext,
} from '@muyun/web-contracts';
import type { HttpClient } from './http';

export interface SessionClient {
  current(): Promise<CurrentUser>;
  tenantBranding?(): Promise<TenantBranding>;
}

export interface MenuClient {
  mine(): Promise<MenuMineResponse>;
}

/** Reads the permission-scoped page entry selected by a concrete menu node. */
export interface PageBootstrapClient {
  byMenu(menuId: string): Promise<PageBootstrap>;
}

export interface AuthClient {
  login(request: LoginRequest): Promise<LoginResult>;
  changeOwnPassword(request: ChangeOwnPasswordRequest, token: string): Promise<void>;
  currentProfile(token: string): Promise<CurrentUserProfile>;
  updateCurrentProfile(request: UpdateCurrentUserProfileRequest, token: string): Promise<CurrentUserProfile>;
  logout(token?: string): Promise<void>;
}

/** Resolves the public context used by a tenant-locked login entry. */
export interface LoginContextClient {
  loginContext(tenantId: string): Promise<TenantLoginContext>;
}

export function createSessionClient(http: HttpClient): SessionClient {
  return {
    current: () => http.request<CurrentUser>({ path: '/iam.auth/context' }),
    tenantBranding: () => http.request<TenantBranding>({ path: '/iam.auth/tenant-branding' }),
  };
}

export function createLoginContextClient(http: HttpClient): LoginContextClient {
  return {
    loginContext: (tenantId) =>
      http.request<TenantLoginContext>({ path: '/iam.auth/login-context', query: { tenantId } }),
  };
}

export function createMenuClient(http: HttpClient): MenuClient {
  return {
    mine: async () => normalizeMenuMineResponse(await http.request<unknown>({ path: '/platform.menu/mine' })),
  };
}

export function createPageBootstrapClient(http: HttpClient): PageBootstrapClient {
  return {
    byMenu: (menuId) => {
      const normalizedMenuId = menuId.trim();
      if (!normalizedMenuId) {
        throw new Error('Page bootstrap requires a menuId');
      }
      return http.request<PageBootstrap>({
        path: `/platform.menu/${encodeURIComponent(normalizedMenuId)}/entry`,
        query: { clientType: 'WEB' },
      });
    },
  };
}

/**
 * The backend serializes Java enum values as upper-case identifiers while the
 * web contract deliberately uses lower-case literals.  Normalize once at the
 * HTTP projection boundary so every workbench consumer sees the same menu
 * contract instead of requiring application-level compatibility code.
 */
export function normalizeMenuMineResponse(response: unknown): MenuMineResponse {
  if (!isRecord(response) || !Array.isArray(response.records)) {
    return response as MenuMineResponse;
  }

  return {
    ...response,
    records: response.records.map(normalizeMenuTreeNode),
  } as MenuMineResponse;
}

function normalizeMenuTreeNode(node: unknown): MenuTreeNode {
  if (!isRecord(node)) {
    return node as MenuTreeNode;
  }

  const record = node.record;
  return {
    ...node,
    record: isRecord(record)
      ? {
          ...record,
          openMode: normalizeMenuOpenMode(record.openMode),
          entryType: normalizeMenuEntryType(record.entryType),
        }
      : record,
    children: Array.isArray(node.children) ? node.children.map(normalizeMenuTreeNode) : [],
  } as MenuTreeNode;
}

function normalizeMenuOpenMode(value: unknown): MenuOpenMode | undefined {
  if (typeof value !== 'string') {
    return undefined;
  }

  const normalized = value.toLowerCase();
  return normalized === 'tab' || normalized === 'window' ? normalized : undefined;
}

function normalizeMenuEntryType(value: unknown): 'module' | 'route' | 'link' | undefined {
  if (typeof value !== 'string') {
    return undefined;
  }

  const normalized = value.toLowerCase();
  return normalized === 'module' || normalized === 'route' || normalized === 'link' ? normalized : undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

export function createAuthClient(http: HttpClient): AuthClient {
  return {
    login: (request) => http.request<LoginResult>({ method: 'POST', path: '/iam.auth/login', body: request }),
    changeOwnPassword: (request, token) =>
      http.request<void>({
        method: 'POST',
        path: '/iam.auth/changeOwnPassword',
        body: request,
        headers: { Authorization: `Bearer ${token}` },
      }),
    currentProfile: (token) =>
      http.request<CurrentUserProfile>({
        path: '/iam.auth/profile',
        headers: { Authorization: `Bearer ${token}` },
      }),
    updateCurrentProfile: (request, token) =>
      http.request<CurrentUserProfile>({
        method: 'POST',
        path: '/iam.auth/profile',
        body: request,
        headers: { Authorization: `Bearer ${token}` },
      }),
    logout: (token) =>
      http.request<void>({
        method: 'POST',
        path: '/iam.auth/logout',
        headers: token ? { Authorization: `Bearer ${token}` } : undefined,
      }),
  };
}
