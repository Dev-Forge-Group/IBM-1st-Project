// features/reservation/pages/SpaceReservationCreate.jsx
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';

import { propertyApi } from '../../property/api/propertyApi';
import { reservationApi } from '../api/reservationApi';
import { contractApi } from '../../contract/api/contractApi';
import { useAuth } from '../../user/hooks/useAuth';
import Header from '../../../app/layouts/components/Header';
import Footer from '../../../app/layouts/components/Footer';

import BuildingSlotButtons from '../components/BuildingSlotButtons';
import TimeSlotButtons from '../components/TimeSlotButtons';
import styles from './SpaceReservationCreate.module.css';
import { toKoreanMessage } from '../../../app/http/errorMapper';
import { formatBuildingDisplay } from '../../../shared/utils/branchLabel';

const EMPTY_ITEMS = [];

/**
 * inlineMode=true  → Header/Footer/topBar 없이 컨텐츠만 렌더 (마이페이지 탭 용)
 * inlineMode=false → 기존 독립 페이지로 렌더 (직접 URL 접근 시)
 * onSuccess        → inlineMode에서 예약 완료 후 콜백 (예: 조회탭으로 전환)
 */
export default function SpaceReservationCreate({
  inlineMode = false,
  onSuccess,
  initSpaceId: propSpaceId,
  initBuildingId: propBuildingId,
  initStartAt: propStartAt, // AI 추천: 자동 선택할 시작 시간 (ISO string)
  initEndAt: propEndAt, // AI 추천: 자동 선택할 종료 시간 (ISO string)
}) {
  const nav = useNavigate();
  const [searchParams] = useSearchParams();
  const { user, loading: authLoading } = useAuth();

  // URL 파라미터 (독립 페이지용) 또는 props (인라인용)
  const initSpaceId =
    propSpaceId ??
    (searchParams.get('spaceId') ? Number(searchParams.get('spaceId')) : null);
  const initBuildingId =
    propBuildingId ??
    (searchParams.get('buildingId')
      ? Number(searchParams.get('buildingId'))
      : null);
  // AI 추천 시간: prop 또는 URL 파라미터
  const initStartAt = propStartAt ?? searchParams.get('startAt') ?? null;
  const initEndAt = propEndAt ?? searchParams.get('endAt') ?? null;
  // AI 추천 시간에서 날짜 추출
  const initDateFromAi = initStartAt ? initStartAt.slice(0, 10) : null;

  // 로그인 필수 (독립 페이지일 때만 리다이렉트)
  useEffect(() => {
    if (!inlineMode && !authLoading && !user) {
      nav('/login', {
        state: { from: window.location.pathname + window.location.search },
      });
    }
  }, [authLoading, user, nav, inlineMode]);

  const [buildings, setBuildings] = useState([]);
  const [bLoading, setBLoading] = useState(false);
  const [bError, setBError] = useState('');
  const [selectedBuilding, setSelectedBuilding] = useState(null);
  const buildingId = Number(selectedBuilding?.buildingId) || null;

  const [date, setDate] = useState(
    initDateFromAi ?? new Date().toISOString().slice(0, 10)
  );
  const [availability, setAvailability] = useState(null);
  const [submitError, setSubmitError] = useState('');
  const availabilityKey = buildingId && date ? JSON.stringify([buildingId, date]) : null;
  const currentAvailability = availability?.key === availabilityKey ? availability : null;
  const spaces = currentAvailability?.spaces ?? EMPTY_ITEMS;
  const spacesLoading = Boolean(availabilityKey && !currentAvailability);
  const spacesError = currentAvailability?.error;

  const [spaceId, setSpaceId] = useState(
    initSpaceId ? String(initSpaceId) : ''
  );
  const [chosenSlot, setChosenSlot] = useState(null);
  const [srNoPeople, setSrNoPeople] = useState(1);

  // AI 추천 시간 자동 선택 (availableSlots 로드 후)
  const aiSlotAppliedRef = React.useRef(false);

  const loadBuildings = async () => {
    setBLoading(true);
    setBError('');
    try {
      // 내 계약 목록에서 active 상태인 건물만 필터링
      const contracts = await contractApi.myContracts();
      const activeBuildingIds = new Set(
        (contracts ?? [])
          .filter(
            (c) => String(c.contractStatus ?? '').toLowerCase() === 'active'
          )
          .map((c) => c.buildingId)
      );
      if (activeBuildingIds.size === 0) {
        setBuildings([]);
        setBError(
          '현재 활성 계약이 없습니다. 공용공간 예약은 계약 중인 건물만 가능합니다.'
        );
        setBLoading(false);
        return;
      }
      const page = await propertyApi.getBuildings({
        page: 1,
        size: 50,
        sort: 'buildingId',
        direct: 'DESC',
      });
      const filtered = (page?.content ?? []).filter((b) =>
        activeBuildingIds.has(Number(b.buildingId))
      );
      setBuildings(filtered);
    } catch (e) {
      setBError(e?.message || '빌딩 조회 실패');
      setBuildings([]);
    } finally {
      setBLoading(false);
    }
  };

  useEffect(() => {
    loadBuildings();
  }, []);

  useEffect(() => {
    if (!initBuildingId || !buildings.length) return;
    const found = buildings.find(
      (b) => Number(b.buildingId) === Number(initBuildingId)
    );
    if (found) setSelectedBuilding((previous) => previous ?? found);
  }, [buildings, initBuildingId]);

  useEffect(() => {
    setAvailability(null);
    setChosenSlot(null);
    setSubmitError('');
    if (!availabilityKey) return;

    let current = true;
    reservationApi.reservableSpaces({
      buildingId, date, page: 1, size: 50, sort: 'spaceId', direct: 'DESC',
    }).then((page) => {
      if (!current) return;
      const results = (page?.content ?? EMPTY_ITEMS).filter((space) => Number(space.buildingId) === buildingId);
      const ids = results.map((space) => String(space.spaceId));
      setAvailability({ key: availabilityKey, spaces: results });
      setSpaceId((previous) => {
        if (ids.includes(previous)) return previous;
        if (ids.includes(String(initSpaceId))) return String(initSpaceId);
        return ids[0] ?? '';
      });
    }).catch((error) => {
      if (current) setAvailability({ key: availabilityKey, error: error?.message || '공용공간 조회 실패' });
    });
    return () => { current = false; };
  }, [availabilityKey, buildingId, date, initSpaceId]);

  const currentSpace = spaces.find((space) => String(space.spaceId) === spaceId);
  const availableSlots = useMemo(() => {
    if (currentSpace?.unavailableReason) return EMPTY_ITEMS;
    return (currentSpace?.availableSlots ?? EMPTY_ITEMS).filter((slot) =>
      slot.startAt?.slice(0, 10) === date && slot.endAt?.slice(0, 10) === date);
  }, [currentSpace, date]);
  const selectedSlot = availableSlots.includes(chosenSlot) ? chosenSlot : null;

  // Apply the recommendation only to its original booking context.
  useEffect(() => {
    if (!initStartAt || !initEndAt || aiSlotAppliedRef.current) return;
    if (date !== initDateFromAi) return;
    if (initBuildingId && Number(initBuildingId) !== buildingId) return;
    if (initSpaceId && String(initSpaceId) !== spaceId) return;

    const targetStart = initStartAt.replace('T', ' ').slice(0, 16);
    const targetEnd = initEndAt.replace('T', ' ').slice(0, 16);
    const match = availableSlots.find((slot) =>
      slot.startAt.replace('T', ' ').slice(0, 16) === targetStart
      && slot.endAt.replace('T', ' ').slice(0, 16) === targetEnd);
    if (match) {
      setChosenSlot(match);
      aiSlotAppliedRef.current = true;
    }
  }, [availableSlots, buildingId, date, initBuildingId, initDateFromAi, initEndAt, initSpaceId,
    initStartAt, spaceId]);

  const capacity = currentSpace?.spaceCapacity;
  const exceedsCapacity = capacity != null && srNoPeople > capacity;
  const canSubmit = Boolean(currentSpace && selectedSlot && Number.isInteger(srNoPeople)
    && srNoPeople > 0 && !exceedsCapacity);

  const [submitLoading, setSubmitLoading] = useState(false);
  const submitRef = useRef(false);

  const doCreateReservation = async () => {
    if (!canSubmit || submitRef.current) return;
    submitRef.current = true;
    setSubmitLoading(true);
    try {
      await reservationApi.createSpaceReservation({
        buildingId,
        spaceId: Number(currentSpace.spaceId),
        srStartAt: selectedSlot.startAt,
        srEndAt: selectedSlot.endAt,
        srNoPeople: Number(srNoPeople),
      });
      if (inlineMode && onSuccess) {
        onSuccess();
      } else {
        nav('/me?tab=space&sub=list');
      }
    } catch (e) {
      setSubmitError(toKoreanMessage(e));
    } finally {
      submitRef.current = false;
      setSubmitLoading(false);
    }
  };

  const onSubmit = async (e) => {
    e.preventDefault();
    await doCreateReservation();
  };

  // ── 폼 내용 (공통) ──────────────────────────────────────────
  const formContent = (
    <form
      className={inlineMode ? styles.formInline : styles.form}
      onSubmit={onSubmit}
    >
      <section className={styles.section}>
        <div className={styles.sectionHead}>
          <h2 className={styles.sectionTitle}>1. 건물 선택</h2>
          <button
            className={styles.refreshBtn}
            type="button"
            onClick={loadBuildings}
            disabled={bLoading}
          >
            {bLoading ? '조회중…' : '새로고침'}
          </button>
        </div>
        {bError && <p className={styles.errMsg}>{bError}</p>}
        {!bLoading && buildings.length === 0 && !bError && (
          <p className={styles.errMsg}>
            ⚠ 현재 계약 중인 건물이 없습니다. 공용공간 예약은 활성 계약이 있는
            건물만 가능합니다.
          </p>
        )}
        <BuildingSlotButtons
          buildings={buildings}
          selectedId={selectedBuilding?.buildingId}
          onSelect={setSelectedBuilding}
        />
        {selectedBuilding && (
          <p className={styles.selectedHint}>
            선택됨: <strong>{formatBuildingDisplay(selectedBuilding)}</strong>
          </p>
        )}
      </section>

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>2. 공용공간 · 날짜 · 시간 선택</h2>
        <div className={styles.rowGroup}>
          <div className={styles.formGroup}>
            <label className={styles.label}>날짜</label>
            <input
              className={styles.input}
              type="date"
              value={date}
              min={new Date().toISOString().slice(0, 10)}
              onChange={(e) => setDate(e.target.value)}
              disabled={!selectedBuilding}
            />
          </div>
          <div className={styles.formGroup}>
            <label className={styles.label}>
              이용 인원
              {capacity != null && ` (최대 ${capacity}명)`}
            </label>
            <input
              className={styles.input}
              type="number"
              min={1}
              max={capacity ?? undefined}
              value={srNoPeople}
              onChange={(e) => setSrNoPeople(Number(e.target.value))}
              disabled={!selectedBuilding}
              style={{ maxWidth: 100 }}
            />
            {exceedsCapacity && (
              <p className={styles.errMsg} role="status">
                예약 인원이 최대 수용 인원({capacity}명)을 초과합니다.
              </p>
            )}
          </div>
        </div>
        {spacesLoading && <p className={styles.mutedMsg}>공용공간 조회 중…</p>}
        {spacesError && <p className={styles.errMsg}>{spacesError}</p>}
        <div className={styles.formGroup}>
          <label className={styles.label}>공용공간 선택</label>
          {currentSpace &&
            initSpaceId &&
            String(currentSpace.spaceId) === String(initSpaceId) && (
              <p className={styles.autoSelectedBadge}>
                🛋️ 자동 선택: {currentSpace.spaceNm}
              </p>
            )}
          <select
            className={styles.select}
            value={currentSpace ? spaceId : ''}
            onChange={(e) => {
              setSpaceId(e.target.value);
              setChosenSlot(null);
              setSubmitError('');
            }}
            disabled={!selectedBuilding || spacesLoading}
          >
            <option value="">선택하세요</option>
            {spaces.map((s) => (
              <option key={s.spaceId} value={s.spaceId}>
                {s.spaceNm ?? '공용공간'} (#{s.spaceId})
              </option>
            ))}
          </select>
        </div>
        <div className={styles.formGroup}>
          <label className={styles.label}>예약 가능 시간</label>
          {initStartAt &&
            selectedSlot &&
            String(selectedSlot.startAt).slice(0, 16) ===
              initStartAt.slice(0, 16) && (
              <p className={styles.autoSelectedBadge}>
                🤖 AI 추천 시간 자동 선택: {initStartAt.slice(11, 16)} ~{' '}
                {initEndAt?.slice(11, 16)}
              </p>
            )}
          {currentSpace?.unavailableReason ? (
            <p className={styles.mutedMsg} role="status">
              {toKoreanMessage({ errorCode: currentSpace.unavailableReason })}
            </p>
          ) : (
            <TimeSlotButtons
              slots={availableSlots}
              selectedKey={selectedSlot ? String(selectedSlot.startAt) : ''}
              onSelect={setChosenSlot}
            />
          )}
        </div>
      </section>

      {selectedSlot && currentSpace && (
        <div className={styles.summaryBox}>
          <p className={styles.summaryTitle}>📋 예약 요약</p>
          <div className={styles.summaryGrid}>
            <span>건물</span>
            <span>{formatBuildingDisplay(selectedBuilding)}</span>
            <span>공용공간</span>
            <span>{currentSpace.spaceNm}</span>
            <span>날짜</span>
            <span>{date}</span>
            <span>시간</span>
            <span>
              {selectedSlot.startAt?.slice(11, 16)} ~{' '}
              {selectedSlot.endAt?.slice(11, 16)}
            </span>
            <span>인원</span>
            <span>{srNoPeople}명</span>
          </div>
        </div>
      )}

      {submitError && (
        <div className={styles.submitError}>⚠️ {submitError}</div>
      )}
      <div className={styles.btnRow}>
        <button
          type="button"
          className={styles.cancelBtn}
          onClick={() => (inlineMode ? null : nav(-1))}
        >
          {inlineMode ? '초기화' : '취소'}
        </button>
        <button
          type="submit"
          className={styles.submitBtn}
          disabled={!canSubmit || submitLoading}
        >
          {submitLoading ? '예약 중…' : '📅 예약 확정'}
        </button>
      </div>

    </form>
  );

  // ── 인라인 모드: 래퍼 없이 바로 폼 반환 ──────────────────────
  if (inlineMode) {
    if (authLoading)
      return (
        <div className={styles.centerBox}>
          <span className={styles.spinner} />
        </div>
      );
    if (!user)
      return (
        <div className={styles.centerBox}>
          <p className={styles.errMsg}>
            🔒 공용공간 예약은 로그인 후 이용 가능합니다.
          </p>
        </div>
      );
    return formContent;
  }

  // ── 독립 페이지 모드 ──────────────────────────────────────────
  if (authLoading) {
    return (
      <div className={styles.page}>
        <Header />
        <div className={styles.centerBox}>
          <span className={styles.spinner} />
        </div>
        <Footer />
      </div>
    );
  }

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
        <h1 className={styles.pageTitle}>🛋️ 공용공간 예약</h1>
        <button
          className={styles.listLink}
          type="button"
          onClick={() => nav('/me?tab=space')}
        >
          내 예약 목록
        </button>
      </div>
      {formContent}
      <Footer />
    </div>
  );
}
