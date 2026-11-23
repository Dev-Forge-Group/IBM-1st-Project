import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';

const { auth, owner, legacy } = vi.hoisted(() => ({
  auth: { user: null, loading: false, refresh: vi.fn() },
  owner: { getMyApplication: vi.fn(), getContracts: vi.fn(), getResidents: vi.fn(), getBuildings: vi.fn(), getRooms: vi.fn(), getSpaces: vi.fn() },
  legacy: { getContracts: vi.fn(), getResidents: vi.fn() },
}));
vi.mock('./features/user/hooks/useAuth', () => ({ useAuth: () => auth }));
vi.mock('./features/owner/api/ownerApi', () => ({ ownerApi: owner }));
vi.mock('./features/admin/api/adminApi', () => ({ adminApi: legacy }));
vi.mock('./app/layouts/components/Header', () => ({ default: () => <p>Shared header</p> }));
vi.mock('./shared/pages/Home', () => ({ default: () => <h1>Public home</h1> }));
vi.mock('./features/user/pages/MemberInfo', () => ({ default: () => <h1>Consumer account</h1> }));
vi.mock('./features/reservation/pages/TourReservationCreate', () => ({ default: () => <h1>Public tour form</h1> }));
vi.mock('./features/chat/components/ChatBot', () => ({ default: () => <p>Consumer assistant</p> }));
vi.mock('./shared/components/ScrollToTop', () => ({ default: () => null }));
// Route guards are exercised here; browser-only face capture is outside this test.
vi.mock('@vladmandic/face-api', () => ({}));

function view(path) { return render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>); }
describe('actual App router uses separate member, owner and staff workspaces', () => {
  beforeEach(() => {
    vi.clearAllMocks(); localStorage.clear(); sessionStorage.clear();
    auth.user = null; auth.loading = false; auth.refresh.mockResolvedValue(undefined);
    owner.getMyApplication.mockResolvedValue({ status: 'NONE' });
    owner.getContracts.mockResolvedValue({ content: [], totalElements: 0, totalPages: 1 }); owner.getResidents.mockResolvedValue([]);
    for (const method of ['getBuildings', 'getRooms', 'getSpaces']) owner[method].mockResolvedValue({ content: [], totalElements: 0, totalPages: 1 });
  });
  afterEach(cleanup);

  it.each(['/me', '/owner', '/reservations/tour/create', '/commerce/orders'])('keeps staff out of %s despite a stale consumer role', async (path) => {
    auth.user = { accountKind: 'STAFF', userRole: 'tenant', roles: ['ADMIN', 'OWNER', 'CUSTOMER'] };
    view(path); expect(await screen.findByRole('heading', { name: '계정 관리' })).toBeInTheDocument();
    expect(screen.queryByText('Consumer assistant')).not.toBeInTheDocument(); expect(owner.getMyApplication).not.toHaveBeenCalled();
    expect(screen.queryByRole('link', { name: '건물·방 관리' })).not.toBeInTheDocument();
  });
  it('removes retired staff business routes and exposes only account management navigation', async () => {
    auth.user = { accountKind: 'STAFF', roles: ['ADMIN'] }; view('/admin/property/buildings');
    expect(await screen.findByRole('heading', { name: '계정 관리' })).toBeInTheDocument();
    expect(screen.getAllByRole('link').every((link) => ['/admin', '/admin/users', '/admin/owners'].includes(link.getAttribute('href')))).toBe(true);
    expect(owner.getBuildings).not.toHaveBeenCalled(); expect(legacy.getContracts).not.toHaveBeenCalled();
  });
  it('does not let a member gain staff access from a stale admin alias', () => {
    auth.user = { accountKind: 'MEMBER', userRole: 'admin', roles: ['CUSTOMER', 'OWNER'] }; view('/admin/users');
    expect(screen.getByRole('heading', { name: 'Public home' })).toBeInTheDocument();
  });
  it('lets a consumer apply for ownership on the existing account and keeps owner business links gated', async () => {
    auth.user = { accountKind: 'MEMBER', roles: ['CUSTOMER'] }; view('/owner');
    expect(await screen.findByLabelText('신청 내용')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '이용자 업무로 전환' })).toHaveAttribute('href', '/me');
    expect(screen.queryByRole('link', { name: '건물·방 관리' })).not.toBeInTheDocument();
  });
  it.each([['/owner/contracts', 'getContracts'], ['/owner/residents', 'getResidents']])('injects the scoped owner API into reused %s business pages', async (path, method) => {
    auth.user = { accountKind: 'MEMBER', roles: ['CUSTOMER', 'OWNER'] }; view(path);
    await waitFor(() => expect(owner[method]).toHaveBeenCalledTimes(1));
    expect(legacy.getContracts).not.toHaveBeenCalled(); expect(legacy.getResidents).not.toHaveBeenCalled();
    expect(screen.getByRole('link', { name: '이용자 업무로 전환' })).toBeInTheDocument();
  });
  it('routes an approved owner to scoped property lists and shows pending drafts before publication', async () => {
    auth.user = { accountKind: 'MEMBER', roles: ['CUSTOMER', 'OWNER'] };
    owner.getBuildings.mockResolvedValue({ content: [{ buildingId: 31, buildingNm: 'Synthetic pending building', ownershipStatus: 'PENDING', publicVisible: false }], totalElements: 1, totalPages: 1 });
    view('/owner/property/buildings'); await screen.findByText('Synthetic pending building');
    expect(screen.getByText('확인 후 공개')).toBeInTheDocument(); expect(owner.getRooms).toHaveBeenCalled();
    expect(screen.getByRole('link', { name: '확인 정보 제출' })).toHaveAttribute('href', '/owner/claims?resourceType=BUILDING&resourceId=31');
    expect(legacy.getContracts).not.toHaveBeenCalled();
  });
  it('keeps explicitly anonymous tour access available', () => {
    view('/reservations/tour/create'); expect(screen.getByRole('heading', { name: 'Public tour form' })).toBeInTheDocument();
  });
});
