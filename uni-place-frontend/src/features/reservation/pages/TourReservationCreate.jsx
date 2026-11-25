// features/reservation/pages/TourReservationCreate.jsx
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';

import { propertyApi } from '../../property/api/propertyApi';
import { reservationApi } from '../api/reservationApi';
import Header from '../../../app/layouts/components/Header';
import Footer from '../../../app/layouts/components/Footer';

import BuildingSlotButtons from '../components/BuildingSlotButtons';
import TimeSlotButtons from '../components/TimeSlotButtons';
import styles from './TourReservationCreate.module.css';
import { toKoreanMessage } from '../../../app/http/errorMapper';
import { formatBuildingDisplay } from '../../../shared/utils/branchLabel';
import { todayInSeoul } from '../../../shared/utils/seoulDate';
import {
  validatePhone,
  validateTourPwd,
} from '../../../shared/utils/validators';

const EMPTY_ITEMS = [];

export default function TourReservationCreate({
  inlineMode = false,
  onSuccess,
  onClose,
  onGoList,
  initialBuildingId = null,
  initialRoomId: propRoomId = '',
  initialDate = null, // AI 추천: 자동 선택할 날짜 (YYYY-MM-DD)
  initialSlotStartAt = null, // AI 추천: 자동 선택할 시작 시간
  initialSlotEndAt = null, // AI 추천: 자동 선택할 종료 시간
}) {
  const nav = useNavigate();
  const [searchParams] = useSearchParams();

  const initRoomId = String(
    propRoomId ||
    searchParams.get('roomId') || ''
  );
  const initBuildingId =
    initialBuildingId ??
    (searchParams.get('buildingId')
      ? Number(searchParams.get('buildingId'))
      : null);
  // AI 추천 날짜
  const initDate = initialDate ?? searchParams.get('date') ?? null;
  const initSlotStartAt =
    initialSlotStartAt ?? searchParams.get('startAt') ?? null;
  const initSlotEndAt = initialSlotEndAt ?? searchParams.get('endAt') ?? null;

  // 빌딩 목록
  const [buildings, setBuildings] = useState([]);
  const [bLoading, setBLoading] = useState(false);
  const [bError, setBError] = useState('');
  const [selectedBuilding, setSelectedBuilding] = useState(null);
  const buildingId = selectedBuilding ? Number(selectedBuilding.buildingId) : null;

  // rooms
  const [roomsPage, setRoomsPage] = useState(null);
  const [roomsLoading, setRoomsLoading] = useState(false);
  const [roomsError, setRoomsError] = useState('');
  const rooms = roomsPage?.buildingId === buildingId
    ? roomsPage.page?.content ?? EMPTY_ITEMS : EMPTY_ITEMS;

  // slot
  const today = todayInSeoul();
  const [date, setDate] = useState(initDate ?? today);
  const [roomId, setRoomId] = useState(initRoomId);
  const [slotsResult, setSlotsResult] = useState(null);
  const [slotsLoading, setSlotsLoading] = useState(false);
  const [slotsError, setSlotsError] = useState('');
  const [slotSelection, setSlotSelection] = useState(null);
  const selectedRoomInfo = rooms.find((r) => String(r.roomId) === String(roomId));
  const verifiedRoomId = selectedRoomInfo ? Number(selectedRoomInfo.roomId) : null;
  const slotQueryKey = buildingId && verifiedRoomId && date
    ? JSON.stringify([buildingId, verifiedRoomId, date]) : null;
  const currentSlotsResult = slotsResult?.key === slotQueryKey ? slotsResult : null;
  const unavailableReason = currentSlotsResult?.unavailableReason;
  const slotUnavailableMessage = unavailableReason ? toKoreanMessage({ errorCode: unavailableReason }) : '';
  const slots = unavailableReason ? EMPTY_ITEMS : currentSlotsResult?.slots ?? EMPTY_ITEMS;
  const selectedSlot = slotSelection?.key === slotQueryKey
    && slots.includes(slotSelection.slot) ? slotSelection.slot : null;
  const setSelectedSlot = useCallback((slot) => {
    setSlotSelection(slot ? { key: slotQueryKey, slot } : null);
  }, [slotQueryKey]);

  const [form, setForm] = useState({ tourNm: '', tourTel: '', tourPwd: '' });
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [submitError, setSubmitError] = useState('');
  const [submitLoading, setSubmitLoading] = useState(false);
  const submitRef = useRef(false);

  // AI 추천 슬롯 자동 선택 (slots 로드 후 한 번만 적용)
  const aiSlotAppliedRef = React.useRef(false);

  // 빌딩 목록 로드
  const loadBuildings = useCallback(async () => {
    setBLoading(true);
    setBError('');
    try {
      const page = await propertyApi.getBuildings({
        page: 1,
        size: 50,
        sort: 'buildingId',
        direct: 'DESC',
      });
      setBuildings(page?.content ?? []);
    } catch (e) {
      setBError(e?.message || '빌딩 조회 실패');
      setBuildings([]);
    } finally {
      setBLoading(false);
    }
  }, []);

  useEffect(() => {
    loadBuildings();
  }, [loadBuildings]);

  // 빌딩 목록 로드 후 URL buildingId로 자동 선택
  useEffect(() => {
    if (!initBuildingId || !buildings.length) return;
    const found = buildings.find(
      (b) => Number(b.buildingId) === Number(initBuildingId)
    );
    if (found && !selectedBuilding) setSelectedBuilding(found);
  }, [buildings, initBuildingId, selectedBuilding]);

  // 빌딩 선택 → 방 목록 로드
  useEffect(() => {
    let current = true;
    setRoomsPage(null);
    setRoomsError('');
    setRoomsLoading(Boolean(buildingId));
    if (!buildingId) {
      setRoomId('');
      return () => { current = false; };
    }
    const loadRooms = async () => {
      try {
        const page = await reservationApi.reservableRooms({
          buildingId, page: 1, size: 200, sort: 'roomNo', direct: 'ASC',
        });
        if (!current) return;
        const availableRooms = page?.content ?? [];
        setRoomsPage({ buildingId, page });
        setRoomId((previous) => {
          if (availableRooms.some((r) => String(r.roomId) === String(previous)))
            return previous;
          if (availableRooms.some((r) => String(r.roomId) === initRoomId))
            return initRoomId;
          return availableRooms.length ? String(availableRooms[0].roomId) : '';
        });
      } catch (e) {
        if (!current) return;
        setRoomsError(e?.message || '방 조회 실패');
        setRoomsPage(null);
        setRoomId('');
      } finally {
        if (current) setRoomsLoading(false);
      }
    };
    loadRooms();
    return () => { current = false; };
  }, [buildingId, initRoomId]);

  // Hide results whose request no longer belongs to the selected building, room and date.
  useEffect(() => {
    let current = true;
    setSlotSelection(null);
    setConfirmOpen(false);
    setSubmitError('');
    setSlotsResult(null);
    setSlotsError('');
    setSlotsLoading(Boolean(slotQueryKey));
    if (!slotQueryKey) return () => { current = false; };
    const loadSlots = async () => {
      try {
        const res = await reservationApi.reservableTourSlots({
          buildingId, roomId: verifiedRoomId, date,
        });
        if (!current) return;
        const availableSlots = (res?.availableSlots ?? []).filter((slot) =>
          slot.startAt?.slice(0, 10) === date && slot.endAt?.slice(0, 10) === date);
        setSlotsResult({ key: slotQueryKey, slots: availableSlots, unavailableReason: res?.unavailableReason });
      } catch (e) {
        if (!current) return;
        setSlotsError(e?.message || '슬롯 조회 실패');
        setSlotsResult(null);
      } finally {
        if (current) setSlotsLoading(false);
      }
    };
    loadSlots();
    return () => { current = false; };
  }, [buildingId, verifiedRoomId, date, slotQueryKey]);

  // Apply the initial recommendation only to its original booking context.
  useEffect(() => {
    if (!initSlotStartAt || aiSlotAppliedRef.current || slotsLoading) return;
    if (!slots.length || !slotQueryKey) return;
    if (initBuildingId && Number(initBuildingId) !== buildingId) return;
    if (initRoomId && initRoomId !== String(verifiedRoomId)) return;
    if (initSlotStartAt.slice(0, 10) !== date) return;

    const targetStart = initSlotStartAt.replace('T', ' ').slice(0, 16);
    const targetEnd = initSlotEndAt?.replace('T', ' ').slice(0, 16);
    const match = slots.find((slot) =>
      (slot.startAt || '').replace('T', ' ').slice(0, 16) === targetStart
      && (!targetEnd || (slot.endAt || '').replace('T', ' ').slice(0, 16) === targetEnd));
    if (match) {
      setSelectedSlot(match);
      aiSlotAppliedRef.current = true;
    }
  }, [slots, slotsLoading, slotQueryKey, initSlotStartAt, initSlotEndAt,
    initBuildingId, initRoomId, buildingId, verifiedRoomId, date, setSelectedSlot]);

  const canSubmit = useMemo(() => {
    if (!buildingId || !verifiedRoomId) return false;
    if (roomsLoading || slotsLoading || submitLoading) return false;
    if (!slotQueryKey || !selectedSlot) return false;
    if (!form.tourNm || !form.tourTel || !form.tourPwd) return false;
    if (form.tourNm.trim().length < 2) return false;
    if (!/^01[016789]\d{7,8}$/.test(form.tourTel.replace(/-/g, '')))
      return false;
    if (!/^[0-9]{4}$/.test(form.tourPwd)) return false;
    return true;
  }, [buildingId, verifiedRoomId, roomsLoading, slotsLoading, submitLoading,
    slotQueryKey, selectedSlot, form]);

  const getFormError = () => {
    if (!form.tourNm || form.tourNm.trim().length < 2)
      return '방문자 이름을 2자 이상 입력해주세요.';
    const telErr = validatePhone(form.tourTel);
    if (telErr) return telErr;
    const pwdErr = validateTourPwd(form.tourPwd);
    if (pwdErr) return pwdErr;
    return '';
  };

  const doCreateTourReservation = async () => {
    if (submitRef.current || !canSubmit) return;
    submitRef.current = true;
    setSubmitLoading(true);
    try {
      await reservationApi.createTourReservation({
        buildingId,
        roomId: verifiedRoomId,
        tourStartAt: selectedSlot.startAt,
        tourEndAt: selectedSlot.endAt,
        tourNm: form.tourNm,
        tourTel: form.tourTel,
        tourPwd: form.tourPwd,
      });
      if (inlineMode && onSuccess) {
        onSuccess();
      } else {
        nav('/reservations/tour/list');
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
    if (!canSubmit) return;
    const formErr = getFormError();
    if (formErr) {
      setSubmitError(formErr);
      return;
    }
    setConfirmOpen(true);
  };

  // ── 인라인 모드: 팝업 안에서 폼만 렌더 ───────────────────────
  if (inlineMode) {
    return (
      <form
        className={inlineMode ? styles.formInline : styles.form}
        onSubmit={onSubmit}
      >
        {inlineMode && (
          <div className={styles.inlineTopBar}>
            <button
              type="button"
              className={styles.listLink}
              onClick={() => onGoList?.()}
            >
              📋 예약 조회
            </button>
          </div>
        )}
        {/* ── 1. 건물 선택 ── */}
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

        {/* ── 2. 방 · 날짜 · 슬롯 ── */}
        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>2. 방 · 날짜 · 시간 선택</h2>

          <div className={styles.rowGroup}>
            <div className={styles.formGroup}>
              <label className={styles.label}>날짜</label>
              <input
                className={styles.input}
                type="date"
                value={date}
                min={today}
                onChange={(e) => setDate(e.target.value)}
                disabled={!selectedBuilding}
              />
            </div>
          </div>

          {roomsLoading && <p className={styles.mutedMsg}>방 목록 조회 중…</p>}
          {roomsError && <p className={styles.errMsg}>{roomsError}</p>}

          <div className={styles.formGroup}>
            <label className={styles.label}>방 선택</label>
            {selectedRoomInfo && (
              <p className={styles.autoSelectedBadge}>
                🏠 자동 선택: {`${selectedRoomInfo.roomNo}호`}
              </p>
            )}
            <select
              className={styles.select}
              value={roomId}
              onChange={(e) => setRoomId(e.target.value)}
              disabled={!selectedBuilding}
            >
              <option value="">선택하세요</option>
              {rooms.map((r) => (
                <option key={r.roomId} value={r.roomId}>
                  {r.roomNo}호
                </option>
              ))}
            </select>
          </div>

          <div className={styles.formGroup}>
            <label className={styles.label}>
              예약 가능 시간{' '}
              {slotsLoading && (
                <span className={styles.mutedInline}>(조회중…)</span>
              )}
            </label>
            {initSlotStartAt &&
              selectedSlot &&
              (String(selectedSlot.startAt) || '').slice(0, 16) ===
                initSlotStartAt.slice(0, 16) && (
                <p className={styles.autoSelectedBadge}>
                  🤖 AI 추천 시간 자동 선택: {initSlotStartAt.slice(11, 16)} ~{' '}
                  {(initSlotEndAt || '').slice(11, 16)}
                </p>
              )}
            {slotsError && <p className={styles.errMsg}>{slotsError}</p>}
            {slotUnavailableMessage ? (
              <p className={styles.mutedMsg} role="status">{slotUnavailableMessage}</p>
            ) : (
              <TimeSlotButtons
                slots={slots}
                selectedKey={selectedSlot ? String(selectedSlot.startAt) : ''}
                onSelect={setSelectedSlot}
              />
            )}
          </div>
        </section>

        {/* ── 3. 예약자 정보 ── */}
        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>3. 예약자 정보</h2>
          <div className={styles.rowGroup}>
            <div className={styles.formGroup}>
              <label className={styles.label}>이름 *</label>
              <input
                className={styles.input}
                type="text"
                placeholder="방문자 이름"
                value={form.tourNm}
                onChange={(e) =>
                  setForm((p) => ({ ...p, tourNm: e.target.value }))
                }
                disabled={!selectedBuilding}
              />
            </div>
            <div className={styles.formGroup}>
              <label className={styles.label}>연락처 *</label>
              <input
                className={styles.input}
                type="tel"
                placeholder="010-0000-0000"
                value={form.tourTel}
                onChange={(e) =>
                  setForm((p) => ({ ...p, tourTel: e.target.value }))
                }
                disabled={!selectedBuilding}
              />
            </div>
          </div>
          <div className={styles.formGroup}>
            <label className={styles.label}>예약 비밀번호 (숫자 4자리) *</label>
            <input
              className={styles.input}
              type="password"
              placeholder="조회·취소 시 사용"
              value={form.tourPwd}
              maxLength={4}
              pattern="\d{4}"
              onChange={(e) =>
                setForm((p) => ({ ...p, tourPwd: e.target.value }))
              }
              disabled={!selectedBuilding}
              style={{ maxWidth: 180 }}
            />
            <p className={styles.inputHint}>예약 조회 및 취소 시 사용합니다.</p>
          </div>
        </section>

        {/* 예약 요약 */}
        {selectedSlot && selectedRoomInfo && (
          <div className={styles.summaryBox}>
            <p className={styles.summaryTitle}>📋 예약 요약</p>
            <div className={styles.summaryGrid}>
              <span>건물</span>
              <span>{formatBuildingDisplay(selectedBuilding)}</span>
              <span>방</span>
              <span>{`${selectedRoomInfo.roomNo}호`}</span>
              <span>날짜</span>
              <span>{date}</span>
              <span>시간</span>
              <span>
                {selectedSlot.startAt?.slice(11, 16)} ~{' '}
                {selectedSlot.endAt?.slice(11, 16)}
              </span>
            </div>
          </div>
        )}

        {submitError && (
          <div className={styles.submitError} role="alert">⚠️ {submitError}</div>
        )}

        <div className={styles.btnRow}>
          <button
            type="button"
            className={styles.cancelBtn}
            onClick={() => (inlineMode ? onClose?.() : nav(-1))}
          >
            취소
          </button>
          <button
            type="submit"
            className={styles.submitBtn}
            disabled={!canSubmit}
          >
            📅 예약 신청
          </button>
        </div>

        {/* 예약 확인 다이얼로그 */}
        {confirmOpen && (
          <div
            style={{
              position: 'fixed',
              inset: 0,
              background: 'rgba(0,0,0,0.45)',
              zIndex: 2000,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <div
              style={{
                background: '#fff',
                borderRadius: '16px',
                padding: '32px 28px',
                boxShadow: '0 8px 32px rgba(0,0,0,0.18)',
                minWidth: '300px',
                textAlign: 'center',
              }}
            >
              <p
                style={{
                  margin: '0 0 8px',
                  fontSize: '16px',
                  fontWeight: '800',
                  color: '#2d2416',
                }}
              >
                예약을 신청하시겠습니까?
              </p>
              {selectedSlot && selectedRoomInfo && (
                <p
                  style={{
                    margin: '0 0 20px',
                    fontSize: '13px',
                    color: '#7a6a50',
                  }}
                >
                  {formatBuildingDisplay(selectedBuilding) || '-'} {'>'}{' '}
                  {`${selectedRoomInfo.roomNo}호`} · {date} ·{' '}
                  {selectedSlot.startAt?.slice(11, 16)}~
                  {selectedSlot.endAt?.slice(11, 16)}
                </p>
              )}
              <div
                style={{
                  display: 'flex',
                  gap: '12px',
                  justifyContent: 'center',
                }}
              >
                <button
                  type="button"
                  style={{
                    minWidth: '80px',
                    height: '40px',
                    borderRadius: '10px',
                    border: '1.5px solid #ccc',
                    background: '#fff',
                    color: '#666',
                    fontWeight: '700',
                    cursor: 'pointer',
                  }}
                  onClick={() => setConfirmOpen(false)}
                >
                  아니오
                </button>
                <button
                  type="button"
                  style={{
                    minWidth: '80px',
                    height: '40px',
                    borderRadius: '10px',
                    border: 0,
                    background: '#c58a3a',
                    color: '#fff',
                    fontWeight: '700',
                    cursor: 'pointer',
                  }}
                  disabled={submitLoading}
                  onClick={() => {
                    setConfirmOpen(false);
                    doCreateTourReservation();
                  }}
                >
                  {submitLoading ? '신청 중…' : '예'}
                </button>
              </div>
            </div>
          </div>
        )}
      </form>
    );
  }

  // ── 독립 페이지 모드 ─────────────────────────────────────────
  return (
    <div className={styles.page}>
      <Header />
      {/* 헤더 */}
      <div className={styles.topBar}>
        <button
          className={styles.backBtn}
          type="button"
          onClick={() => nav(-1)}
        >
          ←
        </button>
        <h1 className={styles.pageTitle}>📅 방문 예약</h1>
        <button
          className={styles.listLink}
          type="button"
          onClick={() => nav('/reservations/tour/list')}
        >
          내 예약 조회
        </button>
      </div>

      <form className={styles.form} onSubmit={onSubmit}>
        {/* ── 1. 건물 선택 ── */}
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

        {/* ── 2. 방 · 날짜 · 슬롯 ── */}
        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>2. 방 · 날짜 · 시간 선택</h2>

          <div className={styles.rowGroup}>
            <div className={styles.formGroup}>
              <label className={styles.label}>날짜</label>
              <input
                className={styles.input}
                type="date"
                value={date}
                min={today}
                onChange={(e) => setDate(e.target.value)}
                disabled={!selectedBuilding}
              />
            </div>
          </div>

          {roomsLoading && <p className={styles.mutedMsg}>방 목록 조회 중…</p>}
          {roomsError && <p className={styles.errMsg}>{roomsError}</p>}

          <div className={styles.formGroup}>
            <label className={styles.label}>방 선택</label>
            {selectedRoomInfo && (
              <p className={styles.autoSelectedBadge}>
                🏠 자동 선택: {`${selectedRoomInfo.roomNo}호`}
              </p>
            )}
            <select
              className={styles.select}
              value={roomId}
              onChange={(e) => setRoomId(e.target.value)}
              disabled={!selectedBuilding}
            >
              <option value="">선택하세요</option>
              {rooms.map((r) => (
                <option key={r.roomId} value={r.roomId}>
                  {r.roomNo}호
                </option>
              ))}
            </select>
          </div>

          <div className={styles.formGroup}>
            <label className={styles.label}>
              예약 가능 시간{' '}
              {slotsLoading && (
                <span className={styles.mutedInline}>(조회중…)</span>
              )}
            </label>
            {initSlotStartAt &&
              selectedSlot &&
              (String(selectedSlot.startAt) || '').slice(0, 16) ===
                initSlotStartAt.slice(0, 16) && (
                <p className={styles.autoSelectedBadge}>
                  🤖 AI 추천 시간 자동 선택: {initSlotStartAt.slice(11, 16)} ~{' '}
                  {(initSlotEndAt || '').slice(11, 16)}
                </p>
              )}
            {slotsError && <p className={styles.errMsg}>{slotsError}</p>}
            {slotUnavailableMessage ? (
              <p className={styles.mutedMsg} role="status">{slotUnavailableMessage}</p>
            ) : (
              <TimeSlotButtons
                slots={slots}
                selectedKey={selectedSlot ? String(selectedSlot.startAt) : ''}
                onSelect={setSelectedSlot}
              />
            )}
          </div>
        </section>

        {/* ── 3. 예약자 정보 ── */}
        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>3. 예약자 정보</h2>
          <div className={styles.rowGroup}>
            <div className={styles.formGroup}>
              <label className={styles.label}>이름 *</label>
              <input
                className={styles.input}
                type="text"
                placeholder="방문자 이름"
                value={form.tourNm}
                onChange={(e) =>
                  setForm((p) => ({ ...p, tourNm: e.target.value }))
                }
                disabled={!selectedBuilding}
              />
            </div>
            <div className={styles.formGroup}>
              <label className={styles.label}>연락처 *</label>
              <input
                className={styles.input}
                type="tel"
                placeholder="010-0000-0000"
                value={form.tourTel}
                onChange={(e) =>
                  setForm((p) => ({ ...p, tourTel: e.target.value }))
                }
                disabled={!selectedBuilding}
              />
            </div>
          </div>
          <div className={styles.formGroup}>
            <label className={styles.label}>예약 비밀번호 (숫자 4자리) *</label>
            <input
              className={styles.input}
              type="password"
              placeholder="조회·취소 시 사용"
              value={form.tourPwd}
              maxLength={4}
              pattern="\d{4}"
              onChange={(e) =>
                setForm((p) => ({ ...p, tourPwd: e.target.value }))
              }
              disabled={!selectedBuilding}
              style={{ maxWidth: 180 }}
            />
            <p className={styles.inputHint}>예약 조회 및 취소 시 사용합니다.</p>
          </div>
        </section>

        {/* 예약 요약 */}
        {selectedSlot && selectedRoomInfo && (
          <div className={styles.summaryBox}>
            <p className={styles.summaryTitle}>📋 예약 요약</p>
            <div className={styles.summaryGrid}>
              <span>건물</span>
              <span>{formatBuildingDisplay(selectedBuilding)}</span>
              <span>방</span>
              <span>{`${selectedRoomInfo.roomNo}호`}</span>
              <span>날짜</span>
              <span>{date}</span>
              <span>시간</span>
              <span>
                {selectedSlot.startAt?.slice(11, 16)} ~{' '}
                {selectedSlot.endAt?.slice(11, 16)}
              </span>
            </div>
          </div>
        )}

        {submitError && (
          <div className={styles.submitError} role="alert">⚠️ {submitError}</div>
        )}

        <div className={styles.btnRow}>
          <button
            type="button"
            className={styles.cancelBtn}
            onClick={() => nav(-1)}
          >
            취소
          </button>
          <button
            type="submit"
            className={styles.submitBtn}
            disabled={!canSubmit}
          >
            📅 예약 신청
          </button>
        </div>

        {/* 예약 확인 다이얼로그 */}
        {confirmOpen && (
          <div
            style={{
              position: 'fixed',
              inset: 0,
              background: 'rgba(0,0,0,0.45)',
              zIndex: 2000,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <div
              style={{
                background: '#fff',
                borderRadius: '16px',
                padding: '32px 28px',
                boxShadow: '0 8px 32px rgba(0,0,0,0.18)',
                minWidth: '300px',
                textAlign: 'center',
              }}
            >
              <p
                style={{
                  margin: '0 0 8px',
                  fontSize: '16px',
                  fontWeight: '800',
                  color: '#2d2416',
                }}
              >
                예약을 신청하시겠습니까?
              </p>
              {selectedSlot && selectedRoomInfo && (
                <p
                  style={{
                    margin: '0 0 20px',
                    fontSize: '13px',
                    color: '#7a6a50',
                  }}
                >
                  {formatBuildingDisplay(selectedBuilding) || '-'} {'>'}{' '}
                  {`${selectedRoomInfo.roomNo}호`} · {date} ·{' '}
                  {selectedSlot.startAt?.slice(11, 16)}~
                  {selectedSlot.endAt?.slice(11, 16)}
                </p>
              )}
              <div
                style={{
                  display: 'flex',
                  gap: '12px',
                  justifyContent: 'center',
                }}
              >
                <button
                  type="button"
                  style={{
                    minWidth: '80px',
                    height: '40px',
                    borderRadius: '10px',
                    border: '1.5px solid #ccc',
                    background: '#fff',
                    color: '#666',
                    fontWeight: '700',
                    cursor: 'pointer',
                  }}
                  onClick={() => setConfirmOpen(false)}
                >
                  아니오
                </button>
                <button
                  type="button"
                  style={{
                    minWidth: '80px',
                    height: '40px',
                    borderRadius: '10px',
                    border: 0,
                    background: '#c58a3a',
                    color: '#fff',
                    fontWeight: '700',
                    cursor: 'pointer',
                  }}
                  disabled={submitLoading}
                  onClick={() => {
                    setConfirmOpen(false);
                    doCreateTourReservation();
                  }}
                >
                  {submitLoading ? '신청 중…' : '예'}
                </button>
              </div>
            </div>
          </div>
        )}
      </form>
      <Footer />
    </div>
  );
}
