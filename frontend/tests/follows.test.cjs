const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const script = fs.readFileSync(path.join(__dirname, '../js/follows.js'), 'utf8');

function fixture(loggedIn = true) {
  const calls = [], errors = [], successes = [];
  const window = {location: {href: ''}};
  let server = false;
  const axios = {
    async get(url) { calls.push(['GET', url]); return {data: server}; },
    async put(url) { calls.push(['PUT', url]); server = url.endsWith('/true'); return {data: server}; }
  };
  vm.runInNewContext(script, {window, axios, token: loggedIn ? 'test-token' : null});
  const app = {followed: false, followPending: false, followReady: false, followRetryIntent: null,
    $message: {error: error => errors.push(error), success: message => successes.push(message)}};
  return {api: window.follows, app, axios, window, calls, errors, successes};
}

test('successful follow and unfollow send explicit intents and use the returned boolean', async () => {
  const f = fixture();
  await f.api.refresh(f.app, 2);
  await f.api.toggle(f.app, 2);
  assert.equal(f.app.followed, true);
  await f.api.toggle(f.app, 2);
  assert.equal(f.app.followed, false);
  assert.deepEqual(f.calls.filter(call => call[0] === 'PUT').map(call => call[1]), ['/follow/2/true', '/follow/2/false']);
  f.axios.put = async () => ({data: false});
  await f.api.toggle(f.app, 2);
  assert.equal(f.app.followed, false, 'must apply the response instead of flipping local state');
  assert.equal(f.app.followPending, false);
});

test('pending includes the request and ignores repeated clicks', async () => {
  const f = fixture();
  await f.api.refresh(f.app, 2);
  let finish;
  let writes = 0;
  f.axios.put = () => { writes++; return new Promise(resolve => { finish = resolve; }); };
  const request = f.api.toggle(f.app, 2);
  assert.equal(f.app.followPending, true);
  assert.equal(f.api.label(f.app), '处理中…');
  assert.equal(await f.api.toggle(f.app, 2), false);
  assert.equal(writes, 1);
  finish({data: true});
  await request;
  assert.equal(f.app.followPending, false);
});

test('timeout recovery queries state but retries the original intent even after a committed write', async () => {
  const f = fixture();
  await f.api.refresh(f.app, 2);
  const writes = [];
  f.axios.put = async url => { writes.push(url); throw new Error('timeout'); };
  f.axios.get = async () => ({data: true}); // timed-out write already committed
  assert.equal(await f.api.toggle(f.app, 2), false);
  assert.equal(f.app.followed, true);
  assert.equal(f.app.followRetryIntent, true);
  assert.equal(f.api.label(f.app), '重试关注');
  f.axios.put = async url => { writes.push(url); return {data: true}; };
  assert.equal(await f.api.toggle(f.app, 2), true);
  assert.deepEqual(writes, ['/follow/2/true', '/follow/2/true']);
  assert.equal(f.app.followRetryIntent, null);
  assert.equal(f.app.followPending, false);
});

test('failed unfollow retains a false retry intent rather than converting it to a follow', async () => {
  const f = fixture();
  f.axios.get = async () => ({data: true});
  await f.api.refresh(f.app, 2);
  f.axios.put = async () => { throw new Error('connection lost'); };
  f.axios.get = async () => ({data: false});
  await f.api.toggle(f.app, 2);
  assert.equal(f.app.followed, false);
  assert.equal(f.app.followRetryIntent, false);
  assert.equal(f.api.label(f.app), '重试取消关注');
  let request;
  f.axios.put = async url => { request = url; return {data: false}; };
  await f.api.toggle(f.app, 2);
  assert.equal(request, '/follow/2/false');
});

test('failed mutation and failed recovery still release pending and preserve retry intent', async () => {
  const f = fixture();
  await f.api.refresh(f.app, 2);
  f.axios.put = async () => { throw new Error('write failed'); };
  f.axios.get = async () => { throw new Error('read failed'); };
  await f.api.toggle(f.app, 2);
  assert.equal(f.app.followPending, false);
  assert.equal(f.app.followReady, false);
  assert.equal(f.app.followRetryIntent, true);
  assert.deepEqual(f.errors, ['write failed']);
});

test('unknown initial state is queried before an operation is chosen', async () => {
  const f = fixture();
  f.axios.get = async () => { throw new Error('read failed'); };
  await f.api.toggle(f.app, 2);
  assert.equal(f.app.followReady, false);
  assert.equal(f.app.followPending, false);
  assert.equal(f.app.followRetryIntent, null);
  assert.equal(f.api.label(f.app), '查询关注状态');
  assert.equal(f.calls.length, 0);
});

test('late state responses for a previous target cannot overwrite the current target', async () => {
  const f = fixture();
  const reads = [];
  f.axios.get = () => new Promise(resolve => reads.push(resolve));
  const first = f.api.refresh(f.app, 2);
  const second = f.api.refresh(f.app, 3);
  reads[1]({data: true});
  await second;
  reads[0]({data: false});
  await first;
  assert.equal(f.app.followed, true);
  assert.equal(f.app.followPending, false);
});

test('a malformed mutation response triggers recovery instead of changing the button arbitrarily', async () => {
  const f = fixture();
  await f.api.refresh(f.app, 2);
  f.axios.put = async () => ({data: 'true'});
  assert.equal(await f.api.toggle(f.app, 2), false);
  assert.equal(f.app.followed, false);
  assert.equal(f.app.followPending, false);
  assert.match(f.errors[0], /关注状态返回异常/);
});

test('anonymous clicks use the existing login page and do not send a mutation', async () => {
  const f = fixture(false);
  assert.equal(await f.api.toggle(f.app, 2), false);
  assert.equal(f.window.location.href, '/login.html');
  assert.deepEqual(f.errors, ['请先登录']);
  assert.equal(f.calls.length, 0);
});

function page(name, axios, follows = {}) {
  const html = fs.readFileSync(path.join(__dirname, '../' + name), 'utf8');
  const inline = [...html.matchAll(/<script>\s*([\s\S]*?)<\/script>/g)][0][1];
  let options;
  const location = {href: ''}, errors = [];
  vm.runInNewContext(inline, {Vue: function(value) {options = value;}, util: {getUrlParam: () => '2'}, axios, follows, location});
  const app = {...options.data, $message: {error: error => errors.push(error)}};
  for (const [name, method] of Object.entries(options.methods)) app[name] = method.bind(app);
  return {app, html, errors, options, location};
}

test('both existing pages wire their button to the shared follow control and disable it during pending', async () => {
  for (const name of ['blog-detail.html', 'other-info.html']) {
    const calls = [];
    const follows = {toggle: async (app, id) => calls.push(['toggle', id]), refresh: async (app, id) => calls.push(['refresh', id]), label: () => '关注'};
    const p = page(name, {}, follows);
    p.app.blog = {userId: 2};
    p.app.user = {id: 2};
    await p.app.follow();
    await p.app.isFollowed();
    assert.deepEqual(calls, [['toggle', 2], ['refresh', 2]]);
    assert.match(p.html, /<button[^>]*[\s\S]*?:disabled="followPending/);
    assert.match(p.html, /follows\.js\?v=/);
  }
});

test('common-follow pagination sends current, preserves server order and stops after a short page', async () => {
  const calls = [];
  const p = page('other-info.html', {get: async (url, config) => {
    calls.push({url, current: config.params.current});
    return {data: config.params.current === 1 ? [3, 4, 5, 6, 7].map(id => ({id})) : [{id: 8}]};
  }});
  p.app.user = {id: 2};
  await p.app.queryCommonFollow(1);
  assert.equal(p.app.commonHasNext, true);
  assert.deepEqual(p.app.commonFollows.map(user => user.id), [3, 4, 5, 6, 7]);
  await p.app.queryCommonFollow(2);
  assert.equal(p.app.commonCurrent, 2);
  assert.equal(p.app.commonHasNext, false);
  assert.deepEqual(calls, [{url: '/follow/common/2', current: 1}, {url: '/follow/common/2', current: 2}]);
  const count = calls.length;
  await p.app.queryCommonFollow(0);
  await p.app.queryCommonFollow(1001);
  assert.equal(calls.length, count);
});

test('common-follow failure keeps the displayed page, reports error and releases pending', async () => {
  const p = page('other-info.html', {get: async () => {throw new Error('failed');}});
  p.app.user = {id: 2};
  p.app.commonFollows = [{id: 3}];
  await p.app.queryCommonFollow(2);
  assert.equal(p.app.commonPending, false);
  assert.equal(p.app.commonCurrent, 1);
  assert.deepEqual(p.app.commonFollows, [{id: 3}]);
  assert.deepEqual(p.errors, ['failed']);
});

test('common-follow empty results and simultaneous page clicks are handled', async () => {
  let finish;
  let reads = 0;
  const p = page('other-info.html', {get: () => {reads++; return new Promise(resolve => {finish = resolve;});}});
  p.app.user = {id: 2};
  const request = p.app.queryCommonFollow(1);
  assert.equal(p.app.commonPending, true);
  await p.app.queryCommonFollow(2);
  assert.equal(reads, 1);
  finish({data: []});
  await request;
  assert.equal(p.app.commonLoaded, true);
  assert.equal(p.app.commonHasNext, false);
  assert.equal(p.app.commonFollows.length, 0);
});

test('common-follow rejects oversized responses and bounds the final page', async () => {
  const p = page('other-info.html', {get: async () => ({data: [1, 2, 3, 4, 5, 6]})});
  p.app.user = {id: 2};
  assert.equal(await p.app.queryCommonFollow(1), false);
  assert.equal(p.app.commonFollows.length, 0);
  p.app.commonPending = false;
  const last = page('other-info.html', {get: async () => ({data: [3, 4, 5, 6, 7].map(id => ({id}))})});
  last.app.user = {id: 2};
  await last.app.queryCommonFollow(1000);
  assert.equal(last.app.commonHasNext, false);
});

test('the shared response interceptor handles a network error without reading a missing response', async () => {
  let onError;
  const axios = {defaults: {}, interceptors: {request: {use() {}}, response: {use(ok, error) {onError = error;}}}};
  const location = {href: ''};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../js/common.js'), 'utf8'), {
    axios, sessionStorage: {getItem: () => null}, console: {log() {}}, location, setTimeout: fn => fn()
  });
  await assert.rejects(onError({message: 'timeout'}), error => error === '网络异常，请重试');
  await assert.rejects(onError({response: {status: 401}}), error => error === '请先登录');
  assert.equal(location.href, '/login.html');
});

const vuePath = path.join(process.env.HMDP_FRONTEND_ROOT || 'D:/JavaCode/nginx-1.18.0/html/hmdp', 'js/vue.js');
test('the actual Vue runtime observes pending and the returned follow state', {skip: !fs.existsSync(vuePath)}, async () => {
  const vueModule = {exports: {}};
  vm.runInNewContext(fs.readFileSync(vuePath, 'utf8'), {
    module: vueModule, exports: vueModule.exports, Promise, setTimeout, clearTimeout, console, process: {env: {NODE_ENV: 'test'}}
  });
  const Vue = vueModule.exports;
  const f = fixture();
  const app = new Vue({data: {followed: false, followPending: false, followReady: false, followRetryIntent: null}});
  app.$message = f.app.$message;
  await f.api.refresh(app, 2);
  const pending = [], states = [];
  app.$watch('followPending', value => pending.push(value));
  app.$watch('followed', value => states.push(value));
  let finish;
  f.axios.put = () => new Promise(resolve => {finish = resolve;});
  const request = f.api.toggle(app, 2);
  await Vue.nextTick();
  finish({data: true});
  await request;
  await Vue.nextTick();
  assert.deepEqual(pending, [true, false]);
  assert.deepEqual(states, [true]);
  app.$destroy();
});
