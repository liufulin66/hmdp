/* Shared by the home page, profile page and blog details (Vue 2 + axios). */
(function (global) {
  "use strict";
  const revisions = new WeakMap();
  const refreshes = new WeakMap();

  function apply(vm, blog, state) {
    if (!state || !Number.isInteger(state.liked) || state.liked < 0 || typeof state.isLike !== "boolean") {
      throw new Error("点赞状态返回异常，请重新加载页面后重试");
    }
    // 详情最初是空对象；显式设置响应式字段，数量和图标使用同一份状态。
    vm.$set(blog, "liked", state.liked);
    vm.$set(blog, "isLike", state.isLike);
  }

  async function toggle(vm, blog) {
    if (!blog || !blog.id || blog.likePending) return;
    vm.$set(blog, "likePending", true);
    revisions.set(blog, (revisions.get(blog) || 0) + 1);
    try {
      const url = "/blog/like/" + blog.id;
      const response = blog.isLike === true ? await axios.delete(url) : await axios.put(url);
      apply(vm, blog, response.data);
    } catch (error) {
      vm.$message.error(error.message || error);
    } finally {
      // 请求失败或返回异常时也恢复按钮，不能一直卡在处理中。
      vm.$set(blog, "likePending", false);
    }
  }

  function refresh(vm, blogs) {
    if (refreshes.has(vm)) return refreshes.get(vm);
    const groups = new Map();
    blogs.filter(blog => blog && blog.id).forEach(blog => {
      const id = String(blog.id);
      if (!groups.has(id)) groups.set(id, []);
      groups.get(id).push({blog, revision: revisions.get(blog) || 0});
    });
    const requests = Array.from(groups, async ([id, copies]) => {
      if (copies.some(copy => copy.blog.likePending)) return;
      try {
        const {data} = await axios.get("/blog/" + id, {
          params: {_likeRefresh: Date.now()},
          headers: {"Cache-Control": "no-cache"}
        });
        copies.forEach(({blog, revision}) => {
          // 刷新请求发出后若又点了赞，旧查询结果不能覆盖新状态。
          if (!blog.likePending && (revisions.get(blog) || 0) === revision) apply(vm, blog, data);
        });
      } catch (error) {
        // 保留已有数量；网络失败不能自行加减赞，也不触发整页刷新。
        console.warn("刷新笔记点赞状态失败", id, error);
      }
    });
    const pending = Promise.all(requests).finally(() => refreshes.delete(vm));
    refreshes.set(vm, pending);
    return pending;
  }

  function watch(vm, getBlogs) {
    const onPageShow = event => {
      if (event.persisted) refresh(vm, getBlogs());
    };
    const onVisibilityChange = () => {
      if (document.visibilityState === "visible") refresh(vm, getBlogs());
    };
    global.addEventListener("pageshow", onPageShow);
    document.addEventListener("visibilitychange", onVisibilityChange);
    return () => {
      global.removeEventListener("pageshow", onPageShow);
      document.removeEventListener("visibilitychange", onVisibilityChange);
    };
  }

  global.blogLikes = {toggle, refresh, watch};
})(window);
