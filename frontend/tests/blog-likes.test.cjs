const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const script = fs.readFileSync(path.join(__dirname, '../js/blog-likes.js'), 'utf8');
const tick = () => new Promise(resolve => setImmediate(resolve));

function events() {
  const listeners = new Map();
  return {
    addEventListener(name, fn) {
      if (!listeners.has(name)) listeners.set(name, new Set());
      listeners.get(name).add(fn);
    },
    removeEventListener(name, fn) { listeners.get(name)?.delete(fn); },
    emit(name, event = {}) { listeners.get(name)?.forEach(fn => fn(event)); }
  };
}

function fixture() {
  const errors = [], calls = [];
  const window = events();
  const document = Object.assign(events(), {visibilityState: 'visible'});
  const server = {liked: 10, isLike: false};
  const axios = {
    async put(url) { calls.push({method: 'PUT', url}); Object.assign(server, {liked: 11, isLike: true}); return {data: {...server}}; },
    async delete(url) { calls.push({method: 'DELETE', url}); Object.assign(server, {liked: 10, isLike: false}); return {data: {...server}}; },
    async get(url) { calls.push({method: 'GET', url}); return {data: {...server}}; }
  };
  vm.runInNewContext(script, {window, document, axios, console: {warn() {}}});
  const app = {$set(target, key, value) {target[key] = value;}, $message: {error(value) {errors.push(value);}}};
  return {api: window.blogLikes, app, window, document, axios, server, errors, calls};
}

test('two consecutive detail clicks send PUT then DELETE and update both fields', async () => {
  const {api, app, calls} = fixture();
  const blog = {id: 4, liked: 10, isLike: false};
  await api.toggle(app, blog);
  assert.equal(blog.liked, 11);
  assert.equal(blog.isLike, true);
  assert.equal(blog.likePending, false);
  await api.toggle(app, blog);
  assert.equal(blog.liked, 10);
  assert.equal(blog.isLike, false);
  assert.deepEqual(calls.map(call => call.method), ['PUT', 'DELETE']);
});

test('in-flight clicks are ignored and network failure releases the button', async () => {
  const f = fixture();
  const blog = {id: 4, liked: 10, isLike: false};
  let reject;
  let requests = 0;
  f.axios.put = () => {requests++; return new Promise((resolve, failure) => {reject = failure;});};
  const pending = f.api.toggle(f.app, blog);
  await f.api.toggle(f.app, blog);
  assert.equal(requests, 1);
  reject(new Error('network failure'));
  await pending;
  assert.equal(blog.likePending, false);
  assert.equal(blog.liked, 10);
  assert.equal(blog.isLike, false);
  assert.deepEqual(f.errors, ['network failure']);
});

test('returning from browser history refreshes the visible list without replacing it', async () => {
  const f = fixture();
  const blogs = [{id: 4, liked: 10, isLike: false}];
  const original = blogs[0];
  const stop = f.api.watch(f.app, () => blogs);
  Object.assign(f.server, {liked: 11, isLike: true}); // Detail page changed the server.
  f.window.emit('pageshow', {persisted: true});
  f.document.emit('visibilitychange'); // Consecutive restore events share one request.
  await tick();
  assert.equal(blogs[0], original);
  assert.equal(blogs[0].liked, 11);
  assert.equal(blogs[0].isLike, true);
  assert.equal(f.calls.length, 1);
  assert.equal(f.calls[0].url, '/blog/4');
  stop();
  f.window.emit('pageshow', {persisted: true});
  await tick();
  assert.equal(f.calls.length, 1);
});

test('an old refresh response cannot overwrite a newer like operation', async () => {
  const f = fixture();
  const blog = {id: 4, liked: 10, isLike: false};
  let finishRead;
  f.axios.get = () => new Promise(resolve => {finishRead = resolve;});
  const pendingRead = f.api.refresh(f.app, [blog]);
  await f.api.toggle(f.app, blog);
  finishRead({data: {liked: 10, isLike: false}});
  await pendingRead;
  assert.equal(blog.liked, 11);
  assert.equal(blog.isLike, true);
});

test('profile lists reuse one query for copies of the same blog', async () => {
  const f = fixture();
  const first = {id: 4, liked: 10, isLike: false};
  const second = {...first};
  Object.assign(f.server, {liked: 11, isLike: true});
  await f.api.refresh(f.app, [first, second]);
  assert.equal(f.calls.length, 1);
  assert.equal(first.isLike, true);
  assert.equal(second.isLike, true);
});

const frontendRoot = process.env.HMDP_FRONTEND_ROOT || 'D:/JavaCode/nginx-1.18.0/html/hmdp';
const vuePath = path.join(frontendRoot, 'js/vue.js');
test('the deployed Vue 2 runtime observes fields added to an initially empty detail object',
  {skip: !fs.existsSync(vuePath)}, async () => {
    // Load the exact deployed Vue build without Node's persistent MessageChannel ports.
    const vueModule = {exports: {}};
    vm.runInNewContext(fs.readFileSync(vuePath, 'utf8'), {
      module: vueModule, exports: vueModule.exports,
      Promise, setTimeout, clearTimeout, console, process: {env: {NODE_ENV: 'test'}}
    });
    const Vue = vueModule.exports;
    const f = fixture();
    const app = new Vue({data: {blog: {id: 4}}});
    app.$message = f.app.$message;
    let renders = 0;
    app.$watch('blog.isLike', () => {renders++;});
    await f.api.toggle(app, app.blog);
    await Vue.nextTick();
    assert.equal(app.blog.isLike, true);
    await f.api.toggle(app, app.blog);
    await Vue.nextTick();
    assert.equal(app.blog.isLike, false);
    assert.equal(app.blog.likePending, false);
    assert.equal(renders, 2);
    app.$destroy();
  });
