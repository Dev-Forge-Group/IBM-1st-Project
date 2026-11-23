import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import AdminRoomModal from './AdminRoomModal';

const api = vi.hoisted(() => ({ getBuildings: vi.fn(), getRoomDetail: vi.fn(), updateRoom: vi.fn(), createRoom: vi.fn() }));
vi.mock('../../../owner/api/ownerApi', () => ({ ownerApi: api }));
vi.mock('../../../file/components/FileUploader', () => ({ default: () => <p>Synthetic file uploader</p> }));
const room = { roomId: 42, buildingId: 31, buildingNm: 'Synthetic parent A', buildingManagementAllowed: false, roomNo: 201, floor: 2, roomSize: 20, rentPrice: 500000, files: [] };

describe('owner room editing uses resource grants and owner API', () => {
  beforeEach(() => {
    vi.resetAllMocks(); api.getBuildings.mockResolvedValue({ content: [{ buildingId: 32, buildingNm: 'Synthetic owned B' }] });
    api.getRoomDetail.mockResolvedValue(room); api.updateRoom.mockResolvedValue({ files: [] }); api.createRoom.mockResolvedValue({ files: [] });
  });
  afterEach(cleanup);
  it('keeps a foreign parent read-only while permitting room-only owner fields to be saved', async () => {
    const close = vi.fn(), saved = vi.fn(); render(<AdminRoomModal roomId={42} onClose={close} onSuccess={saved} />);
    const parent = await screen.findByRole('combobox', { name: /건물 선택/ });
    expect(parent).toBeDisabled(); expect(parent).toHaveValue('31'); expect(screen.getByRole('option', { name: '[31] Synthetic parent A' })).toBeInTheDocument();
    fireEvent.change(screen.getByRole('spinbutton', { name: /호수/ }), { target: { value: '202' } });
    fireEvent.click(screen.getByRole('button', { name: '수정 완료' }));
    await waitFor(() => expect(api.updateRoom).toHaveBeenCalledTimes(1));
    const [id, form] = api.updateRoom.mock.lastCall; expect(id).toBe(42); expect(form.get('roomNo')).toBe('202');
    expect(form.has('buildingNm')).toBe(false); expect(form.has('buildingId')).toBe(false);
    expect(saved).toHaveBeenCalledTimes(1); expect(close).toHaveBeenCalledTimes(1);
  });
  it('allows parent selection by stable ID only when the server grants building management', async () => {
    api.getRoomDetail.mockResolvedValue({ ...room, buildingManagementAllowed: true });
    render(<AdminRoomModal roomId={42} onClose={vi.fn()} />);
    const parent = await screen.findByRole('combobox', { name: /건물 선택/ });
    await waitFor(() => expect(parent).toBeEnabled()); fireEvent.change(parent, { target: { value: '32' } });
    fireEvent.click(screen.getByRole('button', { name: '수정 완료' })); await waitFor(() => expect(api.updateRoom).toHaveBeenCalledTimes(1));
    expect(api.updateRoom.mock.lastCall[1].get('buildingId')).toBe('32'); expect(api.updateRoom.mock.lastCall[1].has('buildingNm')).toBe(false);
  });
  it('does not infer parent management from an old detail response that omits its permission', async () => {
    api.getRoomDetail.mockResolvedValue({ ...room, buildingManagementAllowed: undefined });
    render(<AdminRoomModal roomId={42} onClose={vi.fn()} />); expect(await screen.findByRole('combobox', { name: /건물 선택/ })).toBeDisabled();
  });
  it('retains room edits and reports an ownership denial without closing the form', async () => {
    api.updateRoom.mockRejectedValue(new Error('Synthetic permission revoked')); const close = vi.fn();
    render(<AdminRoomModal roomId={42} onClose={close} />); await screen.findByRole('combobox', { name: /건물 선택/ });
    fireEvent.click(screen.getByRole('button', { name: '수정 완료' })); await screen.findByText('Synthetic permission revoked'); expect(close).not.toHaveBeenCalled();
  });
});
