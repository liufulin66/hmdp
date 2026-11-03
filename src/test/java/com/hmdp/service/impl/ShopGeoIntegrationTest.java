package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DynamicTableNameInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.hmdp.dto.*;
import com.hmdp.entity.*;
import com.hmdp.mapper.*;
import com.hmdp.repository.ShopGeoRepository;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 MySQL/Redis；仅操作随机物理表和随机前缀，不启动应用或定时任务。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named="hmdp.integration",matches="true")
class ShopGeoIntegrationTest {
    private final Map<String,String> tables=new LinkedHashMap<>();
    private final Map<String,Long> before=new HashMap<>();
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager tx;
    private LettuceConnectionFactory redisFactory;
    private StringRedisTemplate redis;
    private ShopMapper shops;
    private ShopGeoTaskMapper tasks;
    private ShopGeoBuildMapper jobs;
    private ShopTypeMapper types;
    private ShopGeoRepository geo;
    private ShopGeoSyncService syncTarget,sync;
    private ShopGeoBuildService builder;
    private ShopServiceImpl target,service;
    private CacheClient cache;
    private String prefix;

    @BeforeAll void connect() throws Exception {
        var env=new StandardEnvironment();
        for (String path:List.of("src/main/resources/application.yaml","config/application-secret.yml")) {
            var resource=new FileSystemResource(path); if (!resource.exists()) continue;
            var yaml=new YamlPropertiesFactoryBean(); yaml.setResources(resource);
            env.getPropertySources().addFirst(new PropertiesPropertySource(path,yaml.getObject()));
        }
        var source=new DriverManagerDataSource(env.getRequiredProperty("spring.datasource.url"),
                env.getRequiredProperty("spring.datasource.username"),env.getRequiredProperty("spring.datasource.password"));
        jdbc=new JdbcTemplate(source); tx=new DataSourceTransactionManager(source);
        assertEquals("hmdp",jdbc.queryForObject("SELECT DATABASE()",String.class));
        String suffix=UUID.randomUUID().toString().replace("-","");
        for (String name:List.of("tb_shop","tb_shop_type","tb_shop_geo_task","tb_shop_geo_build")) {
            before.put(name,jdbc.queryForObject("SELECT COUNT(*) FROM "+name,Long.class));
            tables.put(name,"hmdp_test_geo_"+name.substring(3).replace("_","")+"_"+suffix);
            jdbc.execute("CREATE TABLE "+table(name)+" LIKE "+name);
        }
        var config=new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper:List.of(ShopMapper.class,ShopTypeMapper.class,ShopGeoTaskMapper.class,ShopGeoBuildMapper.class)) config.addMapper(mapper);
        var names=new DynamicTableNameInnerInterceptor(); names.setTableNameHandler((sql,name)->tables.getOrDefault(name,name));
        var interceptor=new MybatisPlusInterceptor(); interceptor.addInnerInterceptor(names);
        var factory=new MybatisSqlSessionFactoryBean(); factory.setConfiguration(config); factory.setDataSource(source); factory.setPlugins(interceptor);
        var session=new SqlSessionTemplate(factory.getObject());
        shops=session.getMapper(ShopMapper.class); types=session.getMapper(ShopTypeMapper.class);
        tasks=session.getMapper(ShopGeoTaskMapper.class); jobs=session.getMapper(ShopGeoBuildMapper.class);
        var redisConfig=new RedisStandaloneConfiguration(env.getRequiredProperty("spring.data.redis.host"),env.getProperty("spring.data.redis.port",Integer.class,6379));
        redisConfig.setDatabase(env.getProperty("spring.data.redis.database",Integer.class,0));
        String password=env.getProperty("spring.data.redis.password"), username=env.getProperty("spring.data.redis.username");
        if (password!=null && !password.isBlank()) redisConfig.setPassword(password);
        if (username!=null && !username.isBlank()) redisConfig.setUsername(username);
        redisFactory=new LettuceConnectionFactory(redisConfig); redisFactory.afterPropertiesSet(); redisFactory.start(); redis=new StringRedisTemplate(redisFactory);
    }
    @BeforeEach void fixtures() {
        for (String name:tables.keySet()) jdbc.update("DELETE FROM "+table(name));
        for (long id=1;id<=3;id++) jdbc.update("INSERT INTO "+table("tb_shop_type")+"(id,name) VALUES(?,?)",id,"type-"+id);
        prefix="hmdp:test:shopgeo:"+UUID.randomUUID()+":"; geo=new ShopGeoRepository(redis,prefix);
        syncTarget=new ShopGeoSyncService(); ReflectionTestUtils.setField(syncTarget,"tasks",tasks); ReflectionTestUtils.setField(syncTarget,"geo",geo); sync=proxy(syncTarget);
        builder=new ShopGeoBuildService(); ReflectionTestUtils.setField(builder,"shops",shops); ReflectionTestUtils.setField(builder,"types",types);
        ReflectionTestUtils.setField(builder,"jobs",jobs); ReflectionTestUtils.setField(builder,"geo",geo);
        target=new ShopServiceImpl(); cache=mock(CacheClient.class);
        ReflectionTestUtils.setField(target,"baseMapper",shops); ReflectionTestUtils.setField(target,"shopTypes",types);
        ReflectionTestUtils.setField(target,"geo",geo); ReflectionTestUtils.setField(target,"geoSync",sync); ReflectionTestUtils.setField(target,"cacheClient",cache); service=proxy(target);
    }
    @AfterEach void cleanup() { Set<String> keys=redis.keys(prefix+"*"); if (keys!=null && !keys.isEmpty()) redis.delete(keys); }
    @AfterAll void close() {
        try { if (jdbc!=null) {
            for (String name:tables.keySet()) jdbc.execute("DROP TABLE IF EXISTS "+table(name));
            before.forEach((name,count)->assertEquals(count,jdbc.queryForObject("SELECT COUNT(*) FROM "+name,Long.class)));
        }} finally { if (redisFactory!=null) redisFactory.destroy(); }
    }
    @Test void ordinaryPagingAndLegacyArraysDoNotDependOnGeo() {
        for (int id=1;id<=11;id++) insert(id,1,id*.0001,0);
        insert(20,2,0,0);
        assertEquals(5,page(1,null,null,5000).list().size()); assertTrue(page(2,null,null,5000).hasMore());
        assertEquals(1,page(3,null,null,5000).list().size()); assertFalse(page(3,null,null,5000).hasMore());
        assertInstanceOf(List.class,service.queryShopByType(1L,1,null,null,5000,false).getData());
        assertEquals("UNINITIALIZED",geo.status()); assertEquals(0L,rows("tb_shop_geo_build"));
    }
    @Test void redisFiveRadiusUsesMetersNearestOrderAndLegalZeroCoordinates() {
        insert(1,1,0,0); insert(2,1,.001,0); insert(3,1,.002,0); insert(4,1,.1,0); insert(5,2,0,0); rebuild();
        ShopPage page=page(1,0D,0D,350);
        assertEquals(List.of(1L,2L,3L),ids(page)); assertFalse(page.hasMore());
        assertTrue(page.list().get(0).getDistance()<1);
        assertTrue(page.list().get(1).getDistance()>100 && page.list().get(1).getDistance()<120);
        assertTrue(page.list().get(1).getDistance()<page.list().get(2).getDistance());
        assertEquals(List.of(1L),ids(page(1,0D,0D,1)));
        assertTrue(page(1,-1D,-1D,1).list().isEmpty());
    }
    @Test void nearbyPagesHaveBoundedLookaheadAndStopAtPageTwenty() {
        for (int id=1;id<=102;id++) insert(id,1,id*.0001,0); rebuild();
        assertEquals(List.of(1L,2L,3L,4L,5L),ids(page(1,0D,0D,20000))); assertTrue(page(1,0D,0D,20000).hasMore());
        assertEquals(List.of(6L,7L,8L,9L,10L),ids(page(2,0D,0D,20000)));
        assertEquals(List.of(96L,97L,98L,99L,100L),ids(page(20,0D,0D,20000))); assertFalse(page(20,0D,0D,20000).hasMore());
        assertFalse(service.queryShopByType(1L,21,0D,0D,5000,true).getSuccess());
    }
    @Test void missingRowsAndWrongTypesAreSkippedWithoutTruncatingRawHasMore() {
        for (int id=1;id<=7;id++) insert(id,1,id*.0001,0); rebuild();
        jdbc.update("DELETE FROM "+table("tb_shop")+" WHERE id=1");
        jdbc.update("UPDATE "+table("tb_shop")+" SET type_id=2 WHERE id=2");
        ShopMapper counted=spy(shops); ReflectionTestUtils.setField(target,"baseMapper",counted);
        ShopPage page=page(1,0D,0D,5000); assertEquals(List.of(3L,4L,5L),ids(page)); assertTrue(page.hasMore());
        verify(counted).selectByIds(argThat(collection -> collection.size()==5));
        verify(counted,never()).selectById(anyLong());
    }
    @Test void invalidParametersFailBeforeQueryAndNoCoordinatesStillWork() {
        for (Double[] coords:List.of(new Double[]{0D,null},new Double[]{null,0D},new Double[]{Double.NaN,0D},
                new Double[]{Double.POSITIVE_INFINITY,0D},new Double[]{181D,0D},new Double[]{0D,86D})) {
            assertFalse(service.queryShopByType(1L,1,coords[0],coords[1],5000,true).getSuccess());
        }
        for (int radius:new int[]{0,-1,20001}) assertFalse(service.queryShopByType(1L,1,0D,0D,radius,true).getSuccess());
        for (int current:new int[]{0,-1,21,Integer.MAX_VALUE}) assertFalse(service.queryShopByType(1L,current,null,null,5000,true).getSuccess());
        assertFalse(service.queryShopByType(0L,1,null,null,5000,true).getSuccess());
        assertFalse(service.queryShopByType(999L,1,null,null,5000,true).getSuccess());
        assertTrue(service.queryShopByType(1L,1,null,null,5000,true).getSuccess());
    }
    @Test void emptyInitializationIsReadyWhileLostNonemptyIndexIsUnavailableUntilRebuild() {
        assertFalse(service.queryShopByType(1L,1,0D,0D,5000,true).getSuccess()); rebuild();
        assertEquals("READY",geo.status()); assertTrue(page(1,0D,0D,5000).list().isEmpty());
        assertFalse(redis.hasKey(geo.key("1")));
        assertTrue(service.saveShop(note().setX(0D).setY(0D)).getSuccess());
        redis.delete(geo.key("1")); assertEquals("LOST",geo.status());
        assertFalse(service.queryShopByType(1L,1,0D,0D,5000,true).getSuccess());
        rebuild(); assertEquals(1,page(1,0D,0D,5000).list().size());
        redis.delete(geo.key("state")); assertEquals("LOST",geo.status());
    }
    @Test void lossOfAllRedisIndexMetadataIsDistinguishedFromNeverInitializedUsingDatabaseHistory() {
        assertEquals("UNINITIALIZED",((Map<?,?>)builder.status().getData()).get("index")); rebuild();
        Set<String> keys=redis.keys(prefix+"*"); if (keys!=null && !keys.isEmpty()) redis.delete(keys);
        assertEquals("LOST",((Map<?,?>)builder.status().getData()).get("index"));
        assertFalse(service.queryShopByType(1L,1,0D,0D,5000,true).getSuccess()); rebuild();
        assertEquals("READY",((Map<?,?>)builder.status().getData()).get("index"));
    }
    @Test void createPositionTypeAndNameChangesKeepCompleteSnapshotsAndSupportNegativeCoordinates() {
        rebuild(); Shop shop=note().setX(-.001).setY(-.001);
        assertTrue(service.saveShop(shop).getSuccess()); assertEquals(1L,tasks.find(taskIds().get(0)).getVersion());
        assertNotNull(redis.opsForZSet().score(geo.key("1"),shop.getId().toString()));
        assertTrue(service.updateShop(new Shop().setId(shop.getId()).setName("new-name")).getSuccess());
        Shop updated=shops.selectById(shop.getId()); assertEquals(-.001,updated.getX()); assertEquals(-.001,updated.getY()); assertEquals(1L,updated.getTypeId());
        assertTrue(service.updateShop(new Shop().setId(shop.getId()).setTypeId(2L).setX(.02)).getSuccess());
        assertNull(redis.opsForZSet().score(geo.key("1"),shop.getId().toString()));
        assertNotNull(redis.opsForZSet().score(geo.key("2"),shop.getId().toString()));
        assertEquals(3L,shops.selectById(shop.getId()).getGeoVersion()); assertEquals(3L,countStatus("SUCCESS"));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM "+table("tb_shop_geo_task")+" WHERE next_attempt_at=0",Long.class));
    }
    @Test void redisFailureIsDurableAndOutOfOrderTypeChangesRemoveTheActualOlderType() {
        insert(1,1,0,0); rebuild(); ShopGeoRepository unavailable=spy(geo);
        doThrow(new IllegalStateException("offline")).when(unavailable).apply(any(ShopGeoTask.class));
        ReflectionTestUtils.setField(syncTarget,"geo",unavailable);
        assertTrue(service.updateShop(new Shop().setId(1L).setTypeId(2L).setX(.01)).getSuccess());
        assertTrue(service.updateShop(new Shop().setId(1L).setTypeId(3L).setX(.02)).getSuccess());
        assertEquals(2L,countStatus("PENDING")); assertEquals(3L,shops.selectById(1L).getTypeId());
        List<Long> ids=taskIds(); dueNow(); ReflectionTestUtils.setField(syncTarget,"geo",geo);
        sync.deliver(ids.get(1)); sync.deliver(ids.get(0)); sync.deliver(ids.get(1));
        assertNull(redis.opsForZSet().score(geo.key("1"),"1")); assertNull(redis.opsForZSet().score(geo.key("2"),"1"));
        assertNotNull(redis.opsForZSet().score(geo.key("3"),"1"));
        geo.apply(tasks.find(ids.get(0))); assertEquals("2|3",redis.opsForHash().get(geo.key("state"),"1"));
        assertEquals(2L,countStatus("SUCCESS"));
    }
    @Test void taskInsertFailureRollsBackShopAndVersionAndDatabaseInsertFailureDoesNotPush() {
        insert(1,1,0,0); rebuild(); ShopGeoTaskMapper broken=spy(tasks);
        doThrow(new IllegalStateException("outbox unavailable")).when(broken).insertTask(any()); ReflectionTestUtils.setField(syncTarget,"tasks",broken);
        assertThrows(RuntimeException.class,()->service.updateShop(new Shop().setId(1L).setX(.1).setName("rolled-back")));
        assertEquals(0D,shops.selectById(1L).getX()); assertEquals(0L,shops.selectById(1L).getGeoVersion()); assertEquals(0L,rows("tb_shop_geo_task"));
        verifyNoInteractions(cache);
        assertThrows(RuntimeException.class,()->service.saveShop(note().setAddress(null)));
        assertEquals(1L,rows("tb_shop")); assertEquals(List.of(1L),ids(page(1,0D,0D,5000)));
    }
    @Test void concurrentClaimsLeaseRecoveryAndStaleAcknowledgementsUseRealConditions() throws Exception {
        insert(1,1,0,0); rebuild(); ShopGeoTask task=task(1,1,1,.001,0); tasks.insertTask(task);
        ExecutorService executor=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1); long now=System.currentTimeMillis();
        try {
            Future<Integer> a=executor.submit(()->{start.await();return tasks.claim(task.getId(),0,5,"a",now,now+60000);});
            Future<Integer> b=executor.submit(()->{start.await();return tasks.claim(task.getId(),0,5,"b",now,now+60000);});
            start.countDown(); assertEquals(1,a.get(10,TimeUnit.SECONDS)+b.get(10,TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS)); }
        String old=tasks.find(task.getId()).getClaimToken();
        jdbc.update("UPDATE "+table("tb_shop_geo_task")+" SET lease_until=0,next_attempt_at=1 WHERE id=?",task.getId());
        assertEquals(1,tasks.claim(task.getId(),1,5,"new",now,now+60000)); assertEquals(0,tasks.succeed(task.getId(),old));
        assertEquals(0,tasks.fail(task.getId(),old,"FAILED",1,"stale"));
        jdbc.update("UPDATE "+table("tb_shop_geo_task")+" SET lease_until=0,next_attempt_at=1 WHERE id=?",task.getId());
        sync.retry(); assertEquals("SUCCESS",tasks.find(task.getId()).getStatus());
        jdbc.update("UPDATE "+table("tb_shop_geo_task")+" SET status='PROCESSING',attempts=5,lease_until=0 WHERE id=?",task.getId());
        sync.retry(); assertEquals("FAILED",tasks.find(task.getId()).getStatus());
    }
    @Test void synchronizationRetryKeepsDueOrderingAndRecoversAfterRedisReturns() {
        insert(1,1,0,0); rebuild(); ShopGeoRepository unavailable=spy(geo);
        doThrow(new IllegalStateException("temporary")).when(unavailable).apply(any()); ReflectionTestUtils.setField(syncTarget,"geo",unavailable);
        assertTrue(service.updateShop(new Shop().setId(1L).setX(.01)).getSuccess());
        ShopGeoTask failed=tasks.find(taskIds().get(0)); assertEquals(1,failed.getAttempts()); assertTrue(failed.getNextAttemptAt()>System.currentTimeMillis());
        dueNow(); ReflectionTestUtils.setField(syncTarget,"geo",geo); sync.retry(); assertEquals(1L,countStatus("SUCCESS"));
        for (int index=0;index<3;index++) { ShopGeoTask fresh=task(100+index,1,1,0,0); tasks.insertTask(fresh); }
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM "+table("tb_shop_geo_task")+" WHERE next_attempt_at=0",Long.class));
    }
    @Test void rebuildInterleavedWithNewShopAndNewerTypeChangePublishesLatestVersions() {
        insert(1,1,0,0); rebuild(); ShopMapper interleaved=spy(shops); AtomicBoolean once=new AtomicBoolean();
        doAnswer(call->{
            List<Shop> snapshot=shops.scanGeo(call.getArgument(0),call.getArgument(1));
            if (once.compareAndSet(false,true)) {
                assertTrue(service.updateShop(new Shop().setId(1L).setTypeId(3L).setX(.01)).getSuccess());
                assertTrue(service.saveShop(note().setTypeId(2L).setX(.02)).getSuccess());
            }
            return snapshot;
        }).when(interleaved).scanGeo(anyLong(),anyInt());
        ReflectionTestUtils.setField(builder,"shops",interleaved); rebuild();
        assertNull(redis.opsForZSet().score(geo.key("1"),"1")); assertNotNull(redis.opsForZSet().score(geo.key("3"),"1"));
        assertEquals("1|3",redis.opsForHash().get(geo.key("state"),"1")); assertEquals(1L,redis.opsForZSet().zCard(geo.key("2")));
        assertEquals("READY",geo.status());
    }
    @Test void rebuildStillCapturesChangesWhenActiveIndexIsLost() {
        insert(1,1,0,0); rebuild(); redis.delete(geo.key("1")); ShopMapper interleaved=spy(shops); AtomicBoolean once=new AtomicBoolean();
        doAnswer(call->{ List<Shop> snapshot=shops.scanGeo(call.getArgument(0),call.getArgument(1));
            if (once.compareAndSet(false,true)) assertTrue(service.updateShop(new Shop().setId(1L).setTypeId(2L).setX(.01)).getSuccess());
            return snapshot;
        }).when(interleaved).scanGeo(anyLong(),anyInt());
        ReflectionTestUtils.setField(builder,"shops",interleaved); rebuild();
        assertEquals("1|2",redis.opsForHash().get(geo.key("state"),"1")); assertEquals("READY",geo.status());
        dueNow(); sync.retry(); assertEquals(1L,countStatus("SUCCESS"));
    }
    @Test void failedBuildKeepsOldIndexAndOldOwnerCannotPublishAnotherGeneration() {
        insert(1,1,0,0); rebuild(); String generation=(String)redis.opsForHash().get(geo.key("meta"),"generation");
        ShopGeoRepository broken=spy(geo); doThrow(new IllegalStateException("interrupted")).when(broken).stage(anyString(),any());
        ReflectionTestUtils.setField(builder,"geo",broken); builder.request(); builder.run();
        assertEquals("FAILED",jobs.find().getStatus()); assertEquals("READY",geo.status());
        assertEquals(generation,redis.opsForHash().get(geo.key("meta"),"generation"));
        assertThrows(RuntimeException.class,()->geo.publish("old-owner")); assertEquals(1,page(1,0D,0D,5000).list().size());
        ReflectionTestUtils.setField(builder,"geo",geo); rebuild();
    }
    @Test void expiredBuildLeaseCanBeReclaimedButFormerOwnerCannotRenewPublishOrConfirm() {
        insert(1,1,0,0); jobs.ensure(); jobs.queue(System.currentTimeMillis()); long now=System.currentTimeMillis();
        assertEquals(1,jobs.claim("old",now,now+60000)); assertTrue(geo.begin("old",60000));
        jdbc.update("UPDATE "+table("tb_shop_geo_build")+" SET lease_until=0 WHERE id=1"); redis.delete(geo.key("owner"));
        assertEquals(1,jobs.claim("new",now,now+60000)); assertTrue(geo.begin("new",60000));
        for (long type=1;type<=3;type++) geo.type("new",type); geo.stage("new",shops.selectById(1L));
        assertEquals(0,jobs.renew("old",now,now+60000)); assertEquals(0,jobs.finish("old","SUCCESS",null));
        assertThrows(RuntimeException.class,()->geo.stage("old",shops.selectById(1L)));
        assertThrows(RuntimeException.class,()->geo.publish("old"));
        geo.publish("new"); assertEquals(1,jobs.finish("new","SUCCESS",null));
        assertEquals("new",redis.opsForHash().get(geo.key("meta"),"generation")); assertEquals("READY",geo.status());
    }
    @Test void invalidHistoricalCoordinatesFailRebuildWithoutRepairingDataOrDiscardingOldIndex() {
        insert(1,1,0,0); rebuild(); insert(2,1,0,86);
        builder.request(); builder.run(); assertEquals("FAILED",jobs.find().getStatus());
        assertEquals("READY",geo.status()); assertEquals(List.of(1L),ids(page(1,0D,0D,5000)));
        assertEquals(86D,shops.selectById(2L).getY()); assertTrue(jobs.find().getLastError().contains("2"));
    }
    @Test void concurrentRebuildRequestsShareOneDatabaseJobAndOneRedisOwner() throws Exception {
        insert(1,1,0,0); builder.request(); ShopGeoRepository blocked=spy(geo);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(call->{ boolean acquired=geo.begin(call.getArgument(0),call.getArgument(1)); entered.countDown();
            assertTrue(release.await(10,TimeUnit.SECONDS)); return acquired;
        }).when(blocked).begin(anyString(),anyLong()); ReflectionTestUtils.setField(builder,"geo",blocked);
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try { Future<?> a=executor.submit(builder::run); assertTrue(entered.await(10,TimeUnit.SECONDS));
            builder.request(); Future<?> b=executor.submit(builder::run); b.get(5,TimeUnit.SECONDS); release.countDown(); a.get(10,TimeUnit.SECONDS);
            verify(blocked,times(1)).begin(anyString(),anyLong()); assertEquals("SUCCESS",jobs.find().getStatus());
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS)); }
    }
    private void insert(long id,long type,double x,double y) { jdbc.update("INSERT INTO "+table("tb_shop")
            +"(id,name,type_id,images,address,x,y,sold,comments,score) VALUES(?,'test',?,'test.jpg','test',?,?,0,0,40)",id,type,x,y); }
    private Shop note() { return new Shop().setName("test").setTypeId(1L).setImages("test.jpg").setAddress("test").setX(0D).setY(0D).setSold(0).setComments(0).setScore(40); }
    private ShopGeoTask task(long id,long version,long type,double x,double y) { ShopGeoTask task=new ShopGeoTask(); task.setShopId(id);task.setVersion(version);task.setTypeId(type);task.setX(x);task.setY(y);task.setNextAttemptAt(System.currentTimeMillis());return task; }
    private void rebuild() { builder.request(); builder.run(); assertEquals("SUCCESS",jobs.find().getStatus(),jobs.find().getLastError()); }
    private ShopPage page(int current,Double x,Double y,int radius) { Result result=service.queryShopByType(1L,current,x,y,radius,true); assertTrue(result.getSuccess(),result.getErrorMsg());return (ShopPage)result.getData(); }
    private List<Long> ids(ShopPage page) { return page.list().stream().map(Shop::getId).toList(); }
    private long rows(String name) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table(name),Long.class); }
    private long countStatus(String status) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table("tb_shop_geo_task")+" WHERE status=?",Long.class,status); }
    private List<Long> taskIds() { return jdbc.queryForList("SELECT id FROM "+table("tb_shop_geo_task")+" ORDER BY id",Long.class); }
    private void dueNow() { jdbc.update("UPDATE "+table("tb_shop_geo_task")+" SET next_attempt_at=1 WHERE status='PENDING'"); }
    @SuppressWarnings("unchecked") private <T> T proxy(T target) { ProxyFactory factory=new ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvice(new TransactionInterceptor(tx,new AnnotationTransactionAttributeSource()));return (T)factory.getProxy(); }
    private String table(String name) { String value=tables.get(name);if(value==null || !value.matches("hmdp_test_geo_[a-z]+_[a-f0-9]{32}"))throw new IllegalArgumentException("Unexpected test table");return "`"+value+"`"; }
}
