import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import RequireMember from './RequireMember';

const auth = vi.hoisted(() => ({ user: null, loading: false }));
vi.mock('../../../features/user/hooks/useAuth', () => ({ useAuth: () => auth }));
function view(allowAnonymous = false) {
  return render(<MemoryRouter initialEntries={['/consumer']}><Routes>
    <Route element={<RequireMember allowAnonymous={allowAnonymous} />}><Route path="/consumer" element={<p>Consumer work</p>} /></Route>
    <Route path="/admin" element={<p>Staff home</p>} /><Route path="/login" element={<p>Login required</p>} /><Route path="/" element={<p>No access</p>} />
  </Routes></MemoryRouter>);
}
describe('consumer and staff account boundaries', () => {
  beforeEach(() => { auth.user = null; auth.loading = false; });
  afterEach(cleanup);
  it.each([false, true])('excludes staff from consumer work with allowAnonymous=%s even when old business aliases remain', (allowAnonymous) => {
    auth.user = { accountKind: 'STAFF', userRole: 'tenant', roles: ['ADMIN', 'OWNER', 'CUSTOMER', 'TENANT'] };
    view(allowAnonymous); expect(screen.getByText('Staff home')).toBeInTheDocument();
  });
  it('allows an owner to continue consumer work without switching the account role', () => {
    auth.user = { accountKind: 'MEMBER', roles: ['CUSTOMER', 'OWNER'] };
    view(); expect(screen.getByText('Consumer work')).toBeInTheDocument();
  });
  it('denies revoked current roles even if a stale tenant alias remains', () => {
    auth.user = { accountKind: 'MEMBER', userRole: 'tenant', roles: [] };
    view(); expect(screen.getByText('No access')).toBeInTheDocument();
  });
  it('only permits anonymous and guest access on explicitly allowed tour routes', () => {
    const rendered = view(true); expect(screen.getByText('Consumer work')).toBeInTheDocument(); rendered.unmount();
    auth.user = { roles: ['GUEST'] }; const guest = view(true); expect(screen.getByText('Consumer work')).toBeInTheDocument(); guest.unmount();
    view(); expect(screen.getByText('No access')).toBeInTheDocument();
  });
  it('sends a signed-out consumer to login and waits for initial profile loading', () => {
    auth.loading = true; const rendered = view(); expect(screen.getByText('계정 정보를 확인하고 있습니다.')).toBeInTheDocument(); rendered.unmount();
    auth.loading = false; view(); expect(screen.getByText('Login required')).toBeInTheDocument();
  });
});
