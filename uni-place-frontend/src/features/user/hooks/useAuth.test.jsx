import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  __resetAuthResumeForTest,
  AUTH_SESSION_EXPIRED_EVENT,
} from '../../../app/auth/authResume';
import { AuthProvider, useAuth } from './useAuth';
import { accountRoles } from '../../../shared/auth/accountAccess';

function jsonResponse(status, data) {
  return {
    status,
    ok: status >= 200 && status < 300,
    headers: new Headers({ 'Content-Type': 'application/json' }),
    json: async () => data,
  };
}

describe('AuthProvider session restoration', () => {
  beforeEach(() => {
    localStorage.clear();
    __resetAuthResumeForTest();
    localStorage.setItem('access_token', 'old-access');
    localStorage.setItem('refresh_token', 'refresh-1');
    localStorage.setItem('device_id', 'device-1');
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it.each([429, 500, 503])(
    'keeps credentials when the profile request returns HTTP %s, then restores on retry',
    async (status) => {
      const user = { userId: 'user-1', userNickname: 'Member' };
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(status, { success: false }))
        .mockResolvedValueOnce(jsonResponse(200, { success: true, data: user }));
      vi.stubGlobal('fetch', fetchMock);

      const { result } = renderHook(() => useAuth(), { wrapper: AuthProvider });
      await waitFor(() => expect(result.current.loading).toBe(false));
      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(localStorage.getItem('refresh_token')).toBe('refresh-1');

      await act(() => result.current.refresh());
      expect(result.current.user).toEqual(user);
      expect(localStorage.getItem('access_token')).toBe('old-access');
    }
  );

  it('keeps credentials when automatic refresh is unavailable, without a second refresh', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401, { success: false }))
      .mockResolvedValueOnce(jsonResponse(503, { success: false }));
    vi.stubGlobal('fetch', fetchMock);
    const onExpired = vi.fn();
    window.addEventListener(AUTH_SESSION_EXPIRED_EVENT, onExpired);

    try {
      const { result } = renderHook(() => useAuth(), { wrapper: AuthProvider });
      await waitFor(() => expect(result.current.loading).toBe(false));
      expect(fetchMock).toHaveBeenCalledTimes(2);
      expect(localStorage.getItem('access_token')).toBe('old-access');
      expect(localStorage.getItem('refresh_token')).toBe('refresh-1');
      expect(onExpired).not.toHaveBeenCalled();
    } finally {
      window.removeEventListener(AUTH_SESSION_EXPIRED_EVENT, onExpired);
    }
  });

  it('keeps credentials after a network failure', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    vi.stubGlobal('fetch', fetchMock);
    const { result } = renderHook(() => useAuth(), { wrapper: AuthProvider });

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem('refresh_token')).toBe('refresh-1');
  });

  it('clears the session when the server rejects the refresh credential', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse(401, { success: false }))
    );
    const { result } = renderHook(() => useAuth(), { wrapper: AuthProvider });

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.user).toBeNull();
    expect(localStorage.getItem('access_token')).toBeNull();
    expect(localStorage.getItem('refresh_token')).toBeNull();
  });

  it('replaces current account roles after owner approval and revocation through an uncached profile refresh', async () => {
    const member = { userId: 'synthetic-member', accountKind: 'MEMBER', userRole: 'user', roles: ['CUSTOMER'], ownerStatus: 'PENDING' };
    const approved = { ...member, roles: ['CUSTOMER', 'OWNER'], ownerStatus: 'APPROVED' };
    const revoked = { ...member, userRole: 'tenant', roles: [], ownerStatus: 'REJECTED' };
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(200, { success: true, data: member }))
      .mockResolvedValueOnce(jsonResponse(200, { success: true, data: approved }))
      .mockResolvedValueOnce(jsonResponse(200, { success: true, data: revoked }));
    vi.stubGlobal('fetch', fetchMock);
    const { result } = renderHook(() => useAuth(), { wrapper: AuthProvider });
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(accountRoles(result.current.user)).toEqual(['CUSTOMER']);
    await act(() => result.current.refresh()); expect(accountRoles(result.current.user)).toEqual(['CUSTOMER', 'OWNER']);
    await act(() => result.current.refresh()); expect(accountRoles(result.current.user)).toEqual([]);
    expect(fetchMock.mock.calls.every(([path, options]) => path.endsWith('/users/me') && options.cache === 'no-store')).toBe(true);
    expect(localStorage.getItem('access_token')).toBe('old-access');
  });
});
