const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const script = fs.readFileSync(path.join(__dirname, '../js/feed.js'), 'utf8');

function fixture() {
  const reads = [], errors = [], window = {};
  const axios = {get: (url, config) => new Promise((resolve, reject) => reads.push({url, config, resolve, reject}))};
  vm.runInNewContext(script, {window, axios});
  const app = {blogs2: [], params: {minTime: 0, offset: 0}, feedPending: false, feedRefreshing: false,
    feedEnded: false, feedLoaded: false, feedError: '', $message: {error: error => errors.push(error)}};
  const reply = (index, ids, minTime, offset) => reads[index].resolve({data: {
    list: ids.map(id => ({id, images: 'one.jpg,two.jpg', liked: 7, isLike: true})), minTime, offset
  }});
  return {api: window.feed, app, reads, reply, errors};
}

async function firstPage(f) {
  const request = f.api.load(f.app, true);
  f.reply(0, [9, 8], 1000, 2);
  await request;
}

test('first page replaces, next page appends, and repeated loads share the pending guard', async () => {
  const f = fixture();
  const first = f.api.load(f.app, true);
  await f.api.load(f.app);
  await f.api.load(f.app, true);
  assert.equal(f.reads.length, 1);
  assert.equal(f.app.feedPending, true);
  f.reply(0, [9, 8], 1000, 2);
  await first;
  assert.equal(f.app.blogs2[0].img, 'one.jpg');
  assert.equal(f.app.blogs2[0].isLike, true);
  const next = f.api.load(f.app);
  assert.equal(f.reads[1].url, '/blog/of/follow');
  assert.equal(f.reads[1].config.params.lastId, 1000);
  assert.equal(f.reads[1].config.params.offset, 2);
  f.reply(1, [7, 6], 1000, 4);
  await next;
  assert.deepEqual(Array.from(f.app.blogs2, blog => blog.id), [9, 8, 7, 6]);
  assert.equal(f.app.params.offset, 4);
  assert.equal(f.app.feedPending, false);
});

test('refresh supersedes an old next-page response and old finally cannot release the new pending state', async () => {
  const f = fixture();
  await firstPage(f);
  const old = f.api.load(f.app);
  const refreshed = f.api.load(f.app, true);
  assert.equal(f.reads.length, 3);
  assert.equal(f.reads[2].config.params.offset, 0);
  f.reply(1, [7, 6], 900, 2);
  await old;
  assert.equal(f.app.feedPending, true);
  assert.deepEqual(Array.from(f.app.blogs2, blog => blog.id), [9, 8]);
  f.reply(2, [12, 11], 1100, 2);
  await refreshed;
  assert.deepEqual(Array.from(f.app.blogs2, blog => blog.id), [12, 11]);
  assert.equal(f.app.feedPending, false);
});

test('a late old response cannot append to a completed refresh', async () => {
  const f = fixture();
  await firstPage(f);
  const old = f.api.load(f.app);
  const refreshed = f.api.load(f.app, true);
  f.reply(2, [12], 1100, 1);
  await refreshed;
  f.reply(1, [7, 6], 900, 2);
  await old;
  assert.deepEqual(Array.from(f.app.blogs2, blog => blog.id), [12]);
  assert.equal(f.app.params.minTime, 1100);
});

test('page failure preserves content and cursor, and retry sends the same cursor', async () => {
  const f = fixture();
  await firstPage(f);
  const failed = f.api.load(f.app);
  f.reads[1].reject(new Error('network failed'));
  await failed;
  assert.equal(f.app.feedPending, false);
  assert.equal(f.app.params.minTime, 1000);
  assert.equal(f.app.params.offset, 2);
  assert.deepEqual(Array.from(f.app.blogs2, blog => blog.id), [9, 8]);
  const retry = f.api.load(f.app);
  assert.equal(f.reads[2].config.params.offset, 2);
  f.reply(2, [7], 900, 1);
  await retry;
  assert.equal(f.app.feedError, '');
  assert.deepEqual(Array.from(f.app.blogs2, blog => blog.id), [9, 8, 7]);
});

test('failed refresh preserves the old list and its retry replaces rather than appends', async () => {
  const f = fixture();
  await firstPage(f);
  const failed = f.api.load(f.app, true);
  f.reads[1].reject(new Error('refresh failed'));
  await failed;
  assert.deepEqual(Array.from(f.app.blogs2, blog => blog.id), [9, 8]);
  const retry = f.api.load(f.app);
  assert.equal(f.reads[2].config.params.offset, 0);
  f.reply(2, [12], 1100, 1);
  await retry;
  assert.deepEqual(Array.from(f.app.blogs2, blog => blog.id), [12]);
});

test('missing database notes advance the cursor without prematurely ending the feed', async () => {
  const f = fixture();
  const first = f.api.load(f.app);
  f.reply(0, [], 1000, 2);
  await first;
  assert.equal(f.app.feedEnded, false);
  assert.equal(f.app.feedLoaded, true);
  const next = f.api.load(f.app);
  assert.equal(f.reads[1].config.params.offset, 2);
  f.reply(1, [7], 900, 1);
  await next;
  assert.equal(f.app.blogs2.length, 1);
  const end = f.api.load(f.app);
  f.reply(2, [], 0, 0);
  await end;
  assert.equal(f.app.feedEnded, true);
  await f.api.load(f.app);
  assert.equal(f.reads.length, 3);
});

test('empty inbox ends explicitly and can be refreshed later', async () => {
  const f = fixture();
  const first = f.api.load(f.app);
  f.reply(0, [], 0, 0);
  await first;
  assert.equal(f.app.feedEnded, true);
  assert.equal(f.app.blogs2.length, 0);
  const refresh = f.api.load(f.app, true);
  f.reply(1, [12], 1100, 1);
  await refresh;
  assert.equal(f.app.feedEnded, false);
});

test('append deduplicates without replacing existing objects carrying pending likes', async () => {
  const f = fixture();
  await firstPage(f);
  const original = f.app.blogs2[1];
  original.likePending = true;
  const next = f.api.load(f.app);
  f.reply(1, [8, 7], 900, 2);
  await next;
  assert.equal(f.app.blogs2[1], original);
  assert.equal(f.app.blogs2[1].likePending, true);
  assert.deepEqual(Array.from(f.app.blogs2, blog => blog.id), [9, 8, 7]);
});

test('refresh preserves the object of an in-flight like and cannot overwrite a newer completed like', async () => {
  const f = fixture();
  await firstPage(f);
  const original = f.app.blogs2[0];
  const refreshing = f.api.load(f.app, true);
  original.likePending = true;
  f.reply(1, [9], 1100, 1);
  await refreshing;
  assert.equal(f.app.blogs2[0], original);
  assert.equal(original.likePending, true);
  original.liked = 6; original.isLike = false; original.likePending = false;
  assert.equal(f.app.blogs2[0].liked, 6);
  const nextRefresh = f.api.load(f.app, true);
  original.liked = 8; original.isLike = true;
  f.reply(2, [9], 1100, 1);
  await nextRefresh;
  assert.equal(f.app.blogs2[0], original);
  assert.equal(f.app.blogs2[0].liked, 8);
  assert.equal(f.app.blogs2[0].isLike, true);
});

test('invalid nonadvancing cursors fail and a destroyed page ignores late results', async () => {
  const f = fixture();
  await firstPage(f);
  const invalid = f.api.load(f.app);
  f.reply(1, [7, 6], 1000, 2);
  await invalid;
  assert.equal(f.app.feedPending, false);
  assert.match(f.app.feedError, /未前进/);
  const late = f.api.load(f.app);
  f.api.destroy(f.app);
  f.reply(2, [7], 900, 1);
  await late;
  assert.deepEqual(Array.from(f.app.blogs2, blog => blog.id), [9, 8]);
});

test('the profile keeps follow notes, scroll loading, detail navigation and shared likes wired', async () => {
  const html = fs.readFileSync(path.join(__dirname, '../info.html'), 'utf8');
  const inline = [...html.matchAll(/<script>\s*([\s\S]*?)<\/script>/g)][0][1];
  let options;
  const calls = [], location = {href: ''};
  vm.runInNewContext(inline, {
    Vue: function(value) {options = value;}, location,
    feed: {load: async (app, clear) => calls.push(['feed', clear]), destroy() {}},
    blogLikes: {toggle: async (app, blog) => calls.push(['like', blog.id])}
  });
  const app = {...options.data};
  for (const [name, method] of Object.entries(options.methods)) app[name] = method.bind(app);
  app.activeName = '4';
  app.handleClick({name: '4'});
  await app.onScroll({target: {scrollTop: 20, offsetHeight: 100, scrollHeight: 120}});
  await app.addLike({id: 9});
  app.toBlogDetail({id: 9});
  assert.deepEqual(calls, [['feed', true], ['feed', undefined], ['like', 9]]);
  assert.match(location.href, /blog-detail\.html\?id=9/);
  assert.match(html, /暂无关注动态/);
  assert.match(html, /已经到底了/);
  assert.match(html, /正在加载动态/);
  assert.match(html, /:disabled="b.likePending"/);
});

const vuePath = path.join(process.env.HMDP_FRONTEND_ROOT || 'D:/JavaCode/nginx-1.18.0/html/hmdp', 'js/vue.js');
test('actual Vue observes a later like pending change after a feed refresh', {skip: !fs.existsSync(vuePath)}, async () => {
  const vueModule = {exports: {}};
  vm.runInNewContext(fs.readFileSync(vuePath, 'utf8'), {
    module: vueModule, exports: vueModule.exports, Promise, setTimeout, clearTimeout, console, process: {env: {NODE_ENV: 'test'}}
  });
  const Vue = vueModule.exports;
  const f = fixture();
  const app = new Vue({data: {blogs2: [], params: {minTime: 0, offset: 0}, feedPending: false,
    feedRefreshing: false, feedEnded: false, feedLoaded: false, feedError: ''}});
  app.$message = f.app.$message;
  const first = f.api.load(app, true);
  f.reply(0, [9], 1000, 1);
  await first;
  const refresh = f.api.load(app, true);
  f.reply(1, [9], 1000, 1);
  await refresh;
  const states = [];
  app.$watch(() => app.blogs2[0].likePending, value => states.push(value));
  app.blogs2[0].likePending = true;
  await Vue.nextTick();
  app.blogs2[0].likePending = false;
  await Vue.nextTick();
  assert.deepEqual(states, [true, false]);
  app.$destroy();
});
