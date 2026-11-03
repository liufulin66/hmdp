const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.join(__dirname, '..');
const source = fs.readFileSync(path.join(root, 'js/blog-detail.js'), 'utf8');

function moduleFixture() {
  const timers = [], cleared = [];
  const window = {
    setTimeout: (fn, ms) => { timers.push({fn, ms}); return timers.length; },
    clearTimeout: id => cleared.push(id)
  };
  vm.runInNewContext(source, {window});
  return {api: window.blogDetail, timers, cleared};
}

function events() {
  const listeners = new Map();
  return {
    addEventListener(name, fn, options) {
      if (!listeners.has(name)) listeners.set(name, new Set());
      listeners.get(name).add(fn);
    },
    removeEventListener(name, fn) { listeners.get(name)?.delete(fn); },
    emit(name, event = {}) { listeners.get(name)?.forEach(fn => fn(event)); },
    count(name) { return listeners.get(name)?.size || 0; }
  };
}

function image(complete) {
  const e = events();
  return Object.assign(e, {complete});
}

test('the comment section is scrolled after nextTick without focusing the input or loading comments', () => {
  const f = moduleFixture(), queue = [], scrolls = [];
  let focused = 0;
  const app = {$refs: {commentInput: {focus: () => focused++}}};
  const handle = f.api.focusComments(app, {nextTick: fn => queue.push(fn)});
  assert.equal(queue.length, 1);
  app.$refs.commentsSection = {scrollIntoView: options => scrolls.push(options)};
  queue.shift()();
  assert.equal(scrolls.length, 1);
  assert.equal(scrolls[0].block, 'start');
  assert.equal(focused, 0);
  assert.equal(queue.length, 0);
  assert.equal(typeof handle.cancel, 'function');
});

test('a comment section rendered later is still found on a retry tick', () => {
  const f = moduleFixture(), queue = [], scrolls = [];
  const app = {$refs: {}};
  f.api.focusComments(app, {nextTick: fn => queue.push(fn), retries: 2});
  queue.shift()();
  assert.equal(queue.length, 1);
  app.$refs.commentsSection = {scrollIntoView: options => scrolls.push(options)};
  queue.shift()();
  assert.equal(scrolls.length, 1);
  assert.equal(scrolls[0].block, 'start');
});

test('images that finish loading reposition the comments; user scrolling stops that tracking', () => {
  const f = moduleFixture(), queue = [], scrolls = [];
  const app = {$refs: {commentsSection: {scrollIntoView: options => scrolls.push(options)}}};
  const container = events(), first = image(false), second = image(false);
  container.querySelectorAll = () => [first, second];
  app.$refs.detailScroll = container;
  f.api.focusComments(app, {nextTick: fn => queue.push(fn)});
  queue.shift()();
  assert.equal(scrolls.length, 1);
  assert.equal(first.count('load'), 1);
  first.emit('load');
  second.emit('load');
  assert.equal(scrolls.length, 3);
  container.emit('wheel');
  assert.equal(first.count('load'), 0);
  assert.equal(container.count('wheel'), 0);
  first.emit('load');
  assert.equal(scrolls.length, 3);
  assert.deepEqual(f.cleared, [1]);
});

test('the tracking window ends on its timer and cancel removes pending listeners', () => {
  const f = moduleFixture(), queue = [], scrolls = [];
  const app = {$refs: {commentsSection: {scrollIntoView: options => scrolls.push(options)}}};
  const container = events(), pending = image(false);
  container.querySelectorAll = () => [pending];
  app.$refs.detailScroll = container;
  f.api.focusComments(app, {nextTick: fn => queue.push(fn)});
  queue.shift()();
  assert.equal(f.timers[0].ms, 3000);
  f.timers.shift().fn();
  assert.equal(pending.count('load'), 0);
  pending.emit('load');
  assert.equal(scrolls.length, 1);

  const secondQueue = [], active = image(false), secondContainer = events();
  secondContainer.querySelectorAll = () => [active];
  const secondApp = {$refs: {commentsSection: {scrollIntoView: options => scrolls.push(options)}, detailScroll: secondContainer}};
  const handle = f.api.focusComments(secondApp, {nextTick: fn => secondQueue.push(fn)});
  secondQueue.shift()();
  handle.cancel();
  assert.equal(active.count('load'), 0);
  active.emit('load');
  assert.equal(scrolls.length, 2);
  assert.equal(f.cleared.length >= 2, true);
});

test('cancel before the first tick prevents any scrolling', () => {
  const f = moduleFixture(), queue = [], scrolls = [];
  const app = {$refs: {}};
  const handle = f.api.focusComments(app, {nextTick: fn => queue.push(fn)});
  handle.cancel();
  app.$refs.commentsSection = {scrollIntoView: options => scrolls.push(options)};
  queue.shift()();
  assert.deepEqual(scrolls, []);
});

function detailPage(params, makeAxios) {
  const html = fs.readFileSync(path.join(root, 'blog-detail.html'), 'utf8');
  const inline = [...html.matchAll(/<script>\s*([\s\S]*?)<\/script>/g)][0][1];
  let options;
  const calls = {attach: [], focus: 0, messages: [], cancel: 0}, location = {href: ''};
  const axios = makeAxios ? makeAxios() : {get: async url => {
    if (url === '/blog/4') return {data: {id: '4', images: 'a.jpg,b.jpg', shopId: '1', comments: 2}};
    if (url === '/shop/1') return {data: {images: 's.jpg'}};
    if (url === '/blog/likes/4') return {data: []};
    return {data: []};
  }};
  vm.runInNewContext(inline, {
    siteVisits: {afterRender() {}},
    Vue: function (value) { options = value; }, location,
    util: {getUrlParam: name => params[name] || ''},
    axios, token: '',
    blogComments: {initial: () => ({loaded: false, countVersion: '0', commentCount: 0}),
      attach: (app, id) => { calls.attach.push(String(id)); return Promise.resolve(true); }, destroy() {}},
    blogDetail: {focusComments: () => { calls.focus++; return {cancel() { calls.cancel++; }}; }},
    blogLikes: {watch: () => () => {}, toggle: async () => false},
    follows: {toggle: async () => {}, refresh: async () => {}, label: () => ''}
  });
  const app = {...options.data, $message: {error: message => calls.messages.push(message), warning() {}},
    $nextTick: fn => fn(), $refs: {}, blogRequestId: 0};
  for (const [key, method] of Object.entries(options.methods)) app[key] = method.bind(app);
  app.init = () => {};
  app.queryShopById = () => {};
  app.queryLikeList = () => Promise.resolve();
  app.queryLoginUser = () => {};
  return {app, options, calls, location, html};
}

const flush = () => new Promise(resolve => setImmediate(resolve));

test('focus=comments schedules one scroll and reuses the existing comment module', async () => {
  const p = detailPage({id: '4', focus: 'comments'});
  p.options.created.call(p.app);
  await flush();
  assert.deepEqual(p.calls.attach, ['4']);
  assert.equal(p.calls.focus, 1);
  assert.equal(p.app.blogError, '');
  assert.equal(p.app.focusCommentsOnLoad, true);
  assert.equal(p.app.commentFocus.cancel instanceof Function, true);
});

test('ordinary detail entry and invalid ids do not try to locate comments', async () => {
  const ordinary = detailPage({id: '4'});
  ordinary.options.created.call(ordinary.app);
  await flush();
  assert.deepEqual(ordinary.calls.attach, ['4']);
  assert.equal(ordinary.calls.focus, 0);

  const invalid = detailPage({id: 'not-a-number', focus: 'comments'});
  invalid.options.created.call(invalid.app);
  await flush();
  assert.equal(invalid.app.blogError, '笔记ID无效');
  assert.deepEqual(invalid.calls.attach, []);
  assert.deepEqual(invalid.calls.messages, ['笔记ID无效']);
  assert.equal(invalid.calls.focus, 0);
});

test('a failed blog request reports the error and does not scroll to comments', async () => {
  const p = detailPage({id: '4', focus: 'comments'}, () => ({
    get: async url => {
      if (url === '/blog/4') throw '笔记不存在';
      return {data: []};
    }
  }));
  p.options.created.call(p.app);
  await flush();
  assert.equal(p.app.blogError, '笔记不存在');
  assert.deepEqual(p.calls.messages, ['笔记不存在']);
  assert.equal(p.calls.focus, 0);
  assert.deepEqual(p.calls.attach, ['4']);
});

test('the bottom comment button still scrolls and focuses only on a deliberate click', () => {
  const p = detailPage({id: '4'});
  const calls = [];
  p.app.$refs = {commentsSection: {scrollIntoView: options => calls.push(['scroll', options])},
    commentInput: {focus: () => calls.push(['focus'])}};
  p.app.focusComment();
  assert.equal(calls.length, 2);
  assert.equal(calls[0][0], 'scroll');
  assert.equal(calls[0][1].block, 'start');
  assert.equal(calls[0][1].behavior, 'smooth');
  assert.equal(calls[1][0], 'focus');
  p.app.blogError = '笔记不存在';
  p.app.focusComment();
  assert.equal(calls.length, 2);
});

test('the detail template exposes the scroll container and hides comments on an error', () => {
  const p = detailPage({id: '4'});
  assert.match(p.html, /ref="detailScroll"/);
  assert.match(p.html, /class="blog-comments" ref="commentsSection" v-if="!blogError"/);
  assert.match(p.html, /focusCommentsOnLoad\) this\.commentFocus = blogDetail\.focusComments\(this\)/);
  assert.match(p.html, /blog-detail\.js\?v=20261103-3/);
});
