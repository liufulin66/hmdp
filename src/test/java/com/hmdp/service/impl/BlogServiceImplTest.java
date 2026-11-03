package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IUserService;
import com.hmdp.repository.FeedRedisRepository;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BlogServiceImplTest {
    private BlogServiceImpl service;
    private IUserService userService;
    private BlogLikeService blogLikeService;
    private BlogMapper blogMapper;
    private BlogPublicationService publication;
    private FeedDeliveryService delivery;
    private FeedRedisRepository inbox;

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();
        service = spy(new BlogServiceImpl());
        userService = mock(IUserService.class);
        blogLikeService = mock(BlogLikeService.class);
        blogMapper = mock(BlogMapper.class);
        ReflectionTestUtils.setField(service, "userService", userService);
        ReflectionTestUtils.setField(service, "blogLikeService", blogLikeService);
        ReflectionTestUtils.setField(service, "baseMapper", blogMapper);
        publication = mock(BlogPublicationService.class);
        delivery = mock(FeedDeliveryService.class);
        inbox = mock(FeedRedisRepository.class);
        ReflectionTestUtils.setField(service, "publication", publication);
        ReflectionTestUtils.setField(service, "delivery", delivery);
        ReflectionTestUtils.setField(service, "inbox", inbox);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void anonymousReaderGetsContentAndAuthor() {
        Blog blog = new Blog().setId(1L).setUserId(2L).setContent("探店正文");
        doReturn(blog).when(service).getById(1L);
        when(userService.getById(2L)).thenReturn(new User().setId(2L)
                .setNickName("作者").setIcon("/imgs/author.png"));

        Result result = service.queryBlogById(1L);

        assertTrue(result.getSuccess());
        Blog detail = (Blog) result.getData();
        assertEquals("探店正文", detail.getContent());
        assertEquals("作者", detail.getName());
        assertEquals("/imgs/author.png", detail.getIcon());
        assertNull(UserHolder.getUser());
    }

    @Test
    void invalidIdsDoNotAccessStorage() {
        assertFalse(service.queryBlogById(null).getSuccess());
        assertFalse(service.queryBlogById(0L).getSuccess());
        assertFalse(service.queryBlogById(-1L).getSuccess());
        verify(service, never()).getById(any());
        verifyNoInteractions(userService);
    }

    @Test
    void missingBlogReturnsBusinessFailure() {
        doReturn(null).when(service).getById(1L);
        Result result = service.queryBlogById(1L);
        assertFalse(result.getSuccess());
        assertEquals("笔记不存在", result.getErrorMsg());
        verifyNoInteractions(userService);
    }

    @Test
    void missingAuthorDoesNotHideBlog() {
        Blog blog = new Blog().setId(1L).setUserId(2L).setContent("探店正文");
        doReturn(blog).when(service).getById(1L);
        Result result = service.queryBlogById(1L);
        assertTrue(result.getSuccess());
        assertSame(blog, result.getData());
        assertEquals("探店正文", blog.getContent());
        assertNull(blog.getName());
    }

    @Test
    void publicationUsesLoggedInAuthorAndReturnsGeneratedId() {
        UserDTO user = new UserDTO();
        user.setId(2L);
        UserHolder.saveUser(user);
        Blog blog = new Blog().setUserId(999L).setTitle("探店");
        when(publication.publish(blog, 2L)).thenReturn(10L);

        Result result = service.saveBlog(blog);
        assertTrue(result.getSuccess());
        assertEquals(10L, result.getData());
        verify(publication).publish(blog, 2L);
        verify(delivery).deliverBlog(10);
    }

    @Test
    void failedPublicationDoesNotPushAndCommittedPublicationSurvivesDeliveryFailure() {
        login(2);
        Blog blog = new Blog();
        when(publication.publish(blog, 2)).thenThrow(new IllegalStateException("database failed"));
        assertThrows(IllegalStateException.class, () -> service.saveBlog(blog));
        verifyNoInteractions(delivery);
        doReturn(10L).when(publication).publish(blog, 2);
        doThrow(new IllegalStateException("redis failed")).when(delivery).deliverBlog(10);
        assertEquals(10L, service.saveBlog(blog).getData());
    }

    @Test
    void feedRejectsInvalidParametersAndAnonymousAccessBeforeStorage() {
        assertFalse(service.queryBlogOfFollow(1000L, 0).getSuccess());
        assertFalse(service.saveBlog(new Blog()).getSuccess());
        login(2);
        for (Long time : new Long[]{null, -1L, 9007199254740992L, Long.MAX_VALUE}) {
            assertFalse(service.queryBlogOfFollow(time, 0).getSuccess());
        }
        for (Integer offset : new Integer[]{null, -1, Integer.MAX_VALUE}) {
            assertFalse(service.queryBlogOfFollow(1000L, offset).getSuccess());
        }
        verifyNoInteractions(inbox, publication, delivery);
    }

    @Test
    void feedUsesOwnInboxOneNoteBatchAndRestoresOrderAndAuthorProfiles() {
        login(2);
        when(inbox.read(2, 1000, 0)).thenReturn(List.of(new FeedRedisRepository.Entry(9, 900), new FeedRedisRepository.Entry(3, 800)));
        Blog nine = new Blog().setId(9L).setUserId(1L);
        Blog three = new Blog().setId(3L).setUserId(1L);
        doReturn(List.of(three, nine)).when(service).listByIds(List.of(9L, 3L));
        when(userService.listByIds(List.of(1L))).thenReturn(List.of(new User().setId(1L).setNickName("author").setIcon("author.png")));
        ScrollResult page = (ScrollResult) service.queryBlogOfFollow(1000L, 0).getData();
        assertEquals(List.of(nine, three), page.getList());
        assertEquals(800L, page.getMinTime());
        assertEquals(1, page.getOffset());
        assertEquals("author", nine.getName());
        verify(service).listByIds(List.of(9L, 3L));
        verify(userService).listByIds(List.of(1L));
        verify(userService, never()).getById(any());
    }

    @Test
    void sameScoreOffsetAccumulatesAndMissingNotesStillAdvanceCursor() {
        login(2);
        when(inbox.read(2, 1000, 4)).thenReturn(List.of(new FeedRedisRepository.Entry(9, 1000), new FeedRedisRepository.Entry(8, 1000)));
        doReturn(List.of()).when(service).listByIds(List.of(9L, 8L));
        ScrollResult page = (ScrollResult) service.queryBlogOfFollow(1000L, 4).getData();
        assertEquals(List.of(), page.getList());
        assertEquals(1000L, page.getMinTime());
        assertEquals(6, page.getOffset());
        verifyNoInteractions(userService);
        when(inbox.read(2, 1000, 6)).thenReturn(List.of());
        ScrollResult end = (ScrollResult) service.queryBlogOfFollow(1000L, 6).getData();
        assertEquals(List.of(), end.getList());
        assertEquals(0L, end.getMinTime());
        assertEquals(0, end.getOffset());
    }

    @Test
    void userBlogListRejectsInvalidTargetsAndMissingUsersBeforeQueryingNotes() {
        assertFalse(service.queryBlogOfUser(null, 1).getSuccess());
        assertFalse(service.queryBlogOfUser(0L, 1).getSuccess());
        assertFalse(service.queryBlogOfUser(-1L, 1).getSuccess());
        assertFalse(service.queryBlogOfUser(2L, null).getSuccess());
        assertFalse(service.queryBlogOfUser(2L, 0).getSuccess());
        assertFalse(service.queryBlogOfUser(2L, 1001).getSuccess());
        verifyNoInteractions(blogMapper);
        when(userService.getById(9L)).thenReturn(null);
        Result missing = service.queryBlogOfUser(9L, 1);
        assertFalse(missing.getSuccess());
        assertEquals("用户不存在", missing.getErrorMsg());
        verifyNoInteractions(blogMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void userBlogListPagesByTheTargetUserAndFillsAuthorOnceAndLikesPerNote() {
        User author = new User().setId(9L).setNickName("作者").setIcon("author.png");
        when(userService.getById(9L)).thenReturn(author);
        Blog newer = new Blog().setId(12L).setUserId(9L);
        Blog older = new Blog().setId(11L).setUserId(9L);
        Page<Blog> page = new Page<>(2, SystemConstants.MAX_PAGE_SIZE);
        page.setRecords(List.of(newer, older));
        when(blogMapper.selectPage(any(Page.class), any())).thenReturn(page);

        Result result = service.queryBlogOfUser(9L, 2);

        assertTrue(result.getSuccess());
        assertEquals(List.of(newer, older), result.getData());
        assertEquals("作者", newer.getName());
        assertEquals("author.png", older.getIcon());
        verify(userService, times(1)).getById(9L);
        verify(blogLikeService, times(2)).fillLikes(any(Blog.class));
        ArgumentCaptor<Page<Blog>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(blogMapper).selectPage(pageCaptor.capture(), any());
        assertEquals(2, pageCaptor.getValue().getCurrent());
        assertEquals(SystemConstants.MAX_PAGE_SIZE, pageCaptor.getValue().getSize());
    }

    @Test
    @SuppressWarnings("unchecked")
    void existingUserWithoutNotesReturnsAnEmptyArray() {
        when(userService.getById(4L)).thenReturn(new User().setId(4L).setNickName("empty"));
        Page<Blog> page = new Page<>(1, SystemConstants.MAX_PAGE_SIZE);
        page.setRecords(List.of());
        when(blogMapper.selectPage(any(Page.class), any())).thenReturn(page);

        Result result = service.queryBlogOfUser(4L, 1);

        assertTrue(result.getSuccess());
        assertEquals(List.of(), result.getData());
        verify(blogLikeService, never()).fillLikes(any(Blog.class));
    }

    private void login(long id) {
        UserDTO user = new UserDTO();
        user.setId(id);
        UserHolder.saveUser(user);
    }
}
