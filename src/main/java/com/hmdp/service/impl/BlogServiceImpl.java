package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private BlogLikeService blogLikeService;

    @Override
    public Result saveBlog(Blog blog) {
        // 作者取自登录身份，防止前端冒用其他用户的 ID。
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        if (!save(blog)) {
            return Result.fail("发布笔记失败");
        }
        return Result.ok(blog.getId());
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
