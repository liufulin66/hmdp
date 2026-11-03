/* Current user's monthly Bitmap calendar. Dates always originate at the server. */
(function (global) {
  'use strict';
  const contexts = new WeakMap();
  const monthPattern = /^[0-9]{4}-(0[1-9]|1[0-2])$/;
  const datePattern = /^[0-9]{4}-(0[1-9]|1[0-2])-[0-9]{2}$/;

  function initial() {
    return {today: '', signed: null, statusPending: false, statusError: '', signPending: false,
      signError: '', month: '', calendar: null, calendarPending: false, calendarError: ''};
  }
  function errorText(error) { return (error && error.message) || String(error || '请求失败，请重试'); }
  function live(vm, value) { return contexts.get(vm) === value; }
  function invalidateReads(state, value) {
    value.status++; value.calendar++;
    state.statusPending = false; state.calendarPending = false;
  }
  function applyStatus(state, data) {
    if (!data || !datePattern.test(data.date) || typeof data.signed !== 'boolean') throw new Error('签到状态返回异常');
    if (state.today && data.date < state.today) return;
    const sameDay = state.today === data.date;
    state.signed = sameDay && state.signed === true ? true : data.signed;
    state.today = data.date;
  }
  async function status(vm, force = false) {
    const value = contexts.get(vm); if (!value) return false;
    const state = vm.signState;
    if ((state.statusPending && !force) || state.signPending) return false;
    const request = ++value.status, active = () => live(vm, value) && value.status === request;
    state.statusPending = true; state.statusError = '';
    try {
      const {data} = await axios.get('/user/sign/status', {headers: {'Cache-Control': 'no-cache'}});
      if (!active()) return false;
      applyStatus(state, data);
      return true;
    } catch (error) {
      if (active()) state.statusError = errorText(error);
      return false;
    } finally { if (active()) state.statusPending = false; }
  }
  async function calendar(vm, month, force = false) {
    const value = contexts.get(vm); if (!value) return false;
    const state = vm.signState, target = month == null ? '' : month;
    if (target && (!monthPattern.test(target) || target.slice(0, 4) === '0000'
        || (state.today && target > state.today.slice(0, 7)))) {
      state.calendarError = '请选择当前或历史月份'; return false;
    }
    if (state.calendarPending && state.month === target && !force) return false;
    const request = ++value.calendar, active = () => live(vm, value) && value.calendar === request;
    if (state.month !== target) state.calendar = null;
    state.month = target; state.calendarPending = true; state.calendarError = '';
    try {
      const {data} = await axios.get('/user/sign/calendar', {
        params: target ? {month: target} : {}, headers: {'Cache-Control': 'no-cache'}
      });
      if (!active()) return false;
      if (!data || !monthPattern.test(data.month) || (target && data.month !== target)
          || !Number.isInteger(data.daysInMonth) || data.daysInMonth < 28 || data.daysInMonth > 31
          || !Array.isArray(data.signedDays) || data.signCount !== data.signedDays.length
          || !data.signedDays.every((day, index, days) => Number.isInteger(day) && day >= 1
            && day <= data.daysInMonth && (!index || day > days[index - 1]))) throw new Error('签到月历返回异常');
      state.month = data.month; state.calendar = data;
      return true;
    } catch (error) {
      if (active()) state.calendarError = errorText(error);
      return false;
    } finally { if (active()) state.calendarPending = false; }
  }
  async function sign(vm) {
    const value = contexts.get(vm); if (!value) return false;
    const state = vm.signState;
    if (state.signPending || state.signed === true || !state.today) return false;
    const request = ++value.sign, active = () => live(vm, value) && value.sign === request;
    const previousMonth = state.today.slice(0, 7), followCurrent = !state.month || state.month === previousMonth;
    invalidateReads(state, value);
    state.signPending = true; state.signError = '';
    try {
      const {data} = await axios.post('/user/sign');
      if (!active()) return false;
      if (!data || data.signed !== true || typeof data.newSign !== 'boolean') throw new Error('签到结果尚未确认');
      applyStatus(state, data);
      invalidateReads(state, value);
      state.statusError = '';
      // 签到期间用户可能已切到历史月份，不能将其切回本月。
      const stillCurrent = followCurrent && (!state.month || state.month === previousMonth);
      await calendar(vm, stillCurrent ? data.date.slice(0, 7) : state.month, true);
      return true;
    } catch (error) {
      if (active()) {
        invalidateReads(state, value);
        state.signed = null;
        state.signError = errorText(error) + '；结果待确认，可查询状态或重试签到';
      }
      return false;
    } finally { if (active()) state.signPending = false; }
  }
  async function refreshStatus(vm) {
    const value = contexts.get(vm);
    const ok = await status(vm, true);
    if (!ok || !value || !live(vm, value)) return false;
    vm.signState.signError = '';
    if (vm.signState.month === vm.signState.today.slice(0, 7)) {
      await calendar(vm, vm.signState.month, true);
    }
    return ok;
  }
  function attach(vm) {
    vm.signState = initial();
    contexts.set(vm, {status: 0, calendar: 0, sign: 0});
    return Promise.all([status(vm), calendar(vm)]);
  }
  function destroy(vm) { contexts.delete(vm); }
  function shift(vm, delta) {
    const state = vm.signState;
    if (!state.month) return false;
    const parts = state.month.split('-').map(Number), index = parts[0] * 12 + parts[1] - 1 + delta;
    if (index < 12 || index > 9999 * 12 + 11) return false;
    return calendar(vm, String(Math.floor(index / 12)).padStart(4, '0') + '-' + String(index % 12 + 1).padStart(2, '0'));
  }
  function cells(state) {
    if (!state.calendar) return [];
    const first = new Date(state.calendar.month + '-01T00:00:00Z').getUTCDay();
    const blanks = Array.from({length: (first + 6) % 7}, () => null);
    return blanks.concat(Array.from({length: state.calendar.daysInMonth}, (_, index) => index + 1));
  }
  global.userSign = {initial, attach, status, refreshStatus, calendar, sign, shift, cells, destroy};
})(window);
