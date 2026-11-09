import React from 'react';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import BoardDetail from './BoardDetail';

const { api, auth } = vi.hoisted(() => ({
  api: {
    getBoard: vi.fn(), getReplies: vi.fn(), getChildReplies: vi.fn(),
    myBoards: vi.fn(), updateBoard: vi.fn(), deleteBoard: vi.fn(),
    updateReply: vi.fn(), deleteReply: vi.fn(), createReply: vi.fn(),
    createChildReply: vi.fn(), likeBoard: vi.fn(), unlikeBoard: vi.fn(),
    likeReply: vi.fn(), unlikeReply: vi.fn(),
  },
  auth: { user: null },
}));

vi.mock('../api/communityApi', () => ({ communityApi: api }));
vi.mock('../../user/hooks/useAuth', () => ({ useAuth: () => auth }));
vi.mock('../../../app/layouts/components/Header', () => ({ default: () => null }));
vi.mock('../../../app/layouts/components/Footer', () => ({ default: () => null }));
vi.mock('../../user/components/UserStatusModal', () => ({
  default: ({ userId }) => <div role="dialog">Member {userId}</div>,
}));

const board = (overrides = {}) => ({
  boardId: 7, boardTitle: 'Anonymous board', boardCtnt: 'Board content',
  anonymity: 'Y', userId: '익명(글쓴이)', realUserId: null,
  realUserNickname: null, mine: false, code: 'BOARD_FREE', files: [],
  ...overrides,
});

const reply = (overrides = {}) => ({
  replyId: 11, boardId: 7, replyCtnt: 'Anonymous reply',
  anonymity: 'Y', userId: '익명', realUserId: null,
  realUserNickname: null, anonymousId: 1, mine: false,
  ...overrides,
});

function renderBoard() {
  return render(
    <MemoryRouter initialEntries={['/community/boards/7']}>
      <Routes>
        <Route path="/community/boards/:boardId" element={<BoardDetail />} />
      </Routes>
    </MemoryRouter>
  );
}

function replyContainer(content) {
  return screen.getByText(content).parentElement;
}

describe('anonymous community author contract', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  beforeEach(() => {
    vi.clearAllMocks();
    sessionStorage.clear();
    auth.user = { userId: 'viewer', userRole: 'tenant', userSt: 'active' };
    api.getBoard.mockResolvedValue(board());
    api.getReplies.mockResolvedValue([]);
    api.getChildReplies.mockResolvedValue([]);
    api.myBoards.mockResolvedValue({ content: [], totalPages: 1 });
    api.updateBoard.mockResolvedValue(undefined);
    api.updateReply.mockResolvedValue(undefined);
    api.deleteReply.mockResolvedValue(undefined);
  });

  it('lets an anonymous board owner edit without receiving their real identity or scanning my posts', async () => {
    api.getBoard.mockResolvedValue(board({ mine: true }));
    renderBoard();

    const article = await screen.findByRole('article');
    fireEvent.click(within(article).getByRole('button', { name: '수정', exact: true }));
    fireEvent.change(within(article).getByPlaceholderText('제목을 입력하세요'), {
      target: { value: 'Updated anonymous board' },
    });
    fireEvent.click(within(article).getByRole('button', { name: '저장', exact: true }));

    await waitFor(() => expect(api.updateBoard).toHaveBeenCalledWith('7', {
      boardTitle: 'Updated anonymous board', boardCtnt: 'Board content', anonymity: 'Y',
    }));
    expect(api.myBoards).not.toHaveBeenCalled();
  });

  it('lets an anonymous reply owner edit with only the server ownership flag', async () => {
    api.getReplies.mockResolvedValue([reply({ mine: true })]);
    renderBoard();

    await screen.findByText('Anonymous reply');
    const container = replyContainer('Anonymous reply');
    expect(within(container).getByText('익명1')).toBeInTheDocument();
    fireEvent.click(within(container).getByRole('button', { name: '수정', exact: true }));
    fireEvent.change(within(container).getByRole('textbox'), {
      target: { value: 'Updated reply' },
    });
    fireEvent.click(within(container).getByRole('button', { name: '저장', exact: true }));

    await waitFor(() => expect(api.updateReply).toHaveBeenCalledWith(11, {
      replyCtnt: 'Updated reply', anonymity: 'Y',
    }));
    expect(api.myBoards).not.toHaveBeenCalled();
  });

  it('does not infer ownership from a display name or a missing mine flag', async () => {
    api.getBoard.mockResolvedValue(board({ anonymity: 'N', userId: 'viewer', mine: false }));
    api.getReplies.mockResolvedValue([reply({
      anonymity: 'N', userId: 'viewer', mine: undefined,
    })]);
    renderBoard();

    await screen.findByText('Anonymous reply');
    expect(within(screen.getByRole('article')).queryByRole('button', {
      name: '수정', exact: true,
    })).not.toBeInTheDocument();
    expect(within(replyContainer('Anonymous reply')).queryByRole('button', {
      name: '수정', exact: true,
    })).not.toBeInTheDocument();
    expect(api.myBoards).not.toHaveBeenCalled();
  });

  it('lets an anonymous child reply owner delete without a real identity', async () => {
    api.getReplies.mockResolvedValue([reply()]);
    api.getChildReplies.mockResolvedValueOnce([reply({
      replyId: 13, parentId: 11, replyCtnt: 'My anonymous child', anonymousId: 2, mine: true,
    })]).mockResolvedValue([]);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    renderBoard();

    await screen.findByText('My anonymous child');
    fireEvent.click(within(replyContainer('My anonymous child')).getByRole('button', {
      name: '삭제', exact: true,
    }));

    await waitFor(() => expect(api.deleteReply).toHaveBeenCalledWith(13));
    await waitFor(() => expect(screen.queryByText('My anonymous child')).not.toBeInTheDocument());
  });

  it('uses the same server-assigned anonymous numbers for root and child replies', async () => {
    auth.user = null;
    api.getReplies.mockResolvedValue([
      reply(),
      reply({ replyId: 12, replyCtnt: 'Second root', anonymousId: 2 }),
    ]);
    api.getChildReplies.mockImplementation((_boardId, parentId) => Promise.resolve(parentId === 11 ? [
      reply({ replyId: 13, parentId: 11, replyCtnt: 'Anonymous board author child', anonymousId: 0 }),
      reply({ replyId: 14, parentId: 11, replyCtnt: 'Second author child', anonymousId: 2 }),
    ] : []));
    renderBoard();

    await screen.findByText('Second author child');
    expect(within(replyContainer('Anonymous board author child')).getByText('익명(글쓴이)')).toBeInTheDocument();
    expect(within(replyContainer('Second author child')).getByText('익명2')).toBeInTheDocument();
    expect(within(replyContainer('Second root')).getByText('익명2')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '수정', exact: true })).not.toBeInTheDocument();
  });

  it('keeps administrator identity display and member-management links', async () => {
    auth.user = { userId: 'moderator', userRole: 'admin', userSt: 'active' };
    api.getBoard.mockResolvedValue(board({ realUserId: 'board-author', realUserNickname: 'Board Member' }));
    api.getReplies.mockResolvedValue([reply({ realUserId: 'reply-author', realUserNickname: 'Reply Member' })]);
    renderBoard();

    fireEvent.click(await screen.findByRole('button', { name: 'Board Member', exact: true }));
    expect(screen.getByRole('dialog')).toHaveTextContent('Member board-author');
    fireEvent.click(await screen.findByRole('button', { name: 'Reply Member', exact: true }));
    expect(screen.getByRole('dialog')).toHaveTextContent('Member reply-author');
    expect(screen.getByRole('button', { name: '🗑 관리자 삭제', exact: true })).toBeInTheDocument();
  });

  it('updates ownership and anonymous labels when refreshed reply metadata changes', async () => {
    api.getBoard.mockResolvedValue(board({ mine: true }));
    api.getReplies.mockResolvedValueOnce([reply()]).mockResolvedValue([reply({ mine: true, anonymousId: 4 })]);
    renderBoard();

    await screen.findByText('Anonymous reply');
    expect(within(replyContainer('Anonymous reply')).queryByRole('button', {
      name: '수정', exact: true,
    })).not.toBeInTheDocument();
    const article = screen.getByRole('article');
    fireEvent.click(within(article).getByRole('button', { name: '수정', exact: true }));
    fireEvent.click(within(article).getByRole('button', { name: '저장', exact: true }));

    await waitFor(() => expect(within(replyContainer('Anonymous reply')).getByRole('button', {
      name: '수정', exact: true,
    })).toBeInTheDocument());
    expect(within(replyContainer('Anonymous reply')).getByText('익명4')).toBeInTheDocument();
  });
});
