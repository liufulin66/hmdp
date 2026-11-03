const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../js/user-sign.js'), 'utf8');
const tick = () => new Promise(resolve => setImmediate(resolve));

function fixture() {
  const reads = [], posts = [], window = {};
  const axios = {
    get: (url, config) => new Promise((resolve, reject) => reads.push({url, config, resolve, reject})),
    post: (url, body) => new Promise((resolve, reject) => posts.push({url, body, resolve, reject}))
  };
  vm.runInNewContext(source, {window, axios});
  const app = {signState: window.userSign.initial()};
  const status = (index, signed = false, date = '2026-11-03', continuousDays = signed ? 1 : 0) => reads[index].resolve({data: {date, signed, continuousDays}});
  const calendar = (index, month = '2026-11', signedDays = [], daysInMonth = 30) => reads[index].resolve({data: {
    month, daysInMonth, signedDays, signCount: signedDays.length
  }});
  const signed = (index, date = '2026-11-03', newSign = true) => posts[index].resolve({data: {date, signed: true, newSign}});
  return {api: window.userSign, app, reads, posts, status, calendar, signed};
}
async function ready(f) {
  const attached = f.api.attach(f.app); f.status(0); f.calendar(1); await attached;
}

test('initial state and calendar use server dates and separate requests', async () => {
  const f = fixture(), attached = f.api.attach(f.app);
  assert.equal(f.app.signState.today, ''); assert.equal(f.app.signState.signed, null);
  assert.equal(f.reads[0].url, '/user/sign/status'); assert.equal(f.reads[1].url, '/user/sign/calendar');
  assert.deepEqual(Object.keys(f.reads[1].config.params), []);
  f.status(0, false, '2026-11-03'); f.calendar(1, '2026-11', [1, 2]); await attached;
  assert.equal(f.app.signState.today, '2026-11-03'); assert.equal(f.app.signState.calendar.signCount, 2);
});

test('duplicate clicks send one bodyless POST and success reloads calendar', async () => {
  const f = fixture(); await ready(f);
  const posted = f.api.sign(f.app); await f.api.sign(f.app);
  assert.equal(f.posts.length, 1); assert.equal(f.posts[0].url, '/user/sign'); assert.equal(f.posts[0].body, undefined);
  assert.equal(f.app.signState.signPending, true);
  f.signed(0); await tick(); assert.equal(f.app.signState.signed, true);
  f.status(2, true, '2026-11-03', 6); f.calendar(3, '2026-11', [3]); await posted;
  assert.equal(f.app.signState.signPending, false); assert.equal(f.app.signState.calendar.signCount, 1);
  assert.equal(f.app.signState.continuousDays, 6);
  await f.api.sign(f.app); assert.equal(f.posts.length, 1);
});

test('pre-sign status and calendar cannot revert success or clear fresh pending', async () => {
  const f = fixture(); await ready(f);
  const oldStatus = f.api.status(f.app, true), oldCalendar = f.api.calendar(f.app, '2026-11', true);
  const posted = f.api.sign(f.app); f.signed(0); await tick();
  f.status(2, false); f.calendar(3); await Promise.all([oldStatus, oldCalendar]);
  assert.equal(f.app.signState.signed, true); assert.equal(f.app.signState.calendarPending, true);
  assert.equal(f.app.signState.statusPending, true); assert.equal(f.app.signState.continuousDays, null);
  f.status(4, true, '2026-11-03', 6); f.calendar(5, '2026-11', [3]); await posted;
  assert.equal(f.app.signState.calendar.signCount, 1); assert.equal(f.app.signState.continuousDays, 6);
});

test('timeout is unknown, status reconciliation and identical-intent POST retry work', async () => {
  const f = fixture(); await ready(f);
  const post = f.api.sign(f.app); f.posts[0].reject(new Error('timeout')); await post;
  assert.equal(f.app.signState.signed, null); assert.match(f.app.signState.signError, /结果待确认/);
  const checked = f.api.refreshStatus(f.app); f.status(2, true); await tick();
  f.calendar(3, '2026-11', [3]); await checked;
  assert.equal(f.app.signState.signed, true); assert.equal(f.app.signState.signError, '');
  const second = fixture(); await ready(second);
  const timedOut = second.api.sign(second.app); second.posts[0].reject(new Error('timeout')); await timedOut;
  const retry = second.api.sign(second.app); second.signed(1, '2026-11-03', false); await tick();
  second.status(2, true); second.calendar(3, '2026-11', [3]); await retry;
  assert.equal(second.posts.length, 2); assert.equal(second.app.signState.calendar.signCount, 1);
});

test('month switch invalidates old replies and old finally cannot clear new pending', async () => {
  const f = fixture(); await ready(f);
  const old = f.api.calendar(f.app, '2026-10'), current = f.api.calendar(f.app, '2026-09');
  f.calendar(2, '2026-10', [1], 31); await old;
  assert.equal(f.app.signState.month, '2026-09'); assert.equal(f.app.signState.calendarPending, true);
  f.calendar(3, '2026-09', [2]); await current;
  assert.equal(f.app.signState.calendar.month, '2026-09'); assert.equal(f.app.signState.calendar.signedDays[0], 2);
});

test('same-month failure preserves calendar and retry uses same month', async () => {
  const f = fixture(); await ready(f);
  const original = f.app.signState.calendar, failed = f.api.calendar(f.app, '2026-11', true);
  f.reads[2].reject(new Error('network')); await failed;
  assert.equal(f.app.signState.calendar, original); assert.equal(f.app.signState.calendarError, 'network');
  const retry = f.api.calendar(f.app, f.app.signState.month, true);
  assert.equal(f.reads[3].config.params.month, '2026-11'); f.calendar(3, '2026-11', [1]); await retry;
  assert.equal(f.app.signState.calendarError, '');
});

test('destroy and reattach isolate all stale requests including mutation finally', async () => {
  const f = fixture(); await ready(f); const old = f.api.sign(f.app);
  f.api.destroy(f.app); const attached = f.api.attach(f.app); const newState = f.app.signState;
  f.signed(0); await old;
  assert.equal(f.app.signState, newState); assert.equal(newState.signed, null); assert.equal(newState.statusPending, true);
  f.status(2); f.calendar(3); await attached;
  const status = f.api.status(f.app); f.api.destroy(f.app); f.status(4, true); await status;
  assert.equal(newState.signed, false);
});

test('cross-month success follows server date while preserving explicit history selection', async () => {
  const f = fixture(); const attached = f.api.attach(f.app);
  f.status(0, false, '2026-10-31'); f.calendar(1, '2026-10', [], 31); await attached;
  const post = f.api.sign(f.app); f.signed(0, '2026-11-01'); await tick();
  assert.equal(f.reads[3].config.params.month, '2026-11');
  f.status(2, true, '2026-11-01', 1); f.calendar(3, '2026-11', [1]); await post;
  assert.equal(f.app.signState.today, '2026-11-01'); assert.equal(f.app.signState.month, '2026-11');
  const g = fixture(); await ready(g); const pending = g.api.sign(g.app);
  const history = g.api.calendar(g.app, '2026-10'); g.calendar(2, '2026-10', [1], 31); await history;
  g.signed(0); await tick(); assert.equal(g.reads[4].config.params.month, '2026-10');
  g.status(3, true, '2026-11-03', 6); g.calendar(4, '2026-10', [1], 31); await pending;
  assert.equal(g.app.signState.month, '2026-10'); assert.equal(g.app.signState.continuousDays, 6);
});

test('failed initial queries remain unknown and explicit retry recovers', async () => {
  const f = fixture(), attached = f.api.attach(f.app);
  f.reads[0].reject(new Error('Redis unavailable')); f.reads[1].reject(new Error('Redis unavailable')); await attached;
  assert.equal(f.app.signState.signed, null); assert.equal(f.app.signState.calendar, null);
  assert.match(f.app.signState.statusError, /Redis/); assert.match(f.app.signState.calendarError, /Redis/);
  const status = f.api.refreshStatus(f.app); f.status(2, false); await status;
  const calendar = f.api.calendar(f.app); f.calendar(3); await calendar;
  assert.equal(f.app.signState.signed, false); assert.equal(f.app.signState.calendar.signCount, 0);
});

test('malformed replies fail, future months are blocked and cells never submit', async () => {
  const f = fixture(); await ready(f);
  await f.api.calendar(f.app, '2026-12'); await f.api.calendar(f.app, '2026-2');
  assert.equal(f.reads.length, 2);
  const invalid = f.api.calendar(f.app, '2026-10');
  f.calendar(2, '2026-10', [3, 1], 31); await invalid;
  assert.match(f.app.signState.calendarError, /返回异常/);
  const calendar = f.api.calendar(f.app, '2026-10'); f.calendar(3, '2026-10', [1, 31], 31); await calendar;
  const cells = f.api.cells(f.app.signState);
  assert.equal(cells.filter(Boolean).length, 31); assert.equal(cells.filter(Boolean)[0], 1);
  assert.equal(f.posts.length, 0);
});

test('known signed date stays signed, but a later server day can be unsigned', async () => {
  const f = fixture(); await ready(f);
  const first = f.api.status(f.app); f.status(2, true); await first;
  const old = f.api.status(f.app); f.status(3, false); await old; assert.equal(f.app.signState.signed, true);
  const next = f.api.status(f.app); f.status(4, false, '2026-11-04'); await next;
  assert.equal(f.app.signState.today, '2026-11-04'); assert.equal(f.app.signState.signed, false);
  assert.equal(f.app.signState.continuousDays, 0);
});

test('profile initializes sign only after login and keeps existing lifecycle and versioned resources', async () => {
  const html = fs.readFileSync(path.join(__dirname, '../info.html'), 'utf8');
  const inline = [...html.matchAll(/<script>\s*([\s\S]*?)<\/script>/g)][0][1];
  let options; const calls = [], location = {href: ''};
  vm.runInNewContext(inline, {
    siteVisits: {afterRender() {}},
    Vue: function (value) {options = value;}, userSign: {initial: () => ({}), attach: () => calls.push('sign'), destroy: () => calls.push('sign-destroy')},
    feed: {destroy: () => calls.push('feed-destroy')}, axios: {get: async () => ({data: {id: '1001'}})}, location
  });
  const app = {...options.data, queryUserInfo: () => calls.push('profile'), queryBlogs: () => calls.push('blogs')};
  options.methods.queryUser.call(app); assert.deepEqual(calls, []); await tick();
  assert.deepEqual(calls, ['sign', 'profile', 'blogs']);
  options.beforeDestroy.call(app); assert.deepEqual(calls.slice(-2), ['feed-destroy', 'sign-destroy']);
  assert.match(html, /user-sign\.js\?v=20261103-sign-3/); assert.match(html, /user-sign\.css\?v=20261103-sign-3/);
  assert.doesNotMatch(html, /class="sign-day"[^>]*@click/);
});

const vuePath = path.join(process.env.HMDP_FRONTEND_ROOT || 'D:/JavaCode/nginx-1.18.0/html/hmdp', 'js/vue.js');
test('a completed old status cannot alter a newly attached page in its outer continuation', async () => {
  const f = fixture(); await ready(f);
  const old = f.api.refreshStatus(f.app); f.status(2, true);
  await Promise.resolve();
  const attached = f.api.attach(f.app); f.app.signState.signError = 'new context error';
  await old; assert.equal(f.app.signState.signError, 'new context error');
  assert.equal(f.app.signState.statusPending, true);
  f.status(3); f.calendar(4); await attached;
});
test('actual Vue runtime observes sign pending and full personal-profile template compiles', {skip: !fs.existsSync(vuePath)}, async () => {
  const module = {exports: {}}, warnings = [];
  const document = {createElement: () => ({set innerHTML(value) {this.textContent = value;}})};
  vm.runInNewContext(fs.readFileSync(vuePath, 'utf8'), {module, exports: module.exports, document, Promise, setTimeout, clearTimeout, console, process: {env: {NODE_ENV: 'test'}}});
  const Vue = module.exports, f = fixture(), app = new Vue({data: {signState: f.api.initial()}}); f.app = app;
  await ready(f); const changes = []; app.$watch('signState.signPending', value => changes.push(value));
  const post = f.api.sign(app); await Vue.nextTick(); f.signed(0); await tick();
  f.status(2, true); f.calendar(3, '2026-11', [3]); await post; await Vue.nextTick(); assert.deepEqual(changes, [true, false]);
  const html = fs.readFileSync(path.join(__dirname, '../info.html'), 'utf8');
  const template = html.slice(html.indexOf('<div id="app">'), html.indexOf('<script src='));
  const compiled = Vue.compile(template, {warn: message => warnings.push(message)});
  assert.deepEqual(warnings, []); assert.equal(typeof compiled.render, 'function'); f.api.destroy(app); app.$destroy();
});

test('post confirmation remains successful when the subsequent streak read fails', async () => {
  const f = fixture(); await ready(f);
  const post = f.api.sign(f.app); f.signed(0); await tick();
  f.reads[2].reject(new Error('history Redis unavailable')); f.calendar(3, '2026-11', [3]);
  assert.equal(await post, true);
  assert.equal(f.app.signState.signed, true); assert.equal(f.app.signState.signError, '');
  assert.equal(f.app.signState.continuousDays, null); assert.match(f.app.signState.statusError, /history Redis/);
  await f.api.sign(f.app); assert.equal(f.posts.length, 1);
  const retry = f.api.refreshStatus(f.app); f.status(4, true, '2026-11-03', 6); await tick();
  f.calendar(5, '2026-11', [3]); await retry; assert.equal(f.app.signState.continuousDays, 6);
});

test('streak refresh hides stale values while loading and never replaces errors with zero', async () => {
  const f = fixture(); await ready(f);
  const first = f.api.status(f.app); f.status(2, true, '2026-11-03', 6); await first;
  const next = f.api.status(f.app); assert.equal(f.app.signState.continuousDays, null);
  assert.equal(f.app.signState.statusPending, true);
  f.reads[3].reject(new Error('timeout')); await next;
  assert.equal(f.app.signState.continuousDays, null); assert.equal(f.app.signState.signed, true);
  assert.equal(f.app.signState.statusPending, false);
});

test('a very late pre-sign response cannot replace the refreshed streak', async () => {
  const f = fixture(); await ready(f);
  const old = f.api.status(f.app, true), post = f.api.sign(f.app);
  f.signed(0); await tick(); f.status(3, true, '2026-11-03', 6); f.calendar(4, '2026-11', [3]); await post;
  f.status(2, false); await old;
  assert.equal(f.app.signState.signed, true); assert.equal(f.app.signState.continuousDays, 6);
  assert.equal(f.app.signState.statusError, '');
});

test('historical calendars never change the current-day streak or its cutoff', async () => {
  const f = fixture(); await ready(f);
  const checked = f.api.status(f.app); f.status(2, true, '2026-11-03', 6); await checked;
  const history = f.api.calendar(f.app, '2026-10'); f.calendar(3, '2026-10', [29, 30, 31], 31); await history;
  assert.equal(f.app.signState.today, '2026-11-03'); assert.equal(f.app.signState.continuousDays, 6);
  assert.equal(f.app.signState.calendar.signCount, 3);
  assert.equal(f.reads.filter(read => read.url === '/user/sign/status').length, 2);
});

test('malformed or inconsistent streak responses are errors rather than displayed counts', async () => {
  const f = fixture(); await ready(f);
  for (const data of [
    {date: '2026-11-03', signed: false}, {date: '2026-11-03', signed: true, continuousDays: 0},
    {date: '2026-11-03', signed: false, continuousDays: 1}, {date: '2026-11-03', signed: true, continuousDays: -1},
    {date: '2026-11-03', signed: true, continuousDays: '6'}, {date: '2026-11-03', signed: true, continuousDays: 1.5}
  ]) {
    const checked = f.api.status(f.app); f.reads[f.reads.length - 1].resolve({data});
    assert.equal(await checked, false); assert.equal(f.app.signState.continuousDays, null);
    assert.match(f.app.signState.statusError, /返回异常/);
  }
});
