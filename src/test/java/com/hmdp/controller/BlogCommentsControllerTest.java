package com.hmdp.controller;
import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.dto.*;
import com.hmdp.service.IBlogCommentsService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.nullValue;
class BlogCommentsControllerTest {
    private IBlogCommentsService comments;
    private MockMvc mvc;
    @BeforeEach void setup(){comments=mock(IBlogCommentsService.class);var controller=new BlogCommentsController();ReflectionTestUtils.setField(controller,"comments",comments);mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new WebExceptionAdvice()).build();}
    @Test void returnsStringIdsAndIgnoresClientAuthorOwnershipStatusCountsAndTime() throws Exception {
        var user=new CommentUser("9007199254740999","public-name","");
        var row=new BlogCommentView("9007199254740997","9007199254740993","0","0",user,null,"text",LocalDateTime.of(2026,11,3,8,0),false,true,true,0);
        when(comments.publish(any())).thenReturn(Result.ok(new CommentMutationResult(row,1,"1",row)));
        mvc.perform(post("/blog-comments").contentType("application/json").content("{\"blogId\":\"9007199254740993\",\"content\":\"text\",\"requestId\":\"request\",\"userId\":999,\"parentId\":999,\"status\":2,\"replyCount\":999,\"createTime\":\"2000-01-01\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.comment.id").value("9007199254740997"))
                .andExpect(jsonPath("$.data.comment.blogId").value("9007199254740993"))
                .andExpect(jsonPath("$.data.comment.author.id").value("9007199254740999"))
                .andExpect(jsonPath("$.data.comment.author.phone").doesNotExist());
        var captured=ArgumentCaptor.forClass(CommentCreateRequest.class);verify(comments).publish(captured.capture());assertEquals(9007199254740993L,captured.getValue().getBlogId());assertNull(captured.getValue().getAnswerId());
    }
    @Test void defaultsAndNullCursorHaveAnExplicitEmptyPageStructure() throws Exception {
        when(comments.queryRoots(1L,null,10)).thenReturn(Result.ok(new CommentPage(List.of(),null,false,0,"0",null)));
        mvc.perform(get("/blog-comments/of/blog/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.list").isEmpty())
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue())).andExpect(jsonPath("$.data.hasMore").value(false));
        verify(comments).queryRoots(1L,null,10);
    }
    @Test void malformedIdsAndSizesAreClientErrorsAndDoNotCallTheService() throws Exception {
        for(String path:List.of("/blog-comments/of/blog/no","/blog-comments/9223372036854775808/replies","/blog-comments/of/blog/1?size=bad"))mvc.perform(get(path)).andExpect(status().isBadRequest());
        mvc.perform(delete("/blog-comments/no")).andExpect(status().isBadRequest());
        mvc.perform(post("/blog-comments").contentType("application/json").content("{\"blogId\":{}}" )).andExpect(status().isBadRequest());
        verifyNoInteractions(comments);
    }
}
