import React from 'react';
import { AdminEndpointDetailProbe } from '../../components/AdminEndpointProbe';
import { ownerApi as adminApi } from '../../../owner/api/ownerApi';

export default function AdminBuildingDetail() {
  return (
    <AdminEndpointDetailProbe
      title="Admin Building Detail"
      idLabel="Building ID"
      fetcher={(id) => adminApi.getBuildingDetail(id)}
      parseId={(v) => (v === '' ? '' : Number(v))}
    />
  );
}
