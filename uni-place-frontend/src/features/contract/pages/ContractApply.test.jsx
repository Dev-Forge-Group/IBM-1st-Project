import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Link, MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ContractApply from './ContractApply';

const { contracts, property } = vi.hoisted(() => ({
  contracts: { myContracts: vi.fn(), createContract: vi.fn() },
  property: { getRoomDetail: vi.fn() },
}));
vi.mock('../api/contractApi', () => ({ contractApi: contracts }));
vi.mock('../../property/api/propertyApi', () => ({ propertyApi: property }));
vi.mock('../../user/hooks/useAuth', () => ({
  useAuth: () => ({ user: { userId: 'synthetic-contract-applicant' }, loading: false }),
}));
vi.mock('../../../app/layouts/components/Header', () => ({ default: () => null }));
vi.mock('../../../app/layouts/components/Footer', () => ({ default: () => null }));
vi.mock('../../../shared/components/Modal/Modal', () => ({ default: () => null }));

const EMPTY_SIGNATURE = /빈 서명 파일은 첨부할 수 없습니다/;
const FILE_A = 'synthetic-signature-a.png';
const FILE_B = 'synthetic-signature-b.png';
let readerCreated;
let fileRead;

const FIRST_ROOM = {
  roomId: 7, roomNo: 101, buildingNm: 'Synthetic building', buildingAddr: 'Synthetic building address',
  rentMin: 12, deposit: 1000000, rentPrice: 500000, manageFee: 50000,
  floor: 1, roomSize: 20, roomCapacity: 1, sunDirection: 's',
};
const SECOND_ROOM = { ...FIRST_ROOM, roomId: 8, roomNo: 202, buildingNm: 'Synthetic second building' };

const signature = (name = FILE_A) => new File(['Synthetic signature fixture'], name, { type: 'image/png' });
const emptySignature = () => new File([], 'empty-signature.png', { type: 'image/png' });
const previewFor = (file) => `data:image/png;base64,${btoa(`Synthetic signature: ${file.name}`)}`;

class FixtureFileReader {
  constructor() { readerCreated(); }
  readAsDataURL(file) {
    fileRead(file);
    this.onload?.({ target: { result: previewFor(file) } });
  }
}

function openApplication() {
  return render(
    <MemoryRouter initialEntries={['/contracts/apply?roomId=7']}>
      <Link to="/contracts/apply?roomId=8">다른 호실 신청</Link>
      <ContractApply />
    </MemoryRouter>,
  );
}

async function readyForm(paymentDay = '15') {
  const view = openApplication();
  await screen.findByPlaceholderText('임차인 성명');
  fireEvent.change(view.container.querySelector('input[name="contractStart"]'), { target: { value: '2100-01-01' } });
  fireEvent.change(screen.getByPlaceholderText('매월 납부일 (1~31)'), { target: { value: paymentDay } });
  fireEvent.change(screen.getByPlaceholderText('임차인 성명'), { target: { value: '합성신청자' } });
  fireEvent.change(screen.getByPlaceholderText('010-0000-0000'), { target: { value: '010-1234-5678' } });
  fireEvent.change(screen.getByPlaceholderText('임차인 주소'), { target: { value: 'Synthetic applicant address' } });
  fireEvent.change(screen.getByPlaceholderText('000000-0000000'), { target: { value: '000000-0000000' } });
  fireEvent.click(screen.getByRole('checkbox', { name: '전체 동의', exact: true }));
  return { form: view.container.querySelector('form'), fileInput: view.container.querySelector('input[type="file"]') };
}

function choose(input, file) {
  fireEvent.change(input, { target: { files: [file] } });
}

function expectInputsRetained(paymentDay = 15) {
  expect(screen.getByPlaceholderText('임차인 성명')).toHaveValue('합성신청자');
  expect(screen.getByPlaceholderText('매월 납부일 (1~31)')).toHaveValue(paymentDay);
  expect(screen.getByPlaceholderText('010-0000-0000')).toHaveValue('010-1234-5678');
  expect(screen.getByPlaceholderText('임차인 주소')).toHaveValue('Synthetic applicant address');
  expect(screen.getByRole('checkbox', { name: '전체 동의', exact: true })).toBeChecked();
}

describe('contract application requires a nonempty signature and preserves recoverable input', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    readerCreated = vi.fn();
    fileRead = vi.fn();
    vi.stubGlobal('FileReader', FixtureFileReader);
    property.getRoomDetail.mockImplementation((id) => Promise.resolve(id === 8 ? SECOND_ROOM : FIRST_ROOM));
    contracts.myContracts.mockResolvedValue([]);
    contracts.createContract.mockResolvedValue({ contractId: 11 });
  });

  afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

  it('uses the current room when a previous room request completes late', async () => {
    let resolveEarlier;
    property.getRoomDetail.mockImplementationOnce(() => new Promise((resolve) => { resolveEarlier = resolve; }));
    openApplication();
    fireEvent.click(screen.getByRole('link', { name: '다른 호실 신청' }));
    await screen.findByRole('button', { name: 'Synthetic second building 202호' });
    await act(async () => { resolveEarlier(FIRST_ROOM); });
    expect(screen.getByRole('button', { name: 'Synthetic second building 202호' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Synthetic building 101호' })).not.toBeInTheDocument();
  });

  it('requires fresh applicant input, consent and a signature when changing rooms', async () => {
    const { fileInput } = await readyForm();
    choose(fileInput, signature());
    expect(screen.getByAltText('서명 미리보기')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('link', { name: '다른 호실 신청' }));
    await screen.findByRole('button', { name: 'Synthetic second building 202호' });
    expect(screen.getByPlaceholderText('임차인 성명')).toHaveValue('');
    expect(screen.getByRole('checkbox', { name: '전체 동의', exact: true })).not.toBeChecked();
    expect(screen.queryByAltText('서명 미리보기')).not.toBeInTheDocument();
  });

  it('shows the selected signature after out-of-order preview reads', async () => {
    const finishing = new Map();
    vi.stubGlobal('FileReader', class {
      readAsDataURL(file) {
        finishing.set(file, () => this.onload?.({ target: { result: previewFor(file) } }));
      }
    });
    const { form, fileInput } = await readyForm();
    const original = signature();
    const current = signature(FILE_B);
    choose(fileInput, original);
    choose(fileInput, current);
    await act(async () => { finishing.get(current)(); });
    await act(async () => { finishing.get(original)(); });
    expect(screen.getByAltText('서명 미리보기')).toHaveAttribute('src', previewFor(current));
    fireEvent.submit(form);
    await waitFor(() => expect(contracts.createContract).toHaveBeenCalledTimes(1));
    expect(contracts.createContract.mock.lastCall[0].signFile).toBe(current);
  });

  it('waits for the preview and ignores it after the pending signature is removed', async () => {
    let finish;
    vi.stubGlobal('FileReader', class {
      readAsDataURL(file) { finish = () => this.onload?.({ target: { result: previewFor(file) } }); }
    });
    const { form, fileInput } = await readyForm();
    choose(fileInput, signature());
    fireEvent.submit(form);
    expect(contracts.createContract).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '✕ 제거', exact: true }));
    await act(async () => { finish(); });
    expect(screen.queryByAltText('서명 미리보기')).not.toBeInTheDocument();
    fireEvent.submit(form);
    expect(contracts.createContract).not.toHaveBeenCalled();
  });

  it('accepts one submission while the same form is waiting for its result', async () => {
    let complete;
    contracts.createContract.mockImplementation(() => new Promise((resolve) => { complete = resolve; }));
    const { form, fileInput } = await readyForm();
    choose(fileInput, signature());
    fireEvent.submit(form);
    fireEvent.submit(form);
    expect(contracts.createContract).toHaveBeenCalledTimes(1);
    await act(async () => { complete({ contractId: 11 }); });
    expect(screen.getByRole('heading', { name: '계약 신청이 완료되었습니다!' })).toBeInTheDocument();
  });

  it('rejects an initially empty signature before creating a reader, showing a preview or submitting', async () => {
    const { form, fileInput } = await readyForm();
    choose(fileInput, emptySignature());

    expect(screen.getByText(EMPTY_SIGNATURE)).toBeInTheDocument();
    expect(readerCreated).not.toHaveBeenCalled();
    expect(fileRead).not.toHaveBeenCalled();
    expect(screen.queryByAltText('서명 미리보기')).not.toBeInTheDocument();
    expectInputsRetained();

    fireEvent.submit(form);
    expect(contracts.createContract).not.toHaveBeenCalled();
    expect(screen.getByText(/서명 이미지를 첨부해주세요\./)).toBeInTheDocument();
  });

  it('keeps a valid selected signature and preview when its attempted replacement is empty', async () => {
    const { form, fileInput } = await readyForm();
    const original = signature();
    choose(fileInput, original);
    const source = screen.getByAltText('서명 미리보기').getAttribute('src');
    choose(fileInput, emptySignature());

    expect(screen.getByText(EMPTY_SIGNATURE)).toBeInTheDocument();
    expect(readerCreated).toHaveBeenCalledTimes(1);
    expect(fileRead).toHaveBeenCalledTimes(1);
    expect(screen.getByAltText('서명 미리보기')).toHaveAttribute('src', source);
    fireEvent.submit(form);
    await waitFor(() => expect(contracts.createContract).toHaveBeenCalledTimes(1));
    expect(contracts.createContract.mock.lastCall[0].signFile).toBe(original);
  });

  it('recovers from an empty selection with a valid file and submits that file with existing values', async () => {
    const { form, fileInput } = await readyForm();
    choose(fileInput, emptySignature());
    expect(screen.getByText(EMPTY_SIGNATURE)).toBeInTheDocument();
    const replacement = signature(FILE_B);
    choose(fileInput, replacement);

    expect(screen.queryByText(EMPTY_SIGNATURE)).not.toBeInTheDocument();
    expect(screen.getByAltText('서명 미리보기')).toHaveAttribute('src', previewFor(replacement));
    expectInputsRetained();
    fireEvent.submit(form);
    await screen.findByRole('heading', { name: '계약 신청이 완료되었습니다!', exact: true });
    expect(contracts.createContract.mock.lastCall[0]).toEqual({
      roomId: 7, contractStart: '2100-01-01', contractEnd: '2100-12-31', paymentDay: 15,
      lessorNm: '합성신청자', lessorTel: '010-1234-5678', lessorAddr: 'Synthetic applicant address',
      lessorRrn: '000000-0000000', signFile: replacement,
    });
    expect(fileRead.mock.calls.map(([file]) => file)).toEqual([replacement]);
  });

  it('submits the most recently selected valid signature', async () => {
    const { form, fileInput } = await readyForm();
    const replacement = signature(FILE_B);
    choose(fileInput, signature());
    choose(fileInput, replacement);
    expect(screen.getByAltText('서명 미리보기')).toHaveAttribute('src', previewFor(replacement));
    fireEvent.submit(form);
    await waitFor(() => expect(contracts.createContract).toHaveBeenCalledTimes(1));
    expect(contracts.createContract.mock.lastCall[0].signFile).toBe(replacement);
  });

  it('shows a server validation rejection while retaining values and signature for an explicit retry', async () => {
    contracts.createContract.mockRejectedValueOnce(Object.assign(new Error('입력값을 확인해주세요.'), {
      status: 400, errorCode: 'COMMON_400',
    }));
    const { form, fileInput } = await readyForm();
    const selected = signature();
    choose(fileInput, selected);
    fireEvent.submit(form);

    await screen.findByText(/입력값을 확인해주세요\./);
    expect(contracts.createContract).toHaveBeenCalledTimes(1);
    expectInputsRetained();
    expect(screen.getByAltText('서명 미리보기')).toHaveAttribute('src', previewFor(selected));
    expect(screen.getByRole('button', { name: /계약 신청하기/ })).toBeEnabled();
    fireEvent.submit(form);

    await screen.findByRole('heading', { name: '계약 신청이 완료되었습니다!', exact: true });
    expect(contracts.createContract).toHaveBeenCalledTimes(2);
    expect(contracts.createContract.mock.calls[1][0]).toEqual(contracts.createContract.mock.calls[0][0]);
    expect(contracts.createContract.mock.calls[1][0].signFile).toBe(selected);
  });

  it('requires a new signature after the selected file is removed', async () => {
    const { form, fileInput } = await readyForm();
    choose(fileInput, signature());
    fireEvent.click(screen.getByRole('button', { name: '✕ 제거', exact: true }));
    expect(screen.queryByAltText('서명 미리보기')).not.toBeInTheDocument();
    fireEvent.submit(form);
    expect(contracts.createContract).not.toHaveBeenCalled();
    expect(screen.getByText(/서명 이미지를 첨부해주세요\./)).toBeInTheDocument();
  });

  it.each([1, 31])('accepts the valid payment-day boundary %s with a nonempty signature', async (paymentDay) => {
    const { form, fileInput } = await readyForm(String(paymentDay));
    const selected = signature();
    choose(fileInput, selected);
    fireEvent.submit(form);
    await waitFor(() => expect(contracts.createContract).toHaveBeenCalledTimes(1));
    expect(contracts.createContract.mock.lastCall[0].paymentDay).toBe(paymentDay);
    expect(contracts.createContract.mock.lastCall[0].signFile).toBe(selected);
  });

  it.each([0, 32])('rejects payment day %s without sending or discarding the selected signature', async (paymentDay) => {
    const { form, fileInput } = await readyForm(String(paymentDay));
    const selected = signature();
    choose(fileInput, selected);
    fireEvent.submit(form);
    expect(contracts.createContract).not.toHaveBeenCalled();
    expect(screen.getByText(/납부일은 1~31 사이의 숫자여야 합니다/)).toBeInTheDocument();
    expect(screen.getByAltText('서명 미리보기')).toHaveAttribute('src', previewFor(selected));
    expectInputsRetained(paymentDay);
  });
});
