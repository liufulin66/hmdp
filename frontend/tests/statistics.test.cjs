const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.join(__dirname, '..');
const visitsSource = fs.readFileSync(path.join(root, 'js/site-visits.js'), 'utf8');
const statsSource = fs.readFileSync(path.join(root, 'js/site-statistics.js'), 'utf8');
const tick = () => new Promise(resolve => setImmediate(resolve));

function visits() {
  const window = {}, posts = [], configs = [];
  const axios = {create: config => { configs.push(config); return {post: (url, body) => new Promise((resolve, reject) => posts.push({url, body, resolve, reject}))}; }};
  vm.runInNewContext(visitsSource, {window, axios});
  return {api: window.siteVisits, posts, configs};
}
test('page reports once with whitelisted type, no token, URL or readable cookie', async () => {
  const f = visits(), first = f.api.report('HOME');
  await f.api.report('HOME'); await f.api.report('WRONG'); await tick();
  assert.equal(f.posts.length, 1); assert.equal(f.posts[0].url, '/statistics/visits');
  assert.deepEqual(Object.keys(f.posts[0].body), ['pageType']); assert.equal(f.posts[0].body.pageType, 'HOME');
  assert.equal(f.configs[0].baseURL, '/api'); assert.equal(f.configs[0].headers, undefined);
  assert.doesNotMatch(visitsSource, /sessionStorage|document\.cookie/);
  f.posts[0].resolve({data: {success: true, data: {collected: true}}}); assert.equal(await first, true);
});
test('failed collection is silent and does not retry on pagination or render', async () => {
  const f = visits(), first = f.api.report('SHOP_LIST'); await tick();
  f.posts[0].reject(new Error('network')); assert.equal(await first, false);
  await f.api.report('SHOP_LIST'); assert.equal(f.posts.length, 1);
});
test('reports only after rendering and skips destroyed or obsolete targets', async () => {
  const f = visits(), callbacks = [], app = {$nextTick: fn => callbacks.push(fn)};
  f.api.afterRender(app, 'BLOG_DETAIL', () => false); callbacks.shift()(); await tick(); assert.equal(f.posts.length, 0);
  f.api.afterRender(app, 'BLOG_DETAIL'); app._isDestroyed = true; callbacks.shift()(); await tick(); assert.equal(f.posts.length, 0);
  app._isDestroyed = false; f.api.afterRender(app, 'BLOG_DETAIL'); assert.equal(f.posts.length, 0);
  callbacks.shift()(); await tick(); assert.equal(f.posts.length, 1); f.posts[0].resolve({data: {success: true, data: {collected: true}}});
});

function page(name, get) {
  const html = fs.readFileSync(path.join(root, name), 'utf8'), inline = [...html.matchAll(/<script>\s*([\s\S]*?)<\/script>/g)][0][1];
  let options; const f = visits(), errors = [], window = {location: {href: ''}};
  vm.runInNewContext(inline, {Vue: function (value) {options = value;}, util: {getUrlParam: () => '4'},
    axios: {get}, siteVisits: f.api, location: window.location, token: '',
    blogComments: {initial: () => ({loaded: false, countVersion: '0'}), attach() {}},
    userSign: {initial: () => ({}), attach() {}}, blogLikes: {}, follows: {}, blogDetail: {},
    shopNearby: {load: async () => true, distance() {}}, console});
  const app = {...options.data, $nextTick: fn => fn(), $message: {error: e => errors.push(e)}};
  for (const [name, method] of Object.entries(options.methods)) app[name] = method.bind(app);
  return {...f, app, options, html, errors};
}
test('home waits for successful initial content and pagination never reports again', async () => {
  const failed = page('index.html', async url => {if (url.startsWith('/blog')) throw new Error('content failed'); return {data: []};});
  failed.options.created.call(failed.app); await tick(); assert.equal(failed.posts.length, 0);
  const f = page('index.html', async () => ({data: []})); f.options.created.call(f.app); await tick();
  assert.equal(f.posts.length, 1); assert.equal(f.posts[0].body.pageType, 'HOME');
  await f.app.queryHotBlogsScroll(); await tick(); assert.equal(f.posts.length, 1);
  f.posts[0].reject(new Error('analytics offline')); await tick(); assert.deepEqual(f.errors, []);
});
test('shop list reports once despite repeated successful pages', async () => {
  const f = page('shop-list.html', async () => ({data: []}));
  await f.app.queryShops(); await f.app.queryShops(); await tick(); assert.equal(f.posts.length, 1);
  assert.equal(f.posts[0].body.pageType, 'SHOP_LIST'); f.posts[0].resolve({data: {success: true, data: {collected: true}}});
});
test('missing shop or blog does not report, successful details report their page type', async () => {
  for (const [file, method, type] of [['shop-detail.html', 'queryShopById', 'SHOP_DETAIL'], ['blog-detail.html', 'queryBlogById', 'BLOG_DETAIL']]) {
    const failure = page(file, async () => {throw new Error('not found');}); failure.app[method]('4'); await tick(); assert.equal(failure.posts.length, 0);
    const f = page(file, async () => ({data: {id: 4, images: 'one.jpg', shopId: 1}}));
    f.app.init = () => {}; f.app.queryShopById = method === 'queryShopById' ? f.app.queryShopById : () => {};
    f.app.queryLikeList = () => {}; f.app.queryLoginUser = () => {};
    f.app[method]('4'); await tick(); assert.equal(f.posts.length, 1, file); assert.equal(f.posts[0].body.pageType, type);
    f.posts[0].resolve({data: {success: true, data: {collected: true}}});
  }
});
test('own and other profiles report only after their main user is loaded', async () => {
  for (const [file, type] of [['info.html', 'MY_PROFILE'], ['other-info.html', 'USER_PROFILE']]) {
    const f = page(file, async () => ({data: {id: 4}}));
    f.app.loginUser = {id: 2}; f.app.queryUserInfo = () => {}; f.app.queryBlogs = () => {}; f.app.isFollowed = () => {};
    f.app.queryUser(); await tick(); assert.equal(f.posts.length, 1); assert.equal(f.posts[0].body.pageType, type);
    f.posts[0].resolve({data: {success: true, data: {collected: true}}});
  }
});

function statistics() {
  const window = {}, reads = [];
  const axios = {create: () => ({get: (url, config) => new Promise((resolve, reject) => reads.push({url, config, resolve, reject}))})};
  vm.runInNewContext(statsSource, {window, axios, sessionStorage: {getItem: () => 'test-token'}});
  const api = window.siteStatistics, app = {statisticsState: api.initial()};
  const day = (date, uv = 0, status = 'AVAILABLE') => ({date, uv, approximate: true, status});
  const reply = (index, data) => reads[index].resolve({data: {success: true, data}});
  return {api, app, reads, day, reply};
}
function recent(f) {
  return {list: Array.from({length: 7}, (_, i) => f.day(i < 4 ? '2026-10-' + (28 + i) : '2026-11-0' + (i - 3), i === 6 ? 2 : null, i === 6 ? 'AVAILABLE' : 'NOT_COLLECTED')),
    requestedFrom: '2026-10-28', requestedTo: '2026-11-03', coverageFrom: '2026-11-03', coverageTo: '2026-11-03', rangeUv: 2, complete: false, approximate: true};
}
async function ready(f) {const p = f.api.attach(f.app); f.reply(0, f.day('2026-11-03')); f.reply(1, recent(f)); await p;}
test('statistics renders actual partial coverage and preserves null for uncollected dates', async () => {
  const f = statistics(); await ready(f); const state = f.app.statisticsState;
  assert.equal(state.daily.uv, 0); assert.equal(state.recent.complete, false); assert.equal(state.recent.list[0].uv, null);
  assert.equal(state.recent.rangeUv, 2); assert.equal(f.reads[0].config.headers.authorization, 'test-token');
});
test('changing the date invalidates old responses and old finally cannot release new pending', async () => {
  const f = statistics(); await ready(f);
  const old = f.api.daily(f.app, '2026-11-01'), next = f.api.daily(f.app, '2026-11-02');
  f.reply(2, f.day('2026-11-01', 5)); await old; assert.equal(f.app.statisticsState.dailyPending, true);
  assert.equal(f.app.statisticsState.daily, null); f.reply(3, f.day('2026-11-02', 7)); await next;
  assert.equal(f.app.statisticsState.daily.uv, 7); assert.equal(f.app.statisticsState.selectedDate, '2026-11-02');
});
test('permission or Redis failures show errors and never fabricated zero; retry recovers', async () => {
  const f = statistics(), attached = f.api.attach(f.app);
  f.reads[0].reject({response: {status: 403}}); f.reads[1].reject(new Error('Redis offline')); await attached;
  assert.match(f.app.statisticsState.dailyError, /管理员/); assert.equal(f.app.statisticsState.daily, null);
  assert.equal(f.app.statisticsState.recent, null); assert.match(f.app.statisticsState.recentError, /Redis/);
  const retry = f.api.daily(f.app); f.reply(2, f.day('2026-11-03', 2)); await retry; assert.equal(f.app.statisticsState.daily.uv, 2);
});
test('destroy and reattach protect the new statistics context', async () => {
  const f = statistics(), old = f.api.attach(f.app); f.api.destroy(f.app); const fresh = f.api.attach(f.app);
  f.reply(0, f.day('2026-11-03', 99)); f.reply(1, recent(f)); await old; assert.equal(f.app.statisticsState.dailyPending, true);
  f.reply(2, f.day('2026-11-03', 1)); f.reply(3, recent(f)); await fresh; assert.equal(f.app.statisticsState.daily.uv, 1);
});
test('malformed full-coverage claim is rejected instead of pretending seven collected days', async () => {
  const f = statistics(), attached = f.api.attach(f.app); f.reply(0, f.day('2026-11-03'));
  f.reply(1, {...recent(f), complete: true}); await attached;
  assert.equal(f.app.statisticsState.recent, null); assert.match(f.app.statisticsState.recentError, /返回异常/);
});
const vuePath = path.join(process.env.HMDP_FRONTEND_ROOT || 'D:/JavaCode/nginx-1.18.0/html/hmdp', 'js/vue.js');
test('all six pages reference the shared collector and the real Vue compiler accepts statistics template', {skip: !fs.existsSync(vuePath)}, () => {
  for (const file of ['index.html', 'shop-list.html', 'shop-detail.html', 'blog-detail.html', 'info.html', 'other-info.html']) {
    assert.match(fs.readFileSync(path.join(root, file), 'utf8'), /site-visits\.js\?v=20261103-uv-1/);
  }
  const module = {exports: {}}, warnings = [], document = {createElement: () => ({set innerHTML(value) {this.textContent = value;}})};
  vm.runInNewContext(fs.readFileSync(vuePath, 'utf8'), {module, exports: module.exports, document, Promise, setTimeout, clearTimeout, console, process: {env: {NODE_ENV: 'test'}}});
  const html = fs.readFileSync(path.join(root, 'statistics.html'), 'utf8');
  const template = html.slice(html.indexOf('<main id="app">'), html.indexOf('<script src='));
  assert.equal(typeof module.exports.compile(template, {warn: message => warnings.push(message)}).render, 'function'); assert.deepEqual(warnings, []);
});
