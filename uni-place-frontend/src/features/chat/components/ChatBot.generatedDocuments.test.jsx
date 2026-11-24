import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ChatBot from './ChatBot';
import { HISTORY_STORAGE_KEY } from '../config/chatConfig';

const { post } = vi.hoisted(() => ({ post: vi.fn() }));
vi.mock('../../../app/http/axiosInstance', () => ({ api: { post } }));
vi.mock('../../../shared/components/Modal/Modal', () => ({
  default: ({ open, children }) => open ? children : null,
}));
vi.mock('../../reservation/pages/SpaceReservationCreate', () => ({
  default: ({ initSpaceId }) => <p>공용공간 예약 {initSpaceId}</p>,
}));
vi.mock('../../reservation/pages/SpaceReservationList', () => ({ default: () => null }));
vi.mock('../../reservation/pages/TourReservationCreate', () => ({ default: () => null }));
vi.mock('../../reservation/pages/TourReservationList', () => ({ default: () => null }));

function openChat() {
  render(
    <MemoryRouter>
      <ChatBot user={{ userId: 'synthetic-member', userRole: 'user' }} useBackend />
    </MemoryRouter>
  );
  fireEvent.click(screen.getByRole('button', { name: 'AI 챗봇 열기' }));
}

describe('AI 문서 기능 중지 후 챗봇 대화', () => {
  let originalScroll;

  beforeEach(() => {
    post.mockReset();
    localStorage.clear();
    vi.stubGlobal('speechSynthesis', { cancel: vi.fn() });
    originalScroll = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollIntoView');
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value: vi.fn(),
    });
  });

  afterEach(() => {
    cleanup();
    localStorage.clear();
    vi.unstubAllGlobals();
    if (originalScroll) {
      Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', originalScroll);
    } else {
      delete HTMLElement.prototype.scrollIntoView;
    }
  });

  it('저장된 문서 액션과 다운로드 주소를 숨기고 예약과 일반 파일 링크를 유지한다', () => {
    const documents = [
      { label: '기존 발주서 주소', url: '/api/ai/payment/order-form/download/synthetic.xlsx' },
      { label: 'AI 원본 주소', url: 'https://ai.example.test/api/v1/ai/payments/order-forms/downloads/synthetic.xlsx' },
      { label: '원본 파일 주소', url: '/api/static-files/AI_DOCUMENT/1/synthetic.xlsx' },
      { label: '기존 파일 번호 주소', url: '/api/files/72/download?download=true' },
      { label: '기존 미리보기 주소', url: 'https://files.example.test/export/synthetic.xlsx' },
    ];
    localStorage.setItem(HISTORY_STORAGE_KEY, JSON.stringify({
      'synthetic-member': [{
        role: 'assistant',
        content: '이전 합성 대화',
        ts: Date.now(),
        metadata: {
          file_id: 72,
          file_name: 'purchase_order_synthetic.xlsx',
          view_url: 'https://files.example.test/export/synthetic.xlsx',
        },
        actions: [
          { type: 'create_order_form', label: '기존 발주서 생성' },
          { type: 'download_order_form', label: '기존 발주서 다운로드' },
          { type: 'space_reserve', label: '기존 예약 선택', spaceId: 9 },
        ],
        buttons: [
          ...documents,
          { label: '다른 파일 다운로드', url: '/api/files/73/download' },
          { label: '방 둘러보기', url: '/rooms' },
        ],
      }],
    }));

    openChat();

    expect(screen.getByText('이전 합성 대화')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '기존 발주서 생성' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '기존 발주서 다운로드' })).not.toBeInTheDocument();
    for (const { label } of documents) {
      expect(screen.queryByRole('button', { name: label })).not.toBeInTheDocument();
    }
    expect(screen.getByRole('button', { name: '다른 파일 다운로드' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '방 둘러보기' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '기존 예약 선택' }));
    expect(screen.getByText('공용공간 예약 9')).toBeInTheDocument();
    expect(post).not.toHaveBeenCalled();
  });

  it('새 답변의 문서 버튼을 추가하지 않고 공용공간 추천을 유지한다', async () => {
    post.mockResolvedValue({
      data: {
        data: {
          answer: '새 합성 답변',
          metadata: {
            suggestions: [{ name: '합성 항목' }],
            file_name: 'purchase_order_synthetic.xlsx',
            file_id: 72,
            action_buttons: [{ label: '엑셀 다운로드', url: '/api/files/72/view' }],
            recommend_type: 'space_reserve',
            space_id: 8,
          },
        },
      },
    });
    openChat();
    fireEvent.change(screen.getByRole('textbox', { name: '메시지 입력' }), {
      target: { value: '공용공간 보여줘' },
    });
    fireEvent.click(screen.getByRole('button', { name: '전송' }));
    expect(await screen.findByText('새 합성 답변')).toBeInTheDocument();

    expect(screen.queryByRole('button', { name: /발주서|엑셀 다운로드/ })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /바로 예약하기/ }));
    expect(screen.getByText('공용공간 예약 8')).toBeInTheDocument();
    expect(post).toHaveBeenCalledTimes(1);
    expect(post.mock.calls[0][0]).toBe('/ai/chat/agent-chatbot');
  });
});
