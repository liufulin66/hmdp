package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    public static final int MAX_COMMON_PAGE = 1000;
    private static final Pattern DUPLICATE_INDEX = Pattern.compile("for key ['`]([^'`]+)['`]", Pattern.CASE_INSENSITIVE);

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Result invalid = validate(followUserId);
        if (invalid != null) return invalid;
        if (isFollow == null) return Result.fail("关注状态不能为空");
        long userId = UserHolder.getUser().getId();
        if (userId == followUserId) return Result.fail("不能关注或取消关注自己");
        if (userService.getById(followUserId) == null) return Result.fail("目标用户不存在");

        if (isFollow) {
            try {
                // 不做“先查再插”：并发请求由数据库联合唯一约束裁决。
                baseMapper.insertRelation(userId, followUserId);
            } catch (DuplicateKeyException duplicate) {
                if (!isRelationDuplicate(duplicate)) throw duplicate;
            }
        } else {
            baseMapper.deleteRelation(userId, followUserId);
        }
        // 表示本次设置已成功；不承诺其他设备随后不会再修改这条关系。
        return Result.ok(isFollow);
    }

    @Override
    public Result isFollow(Long followUserId) {
        Result invalid = validate(followUserId);
        if (invalid != null) return invalid;
        long userId = UserHolder.getUser().getId();
        if (userId == followUserId) return Result.ok(false);
        if (userService.getById(followUserId) == null) return Result.fail("目标用户不存在");
        return Result.ok(baseMapper.existsRelation(userId, followUserId));
    }

    @Override
    public Result commonFollow(Long followUserId, Integer current) {
        Result invalid = validate(followUserId);
        if (invalid != null) return invalid;
        if (current == null || current < 1 || current > MAX_COMMON_PAGE) {
            return Result.fail("页码必须在1到1000之间");
        }
        long userId = UserHolder.getUser().getId();
        if (userId == followUserId) return Result.fail("共同关注的目标不能是自己");
        if (userService.getById(followUserId) == null) return Result.fail("目标用户不存在");

        int size = SystemConstants.DEFAULT_PAGE_SIZE;
        List<Long> ids = baseMapper.selectCommonUserIds(userId, followUserId, (current - 1) * size, size);
        if (ids.isEmpty()) return Result.ok(List.of());
        Map<Long, User> users = new HashMap<>();
        userService.listByIds(ids).forEach(user -> users.put(user.getId(), user));
        List<UserDTO> result = new ArrayList<>(ids.size());
        // IN 查询不保证顺序，按 SQL 已确定的 ID 顺序组装，不逐个查用户。
        for (Long id : ids) {
            User user = users.get(id);
            if (user != null) result.add(BeanUtil.copyProperties(user, UserDTO.class));
        }
        return Result.ok(result);
    }

    private Result validate(Long followUserId) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null || user.getId() <= 0) return Result.fail("请先登录");
        if (followUserId == null || followUserId <= 0) return Result.fail("用户ID必须为正数");
        return null;
    }

    private boolean isRelationDuplicate(DuplicateKeyException duplicate) {
        for (Throwable cause = duplicate; cause != null; cause = cause.getCause()) {
            if (!(cause instanceof SQLException sql) || sql.getErrorCode() != 1062
                    || !"23000".equals(sql.getSQLState()) || sql.getMessage() == null) continue;
            Matcher matcher = DUPLICATE_INDEX.matcher(sql.getMessage());
            if (!matcher.find()) return false;
            String key = matcher.group(1);
            String indexName = key.substring(key.lastIndexOf('.') + 1);
            return baseMapper.isRelationUniqueIndex(indexName) == 1;
        }
        return false;
    }
}
