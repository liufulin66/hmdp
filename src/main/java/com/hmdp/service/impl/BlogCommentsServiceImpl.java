package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.BlogComments;
import com.hmdp.mapper.BlogCommentsMapper;
import com.hmdp.service.IBlogCommentsService;
import org.springframework.stereotype.Service;
import com.hmdp.dto.*;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.utils.CommentCursor;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.*;
import java.sql.SQLException;
import java.util.*;
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
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

    @Resource private BlogMapper blogs;
    @Resource private UserMapper users;
    private static final Pattern REQUEST_INDEX=Pattern.compile("for key ['`]([^'`]+)['`]",Pattern.CASE_INSENSITIVE);

    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public Result publish(CommentCreateRequest request) {
        Long userId=currentUser();
        if (userId==null) return Result.fail("请先登录");
        if (request==null || request.getBlogId()==null || request.getBlogId()<=0) return Result.fail("笔记ID必须为正数");
        if (request.getRequestId()==null || !request.getRequestId().matches("[A-Za-z0-9_-]{1,64}")) return Result.fail("提交编号必须是1到64位字母、数字、下划线或短横线");
        long answerId=request.getAnswerId()==null ? 0:request.getAnswerId();
        if (answerId<0) return Result.fail("回复目标ID不能为负数");
        String content=trimContent(request.getContent());
        if (content.isEmpty() || content.codePointCount(0,content.length())>500) return Result.fail("评论内容不能为空且不能超过500个字符");
        Blog blog=blogs.lockForComments(request.getBlogId());
        if (blog==null) return Result.fail("笔记不存在");
        // READ COMMITTED + 笔记行锁：同笔记的先前事务已提交后才检查提交编号。
        BlogComments existing=baseMapper.byRequest(userId,request.getRequestId());
        if (existing!=null) return replay(baseMapper.lockRequest(userId,request.getRequestId()),request,answerId,content);
        long rootId=0;
        if (answerId!=0) {
            BlogComments answer=baseMapper.lockById(answerId);
            if (answer==null || !request.getBlogId().equals(answer.getBlogId())) return Result.fail("回复目标不存在或不属于本篇笔记");
            if (!available(answer)) return Result.fail("该评论已删除或不可回复");
            rootId=answer.getParentId()==0 ? answer.getId():answer.getParentId();
            BlogComments root=baseMapper.lockById(rootId);
            if (root==null || root.getParentId()!=0 || !request.getBlogId().equals(root.getBlogId()) || !available(root)) return Result.fail("一级评论不存在或不允许新回复");
        }
        BlogComments comment=new BlogComments().setBlogId(request.getBlogId()).setUserId(userId).setParentId(rootId)
                .setAnswerId(answerId).setContent(content).setRequestId(request.getRequestId());
        try {
            if (baseMapper.insertComment(comment)!=1) throw new IllegalStateException("评论保存失败");
        } catch (DuplicateKeyException duplicate) {
            if (!isRequestDuplicate(duplicate)) throw duplicate;
            // 唯一键等待结束后用当前读取得已提交原结果；不可使用旧快照或吞掉其他唯一键冲突。
            BlogComments committed=baseMapper.lockRequest(userId,request.getRequestId());
            if (committed==null) throw duplicate;
            return replay(committed,request,answerId,content);
        }
        if (rootId!=0 && baseMapper.adjustReplies(rootId,1)!=1) throw new IllegalStateException("一级评论回复计数不一致，事务已回滚");
        adjustBlog(request.getBlogId(),1);
        return mutation(baseMapper.lockById(comment.getId()));
    }

    private Result replay(BlogComments original,CommentCreateRequest request,long answerId,String content) {
        if (original==null || !original.getBlogId().equals(request.getBlogId()) || original.getAnswerId()!=answerId
                || !original.getContent().equals(content)) return Result.fail("该提交编号已用于不同的笔记、回复目标或内容，请使用新的提交编号");
        return mutation(original);
    }

    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public Result deleteComment(Long id) {
        Long userId=currentUser();
        if (userId==null) return Result.fail("请先登录");
        if (id==null || id<=0) return Result.fail("评论ID必须为正数");
        BlogComments locator=baseMapper.selectById(id);
        if (locator==null) return Result.fail("评论不存在");
        // 定位读取不加行锁；统一先锁所属笔记，再锁评论，与发表/回复保持同一顺序。
        if (blogs.lockForComments(locator.getBlogId())==null) return Result.fail("笔记不存在");
        BlogComments comment=baseMapper.lockById(id);
        if (!userId.equals(comment.getUserId())) return Result.fail("只能删除自己的评论");
        if (Boolean.TRUE.equals(comment.getDeleted())) return mutation(comment);
        if (baseMapper.markDeleted(id,userId)!=1) throw new IllegalStateException("评论删除状态更新失败");
        boolean normal=Objects.equals(comment.getStatus(),0);
        if (normal && comment.getParentId()!=0 && baseMapper.adjustReplies(comment.getParentId(),-1)!=1) {
            throw new IllegalStateException("一级评论回复计数不一致，事务已回滚");
        }
        adjustBlog(comment.getBlogId(),normal ? -1:0);
        return mutation(baseMapper.lockById(id));
    }

    private void adjustBlog(long id,int delta) {
        if (blogs.adjustComments(id,delta)!=1) throw new IllegalStateException("笔记评论计数不一致或超出范围，事务已回滚");
    }

    @Override
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Result queryRoots(Long blogId,String cursor,Integer size) {
        if (blogId==null || blogId<=0) return Result.fail("笔记ID必须为正数");
        if (size==null || size<1 || size>50) return Result.fail("每页数量必须在1到50之间");
        CommentCursor boundary;
        try { boundary=CommentCursor.decode(cursor); } catch (IllegalArgumentException e) { return Result.fail(e.getMessage()); }
        Blog blog=blogs.selectById(blogId);
        if (blog==null) return Result.fail("笔记不存在");
        return page(baseMapper.roots(blogId,boundary==null ? null:boundary.time(),boundary==null ? null:boundary.id(),size+1),size,blog,null);
    }

    @Override
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Result queryReplies(Long rootId,String cursor,Integer size) {
        if (rootId==null || rootId<=0) return Result.fail("一级评论ID必须为正数");
        if (size==null || size<1 || size>50) return Result.fail("每页数量必须在1到50之间");
        CommentCursor boundary;
        try { boundary=CommentCursor.decode(cursor); } catch (IllegalArgumentException e) { return Result.fail(e.getMessage()); }
        BlogComments root=baseMapper.selectById(rootId);
        if (root==null || root.getParentId()!=0) return Result.fail("一级评论不存在");
        Blog blog=blogs.selectById(root.getBlogId());
        if (blog==null) return Result.fail("笔记不存在");
        return page(baseMapper.replies(rootId,root.getBlogId(),boundary==null ? null:boundary.time(),boundary==null ? null:boundary.id(),size+1),size,blog,root);
    }

    private Result page(List<BlogComments> candidates,int size,Blog blog,BlogComments root) {
        boolean more=candidates.size()>size;
        List<BlogComments> shown=new ArrayList<>(candidates.subList(0,Math.min(size,candidates.size())));
        String next=shown.isEmpty() ? null:new CommentCursor(shown.get(shown.size()-1).getCreateTime(),shown.get(shown.size()-1).getId()).encode();
        List<BlogComments> all=new ArrayList<>(shown); if (root!=null) all.add(root);
        Map<Long,BlogCommentView> views=views(all,root);
        return Result.ok(new CommentPage(shown.stream().map(row->views.get(row.getId())).toList(),next,more,
                blog.getComments(),Long.toString(blog.getCommentVersion()),root==null ? null:views.get(root.getId())));
    }

    private Result mutation(BlogComments comment) {
        Blog blog=blogs.lockForComments(comment.getBlogId());
        BlogComments root=comment.getParentId()==0 ? comment:baseMapper.lockById(comment.getParentId());
        if (root==null) throw new IllegalStateException("评论归属的一级评论缺失");
        Map<Long,BlogCommentView> views=views(List.of(comment,root),root);
        return Result.ok(new CommentMutationResult(views.get(comment.getId()),blog.getComments(),
                Long.toString(blog.getCommentVersion()),views.get(root.getId())));
    }

    private Map<Long,BlogCommentView> views(List<BlogComments> rows,BlogComments root) {
        if (rows.isEmpty()) return Map.of();
        Set<Long> targets=new HashSet<>();
        for (BlogComments row:rows) if (row.getAnswerId()!=0) targets.add(row.getAnswerId());
        Map<Long,BlogComments> answers=new HashMap<>();
        if (!targets.isEmpty()) baseMapper.selectByIds(targets).forEach(answer->answers.put(answer.getId(),answer));
        Set<Long> ids=new HashSet<>(); rows.forEach(row->ids.add(row.getUserId())); answers.values().forEach(answer->ids.add(answer.getUserId()));
        Map<Long,User> profiles=new HashMap<>();
        if (!ids.isEmpty()) users.selectByIds(ids).forEach(user->profiles.put(user.getId(),user));
        Map<Long,BlogCommentView> result=new HashMap<>();
        Long viewer=currentUser();
        for (BlogComments row:rows) {
            boolean deleted=Boolean.TRUE.equals(row.getDeleted());
            String content=deleted ? "该评论已删除":Objects.equals(row.getStatus(),0) ? row.getContent():
                    Objects.equals(row.getStatus(),1) ? "该评论已被举报，暂不可见":"该评论暂不可见";
            BlogComments answer=answers.get(row.getAnswerId());
            boolean canReply=available(row) && (row.getParentId()==0 || root!=null && available(root));
            result.put(row.getId(),new BlogCommentView(row.getId().toString(),row.getBlogId().toString(),row.getParentId().toString(),row.getAnswerId().toString(),
                    profile(row.getUserId(),profiles),answer==null ? null:profile(answer.getUserId(),profiles),content,row.getCreateTime(),
                    deleted,!deleted && row.getUserId().equals(viewer),canReply,row.getReplyCount()));
        }
        return result;
    }
    private CommentUser profile(long id,Map<Long,User> users) {
        User user=users.get(id); return new CommentUser(Long.toString(id),user==null ? "用户已注销":user.getNickName(),user==null ? "":user.getIcon());
    }
    private static boolean available(BlogComments comment) { return !Boolean.TRUE.equals(comment.getDeleted()) && Objects.equals(comment.getStatus(),0); }
    private static String trimContent(String value) {
        if (value==null) return "";
        int from=0,to=value.length();
        while (from<to && whitespace(value.codePointAt(from))) from+=Character.charCount(value.codePointAt(from));
        while (to>from && whitespace(value.codePointBefore(to))) to-=Character.charCount(value.codePointBefore(to));
        return value.substring(from,to);
    }
    private static boolean whitespace(int codePoint) { return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint==0xFEFF; }
    private static Long currentUser() { UserDTO user=UserHolder.getUser(); return user==null || user.getId()==null || user.getId()<=0 ? null:user.getId(); }
    private static boolean isRequestDuplicate(DuplicateKeyException duplicate) {
        for (Throwable cause=duplicate;cause!=null;cause=cause.getCause()) {
            if (!(cause instanceof SQLException sql) || sql.getErrorCode()!=1062 || !"23000".equals(sql.getSQLState()) || sql.getMessage()==null) continue;
            var matcher=REQUEST_INDEX.matcher(sql.getMessage());
            if (!matcher.find()) return false;
            String index=matcher.group(1); index=index.substring(index.lastIndexOf('.')+1);
            return index.equals("uk_blog_comment_user_request");
        }
        return false;
    }

}
