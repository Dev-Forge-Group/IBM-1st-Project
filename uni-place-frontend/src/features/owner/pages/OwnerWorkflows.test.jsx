import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import OwnerApplication from './OwnerApplication';
import OwnerAssetClaims from './OwnerAssetClaims';
import OwnerApprovalManagement from './OwnerApprovalManagement';

const { api, auth } = vi.hoisted(() => ({
  api: { getMyApplication: vi.fn(), apply: vi.fn(), getAssetClaims: vi.fn(), claimAsset: vi.fn(), getApplications: vi.fn(), reviewApplication: vi.fn(), getAllAssetClaims: vi.fn(), reviewAssetClaim: vi.fn() },
  auth: { user: { accountKind: 'MEMBER', roles: ['CUSTOMER'] }, refresh: vi.fn() },
}));
vi.mock('../api/ownerApi', () => ({ ownerApi: api }));
vi.mock('../../user/hooks/useAuth', () => ({ useAuth: () => auth }));
const claim = { membershipId: 7, resourceType: 'BUILDING', resourceId: 31, resourceName: 'Synthetic building', userId: 'member-one', userNm: 'Synthetic owner', status: 'PENDING', origin: 'CREATED', proofReference: 'Synthetic evidence' };
function view(Page) { return render(<MemoryRouter><Page /></MemoryRouter>); }

describe('owner qualification, asset verification and staff review workflows', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    auth.user = { accountKind: 'MEMBER', roles: ['CUSTOMER'] }; auth.refresh.mockResolvedValue(undefined);
    api.getMyApplication.mockResolvedValue({ status: 'NONE' });
    api.getAssetClaims.mockResolvedValue([]); api.getApplications.mockResolvedValue([]); api.getAllAssetClaims.mockResolvedValue([]);
  });
  afterEach(cleanup);

  it('applies on the same member account, shows pending status, and refreshes current account roles', async () => {
    api.apply.mockResolvedValue({ status: 'PENDING', applicationNote: 'Synthetic ownership application' });
    view(OwnerApplication);
    fireEvent.change(await screen.findByLabelText('신청 내용'), { target: { value: '  Synthetic ownership application  ' } });
    fireEvent.click(screen.getByRole('button', { name: '소유주 신청하기' }));
    await screen.findByText('확인 중', { selector: 'strong' });
    expect(api.apply).toHaveBeenCalledWith('Synthetic ownership application'); expect(auth.refresh).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('link', { name: '내 건물·방 관리' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: '이용자 업무' })).toHaveAttribute('href', '/me');
  });

  it('allows a rejected qualification to be corrected and resubmitted', async () => {
    api.getMyApplication.mockResolvedValue({ status: 'REJECTED', applicationNote: 'Old synthetic note', reviewNote: 'Synthetic missing evidence' });
    api.apply.mockResolvedValue({ status: 'PENDING' });
    view(OwnerApplication); const note = await screen.findByLabelText('신청 내용');
    expect(screen.getByText('확인 의견: Synthetic missing evidence')).toBeInTheDocument();
    fireEvent.change(note, { target: { value: 'Corrected synthetic note' } }); fireEvent.click(screen.getByRole('button', { name: '다시 신청' }));
    await waitFor(() => expect(api.apply).toHaveBeenCalledWith('Corrected synthetic note'));
  });

  it('saves a correction while qualification is pending and prevents an unchanged resubmission', async () => {
    api.getMyApplication.mockResolvedValue({ status: 'PENDING', applicationNote: 'Original application evidence' });
    api.apply.mockResolvedValue({ status: 'PENDING', applicationNote: 'Corrected application evidence' });
    view(OwnerApplication);

    const note = await screen.findByLabelText('신청 내용');
    const save = screen.getByRole('button', { name: '신청 내용 수정' });
    expect(note).toHaveValue('Original application evidence');
    expect(save).toBeDisabled();
    fireEvent.change(note, { target: { value: '  Corrected application evidence  ' } });
    fireEvent.click(save);

    await screen.findByText('신청 내용을 수정했습니다.');
    expect(api.apply).toHaveBeenCalledTimes(1);
    expect(api.apply).toHaveBeenCalledWith('Corrected application evidence');
    expect(note).toHaveValue('Corrected application evidence');
    expect(await screen.findByRole('button', { name: '신청 내용 수정' })).toBeDisabled();
  });

  it('retries a failed status lookup and refreshes effective roles after staff approval', async () => {
    api.getMyApplication.mockRejectedValueOnce(new Error('Synthetic lookup failure')).mockResolvedValue({ status: 'APPROVED' });
    const rendered = view(OwnerApplication); await screen.findByRole('alert');
    fireEvent.click(screen.getByRole('button', { name: '상태·권한 다시 확인' }));
    await screen.findByText('승인', { selector: 'strong' }); expect(auth.refresh).toHaveBeenCalledTimes(1);
    auth.user = { accountKind: 'MEMBER', roles: ['CUSTOMER', 'OWNER'] }; rendered.rerender(<MemoryRouter><OwnerApplication /></MemoryRouter>);
    expect(screen.getByRole('link', { name: '내 건물·방 관리' })).toHaveAttribute('href', '/owner/property/buildings');
    expect(screen.getByText('소유주 확인이 완료되었습니다. 새 자산은 소유 관계 확인 후 공개됩니다.')).toBeInTheDocument();
  });

  it('keeps an application available after a request failure so it can be retried', async () => {
    api.apply.mockRejectedValueOnce(new Error('Synthetic submit failure')).mockResolvedValue({ status: 'PENDING' });
    view(OwnerApplication); fireEvent.change(await screen.findByLabelText('신청 내용'), { target: { value: 'Synthetic application' } });
    fireEvent.click(screen.getByRole('button', { name: '소유주 신청하기' })); await screen.findByRole('alert');
    fireEvent.click(screen.getByRole('button', { name: '소유주 신청하기' })); await screen.findByText('확인 중', { selector: 'strong' });
    expect(api.apply).toHaveBeenCalledTimes(2);
  });

  it('submits separate evidence for a newly created pending draft and refreshes its claim status', async () => {
    api.getAssetClaims.mockResolvedValue([claim]); api.claimAsset.mockResolvedValue({ ...claim, status: 'PENDING' });
    view(OwnerAssetClaims); await screen.findByText('건물 #31 Synthetic building');
    fireEvent.click(screen.getByRole('button', { name: '이 자산의 확인 정보 입력' }));
    fireEvent.change(screen.getByLabelText('확인 참고 정보'), { target: { value: '  Corrected synthetic evidence  ' } });
    fireEvent.click(screen.getByRole('button', { name: '소유 관계 확인 요청', exact: true }));
    await waitFor(() => expect(api.claimAsset).toHaveBeenCalledWith({ resourceType: 'BUILDING', resourceId: '31', proofReference: 'Corrected synthetic evidence' }));
    await screen.findByText('소유 관계 확인 요청이 접수되었습니다. 확인이 완료되면 공개·관리 권한에 반영됩니다.');
    expect(api.getAssetClaims).toHaveBeenCalledTimes(2);
    api.getAssetClaims.mockResolvedValue([{ ...claim, status: 'ACTIVE' }]);
    fireEvent.click(screen.getByRole('button', { name: '목록·권한 다시 확인' }));
    await screen.findByText('확인 완료'); expect(auth.refresh).toHaveBeenCalledTimes(1);
  });

  it('shows a rejected asset review and lets the owner correct that exact resource', async () => {
    api.getAssetClaims.mockResolvedValue([{ ...claim, resourceType: 'ROOM', resourceId: 42, status: 'REJECTED', reviewNote: 'Synthetic correction needed' }]);
    view(OwnerAssetClaims); await screen.findByText('반려'); fireEvent.click(screen.getByRole('button', { name: '이 자산의 확인 정보 입력' }));
    expect(screen.getByLabelText('대상 종류')).toHaveValue('ROOM'); expect(screen.getByLabelText('대상 번호')).toHaveValue(42);
    expect(screen.getByText('확인 의견: Synthetic correction needed')).toBeInTheDocument();
  });

  it('shows the approved state when the server returns an existing active asset claim', async () => {
    const active = { ...claim, status: 'ACTIVE' };
    api.getAssetClaims.mockResolvedValue([active]);
    api.claimAsset.mockResolvedValue(active);
    render(<MemoryRouter initialEntries={['/owner/claims?resourceType=BUILDING&resourceId=31']}><OwnerAssetClaims /></MemoryRouter>);
    await screen.findByText('확인 완료');
    fireEvent.change(screen.getByLabelText('확인 참고 정보'), { target: { value: 'Synthetic duplicate claim' } });
    fireEvent.click(screen.getByRole('button', { name: '소유 관계 확인 요청', exact: true }));

    await screen.findByText('이미 소유 관계가 확인된 자산입니다.');
    expect(screen.queryByText(/확인 요청이 접수되었습니다/)).not.toBeInTheDocument();
    expect(api.claimAsset).toHaveBeenCalledTimes(1);
    expect(api.claimAsset).toHaveBeenCalledWith({
      resourceType: 'BUILDING', resourceId: '31', proofReference: 'Synthetic duplicate claim',
    });
  });

  it('prefills the exact draft asset from a property list link without requesting approval automatically', async () => {
    render(<MemoryRouter initialEntries={['/owner/claims?resourceType=ROOM&resourceId=42']}><OwnerAssetClaims /></MemoryRouter>);
    await screen.findByText('확인 요청이 없습니다.'); expect(screen.getByLabelText('대상 종류')).toHaveValue('ROOM');
    expect(screen.getByLabelText('대상 번호')).toHaveValue(42); expect(api.claimAsset).not.toHaveBeenCalled();
  });

  it('requires review grounds before staff approval and reloads the pending application queue', async () => {
    const updatedAt = '2026-11-25T00:00:01.123456';
    api.getApplications.mockResolvedValue([{ userId: 'member-one', userNm: 'Synthetic member', status: 'PENDING', applicationNote: 'Synthetic qualification', updatedAt }]);
    api.reviewApplication.mockResolvedValue({ status: 'APPROVED' }); view(OwnerApprovalManagement);
    fireEvent.click(await screen.findByRole('button', { name: '확인·승인' })); expect(api.reviewApplication).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent('확인 근거와 처리 의견');
    fireEvent.change(screen.getByLabelText('처리 의견 member-one'), { target: { value: 'Synthetic verified identity' } });
    fireEvent.click(screen.getByRole('button', { name: '확인·승인' }));
    await waitFor(() => expect(api.reviewApplication).toHaveBeenCalledWith('member-one', true, 'Synthetic verified identity', updatedAt));
    await waitFor(() => expect(api.getApplications).toHaveBeenCalledTimes(2));
  });

  it('reviews asset evidence separately and cannot approve a draft that has no evidence', async () => {
    api.getAllAssetClaims.mockResolvedValue([{ ...claim, proofReference: '' }]); view(OwnerApprovalManagement);
    fireEvent.click(screen.getByRole('button', { name: '자산 소유 관계', exact: true }));
    expect(await screen.findByRole('button', { name: '확인·승인' })).toBeDisabled();
    fireEvent.change(screen.getByLabelText('처리 의견 BUILDING-7'), { target: { value: 'Synthetic missing reference' } });
    fireEvent.click(screen.getByRole('button', { name: '반려' }));
    await waitFor(() => expect(api.reviewAssetClaim).toHaveBeenCalledWith({ ...claim, proofReference: '' }, false, 'Synthetic missing reference'));
    expect(api.reviewApplication).not.toHaveBeenCalled();
  });

  it('lets staff retry a failed review without silently removing the pending request', async () => {
    api.getAllAssetClaims.mockResolvedValue([claim]); api.reviewAssetClaim.mockRejectedValueOnce(new Error('Synthetic review failure')).mockResolvedValue({ ...claim, status: 'ACTIVE' });
    view(OwnerApprovalManagement); fireEvent.click(screen.getByRole('button', { name: '자산 소유 관계', exact: true }));
    fireEvent.change(await screen.findByLabelText('처리 의견 BUILDING-7'), { target: { value: 'Synthetic verified asset' } });
    fireEvent.click(screen.getByRole('button', { name: '확인·승인' })); await screen.findByText('Synthetic review failure');
    fireEvent.click(screen.getByRole('button', { name: '확인·승인' }));
    await waitFor(() => expect(api.reviewAssetClaim).toHaveBeenCalledTimes(2));
  });

  it('requires staff to load revised evidence before either review decision can be sent', async () => {
    const original = { ...claim, updatedAt: '2026-11-25T00:00:01.123456' };
    const revised = { ...original, proofReference: 'Revised synthetic evidence', updatedAt: '2026-11-25T00:00:02.123456' };
    const conflict = Object.assign(new Error('신청 내용이 변경되었습니다. 새로고침 후 다시 확인해주세요.'), { errorCode: 'OWNERSHIP_409', status: 409 });
    api.getAllAssetClaims.mockResolvedValueOnce([original]).mockResolvedValue([revised]);
    api.reviewAssetClaim.mockRejectedValueOnce(conflict).mockResolvedValue({ ...revised, status: 'ACTIVE' });
    view(OwnerApprovalManagement);
    fireEvent.click(screen.getByRole('button', { name: '자산 소유 관계', exact: true }));
    const approval = await screen.findByRole('button', { name: '확인·승인' });
    fireEvent.change(screen.getByLabelText('처리 의견 BUILDING-7'), { target: { value: 'Original synthetic review' } });
    fireEvent.click(approval);
    expect(await screen.findByRole('alert')).toHaveTextContent('새로고침 후 다시 확인');
    expect(approval).toBeDisabled();
    expect(screen.getByRole('button', { name: '반려' })).toBeDisabled();
    expect(api.reviewAssetClaim).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole('button', { name: '새로고침' }));
    await screen.findByText('Revised synthetic evidence');
    fireEvent.change(screen.getByLabelText('처리 의견 BUILDING-7'), { target: { value: 'Revised synthetic review' } });
    fireEvent.click(screen.getByRole('button', { name: '확인·승인' }));
    await waitFor(() => expect(api.reviewAssetClaim).toHaveBeenLastCalledWith(revised, true, 'Revised synthetic review'));
  });
});
