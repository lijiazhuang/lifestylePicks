package com.lifestylepicks.content.controller;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lifestylepicks.common.context.UserContext;
import com.lifestylepicks.common.dto.Result;
import com.lifestylepicks.content.entity.BlogComments;
import com.lifestylepicks.content.mapper.BlogCommentsMapper;
import com.lifestylepicks.content.mapper.BlogMapper;
import org.springframework.web.bind.annotation.*;
import java.util.Objects;

@RestController
@RequestMapping("/blog-comments")
public class BlogCommentsController {
    private final BlogCommentsMapper comments;
    private final BlogMapper blogs;
    public BlogCommentsController(BlogCommentsMapper comments, BlogMapper blogs) { this.comments=comments; this.blogs=blogs; }
    @GetMapping("/of/blog")
    public Result list(@RequestParam Long blogId) {
        return Result.ok(comments.selectList(new QueryWrapper<BlogComments>().eq("blog_id", blogId)
                .eq("status",0).orderByAsc("id").last("LIMIT 100")));
    }
    @PostMapping
    public Result create(@RequestBody BlogComments comment) {
        if (comment.getBlogId()==null || blogs.selectById(comment.getBlogId())==null) { return Result.fail("笔记不存在"); }
        if (comment.getContent()==null || comment.getContent().trim().isEmpty()) { return Result.fail("评论不能为空"); }
        comment.setId(null); comment.setUserId(UserContext.getUserId()); comment.setLiked(0); comment.setStatus(false);
        if (comment.getParentId()==null) { comment.setParentId(0L); }
        if (comment.getAnswerId()==null) { comment.setAnswerId(0L); }
        comments.insert(comment); return Result.ok(comment.getId());
    }
    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Long id) {
        BlogComments comment=comments.selectById(id);
        if (comment==null || !Objects.equals(comment.getUserId(),UserContext.getUserId())) { return Result.fail("只能删除自己的评论"); }
        comments.deleteById(id); return Result.ok();
    }
}
