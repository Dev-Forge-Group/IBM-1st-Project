import React from 'react';
import { AdminEndpointDetailProbe } from '../../components/AdminEndpointProbe';
import { ownerApi as adminApi } from '../../../owner/api/ownerApi';

export default function AdminRoomDetail() {
  return (
    <AdminEndpointDetailProbe
      title="Admin Room Detail"
      idLabel="Room ID"
      fetcher={(id) => adminApi.getRoomDetail(id)}
      parseId={(v) => (v === '' ? '' : Number(v))}
    />
  );
}
