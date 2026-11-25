import { useEffect, useState } from 'react';
import { useAuth } from '../../user/hooks/useAuth';
import { reservationApi } from '../api/reservationApi';

const INITIAL_QUERY = { page: 1, size: 10, sort: 'reservationId', direct: 'DESC', status: '' };

export default function useSpaceReservations() {
  const { user, loading: authLoading } = useAuth();
  const userId = user?.userId;
  const [myQuery, setMyQuery] = useState(INITIAL_QUERY);
  const [refresh, setRefresh] = useState(0);
  const [result, setResult] = useState(null);
  const currentResult = !authLoading && result?.userId === userId && result?.query === myQuery
    && result?.refresh === refresh ? result : null;

  useEffect(() => {
    if (authLoading || !userId) return;
    const controller = new AbortController();
    let current = true;
    reservationApi.mySpaceReservations({ ...myQuery, signal: controller.signal }).then((page) => {
      if (!current) return;
      const lastPage = Math.max(1, page?.totalPages ?? 0);
      if (myQuery.page > lastPage) {
        setMyQuery((previous) => previous === myQuery ? { ...previous, page: lastPage } : previous);
        return;
      }
      setResult({ userId, query: myQuery, refresh, page });
    }).catch((error) => {
      if (current) setResult({ userId, query: myQuery, refresh, error: error?.message || '내 예약 조회 실패' });
    });
    return () => {
      current = false;
      controller.abort();
    };
  }, [authLoading, myQuery, refresh, userId]);

  return {
    myQuery,
    setMyQuery,
    myPage: currentResult?.page ?? null,
    myLoading: authLoading || Boolean(userId && !currentResult),
    myError: !authLoading && !userId
      ? '로그인 후 예약 내역을 확인할 수 있습니다.' : currentResult?.error ?? '',
    reloadMy: () => setRefresh((previous) => previous + 1),
    cancel: reservationApi.cancelSpaceReservation,
  };
}
