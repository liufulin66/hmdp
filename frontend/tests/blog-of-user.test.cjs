const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.join(__dirname, '..');
const source = fs.readFileSync(path.join(root, 'js/blog-of-user.js'), 'utf8');

function fixture() {
  const reads = [], errors = [], window = {};
  const axios = {get: (url, config) => new Promise((resolve, reject) => reads.push({url, config, resolve, reject}))};
  vm.runInNewContext(source, {window, axios});
  const app = {blogs: [], blogsPending: false, blogsLoaded: false, blogsEnded: false, blogsCurrent: 0,
    blogsError: '', $message: {error: message => errors.push(message)}};
  const reply = (index, ids, options = {}) => reads[index].resolve({data: ids.map(id =>
    ({id, images: 'one.jpg,two.jpg', comments: 0, liked: 1, title: 'note-' + id, ...options}))});
  const ids = list => Array.from(list, blog => String(blog.id));
  return {api: window.blogOfUser, app, reads, errors, reply, ids};
}

test('the first page asks for the target user and a full page enables load more', async () => {
  const f = fixture();
  const first = f.api.load(f.app, '2', true);
  assert.equal(f.reads[0].url, '/blog/of/user');
  assert.equal(f.reads[0].config.params.id, '2');
  assert.equal(f.reads[0].config.params.current, 1);
  assert.equal(f.app.blogsPending, true);
  assert.equal(f.reads[0].config.headers['Cache-Control'], 'no-cache');
  f.reply(0, [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
  await first;
  assert.deepEqual(f.ids(f.app.blogs), ['1', '2', '3', '4', '5', '6', '7', '8', '9', '10']);
  assert.equal(f.app.blogs[0].img, 'one.jpg');
  assert.equal(f.app.blogsLoaded, true);
  assert.equal(f.app.blogsEnded, false);
  assert.equal(f.app.blogsCurrent, 1);
  assert.equal(f.app.blogsPending, false);
  const next = f.api.load(f.app, '2');
  assert.equal(f.reads[1].config.params.id, '2');
  assert.equal(f.reads[1].config.params.current, 2);
  f.reply(1, [11, 12]);
  await next;
  assert.deepEqual(f.ids(f.app.blogs).slice(-2), ['11', '12']);
  assert.equal(f.app.blogsCurrent, 2);
  assert.equal(f.app.blogsEnded, true);
});

test('an empty first page is a completed empty state, not an error', async () => {
  const f = fixture();
  const first = f.api.load(f.app, '4', true);
  f.reply(0, []);
  await first;
  assert.equal(f.app.blogsLoaded, true);
  assert.equal(f.app.blogsEnded, true);
  assert.equal(f.app.blogsCurrent, 1);
  assert.deepEqual(f.ids(f.app.blogs), []);
  assert.equal(f.app.blogsError, '');
  assert.deepEqual(f.errors, []);
});

test('a failed next page keeps the old list and cursor, and retry sends the same page', async () => {
  const f = fixture();
  const first = f.api.load(f.app, '2', true);
  f.reply(0, [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
  await first;
  const failed = f.api.load(f.app, '2');
  f.reads[1].reject(new Error('offline'));
  await failed;
  assert.deepEqual(f.ids(f.app.blogs), ['1', '2', '3', '4', '5', '6', '7', '8', '9', '10']);
  assert.equal(f.app.blogsCurrent, 1);
  assert.equal(f.app.blogsPending, false);
  assert.equal(f.app.blogsError, 'offline');
  assert.deepEqual(f.errors, ['offline']);
  const retry = f.api.load(f.app, '2');
  assert.equal(f.reads[2].config.params.id, '2');
  assert.equal(f.reads[2].config.params.current, 2);
  f.reply(2, [11]);
  await retry;
  assert.equal(f.app.blogsError, '');
  assert.deepEqual(f.ids(f.app.blogs).slice(-1), ['11']);
  assert.equal(f.app.blogsCurrent, 2);
  assert.equal(f.app.blogsEnded, true);
});

test('switching the target user clears the old list and ignores the old response', async () => {
  const f = fixture();
  const two = f.api.load(f.app, '2', true);
  const three = f.api.load(f.app, '3');
  assert.deepEqual(f.ids(f.app.blogs), []);
  assert.equal(f.reads[1].config.params.id, '3');
  assert.equal(f.reads[1].config.params.current, 1);
  f.reply(1, [30]);
  await three;
  f.reply(0, [20]);
  await two;
  assert.deepEqual(f.ids(f.app.blogs), ['30']);
  assert.equal(f.app.blogsCurrent, 1);
  assert.equal(f.app.blogsLoaded, true);
  assert.equal(f.app.blogsPending, false);
});

test('duplicate loads are ignored and a refresh may replace an in-flight next page', async () => {
  const f = fixture();
  const first = f.api.load(f.app, '2', true);
  await f.api.load(f.app, '2');
  await f.api.load(f.app, '2', true);
  assert.equal(f.reads.length, 1);
  f.reply(0, [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
  await first;
  const more = f.api.load(f.app, '2');
  const refreshed = f.api.load(f.app, '2', true);
  assert.equal(f.reads.length, 3);
  f.reply(2, [13]);
  await refreshed;
  f.reply(1, [14]);
  await more;
  assert.deepEqual(f.ids(f.app.blogs), ['13']);
  assert.equal(f.app.blogsCurrent, 1);
  assert.equal(f.app.blogsPending, false);
});

test('appending deduplicates by string id and preserves the server order', async () => {
  const f = fixture();
  const first = f.api.load(f.app, '2', true);
  f.reply(0, [3, 2, 4, 5, 6, 7, 8, 9, 10, 11]);
  await first;
  const next = f.api.load(f.app, '2');
  f.reply(1, [2, 1]);
  await next;
  assert.deepEqual(f.ids(f.app.blogs), ['3', '2', '4', '5', '6', '7', '8', '9', '10', '11', '1']);
});

test('invalid target ids and oversized responses are rejected without replacing data', async () => {
  const f = fixture();
  assert.equal(await f.api.load(f.app, 'abc'), false);
  assert.equal(f.app.blogsError, '用户ID无效');
  assert.equal(f.reads.length, 0);
  const first = f.api.load(f.app, '2', true);
  f.reply(0, [1, 2]);
  await first;
  const bad = f.api.load(f.app, '2', true);
  f.reads[1].resolve({data: Array.from({length: 11}, (_, i) => ({id: i + 1}))});
  await bad;
  assert.deepEqual(f.ids(f.app.blogs), ['1', '2']);
  assert.match(f.app.blogsError, /返回异常/);
  assert.equal(f.app.blogsPending, false);
});

test('a destroyed page ignores its late response and never touches the list', async () => {
  const f = fixture();
  const first = f.api.load(f.app, '2', true);
  f.api.destroy(f.app);
  f.reply(0, [1]);
  await first;
  assert.deepEqual(f.ids(f.app.blogs), []);
});

function page() {
  const html = fs.readFileSync(path.join(root, 'other-info.html'), 'utf8');
  const inline = [...html.matchAll(/<script>\s*([\s\S]*?)<\/script>/g)][0][1];
  let options;
  const calls = [], location = {href: ''};
  vm.runInNewContext(inline, {
    siteVisits: {afterRender() {}},
    Vue: function (value) { options = value; }, location,
    util: {getUrlParam: () => '2'},
    axios: {get: async () => ({data: []})},
    follows: {toggle: async () => {}, refresh: async () => {}, label: () => ''},
    blogLinks: {detailUrl: () => '', open: () => ''},
    blogOfUser: {load: async (app, id, reset) => { calls.push(['load', String(id), reset]); }, destroy() {}},
    blogComments: {initial: () => ({})}
  });
  const app = {...options.data, $message: {error() {}, warning() {}}};
  for (const [key, method] of Object.entries(options.methods)) app[key] = method.bind(app);
  return {app, html, calls, options, location};
}

test('another profile sends the target user id, never the logged-in user id', async () => {
  const p = page();
  p.app.user = {id: '9007199254740993'};
  p.app.loginUser = {id: '1'};
  await p.app.queryBlogs(true);
  assert.deepEqual(p.calls, [['load', '9007199254740993', true]]);
});

test('another profile exposes loading, empty, retry and load-more states for real paging', () => {
  const p = page();
  for (const state of ['blogsPending', 'blogsError', 'blogsLoaded', 'blogsEnded', 'blogsCurrent']) assert.ok(state in p.options.data, state);
  assert.match(p.html, /正在加载笔记…/);
  assert.match(p.html, /暂无笔记/);
  assert.match(p.html, /@click="queryBlogs\(\)">重试/);
  assert.match(p.html, /@click="queryBlogs\(\)">加载更多/);
  assert.equal(/v-for="b in \[/.test(p.html), false);
});
