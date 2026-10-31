/* Shared follow controls for the existing Vue 2 pages. */
(function (global) {
  "use strict";
  const contexts = new WeakMap();

  function authenticated(vm) {
    if (typeof token !== "undefined" && token) return true;
    vm.$message.error("请先登录");
    global.location.href = "/login.html";
    return false;
  }

  function context(vm, targetId) {
    const target = String(targetId);
    let value = contexts.get(vm);
    if (!value || value.target !== target) {
      value = {target, revision: 0};
      contexts.set(vm, value);
      vm.followed = false;
      vm.followPending = false;
      vm.followReady = false;
      vm.followRetryIntent = null;
    }
    return value;
  }

  function active(vm, value, revision) {
    return contexts.get(vm) === value && value.revision === revision;
  }

  function apply(vm, data) {
    if (typeof data !== "boolean") throw new Error("关注状态返回异常，请重试");
    vm.followed = data;
    vm.followReady = true;
  }

  function errorMessage(error) {
    return (error && error.message) || error || "关注请求失败，请稍后重试";
  }

  async function read(vm, value, revision) {
    const {data} = await axios.get("/follow/or/not/" + value.target, {
      params: {_followRefresh: Date.now()}, headers: {"Cache-Control": "no-cache"}
    });
    if (active(vm, value, revision)) apply(vm, data);
  }

  async function refresh(vm, targetId) {
    if (!targetId || !authenticated(vm)) return false;
    const value = context(vm, targetId);
    if (vm.followPending) return false;
    const revision = ++value.revision;
    vm.followPending = true;
    try {
      await read(vm, value, revision);
      return active(vm, value, revision);
    } catch (error) {
      if (active(vm, value, revision)) {
        vm.followReady = false;
        vm.$message.error(errorMessage(error));
      }
      return false;
    } finally {
      if (active(vm, value, revision)) vm.followPending = false;
    }
  }

  async function toggle(vm, targetId) {
    if (!targetId || !authenticated(vm)) return false;
    const value = context(vm, targetId);
    if (vm.followPending) return false;
    if (!vm.followReady && typeof vm.followRetryIntent !== "boolean") return refresh(vm, targetId);
    // 在发送请求之前固定意图。失败后即使状态重查变了，重试仍使用这个值。
    const intent = typeof vm.followRetryIntent === "boolean" ? vm.followRetryIntent : !vm.followed;
    const revision = ++value.revision;
    vm.followPending = true;
    try {
      const {data} = await axios.put("/follow/" + value.target + "/" + intent);
      if (!active(vm, value, revision)) return false;
      apply(vm, data);
      vm.followRetryIntent = null;
      vm.$message.success(data ? "已关注" : "已取消关注");
      return true;
    } catch (error) {
      if (!active(vm, value, revision)) return false;
      vm.followRetryIntent = intent;
      vm.followReady = false;
      vm.$message.error(errorMessage(error));
      // 超时不能证明服务端没写入。重查只校准按钮状态，不反转或自动重发。
      try {
        await read(vm, value, revision);
      } catch (ignored) {
        if (active(vm, value, revision)) vm.followReady = false;
      }
      return false;
    } finally {
      if (active(vm, value, revision)) vm.followPending = false;
    }
  }

  function label(vm) {
    if (vm.followPending) return "处理中…";
    if (typeof vm.followRetryIntent === "boolean") return vm.followRetryIntent ? "重试关注" : "重试取消关注";
    if (!vm.followReady) return "查询关注状态";
    return vm.followed ? "取消关注" : "关注";
  }

  global.follows = {refresh, toggle, label};
})(window);
