import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MyContractView from './MyContractView';

const { contracts, property, auth, documents } = vi.hoisted(() => ({
  contracts: { myContracts: vi.fn() }, property: { getRoomDetail: vi.fn() },
  auth: { me: vi.fn(), login: vi.fn() }, documents: { getPrivateFileBlob: vi.fn() },
}));
vi.mock('../api/contractApi', () => ({ contractApi: contracts }));
vi.mock('../../property/api/propertyApi', () => ({ propertyApi: property }));
vi.mock('../../user/api/authApi', () => ({ authApi: auth }));
vi.mock('../../file/api/fileApi', async (original) => ({ ...(await original()), fileApi: documents }));

const EXTERNAL = 'https://private-storage.invalid/untrusted-contract.png';
const ROOM_IMAGE = 'https://public.invalid/room.png';
const makeBlob = (type = 'image/png') => new Blob(['Synthetic contract document'], { type });
let contract;
let createUrl;
let revokeUrl;
let anchorClick;

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

async function resolve(request, value) {
  await act(async () => { request.resolve(value); await request.promise; });
}

async function reject(request) {
  await act(async () => { request.reject(new Error('Obsolete document failure')); await request.promise.catch(() => {}); });
}

function view() { return <MemoryRouter><MyContractView /></MemoryRouter>; }

async function openDocument() {
  fireEvent.click(await screen.findByRole('button', { name: /계약서 보기/ }));
  fireEvent.change(screen.getByPlaceholderText('비밀번호'), { target: { value: 'synthetic-password' } });
  fireEvent.click(screen.getByRole('button', { name: '확인', exact: true }));
  await screen.findByText('📄 계약서 · Fixture building 101호');
}

describe('contract documents use authenticated blobs rather than storage URLs', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    localStorage.clear();
    localStorage.setItem('access_token', 'old-synthetic-access');
    localStorage.setItem('refresh_token', 'old-synthetic-refresh');
    localStorage.setItem('device_id', 'synthetic-device');
    contract = { contractId: 1, roomId: 7, roomNo: 101, buildingNm: 'Fixture building', contractStatus: 'active',
      contractPdfFileId: 23, contractPdfUrl: EXTERNAL, contractStart: '2100-01-01', contractEnd: '2101-01-01' };
    contracts.myContracts.mockResolvedValue([contract]);
    property.getRoomDetail.mockResolvedValue({ roomId: 7, roomNo: 101, buildingNm: 'Fixture building', roomSt: 'contracted',
      files: [{ fileId: 8, fileType: '.png', viewUrl: ROOM_IMAGE }] });
    auth.me.mockResolvedValue({ userEmail: 'fixture@example.test' });
    auth.login.mockResolvedValue({ accessToken: 'fresh-synthetic-access', refreshToken: 'fresh-synthetic-refresh', deviceId: 'fresh-synthetic-device' });
    documents.getPrivateFileBlob.mockResolvedValue({ blob: makeBlob(), filename: 'contract-file-23.png' });
    createUrl = vi.fn(() => `blob:synthetic-${createUrl.mock.calls.length}`);
    revokeUrl = vi.fn();
    const OriginalURL = globalThis.URL;
    vi.stubGlobal('URL', class extends OriginalURL { static createObjectURL = createUrl; static revokeObjectURL = revokeUrl; });
    anchorClick = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
  });
  afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); localStorage.clear(); });

  it('loads only the fixed file ID and stores successful reauthentication tokens before opening it', async () => {
    documents.getPrivateFileBlob.mockImplementationOnce(async () => {
      expect(localStorage.getItem('access_token')).toBe('fresh-synthetic-access');
      expect(localStorage.getItem('refresh_token')).toBe('fresh-synthetic-refresh');
      expect(localStorage.getItem('device_id')).toBe('fresh-synthetic-device');
      return { blob: makeBlob(), filename: 'contract-file-23.png' };
    });
    render(view());
    await openDocument();
    const image = await screen.findByAltText('계약서');
    expect(image.getAttribute('src')).toBe('blob:synthetic-1');
    expect(documents.getPrivateFileBlob.mock.lastCall[0]).toBe(23);
    expect(documents.getPrivateFileBlob.mock.lastCall[1].download).not.toBe(true);
    expect(image.getAttribute('src')).not.toBe(EXTERNAL);
    expect(localStorage.getItem('access_token') === 'fresh-synthetic-access').toBe(true);
    expect(localStorage.getItem('refresh_token') === 'fresh-synthetic-refresh').toBe(true);
    expect((await screen.findByAltText('방 사진 1')).getAttribute('src')).toBe(ROOM_IMAGE);
  });

  it.each([401, 403])('shows a recoverable HTTP%s failure without an external document fallback', async (status) => {
    documents.getPrivateFileBlob.mockRejectedValueOnce(Object.assign(new Error('Access denied'), { status }));
    render(view());
    await openDocument();
    await screen.findByRole('alert');
    expect(screen.queryByAltText('계약서')).not.toBeInTheDocument();
    expect(createUrl).not.toHaveBeenCalled();
    expect(document.querySelector(`a[href="${EXTERNAL}"]`)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도', exact: true }));
    await screen.findByAltText('계약서');
    expect(documents.getPrivateFileBlob.mock.calls.length).toBe(2);
  });

  it('discards a blob arriving after the viewer is closed', async () => {
    const pending = deferred();
    documents.getPrivateFileBlob.mockReturnValueOnce(pending.promise);
    render(view());
    await openDocument();
    fireEvent.click(screen.getByRole('button', { name: '✕', exact: true }));
    expect(documents.getPrivateFileBlob.mock.lastCall[1].signal.aborted).toBe(true);
    await resolve(pending, { blob: makeBlob(), filename: 'contract-file-23.png' });
    expect(createUrl).not.toHaveBeenCalled();
    expect(screen.queryByAltText('계약서')).not.toBeInTheDocument();
  });

  it('revokes the preview on unmount', async () => {
    const rendered = render(view());
    await openDocument();
    await screen.findByAltText('계약서');
    rendered.unmount();
    expect(revokeUrl).toHaveBeenCalledTimes(1);
    expect(revokeUrl).toHaveBeenCalledWith('blob:synthetic-1');
  });

  it.each(['success', 'failure'])('ignores a late old-file %s after the contract file ID changes', async (outcome) => {
    const old = deferred();
    const current = deferred();
    documents.getPrivateFileBlob.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise);
    const rendered = render(view());
    await openDocument();
    contract.contractPdfFileId = 24;
    rendered.rerender(view());
    await waitFor(() => expect(documents.getPrivateFileBlob.mock.calls.length).toBe(2));
    await resolve(current, { blob: makeBlob(), filename: 'contract-file-24.png' });
    expect((await screen.findByAltText('계약서')).getAttribute('src')).toBe('blob:synthetic-1');
    if (outcome === 'success') await resolve(old, { blob: makeBlob(), filename: 'old.png' });
    else await reject(old);
    expect(createUrl).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('releases an already loaded preview when the contract file changes', async () => {
    const next = deferred();
    const rendered = render(view());
    await openDocument();
    await screen.findByAltText('계약서');
    documents.getPrivateFileBlob.mockReturnValueOnce(next.promise);
    contract.contractPdfFileId = 24;
    rendered.rerender(view());
    await waitFor(() => expect(revokeUrl).toHaveBeenCalledWith('blob:synthetic-1'));
    expect(screen.queryByAltText('계약서')).not.toBeInTheDocument();
    await resolve(next, { blob: makeBlob(), filename: 'contract-file-24.png' });
    expect((await screen.findByAltText('계약서')).getAttribute('src')).toBe('blob:synthetic-2');
  });

  it('revokes a preview that fails to render and permits retry', async () => {
    render(view());
    await openDocument();
    fireEvent.error(await screen.findByAltText('계약서'));
    await screen.findByRole('alert');
    expect(revokeUrl).toHaveBeenCalledWith('blob:synthetic-1');
    expect(screen.queryByAltText('계약서')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도', exact: true }));
    await screen.findByAltText('계약서');
  });

  it('downloads a newly authorized blob with a safe filename and releases its URL', async () => {
    render(view());
    await openDocument();
    await screen.findByAltText('계약서');
    fireEvent.click(screen.getByRole('button', { name: /저장/ }));
    await waitFor(() => expect(anchorClick).toHaveBeenCalledTimes(1));
    const anchor = anchorClick.mock.instances[0];
    expect(anchor.getAttribute('href')).toBe('blob:synthetic-2');
    expect(anchor.getAttribute('download')).toBe('contract-file-23.png');
    expect(documents.getPrivateFileBlob.mock.lastCall[1].download).toBe(true);
    await waitFor(() => expect(revokeUrl).toHaveBeenCalledWith('blob:synthetic-2'));
  });

  it('removes the preview when a fresh download is denied', async () => {
    render(view());
    await openDocument();
    await screen.findByAltText('계약서');
    documents.getPrivateFileBlob.mockRejectedValueOnce(Object.assign(new Error('Access denied'), { status: 403 }));
    fireEvent.click(screen.getByRole('button', { name: /저장/ }));
    await screen.findByRole('alert');
    expect(anchorClick).not.toHaveBeenCalled();
    expect(revokeUrl).toHaveBeenCalledWith('blob:synthetic-1');
    expect(screen.queryByAltText('계약서')).not.toBeInTheDocument();
  });

  it.each(['success', 'failure'])('ignores a late download %s after unmount', async (outcome) => {
    const pending = deferred();
    const rendered = render(view());
    await openDocument();
    await screen.findByAltText('계약서');
    documents.getPrivateFileBlob.mockReturnValueOnce(pending.promise);
    fireEvent.click(screen.getByRole('button', { name: /저장/ }));
    const signal = documents.getPrivateFileBlob.mock.lastCall[1].signal;
    rendered.unmount();
    expect(signal.aborted).toBe(true);
    if (outcome === 'success') await resolve(pending, { blob: makeBlob(), filename: 'contract-file-23.png' });
    else await reject(pending);
    expect(anchorClick).not.toHaveBeenCalled();
    expect(createUrl).toHaveBeenCalledTimes(1);
  });
});
