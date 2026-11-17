import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import AdminContractList from './AdminContractList';

const { admin, files, saveBlob } = vi.hoisted(() => ({
  admin: { getContracts: vi.fn(), getContractById: vi.fn(), updateContract: vi.fn() },
  files: { getPrivateFileBlob: vi.fn() },
  saveBlob: vi.fn(),
}));

vi.mock('../../api/adminApi', () => ({ adminApi: admin }));
vi.mock('../../../file/api/fileApi', () => ({ fileApi: files, savePrivateFileBlob: saveBlob }));

const RAW_URL = 'https://fixture-storage.invalid/CONTRACT/private-document.png';
const BLOB = new Blob(['synthetic document'], { type: 'image/png' });
const DOWNLOAD = { blob: BLOB, filename: 'contract-file-101.png' };
let records;

function contract(id = 1, fileId = 101) {
  return { contractId: id, contractPdfFileId: fileId, contractPdfUrl: RAW_URL,
    buildingNm: `Synthetic building ${id}`, roomNo: 100 + id,
    tenantNm: 'Synthetic tenant', lessorNm: 'Synthetic lessor',
    contractStart: '2100-03-12', contractEnd: '2101-03-11',
    rentType: 'monthly_rent', rentPrice: 500000, contractStatus: 'active' };
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

async function resolve(request, value) {
  await act(async () => { request.resolve(value); await request.promise; });
}

function view() {
  return render(<MemoryRouter><AdminContractList /></MemoryRouter>);
}

async function row(id = 1) {
  return (await screen.findByText(`#${id}`, { selector: 'td' })).closest('tr');
}

async function openDetail(id = 1) {
  fireEvent.click(within(await row(id)).getByRole('button', { name: '관리', exact: true }));
  const heading = await screen.findByText('📄 계약 상세/수정');
  const modal = heading.parentElement.parentElement;
  await within(modal).findByText('현재 상태');
  return modal;
}

function assertNoDirectLinks() {
  expect(document.querySelector(`a[href="${RAW_URL}"]`)).toBeNull();
  expect(screen.queryByRole('link', { name: '다운로드', exact: true })).not.toBeInTheDocument();
}

describe('admin contract documents use authorized file-id downloads', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    records = [contract()];
    admin.getContracts.mockImplementation(async () => ({ content: records, totalElements: records.length, totalPages: 1 }));
    admin.getContractById.mockImplementation(async (id) => contract(id, id === 1 ? 202 : 303));
    files.getPrivateFileBlob.mockResolvedValue(DOWNLOAD);
    saveBlob.mockReturnValue(vi.fn());
    vi.spyOn(window, 'alert').mockImplementation(() => {});
  });

  afterEach(() => { cleanup(); vi.restoreAllMocks(); });

  it('downloads a list document through its file ID and never follows the supplied storage URL', async () => {
    view();
    const item = await row();
    assertNoDirectLinks();
    fireEvent.click(within(item).getByRole('button', { name: '다운로드', exact: true }));
    await waitFor(() => expect(saveBlob).toHaveBeenCalledWith(BLOB, DOWNLOAD.filename));
    const [id, options] = files.getPrivateFileBlob.mock.lastCall;
    expect(id).toBe(101);
    expect(options.download).toBe(true);
    expect(options.signal).toBeInstanceOf(AbortSignal);
    expect(options.signal.aborted).toBe(false);
  });

  it('uses the current detail file ID for its separate modal download', async () => {
    view();
    const modal = await openDetail();
    assertNoDirectLinks();
    fireEvent.click(within(modal).getByRole('button', { name: '다운로드', exact: true }));
    await waitFor(() => expect(saveBlob).toHaveBeenCalledTimes(1));
    expect(files.getPrivateFileBlob.mock.lastCall[0]).toBe(202);
    expect(files.getPrivateFileBlob.mock.lastCall[1].download).toBe(true);
  });

  it('does not fall back to an external URL when the file ID is missing', async () => {
    records = [contract(1, null)];
    admin.getContractById.mockResolvedValue(contract(1, null));
    view();
    await row();
    const modal = await openDetail();
    assertNoDirectLinks();
    expect(within(modal).queryByRole('button', { name: '다운로드', exact: true })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '다운로드', exact: true })).not.toBeInTheDocument();
    expect(files.getPrivateFileBlob).not.toHaveBeenCalled();
  });

  it.each(['list', 'detail'])('shows a recoverable %s download failure without saving a response', async (scope) => {
    files.getPrivateFileBlob.mockRejectedValueOnce(new Error('synthetic denied request'));
    view();
    const container = scope === 'detail' ? await openDetail() : await row();
    fireEvent.click(within(container).getByRole('button', { name: '다운로드', exact: true }));
    await screen.findByRole('alert');
    expect(saveBlob).not.toHaveBeenCalled();
    assertNoDirectLinks();
    fireEvent.click(within(container).getByRole('button', { name: '다운로드', exact: true }));
    await waitFor(() => expect(saveBlob).toHaveBeenCalledTimes(1));
    expect(files.getPrivateFileBlob.mock.calls.length).toBe(2);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it.each(['list', 'detail'])('blocks duplicate %s download clicks while the authorized request is pending', async (scope) => {
    const pending = deferred();
    files.getPrivateFileBlob.mockReturnValue(pending.promise);
    view();
    const container = scope === 'detail' ? await openDetail() : await row();
    const button = within(container).getByRole('button', { name: '다운로드', exact: true });
    fireEvent.click(button);
    fireEvent.click(button);
    expect(files.getPrivateFileBlob).toHaveBeenCalledTimes(1);
    expect(button).toBeDisabled();
    await resolve(pending, DOWNLOAD);
    expect(saveBlob).toHaveBeenCalledTimes(1);
    expect(button).toBeEnabled();
  });

  it('aborts a modal request on close and ignores its late response', async () => {
    const pending = deferred();
    files.getPrivateFileBlob.mockReturnValue(pending.promise);
    view();
    const modal = await openDetail();
    fireEvent.click(within(modal).getByRole('button', { name: '다운로드', exact: true }));
    const signal = files.getPrivateFileBlob.mock.lastCall[1].signal;
    fireEvent.click(within(modal).getByRole('button', { name: '×', exact: true }));
    expect(signal.aborted).toBe(true);
    await resolve(pending, DOWNLOAD);
    expect(saveBlob).not.toHaveBeenCalled();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('aborts an unmounted list request and does not start a late download', async () => {
    const pending = deferred();
    files.getPrivateFileBlob.mockReturnValue(pending.promise);
    const rendered = view();
    fireEvent.click(within(await row()).getByRole('button', { name: '다운로드', exact: true }));
    const signal = files.getPrivateFileBlob.mock.lastCall[1].signal;
    rendered.unmount();
    expect(signal.aborted).toBe(true);
    await resolve(pending, DOWNLOAD);
    expect(saveBlob).not.toHaveBeenCalled();
  });

  it.each(['list', 'detail'])('releases the saved %s object URL cleanup on close or unmount', async (scope) => {
    const release = vi.fn();
    saveBlob.mockReturnValue(release);
    const rendered = view();
    const container = scope === 'detail' ? await openDetail() : await row();
    fireEvent.click(within(container).getByRole('button', { name: '다운로드', exact: true }));
    await waitFor(() => expect(saveBlob).toHaveBeenCalledTimes(1));
    if (scope === 'detail') {
      fireEvent.click(within(container).getByRole('button', { name: '×', exact: true }));
      expect(release).toHaveBeenCalledTimes(1);
    }
    rendered.unmount();
    expect(release).toHaveBeenCalledTimes(1);
  });

  it('cancels the obsolete detail document when saving replaces its file ID', async () => {
    const pending = deferred();
    files.getPrivateFileBlob.mockReturnValueOnce(pending.promise);
    admin.getContractById
      .mockResolvedValueOnce(contract(1, 202))
      .mockResolvedValueOnce(contract(1, 404));
    view();
    const modal = await openDetail();
    fireEvent.click(within(modal).getByRole('button', { name: '다운로드', exact: true }));
    const signal = files.getPrivateFileBlob.mock.lastCall[1].signal;
    fireEvent.click(within(modal).getByRole('button', { name: '저장', exact: true }));
    await waitFor(() => expect(signal.aborted).toBe(true));
    await resolve(pending, DOWNLOAD);
    expect(saveBlob).not.toHaveBeenCalled();
    fireEvent.click(within(modal).getByRole('button', { name: '다운로드', exact: true }));
    await waitFor(() => expect(saveBlob).toHaveBeenCalledTimes(1));
    expect(files.getPrivateFileBlob.mock.lastCall[0]).toBe(404);
  });
});
