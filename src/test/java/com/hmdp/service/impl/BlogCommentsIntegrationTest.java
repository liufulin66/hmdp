package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DynamicTableNameInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.hmdp.dto.*;
import com.hmdp.entity.*;
import com.hmdp.mapper.*;
import com.hmdp.utils.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实多连接 MySQL、真实唯一约束和事务；仅创建随机测试表，不启动完整应用。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named="hmdp.integration",matches="true")
class BlogCommentsIntegrationTest {
    private final Map<String,String> tables=new LinkedHashMap<>();
    private final Map<String,Long> before=new HashMap<>();
    private JdbcTemplate jdbc;
    private BlogMapper blogs;
    private BlogCommentsMapper comments;
    private UserMapper users;
    private BlogCommentsServiceImpl target,service;
    private DataSourceTransactionManager tx;
    @BeforeAll void connect() throws Exception {
        var env=new StandardEnvironment();
        for(String path:List.of("src/main/resources/application.yaml","config/application-secret.yml")) {
            var resource=new FileSystemResource(path); if(!resource.exists())continue;
            var yaml=new YamlPropertiesFactoryBean();yaml.setResources(resource);env.getPropertySources().addFirst(new PropertiesPropertySource(path,yaml.getObject()));
        }
        var source=new DriverManagerDataSource(env.getRequiredProperty("spring.datasource.url"),env.getRequiredProperty("spring.datasource.username"),env.getRequiredProperty("spring.datasource.password"));
        jdbc=new JdbcTemplate(source);tx=new DataSourceTransactionManager(source);assertEquals("hmdp",jdbc.queryForObject("SELECT DATABASE()",String.class));
        String suffix=UUID.randomUUID().toString().replace("-","");
        for(String name:List.of("tb_blog","tb_blog_comments","tb_user")) {
            before.put(name,jdbc.queryForObject("SELECT COUNT(*) FROM "+name,Long.class));
            tables.put(name,"hmdp_test_comments_"+name.substring(3).replace("_","")+"_"+suffix);jdbc.execute("CREATE TABLE "+table(name)+" LIKE "+name);
        }
        var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(BlogMapper.class);configuration.addMapper(BlogCommentsMapper.class);configuration.addMapper(UserMapper.class);
        var names=new DynamicTableNameInnerInterceptor();names.setTableNameHandler((sql,name)->tables.getOrDefault(name,name));
        var interceptor=new MybatisPlusInterceptor();interceptor.addInnerInterceptor(names);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(configuration);factory.setPlugins(interceptor);
        var session=new SqlSessionTemplate(factory.getObject());blogs=session.getMapper(BlogMapper.class);comments=session.getMapper(BlogCommentsMapper.class);users=session.getMapper(UserMapper.class);
        target=new BlogCommentsServiceImpl();ReflectionTestUtils.setField(target,"baseMapper",comments);ReflectionTestUtils.setField(target,"blogs",blogs);ReflectionTestUtils.setField(target,"users",users);
        var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(tx,new AnnotationTransactionAttributeSource()));service=(BlogCommentsServiceImpl)proxy.getProxy();
    }
    @BeforeEach void fixtures() {
        for(String name:tables.keySet())jdbc.update("DELETE FROM "+table(name));
        for(long id=1;id<=12;id++)jdbc.update("INSERT INTO "+table("tb_user")+"(id,phone,nick_name,icon) VALUES(?,?,?,?)",id,"test-"+id,"user-"+id,"icon-"+id);
        for(long id=1;id<=2;id++)jdbc.update("INSERT INTO "+table("tb_blog")+"(id,shop_id,user_id,title,images,content,comments) VALUES(?,1,1,'test','test.jpg','test',0)",id);
        ReflectionTestUtils.setField(target,"baseMapper",comments);ReflectionTestUtils.setField(target,"blogs",blogs);ReflectionTestUtils.setField(target,"users",users);login(1);
    }
    @AfterEach void logout(){UserHolder.removeUser();}
    @AfterAll void close(){if(jdbc!=null){for(String name:tables.keySet())jdbc.execute("DROP TABLE IF EXISTS "+table(name));before.forEach((name,count)->assertEquals(count,jdbc.queryForObject("SELECT COUNT(*) FROM "+name,Long.class)));}}
    @Test void rootsAndNestedRepliesDeriveRelationsAndBatchPublicProfiles() {
        var root=post(1,0,"root","root");long rootId=id(root);login(2);
        var reply=post(1,rootId,"DO-NOT-LEAK","reply");login(3);var nested=post(1,id(reply),"third","nested");
        assertEquals(rootId,Long.parseLong(reply.comment().parentId()));assertEquals(rootId,Long.parseLong(nested.comment().parentId()));assertEquals(reply.comment().id(),nested.comment().answerId());
        assertEquals("2",nested.comment().answerUser().id());assertEquals(2,nested.root().replyCount());assertEquals(3,count(1));
        UserMapper counted=spy(users);ReflectionTestUtils.setField(target,"users",counted);UserHolder.removeUser();
        CommentPage page=replies(rootId,null,10);assertEquals(2,page.list().size());assertFalse(page.list().get(0).canDelete());
        verify(counted,times(1)).selectByIds(anyCollection());verify(counted,never()).selectById(anyLong());
    }
    @Test void crossBlogDeletedTargetsAndDeletedRootsRejectNewReplies() {
        var root=post(1,0,"root","r");var child=post(1,id(root),"child","c");
        assertFalse(service.publish(request(2,id(root),"cross","cross")).getSuccess());
        assertTrue(service.deleteComment(id(child)).getSuccess());assertFalse(service.publish(request(1,id(child),"no","deleted-target")).getSuccess());
        assertTrue(service.deleteComment(id(root)).getSuccess());assertFalse(service.publish(request(1,id(root),"no","deleted-root")).getSuccess());assertEquals(0,count(1));
    }
    @Test void unauthenticatedWritesAndOtherAuthorsDeletionFail() {
        var root=post(1,0,"root","r");login(2);assertFalse(service.deleteComment(id(root)).getSuccess());UserHolder.removeUser();
        assertFalse(service.publish(request(1,0,"text","guest")).getSuccess());assertFalse(service.deleteComment(id(root)).getSuccess());
        assertTrue(service.queryRoots(1L,null,10).getSuccess());assertEquals(1,count(1));
    }
    @Test void retriesAreIdempotentEvenAfterDeletionAndDifferentRequestsCanRepeatText() {
        var root=post(1,0,"same","same-key");var retry=post(1,0," same ","same-key");assertEquals(root.comment().id(),retry.comment().id());assertEquals(1,count(1));
        assertFalse(service.publish(request(1,0,"different","same-key")).getSuccess());assertFalse(service.publish(request(2,0,"same","same-key")).getSuccess());
        assertFalse(service.publish(request(1,id(root),"same","same-key")).getSuccess());post(1,0,"same","new-key");assertEquals(2,count(1));
        service.deleteComment(id(root));var deleted=post(1,0,"same","same-key");assertTrue(deleted.comment().deleted());assertEquals("该评论已删除",deleted.comment().content());assertEquals(1,count(1));assertEquals(2,rows());
    }
    @Test void concurrentIdenticalSubmissionsUseTheActualDatabaseUniqueConstraintOnce() throws Exception {
        List<Result> results=parallel(8,worker->service.publish(request(1,0,"same","one-request")),false);
        Set<String> ids=new HashSet<>();for(Result result:results){assertTrue(result.getSuccess(),result.getErrorMsg());ids.add(((CommentMutationResult)result.getData()).comment().id());}
        assertEquals(1,ids.size());assertEquals(1,rows());assertEquals(1,count(1));
    }
    @Test void differentBlogConcurrentUniqueConflictReadsCommittedOriginalAndRejectsDifferentPayload() throws Exception {
        BlogCommentsMapper interleaved=spy(comments);CountDownLatch bothRead=new CountDownLatch(2);
        doAnswer(call->{var result=comments.byRequest(call.getArgument(0),call.getArgument(1));bothRead.countDown();assertTrue(bothRead.await(10,TimeUnit.SECONDS));return result;})
                .when(interleaved).byRequest(eq(1L),eq("cross-blog-key"));ReflectionTestUtils.setField(target,"baseMapper",interleaved);
        List<Result> results=parallel(2,worker->service.publish(request(worker+1,0,"text","cross-blog-key")),false);
        assertEquals(1,results.stream().filter(result->Boolean.TRUE.equals(result.getSuccess())).count());assertEquals(1,rows());assertEquals(1,count(1)+count(2));
        verify(interleaved,times(2)).insertComment(any());verify(interleaved,times(1)).lockRequest(1L,"cross-blog-key");
    }
    @Test void concurrentDifferentUsersDoNotLoseCounterIncrements() throws Exception {
        List<Result> results=parallel(10,worker->service.publish(request(1,0,"hello","user-request")),true);
        results.forEach(result->assertTrue(result.getSuccess(),result.getErrorMsg()));assertEquals(10,count(1));assertEquals(10,rows());assertCountMatches(1);
    }
    @Test void insertOrCounterFailureRollsBackTheWholeTransactionIncludingRootReplyCount() {
        jdbc.execute("ALTER TABLE "+table("tb_blog_comments")+" ADD CONSTRAINT chk_comment_test_content CHECK (content<>'reject')");
        try{assertThrows(RuntimeException.class,()->service.publish(request(1,0,"reject","insert-fail")));}finally{jdbc.execute("ALTER TABLE "+table("tb_blog_comments")+" DROP CHECK chk_comment_test_content");}
        assertEquals(0,rows());assertEquals(0,count(1));var root=post(1,0,"root","root");
        BlogMapper broken=spy(blogs);doReturn(0).when(broken).adjustComments(1L,1);ReflectionTestUtils.setField(target,"blogs",broken);
        assertThrows(IllegalStateException.class,()->service.publish(request(1,id(root),"child","counter-fail")));
        assertEquals(1,rows());assertEquals(1,count(1));assertEquals(0,comments.selectById(id(root)).getReplyCount());
    }
    @Test void otherUniqueConstraintErrorsAreNeverTreatedAsIdempotentSuccess() {
        jdbc.execute("ALTER TABLE "+table("tb_blog_comments")+" ADD UNIQUE KEY uk_comment_test_content(blog_id,content(100))");
        try{post(1,0,"same","a");assertThrows(DuplicateKeyException.class,()->service.publish(request(1,0,"same","b")));assertEquals(1,count(1));}
        finally{jdbc.execute("ALTER TABLE "+table("tb_blog_comments")+" DROP INDEX uk_comment_test_content");}
    }
    @Test void deletionKeepsPlaceholdersAndRelationshipsWithoutLeakingContentOrDoubleDecrement() {
        var root=post(1,0,"root","r");login(2);var child=post(1,id(root),"DO-NOT-LEAK","c");login(3);var nested=post(1,id(child),"third","n");
        login(1);service.deleteComment(id(root));assertEquals(1,roots(null,10).list().size());assertFalse(replies(id(root),null,10).list().get(0).canReply());
        login(2);var removed=(CommentMutationResult)service.deleteComment(id(child)).getData();assertEquals("该评论已删除",removed.comment().content());
        long revision=blogs.selectById(1L).getCommentVersion();service.deleteComment(id(child));assertEquals(revision,blogs.selectById(1L).getCommentVersion());
        CommentPage page=replies(id(root),null,10);assertEquals("2",page.list().get(0).answerUser().id());assertTrue(page.list().stream().noneMatch(view->view.content().contains("DO-NOT-LEAK")));
        login(3);service.deleteComment(id(nested));assertTrue(roots(null,10).list().isEmpty());assertEquals(0,count(1));assertCountMatches(1);
    }
    @Test void counterInconsistencyFailsAndRollsBackDeletionInsteadOfClampingToZero() {
        var root=post(1,0,"root","r");jdbc.update("UPDATE "+table("tb_blog")+" SET comments=0 WHERE id=1");
        assertThrows(IllegalStateException.class,()->service.deleteComment(id(root)));assertFalse(comments.selectById(id(root)).getDeleted());
    }
    @Test void deletionRacingAReplyHasASerializedValidOutcome() throws Exception {
        var root=post(1,0,"root","r");List<Result> results=parallel(2,worker->worker==0?service.deleteComment(id(root)):service.publish(request(1,id(root),"reply","race")),true);
        assertTrue(results.get(0).getSuccess());assertTrue(comments.selectById(id(root)).getDeleted());assertCountMatches(1);
        assertEquals(Boolean.TRUE.equals(results.get(1).getSuccess())?1:0,count(1));
        assertEquals(count(1),roots(null,10).list().size());
    }
    @Test void sameMicrosecondRowsAcrossManyPagesKeepExactTimeAndLargeStringIds() {
        LocalDateTime time=LocalDateTime.of(2026,11,3,8,0,0,123456000);long first=9007199254740993L;
        for(int index=0;index<13;index++)seed(first+index,0,0,time);
        jdbc.update("UPDATE "+table("tb_blog")+" SET comments=13 WHERE id=1");UserHolder.removeUser();
        List<String> all=new ArrayList<>();String cursor=null;
        do{CommentPage page=roots(cursor,3);page.list().forEach(row->all.add(row.id()));assertEquals(123456000,CommentCursor.decode(page.nextCursor()).time().getNano());cursor=page.hasMore()?page.nextCursor():null;}while(cursor!=null);
        assertEquals(13,all.size());assertEquals(13,new HashSet<>(all).size());assertEquals(Long.toString(first+12),all.get(0));assertEquals(Long.toString(first),all.get(12));
        Map<String,Object> plan=jdbc.queryForMap("EXPLAIN SELECT * FROM "+table("tb_blog_comments")+" WHERE blog_id=1 AND parent_id=0 AND (deleted=0 OR reply_count>0) AND (create_time<? OR (create_time=? AND id<?)) ORDER BY create_time DESC,id DESC LIMIT 4",time,time,first+10);
        assertEquals("idx_blog_comments_thread_scroll",plan.get("key"));assertFalse(String.valueOf(plan.get("Extra")).contains("filesort"));
    }
    @Test void repliesHaveAnIndependentCursorAndProbingRowIsNotUsedAsTheCursor() {
        var root=post(1,0,"root","r");LocalDateTime time=LocalDateTime.of(2026,11,3,8,0,0,654321000);
        for(int index=0;index<12;index++)seed(1000+index,id(root),id(root),time);
        jdbc.update("UPDATE "+table("tb_blog_comments")+" SET reply_count=12 WHERE id=?",id(root));jdbc.update("UPDATE "+table("tb_blog")+" SET comments=13 WHERE id=1");
        CommentPage first=replies(id(root),null,5),second=replies(id(root),first.nextCursor(),5),last=replies(id(root),second.nextCursor(),5);
        assertEquals("1007",first.list().get(4).id());assertEquals(1007,CommentCursor.decode(first.nextCursor()).id());assertEquals("1006",second.list().get(0).id());assertEquals(2,last.list().size());assertFalse(last.hasMore());
    }
    @Test void reportedAndForbiddenCommentsOnlyExposePlaceholdersWhileNormalRepliesStayVisible() {
        var root=post(1,0,"HIDDEN-ROOT","r");post(1,id(root),"visible reply","c");
        jdbc.update("UPDATE "+table("tb_blog_comments")+" SET status=1 WHERE id=?",id(root));jdbc.update("UPDATE "+table("tb_blog")+" SET comments=1 WHERE id=1");
        assertEquals("该评论已被举报，暂不可见",roots(null,10).list().get(0).content());assertFalse(service.publish(request(1,id(root),"no","blocked")).getSuccess());
        jdbc.update("UPDATE "+table("tb_blog_comments")+" SET status=2 WHERE id=?",id(root));assertEquals("该评论暂不可见",roots(null,10).list().get(0).content());assertEquals("visible reply",replies(id(root),null,10).list().get(0).content());
        service.deleteComment(id(root));assertEquals(1,count(1));
    }
    @Test void validatesContentSizeAndCursorWithoutModifyingBusinessRows() {
        for(String text:new String[]{"","  ","\u00a0\u2003\ufeff","x".repeat(501)})assertFalse(service.publish(request(1,0,text,"bad")).getSuccess());
        assertFalse(service.publish(request(99,0,"text","missing-blog")).getSuccess());
        var valid=post(1,0,"😀".repeat(500),"unicode");assertEquals(500,valid.comment().content().codePointCount(0,valid.comment().content().length()));
        assertFalse(service.queryRoots(1L,"bad",10).getSuccess());assertFalse(service.queryRoots(1L,null,51).getSuccess());assertFalse(service.queryReplies(id(valid),"",10).getSuccess());
        assertFalse(service.publish(request(1,0,"text"," ")).getSuccess());assertCountMatches(1);
    }
    private CommentMutationResult post(long blog,long answer,String text,String key){Result result=service.publish(request(blog,answer,text,key));assertTrue(result.getSuccess(),result.getErrorMsg());return (CommentMutationResult)result.getData();}
    private CommentCreateRequest request(long blog,long answer,String text,String key){var request=new CommentCreateRequest();request.setBlogId(blog);request.setAnswerId(answer);request.setContent(text);request.setRequestId(key);return request;}
    private long id(CommentMutationResult result){return Long.parseLong(result.comment().id());}
    private CommentPage roots(String cursor,int size){Result result=service.queryRoots(1L,cursor,size);assertTrue(result.getSuccess(),result.getErrorMsg());return (CommentPage)result.getData();}
    private CommentPage replies(long root,String cursor,int size){Result result=service.queryReplies(root,cursor,size);assertTrue(result.getSuccess(),result.getErrorMsg());return (CommentPage)result.getData();}
    private long rows(){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table("tb_blog_comments"),Long.class);}
    private long count(long blog){return jdbc.queryForObject("SELECT comments FROM "+table("tb_blog")+" WHERE id=?",Long.class,blog);}
    private void assertCountMatches(long blog){assertEquals(count(blog),jdbc.queryForObject("SELECT COUNT(*) FROM "+table("tb_blog_comments")+" WHERE blog_id=? AND status=0 AND deleted=0",Long.class,blog));}
    private void seed(long id,long parent,long answer,LocalDateTime time){jdbc.update("INSERT INTO "+table("tb_blog_comments")+"(id,user_id,blog_id,parent_id,answer_id,content,status,create_time,update_time) VALUES(?,1,1,?,?,'seed',0,?,?)",id,parent,answer,time,time);}
    private void login(long id){UserDTO user=new UserDTO();user.setId(id);UserHolder.saveUser(user);}
    private List<Result> parallel(int n,java.util.function.IntFunction<Result> action,boolean differentUsers)throws Exception{
        ExecutorService executor=Executors.newFixedThreadPool(n);CountDownLatch start=new CountDownLatch(1);List<Future<Result>> futures=new ArrayList<>();
        try{for(int index=0;index<n;index++){int worker=index;futures.add(executor.submit(()->{login(differentUsers?worker+1:1);try{start.await();return action.apply(worker);}finally{UserHolder.removeUser();}}));}start.countDown();List<Result> results=new ArrayList<>();for(var future:futures)results.add(future.get(20,TimeUnit.SECONDS));return results;}
        finally{executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));}
    }
    private String table(String name){String value=tables.get(name);if(value==null||!value.matches("hmdp_test_comments_[a-z]+_[a-f0-9]{32}"))throw new IllegalArgumentException("Unexpected test table");return "`"+value+"`";}
}
