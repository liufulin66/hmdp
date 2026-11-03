/* Other users' notes: page-number paging with target and request generations. */
(function (global) {
  'use strict';
  const contexts = new WeakMap();
  const PAGE_SIZE = 10;

  function context(vm) {
    let value = contexts.get(vm);
    if (!value) {
      value = {user: '', generation: 0, request: 0, current: 0, refreshing: false};
      contexts.set(vm, value);
    }
    return value;
  }
  function validId(id) {
    return /^[1-9][0-9]*$/.test(String(id == null ? '' : id));
  }
  function validBlog(blog) {
    return !!blog && blog.id != null && /^[0-9]+$/.test(String(blog.id));
  }
  function reset(vm, value, user) {
    value.generation++; value.request++; value.user = user; value.current = 0; value.refreshing = false;
    vm.blogs = []; vm.blogsLoaded = false; vm.blogsEnded = false; vm.blogsCurrent = 0;
  }

  async function load(vm, userId, shouldReset = false) {
    const value = context(vm), target = String(userId == null ? '' : userId);
    if (!validId(target)) { vm.blogsError = '用户ID无效'; return false; }
    if (value.user !== target) { reset(vm, value, target); shouldReset = true; }
    shouldReset = !!shouldReset;
    // 同一轮重复刷新忽略；加载更多进行中时也忽略；显式刷新可以取代旧的加载更多。
    if (vm.blogsPending && (!shouldReset || value.refreshing)) return false;
    if (!shouldReset && (vm.blogsPending || vm.blogsEnded)) return false;
    const page = shouldReset ? 1 : value.current + 1;
    if (page > 1000) { vm.blogsEnded = true; return false; }
    if (shouldReset) { value.generation++; value.refreshing = true; }
    const generation = value.generation, request = ++value.request;
    const active = () => contexts.get(vm) === value && value.generation === generation && value.request === request;
    vm.blogsPending = true; vm.blogsError = '';
    try {
      const {data} = await axios.get('/blog/of/user', {
        params: {id: target, current: page}, headers: {'Cache-Control': 'no-cache'}
      });
      if (!active()) return false;
      if (!Array.isArray(data) || data.length > PAGE_SIZE || !data.every(validBlog)) {
        throw new Error('笔记列表返回异常，请重试');
      }
      const rows = data.map(blog => ({...blog, img: String(blog.images || '').split(',')[0]}));
      if (shouldReset) vm.blogs = rows;
      else {
        const seen = new Set(vm.blogs.map(blog => String(blog.id)));
        vm.blogs = vm.blogs.concat(rows.filter(blog => {
          const id = String(blog.id);
          if (seen.has(id)) return false;
          seen.add(id);
          return true;
        }));
      }
      value.current = page;
      vm.blogsCurrent = page;
      vm.blogsLoaded = true;
      vm.blogsEnded = rows.length < PAGE_SIZE || page >= 1000;
      return true;
    } catch (error) {
      if (active()) {
        vm.blogsError = (error && error.message) || error || '笔记加载失败，请重试';
        if (vm.$message && typeof vm.$message.error === 'function') vm.$message.error(vm.blogsError);
      }
      return false;
    } finally {
      if (active()) { vm.blogsPending = false; value.refreshing = false; }
    }
  }

  function destroy(vm) { contexts.delete(vm); }

  global.blogOfUser = {load, destroy, pageSize: PAGE_SIZE};
})(window);
