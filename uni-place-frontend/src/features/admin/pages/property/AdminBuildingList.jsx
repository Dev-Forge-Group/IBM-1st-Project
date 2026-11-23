import React, { useCallback, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ownerApi as adminApi } from '../../../owner/api/ownerApi';
import AdminPropertyListTable from './AdminPropertyListTable';
import AdminBuildingModal from './AdminBuildingModal';
import styles from './AdminPropertyListTable.module.css';

export default function AdminBuildingList() {
  const [createModal, setCreateModal] = useState(false);
  const [editId, setEditId] = useState(null);
  const reloadRef = useRef(null);

  const fetchBuildings = useCallback(
    (q) =>
      adminApi.getBuildings({
        page: q.page,
        size: q.size,
        sort: q.sort,
        direct: q.direct,
      }),
    []
  );

  const columns = [
    { key: 'ownershipStatus', label: '소유 관계', render: (row) => ({ ACTIVE: '확인 완료', PENDING: '확인 중', INHERITED: '건물 권한 적용', REJECTED: '반려', REVOKED: '권한 회수', UNASSIGNED: '미확인' }[row.ownershipStatus] || row.ownershipStatus || '-') },
    { key: 'publicVisible', label: '공개 상태', render: (row) => row.publicVisible ? '공개' : '확인 후 공개' },
    { key: 'claim', label: '소유 관계 확인', render: (row) => row.ownershipStatus !== 'ACTIVE' ? <Link to={`/owner/claims?resourceType=BUILDING&resourceId=${row.buildingId}`} onClick={(event) => event.stopPropagation()}>확인 정보 제출</Link> : '-' },
    { key: 'buildingId', label: '건물 ID' },
    { key: 'buildingNm', label: '건물명' },
    { key: 'buildingAddr', label: '주소' },
    { key: 'buildingUsage', label: '용도' },
    { key: 'parkingCapacity', label: '주차 가능' },
    {
      key: 'actions',
      label: '관리',
      render: (row) => (
        <button
          type="button"
          className={styles.btnEdit}
          onClick={(e) => {
            e.stopPropagation();
            setEditId(row.buildingId);
          }}
        >
          수정
        </button>
      ),
    },
  ];

  const handleSuccess = () => reloadRef.current?.();

  // 체크박스 일괄 삭제 (순차 처리)
  const handleDeleteSelected = async (ids) => {
    const results = await Promise.allSettled(
      ids.map((id) => adminApi.deleteBuilding(id))
    );
    const failed = results.filter((r) => r.status === 'rejected');
    if (failed.length > 0) {
      throw new Error(`${failed.length}개 삭제 실패 (나머지는 완료)`);
    }
  };

  return (
    <>
      <AdminPropertyListTable
        title="건물 목록"
        subtitle="시설 관리 대상 건물"
        fetcher={fetchBuildings}
        columns={columns}
        initialQuery={{ page: 1, size: 10, sort: 'buildingId', direct: 'DESC' }}
        rowKey="buildingId"
        emptyMessage="등록된 건물이 없습니다."
        createLabel="건물 등록"
        onCreateClick={() => setCreateModal(true)}
        reloadRef={reloadRef}
        onDeleteSelected={handleDeleteSelected}
        deleteLabel="건물 삭제"
      />

      {createModal && (
        <AdminBuildingModal
          onClose={() => setCreateModal(false)}
          onSuccess={handleSuccess}
        />
      )}

      {editId && (
        <AdminBuildingModal
          buildingId={editId}
          onClose={() => setEditId(null)}
          onSuccess={handleSuccess}
        />
      )}
    </>
  );
}
