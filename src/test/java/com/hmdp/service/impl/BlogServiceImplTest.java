package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.repository.FeedRedisRepository;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BlogServiceImplTest {
    private BlogServiceImpl service;
    private IUserService userService;
    private BlogPublicationService publication;
    private FeedDeliveryService delivery;
    private FeedRedisRepository inbox;

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();
        service = spy(new BlogServiceImpl());
        userService = mock(IUserService.class);
        ReflectionTestUtils.setField(service, "userService", userService);
        ReflectionTestUtils.setField(service, "blogLikeService", mock(BlogLikeService.class));
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

    private void login(long id) {
        UserDTO user = new UserDTO();
        user.setId(id);
        UserHolder.saveUser(user);
    }
}
