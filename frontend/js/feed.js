/* Followed authors' notes: cursor paging with refresh generations. */
(function (global) {
  "use strict";
  const contexts = new WeakMap();
  function context(vm) {
    let value = contexts.get(vm);
    if (!value) {
      value = {generation: 0, request: 0, initialized: false, needsRefresh: true, refreshing: false};
      contexts.set(vm, value);
    }
    return value;
  }
  function current(vm, value, generation, request) {
    return contexts.get(vm) === value && value.generation === generation && value.request === request;
  }
  function validate(data, cursor) {
    if (!data || !Array.isArray(data.list) || data.list.length > 2 || !Number.isSafeInteger(data.minTime)
        || data.minTime < 0 || data.minTime > cursor.minTime || !Number.isInteger(data.offset) || data.offset < 0) {
      throw new Error("动态分页返回异常，请重试");
    }
    if (data.minTime === 0) {
      if (data.offset !== 0 || data.list.length !== 0) throw new Error("动态结束游标异常");
    } else if (data.minTime === cursor.minTime) {
      if (data.offset <= cursor.offset || data.offset > cursor.offset + 2) throw new Error("动态游标未前进");
    } else if (data.offset < 1 || data.offset > 2) throw new Error("动态游标异常");
  }
  async function load(vm, clear = false) {
    const value = context(vm);
    const refresh = !!clear || value.needsRefresh || !value.initialized;
    // 显式刷新可使旧翻页失效；同一轮重复刷新或触底则忽略。
    if (vm.feedPending && (!refresh || value.refreshing)) return false;
    if (vm.feedEnded && !refresh) return false;
    if (refresh) {
      value.generation++;
      value.needsRefresh = true;
      vm.params = {minTime: Date.now() + 1, offset: 0};
      vm.feedEnded = false;
    }
    const cursor = {...vm.params};
    const likeSnapshots = new Map(vm.blogs2.map(blog => [String(blog.id), {
      blog, liked: blog.liked, isLike: blog.isLike, pending: blog.likePending
    }]));
    const generation = value.generation;
    const request = ++value.request;
    value.refreshing = refresh;
    vm.feedRefreshing = refresh;
    vm.feedPending = true;
    vm.feedError = "";
    try {
      const {data} = await axios.get("/blog/of/follow", {
        params: {lastId: cursor.minTime, offset: cursor.offset}, headers: {"Cache-Control": "no-cache"}
      });
      if (!current(vm, value, generation, request)) return false;
      validate(data, cursor);
      const rows = data.list.map(blog => {
        const row = {...blog, img: String(blog.images || "").split(",")[0], likePending: false};
        const before = likeSnapshots.get(String(blog.id));
        if (!refresh || !before) return row;
        // 保留对象身份：未完成的点赞仍会更新正在展示的同一对象。
        const original = before.blog;
        const changed = before.pending || original.likePending || original.liked !== before.liked || original.isLike !== before.isLike;
        const liked = original.liked, isLike = original.isLike, pending = original.likePending;
        Object.assign(original, row);
        if (changed) { original.liked = liked; original.isLike = isLike; }
        original.likePending = pending;
        return original;
      });
      if (refresh) vm.blogs2 = rows;
      else {
        const existing = new Set(vm.blogs2.map(blog => String(blog.id)));
        vm.blogs2 = vm.blogs2.concat(rows.filter(blog => {
          const id = String(blog.id);
          if (existing.has(id)) return false;
          existing.add(id);
          return true;
        }));
      }
      vm.params = {minTime: data.minTime, offset: data.offset};
      vm.feedLoaded = true;
      vm.feedEnded = data.minTime === 0;
      value.initialized = true;
      value.needsRefresh = false;
      return true;
    } catch (error) {
      if (current(vm, value, generation, request)) {
        vm.feedError = (error && error.message) || error || "动态加载失败，请重试";
        vm.$message.error(vm.feedError);
      }
      return false;
    } finally {
      if (current(vm, value, generation, request)) {
        vm.feedPending = false;
        vm.feedRefreshing = false;
        value.refreshing = false;
      }
    }
  }
  function destroy(vm) { contexts.delete(vm); }
  global.feed = {load, destroy};
})(window);
