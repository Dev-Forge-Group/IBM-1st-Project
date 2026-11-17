// features/reservation/pages/TourReservationList.jsx
// 팝업 내부 + 독립 페이지 겸용
import React, { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import useTourReservations from '../hooks/useTourReservations';
import Header from '../../../app/layouts/components/Header';
import Footer from '../../../app/layouts/components/Footer';
import { toKoreanMessage } from '../../../app/http/errorMapper';
import styles from './TourReservationList.module.css';

function StatusBadge({ status }) {
  // 백엔드 enum: requested / confirmed / ended / cancelled
  const map = {
    requested: { label: '대기', cls: styles.statusPending },
    confirmed: { label: '확정', cls: styles.statusConfirmed },
    cancelled: { label: '취소됨', cls: styles.statusCancelled },
    ended: { label: '완료', cls: styles.statusCompleted },
  };
  const s = map[status] ?? { label: status ?? '-', cls: styles.statusPending };
  return <span className={`${styles.statusBadge} ${s.cls}`}>{s.label}</span>;
}

function TourCard({ item, onCancel, cancelDisabled, pendingCancelId }) {
  const id = item.tourId ?? item.id;
  const startAt = item.tourStartAt ?? item.startAt ?? '-';
  const endAt = item.tourEndAt ?? item.endAt ?? '-';
  const formatDt = (s) =>
    s && s !== '-' ? s.replace('T', ' ').slice(0, 16) : '-';
  // 백엔드 필드: tourSt
  const status = item.tourSt ?? item.status;

  return (
    <div className={styles.card}>
      <div className={styles.cardTop}>
        <div>
          {item.buildingNm && (
            <p className={styles.cardBuilding}>🏢 {item.buildingNm}</p>
          )}
          <p className={styles.cardRoomId}>
            {item.roomNo ? `${item.roomNo}호` : `방 #${item.roomId ?? '-'}`}
          </p>
          <p className={styles.cardTime}>
            {formatDt(startAt)} ~ {formatDt(endAt).slice(11)}
          </p>
        </div>
        <StatusBadge status={status} />
      </div>
      <div className={styles.cardBody}>
        <span className={styles.metaItem}>👤 {item.tourNm ?? '-'}</span>
        <span className={styles.metaItem}>📞 {item.tourTel ?? '-'}</span>
      </div>
      <div className={styles.cardBottom}>
        <span className={styles.cardId}>예약 #{id}</span>
        {status !== 'cancelled' && status !== 'ended' && (
          <button
            className={styles.cancelBtn}
            type="button"
            disabled={cancelDisabled}
            onClick={() => onCancel(id)}
          >
            {pendingCancelId === id ? '취소 중…' : '예약 취소'}
          </button>
        )}
      </div>
    </div>
  );
}

/**
 * inlineMode=true  → Header/Footer/topBar 없이 (Modal 안에서 사용)
 * onGoCreate       → "예약 생성" 버튼 클릭 콜백
 * onClose          → "닫기" 콜백
 */
export default function TourReservationList({
  inlineMode = false,
  onGoCreate,
  onClose,
}) {
  const nav = useNavigate();
  const { lookup, invalidateLookup, lookupPage, lookupLoading, lookupError, cancel } =
    useTourReservations();

  const [tourTel, setTourTel] = useState('');
  const [tourPwd, setTourPwd] = useState('');

  const [statusFilter, setStatusFilter] = useState('all');
  const [hasLookup, setHasLookup] = useState(false);
  const [pendingCancelId, setPendingCancelId] = useState(null);
  const lookupContext = useRef(null);
  const cancelInFlight = useRef(false);
  const mounted = useRef(true);
  const isCancelling = pendingCancelId !== null;
  const items = lookupPage?.content ?? [];

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; lookupContext.current = null; };
  }, []);

  const changeCredential = (field, value) => {
    if (cancelInFlight.current) return;
    if (field === 'phone') setTourTel(value);
    else setTourPwd(value);
    lookupContext.current = null;
    setHasLookup(false);
    setStatusFilter('all');
    invalidateLookup();
  };

  const loadLookup = async ({ credentials, status, page }, canClamp = true) => {
    const context = { credentials: { ...credentials }, status, page };
    lookupContext.current = context;
    setHasLookup(true);
    setStatusFilter(status);
    try {
      const result = await lookup(context.credentials, {
        page, size: 10, sort: 'tourId', direct: 'DESC',
        status: status === 'all' ? undefined : status,
      });
      if (!result || !mounted.current || lookupContext.current !== context) return;
      const lastPage = Math.max(1, result.totalPages ?? 1);
      if (canClamp && result.page > lastPage) {
        await loadLookup({ credentials: context.credentials, status, page: lastPage }, false);
      }
    } catch {
      // The hook publishes only the current request's error.
    }
  };

  const onLookup = async (page = 1) => {
    if (cancelInFlight.current || lookupLoading) return;
    if (!tourTel.trim()) return alert('연락처를 입력해주세요.');
    if (!/^[0-9]{4}$/.test(tourPwd))
      return alert('비밀번호는 숫자 4자리입니다.');
    await loadLookup({ credentials: { tourTel: tourTel.trim(), tourPwd }, status: statusFilter, page });
  };

  const onFilter = (status) => {
    if (cancelInFlight.current || !lookupContext.current) return;
    loadLookup({ credentials: lookupContext.current.credentials, status, page: 1 });
  };

  const onPage = (page) => {
    if (cancelInFlight.current || lookupLoading || !lookupContext.current) return;
    loadLookup({ ...lookupContext.current, page });
  };

  const onCancel = async (tourId) => {
    const context = lookupContext.current;
    if (cancelInFlight.current || lookupLoading || !context
        || !items.some((item) => (item.tourId ?? item.id) === tourId)) return;
    cancelInFlight.current = true;
    setPendingCancelId(tourId);
    try {
      if (!window.confirm('예약을 취소하시겠습니까?')) return;
      await cancel(tourId, context.credentials);
      if (!mounted.current || lookupContext.current !== context) return;
      alert('취소 완료');
      await loadLookup({ ...context, page: lookupPage?.page ?? context.page });
    } catch (e) {
      if (mounted.current && lookupContext.current === context)
        alert(toKoreanMessage(e, '취소 처리 중 오류가 발생했습니다.'));
    } finally {
      cancelInFlight.current = false;
      if (mounted.current) setPendingCancelId(null);
    }
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter') onLookup(1);
  };

  const mainContent = (
    <>
      {/* 상단 바 - 인라인 모드에서는 숨김 (Modal headerAction으로 이동) */}
      {!inlineMode && (
        <div className={styles.topBarInline}>
          <span className={styles.inlineTitle}>📋 방문 예약 조회</span>
          <button
            className={styles.createLink}
            type="button"
            onClick={() => nav('/reservations/tour/create')}
          >
            + 예약 생성
          </button>
        </div>
      )}

      {/* 조회 폼 */}
      <div className={styles.lookupBox}>
        <p className={styles.lookupDesc}>
          예약 시 입력한 연락처와 비밀번호로 조회합니다.
        </p>
        <div className={styles.lookupRow}>
          <input
            className={styles.input}
            type="tel"
            placeholder="연락처 (예: 010-0000-0000)"
            value={tourTel}
            disabled={isCancelling}
            onChange={(e) => changeCredential('phone', e.target.value)}
            onKeyDown={handleKeyDown}
          />
          <input
            className={styles.input}
            type="password"
            placeholder="비밀번호 4자리"
            value={tourPwd}
            maxLength={4}
            pattern="\d{4}"
            disabled={isCancelling}
            onChange={(e) => changeCredential('pin', e.target.value)}
            onKeyDown={handleKeyDown}
            style={{ maxWidth: 160 }}
          />
          <button
            className={styles.lookupBtn}
            type="button"
            onClick={() => onLookup(1)}
            disabled={lookupLoading || isCancelling}
          >
            {lookupLoading ? '조회 중…' : '🔍 조회'}
          </button>
        </div>
        {lookupError && <p className={styles.errMsg}>{lookupError}</p>}
      </div>

      {/* 결과 */}
      {hasLookup && (
          <div className={styles.filterRow}>
            {[
              { value: 'all', label: '전체' },
              { value: 'requested', label: '대기' },
              { value: 'confirmed', label: '확정' },
              { value: 'cancelled', label: '취소' },
              { value: 'ended', label: '완료' },
            ].map((f) => (
              <button
                key={f.value}
                type="button"
                className={`${styles.filterChip} ${statusFilter === f.value ? styles.filterChipActive : ''}`}
                disabled={isCancelling}
                aria-pressed={statusFilter === f.value}
                onClick={() => onFilter(f.value)}
              >
                {f.label}
              </button>
            ))}
          </div>
      )}
      {lookupPage && (
        <>
          <div className={styles.resultHeader}>
            <span className={styles.resultCount}>
              총 <strong>{lookupPage.totalElements ?? 0}</strong>건
            </span>
            <div className={styles.paging}>
              <button
                className={styles.pageBtn}
                type="button"
                disabled={lookupLoading || isCancelling || (lookupPage.page ?? 1) <= 1}
                onClick={() => onPage(Math.max(1, (lookupPage.page ?? 1) - 1))}
              >
                이전
              </button>
              <span className={styles.pageInfo}>
                {lookupPage.page ?? 1}페이지
              </span>
              <button
                className={styles.pageBtn}
                type="button"
                disabled={lookupLoading || isCancelling || (lookupPage.page ?? 1) >= (lookupPage.totalPages ?? 0)}
                onClick={() => onPage((lookupPage.page ?? 1) + 1)}
              >
                다음
              </button>
            </div>
          </div>
          {items.length === 0 ? (
            <div className={styles.empty}>
              <span className={styles.emptyIcon}>🗓️</span>
              <p>조회된 예약이 없습니다.</p>
            </div>
          ) : (
            <div className={styles.cardList}>
              {items.map((it) => (
                <TourCard
                  key={it.tourId ?? it.id}
                  item={it}
                  onCancel={onCancel}
                  cancelDisabled={lookupLoading || isCancelling}
                  pendingCancelId={pendingCancelId}
                />
              ))}
            </div>
          )}
        </>
      )}
    </>
  );

  if (inlineMode) return <div className={styles.inlineWrap}>{mainContent}</div>;

  return (
    <div className={styles.page}>
      <Header />
      <div className={styles.topBar}>
        <button
          className={styles.backBtn}
          type="button"
          onClick={() => nav(-1)}
        >
          ←
        </button>
        <h1 className={styles.pageTitle}>📋 방문 예약 조회</h1>
        <button
          className={styles.createLink}
          type="button"
          onClick={() => nav('/reservations/tour/create')}
        >
          + 예약 생성
        </button>
      </div>
      {mainContent}
      <Footer />
    </div>
  );
}
