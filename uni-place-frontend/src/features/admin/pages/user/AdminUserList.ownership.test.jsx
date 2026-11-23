import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import AdminUserList from './AdminUserList';

const api = vi.hoisted(() => ({ users: vi.fn(), getUserDetail: vi.fn(), updateUserStatus: vi.fn(), updateUserRole: vi.fn() }));
vi.mock('../../api/adminApi', () => ({ adminApi: api }));
const member = { userId: 'synthetic-member', userNm: 'Synthetic member', accountKind: 'MEMBER', userRole: 'user', roles: ['CUSTOMER', 'OWNER'], ownerStatus: 'APPROVED', userSt: 'active' };
const staff = { userId: 'synthetic-staff', userNm: 'Synthetic staff', accountKind: 'STAFF', userRole: 'admin', roles: ['ADMIN'], ownerStatus: 'NONE', userSt: 'active' };

describe('staff account management keeps account kind separate from business entitlements', () => {
  beforeEach(() => {
    vi.resetAllMocks(); api.users.mockResolvedValue({ content: [member, staff], totalElements: 2, totalPages: 1 });
    api.getUserDetail.mockImplementation(async (id) => id === member.userId ? member : staff);
    api.updateUserStatus.mockResolvedValue(member); api.updateUserRole.mockResolvedValue({ ...staff, roles: [] });
  });
  afterEach(cleanup);
  async function open(id) {
    const row = (await screen.findByText(id, { selector: 'td' })).closest('tr'); fireEvent.click(within(row).getByRole('button', { name: '관리' }));
    await screen.findByText(/현재 권한:/);
  }
  it('shows both customer and owner roles on the same member and displays staff as a separate account kind', async () => {
    render(<AdminUserList />); await screen.findByText('CUSTOMER, OWNER');
    expect(screen.getByText('이용자 계정', { selector: 'td' })).toBeInTheDocument(); expect(screen.getByText('운영 계정', { selector: 'td' })).toBeInTheDocument();
    expect(screen.getByText('ADMIN', { selector: 'td' })).toBeInTheDocument(); expect(screen.getByText('승인', { selector: 'td' })).toBeInTheDocument();
  });
  it('lets staff suspend a member without directly granting owner, tenant or staff roles', async () => {
    render(<AdminUserList />); await open(member.userId);
    expect(screen.queryByRole('button', { name: '계정 관리 권한 사용' })).not.toBeInTheDocument();
    expect(screen.getByText('소유주 권한은 소유주 신청 확인에서 관리하며, 입주자 기능은 유효한 계약에 따라 반영됩니다.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '정지' })); fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(api.updateUserStatus).toHaveBeenCalledWith(member.userId, 'banned'));
    expect(api.updateUserRole).not.toHaveBeenCalled();
  });
  it('only offers staff administration enable/disable on a staff identity', async () => {
    render(<AdminUserList />); await open(staff.userId);
    fireEvent.click(screen.getByRole('button', { name: '계정 관리 권한 해제' })); fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(api.updateUserRole).toHaveBeenCalledWith(staff.userId, 'user'));
    expect(screen.queryByRole('button', { name: '소유주 권한 부여' })).not.toBeInTheDocument();
  });
  it('keeps a tenant-owner returned by server member filtering instead of re-filtering its legacy display alias', async () => {
    api.users.mockResolvedValue({ content: [{ ...member, userRole: 'tenant', roles: ['CUSTOMER', 'OWNER', 'TENANT'] }], totalElements: 1, totalPages: 1 });
    render(<AdminUserList />); await screen.findByText('CUSTOMER, OWNER, TENANT');
    fireEvent.change(screen.getByRole('combobox', { name: '계정 유형·입주자 필터' }), { target: { value: 'user' } });
    await waitFor(() => expect(api.users).toHaveBeenLastCalledWith(expect.objectContaining({ role: 'user' })));
    expect(await screen.findByText(member.userId, { selector: 'td' })).toBeInTheDocument();
  });
});
