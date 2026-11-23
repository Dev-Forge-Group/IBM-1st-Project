import React from 'react';
import { AdminEndpointDetailProbe } from '../../components/AdminEndpointProbe';
import { ownerApi as adminApi } from '../../../owner/api/ownerApi';

export default function AdminSpaceDetail() {
  return (
    <AdminEndpointDetailProbe
      title="Admin Space Detail"
      idLabel="Space ID"
      fetcher={(id) => adminApi.getSpaceDetail(id)}
      parseId={(v) => (v === '' ? '' : Number(v))}
    />
  );
}
