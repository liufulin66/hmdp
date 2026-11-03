(function (global) {
  'use strict';
  const contexts = new WeakMap();
  const http = axios.create({baseURL: '/api', timeout: 5000});
  const states = new Set(['AVAILABLE', 'NOT_COLLECTED', 'EXPIRED']);
  function initial() {
    return {selectedDate: '', serverToday: '', daily: null, dailyPending: false, dailyError: '',
      recent: null, recentPending: false, recentError: ''};
  }
  async function read(path, params) {
    const token = sessionStorage.getItem('token');
    try {
      const response = await http.get(path, {params, headers: token ? {authorization: token, 'Cache-Control': 'no-cache'} : {'Cache-Control': 'no-cache'}});
      if (!response.data || !response.data.success) throw new Error(response.data && response.data.errorMsg || '统计暂不可用');
      return response.data.data;
    } catch (error) {
      const status = error.response && error.response.status;
      if (status === 401) throw new Error('请先登录后查看管理员统计');
      if (status === 403) throw new Error('需要管理员权限，当前账号无权查看');
      throw new Error(error.message || '统计请求失败，请重试');
    }
  }
  function validDay(row) {
    return row && /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(row.date) && row.approximate === true && states.has(row.status)
      && (row.status === 'AVAILABLE' ? Number.isSafeInteger(row.uv) && row.uv >= 0 : row.uv === null);
  }
  function validRecent(data) {
    if (!data || !Array.isArray(data.list) || data.list.length !== 7 || !data.list.every(validDay)
        || data.approximate !== true || typeof data.complete !== 'boolean') return false;
    const start = Date.parse(data.requestedFrom + 'T00:00:00Z');
    if (!Number.isFinite(start) || !data.list.every((row, index) => row.date === new Date(start + index * 86400000).toISOString().slice(0, 10))
        || data.requestedTo !== data.list[6].date) return false;
    const covered = data.list.filter(row => row.status === 'AVAILABLE');
    return data.complete === (covered.length === 7) && (covered.length
      ? data.coverageFrom === covered[0].date && data.coverageTo === covered[covered.length - 1].date && Number.isSafeInteger(data.rangeUv) && data.rangeUv >= 0
      : data.coverageFrom === null && data.coverageTo === null && data.rangeUv === null);
  }
  async function daily(vm, date, force = false) {
    const value = contexts.get(vm); if (!value) return false;
    const state = vm.statisticsState, target = date == null ? '' : date;
    if (state.dailyPending && value.date === target && !force) return false;
    value.date = target; state.selectedDate = target;
    const request = ++value.daily, active = () => contexts.get(vm) === value && value.daily === request;
    state.dailyPending = true; state.dailyError = ''; state.daily = null;
    try {
      const data = await read('/statistics/uv/daily', target ? {date: target} : {});
      if (!active()) return false;
      if (!validDay(data) || (target && data.date !== target)) throw new Error('每日UV返回异常');
      state.daily = data; state.selectedDate = data.date;
      if (!target) state.serverToday = data.date;
      return true;
    } catch (error) { if (active()) state.dailyError = error.message; return false; }
    finally { if (active()) state.dailyPending = false; }
  }
  async function recent(vm, force = false) {
    const value = contexts.get(vm); if (!value) return false;
    const state = vm.statisticsState; if (state.recentPending && !force) return false;
    const request = ++value.recent, active = () => contexts.get(vm) === value && value.recent === request;
    state.recentPending = true; state.recentError = ''; state.recent = null;
    try {
      const data = await read('/statistics/uv/recent', {});
      if (!active()) return false;
      if (!validRecent(data)) throw new Error('最近7日UV返回异常');
      state.recent = data; state.serverToday = data.requestedTo;
      return true;
    } catch (error) { if (active()) state.recentError = error.message; return false; }
    finally { if (active()) state.recentPending = false; }
  }
  function attach(vm) { vm.statisticsState = initial(); contexts.set(vm, {daily: 0, recent: 0, date: ''}); return Promise.all([daily(vm), recent(vm)]); }
  function destroy(vm) { contexts.delete(vm); }
  function label(status) { return {AVAILABLE: '已采集', NOT_COLLECTED: '未采集', EXPIRED: '已过期'}[status] || '不可用'; }
  global.siteStatistics = {initial, attach, daily, recent, destroy, label};
})(window);
