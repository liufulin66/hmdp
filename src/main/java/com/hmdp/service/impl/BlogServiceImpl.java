package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.repository.FeedRedisRepository;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private BlogLikeService blogLikeService;

    @Resource
    private BlogLikeRankingService blogLikeRankingService;

    @Resource private BlogPublicationService publication;
    @Resource private FeedDeliveryService delivery;
    @Resource private FeedRedisRepository inbox;

    @Override
    public Result queryBlogLikes(Long id) {
        return blogLikeRankingService.queryLikes(id);
    }

    @Override
    public Result saveBlog(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null || user.getId() <= 0) return Result.fail("请先登录");
        if (blog == null) return Result.fail("笔记不能为空");
        long id = publication.publish(blog, user.getId());
        // 独立事务已提交；Redis 故障不改变“笔记已发布”的结果。
        try {
            delivery.deliverBlog(id);
        } catch (RuntimeException e) {
            log.error("笔记 {} 已发布，首次投递异常，等待任务重试", id, e);
        }
        return Result.ok(id);
    }

    @Override
    public Result queryBlogOfFollow(Long lastId, Integer offset) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null || user.getId() <= 0) return Result.fail("请先登录");
        if (lastId == null || lastId < 0 || lastId > SystemConstants.MAX_FEED_TIMESTAMP) {
            return Result.fail("时间边界必须是有效的非负毫秒时间戳");
        }
        if (offset == null || offset < 0 || offset > SystemConstants.MAX_FEED_OFFSET) {
            return Result.fail("偏移量超出有效范围");
        }
        try {
            var entries = inbox.read(user.getId(), lastId, offset);
            ScrollResult result = new ScrollResult();
            result.setList(List.of());
            result.setMinTime(0L);
            result.setOffset(0);
            if (entries.isEmpty()) return Result.ok(result);

            long minTime = entries.get(entries.size() - 1).timestamp();
            int sameTime = 0;
            for (var entry : entries) if (entry.timestamp() == minTime) sameTime++;
            result.setMinTime(minTime);
            result.setOffset(minTime == lastId ? offset + sameTime : sameTime);
            // 游标先按 Redis 原始条目计算；即使数据库记录缺失也能继续向前。
            List<Long> ids = entries.stream().map(FeedRedisRepository.Entry::blogId).toList();
            Map<Long, Blog> blogs = new HashMap<>();
            listByIds(ids).forEach(blog -> blogs.put(blog.getId(), blog));
            List<Blog> ordered = new ArrayList<>();
            for (Long id : ids) if (blogs.containsKey(id)) ordered.add(blogs.get(id));
            fillFeedAuthors(ordered);
            ordered.forEach(blogLikeService::fillLikes);
            result.setList(ordered);
            return Result.ok(result);
        } catch (RuntimeException e) {
            log.warn("用户 {} 关注动态读取失败", user.getId(), e);
            return Result.fail("关注动态暂不可用，请稍后重试");
        }
    }

    private void fillFeedAuthors(List<Blog> blogs) {
        List<Long> ids = blogs.stream().map(Blog::getUserId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return;
        Map<Long, User> authors = new HashMap<>();
        userService.listByIds(ids).forEach(author -> authors.put(author.getId(), author));
        for (Blog blog : blogs) {
            User author = authors.get(blog.getUserId());
            if (author != null) { blog.setName(author.getNickName()); blog.setIcon(author.getIcon()); }
        }
    }

    @Override
    public Result likeBlog(Long id) {
        return blogLikeService.changeLike(id, true);
    }

    @Override
    public Result unlikeBlog(Long id) {
        return blogLikeService.changeLike(id, false);
    }

    @Override
    public Result queryMyBlog(Integer current) {
        UserDTO user = UserHolder.getUser();
        Page<Blog> page = query().eq("user_id", user.getId())
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        page.getRecords().forEach(blogLikeService::fillLikes);
        return Result.ok(page.getRecords());
    }

    @Override
    public Result queryHotBlog(Integer current) {
        Page<Blog> page = query().orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        records.forEach(this::fillBlogAuthor);
        records.forEach(blogLikeService::fillLikes);
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        if (id == null || id <= 0) {
            return Result.fail("笔记ID必须为正数");
        }
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        fillBlogAuthor(blog);
        blogLikeService.fillLikes(blog);
        return Result.ok(blog);
    }

    private void fillBlogAuthor(Blog blog) {
        if (blog.getUserId() == null) {
            return;
        }
        User user = userService.getById(blog.getUserId());
        // 作者缺失时仍返回笔记正文，避免空指针导致页面失败。
        if (user != null) {
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
        }
    }

}
