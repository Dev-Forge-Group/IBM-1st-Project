import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import RequireRole from './RequireRole';

const auth = vi.hoisted(() => ({ user: null, loading: false }));
vi.mock('../../../features/user/hooks/useAuth', () => ({ useAuth: () => auth }));

function view(allow = ['owner']) {
  return render(<MemoryRouter initialEntries={['/protected']}><Routes>
    <Route element={<RequireRole allow={allow} />}>
      <Route path="/protected" element={<p>Protected workspace</p>} />
    </Route>
    <Route path="/" element={<p>Access denied</p>} />
    <Route path="/login" element={<p>Login required</p>} />
  </Routes></MemoryRouter>);
}

describe('account roles and staff workspace boundaries', () => {
  beforeEach(() => { auth.user = null; auth.loading = false; });
  afterEach(cleanup);

  it('allows an additional owner role while the primary role remains a consumer', () => {
    auth.user = { accountKind: 'MEMBER', userRole: 'user', roles: ['CUSTOMER', 'OWNER'] };
    view();
    expect(screen.getByText('Protected workspace')).toBeInTheDocument();
  });

  it('allows an active tenant entitlement without requiring a mutually exclusive primary role', () => {
    auth.user = { accountKind: 'MEMBER', userRole: 'user', roles: ['CUSTOMER', 'OWNER', 'TENANT'] };
    view(['tenant']);
    expect(screen.getByText('Protected workspace')).toBeInTheDocument();
  });

  it('treats an explicitly empty roles list as authoritative over a stale primary role', () => {
    auth.user = { accountKind: 'MEMBER', userRole: 'tenant', roles: [] };
    view(['tenant']);
    expect(screen.getByText('Access denied')).toBeInTheDocument();
  });

  it('does not turn a staff identity into an owner workspace through a business role', () => {
    auth.user = { accountKind: 'STAFF', userRole: 'owner', roles: ['OWNER', 'CUSTOMER'] };
    view();
    expect(screen.getByText('Access denied')).toBeInTheDocument();
  });

  it('does not grant account administration to a member identity', () => {
    auth.user = { accountKind: 'MEMBER', userRole: 'admin', roles: ['ADMIN', 'CUSTOMER'] };
    view(['admin']);
    expect(screen.getByText('Access denied')).toBeInTheDocument();
  });

  it('allows an authorized staff account to manage accounts', () => {
    auth.user = { accountKind: 'STAFF', userRole: 'admin', roles: ['ADMIN'] };
    view(['admin']);
    expect(screen.getByText('Protected workspace')).toBeInTheDocument();
  });

  it('preserves the legacy role response during the compatibility transition', () => {
    auth.user = { userRole: 'tenant' };
    view(['tenant']);
    expect(screen.getByText('Protected workspace')).toBeInTheDocument();
  });

  it('waits for profile loading and sends a signed-out user to login', () => {
    auth.loading = true;
    const first = view();
    expect(screen.queryByText('Protected workspace')).not.toBeInTheDocument();
    first.unmount();
    auth.loading = false;
    view();
    expect(screen.getByText('Login required')).toBeInTheDocument();
  });
});
