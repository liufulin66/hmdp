package com.hmdp.controller;


import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.*;
import com.hmdp.dto.*;
import com.hmdp.service.IBlogCommentsService;
import com.hmdp.annotation.AnonymousRead;
import jakarta.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog-comments")
public class BlogCommentsController {

    @Resource private IBlogCommentsService comments;
    @PostMapping
    public Result publish(@RequestBody CommentCreateRequest request) { return comments.publish(request); }
    @AnonymousRead
    @GetMapping("/of/blog/{blogId}")
    public Result roots(@PathVariable("blogId") Long blogId,
            @RequestParam(value="cursor",required=false) String cursor,
            @RequestParam(value="size",defaultValue="10") Integer size) { return comments.queryRoots(blogId,cursor,size); }
    @AnonymousRead
    @GetMapping("/{rootId}/replies")
    public Result replies(@PathVariable("rootId") Long rootId,
            @RequestParam(value="cursor",required=false) String cursor,
            @RequestParam(value="size",defaultValue="10") Integer size) { return comments.queryReplies(rootId,cursor,size); }
    @DeleteMapping("/{id}")
    public Result delete(@PathVariable("id") Long id) { return comments.deleteComment(id); }

}
