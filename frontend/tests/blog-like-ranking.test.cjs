const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const html = fs.readFileSync(path.join(__dirname, '../blog-detail.html'), 'utf8');
const script = [...html.matchAll(/<script>\s*([\s\S]*?)<\/script>/g)][0][1];

function page(axios, successful = true) {
  let options;
  const errors = [];
  vm.runInNewContext(script, {Vue: function(value) {options = value;}, util: {}, axios,
    blogLikes: {toggle: async () => successful}});
  const app = {blog: {id: 4}, likes: [], $message: {error: value => errors.push(value)}};
  for (const [name, method] of Object.entries(options.methods)) app[name] = method.bind(app);
  return {app, errors};
}

test('successful like or cancellation reloads the displayed ranked users', async () => {
  const calls = [];
  const {app} = page({get: async url => {calls.push(url); return {data: [{id: 9}, {id: 2}, {id: 7}]};}});
  await app.addLike();
  assert.deepEqual(calls, ['/blog/likes/4']);
  assert.deepEqual(app.likes, [{id: 9}, {id: 2}, {id: 7}]);
});

test('failed or ignored like does not replace the existing user list', async () => {
  let queries = 0;
  const {app} = page({get: async () => {queries++; return {data: []};}}, false);
  app.likes = [{id: 9}];
  await app.addLike();
  assert.equal(queries, 0);
  assert.deepEqual(app.likes, [{id: 9}]);
});

test('a slow older ranking response cannot overwrite a newer response', async () => {
  const responses = [];
  const {app} = page({get: () => new Promise(resolve => responses.push(resolve))});
  const first = app.queryLikeList(4);
  const second = app.queryLikeList(4);
  responses[1]({data: [{id: 2}]});
  await second;
  responses[0]({data: [{id: 9}]});
  await first;
  assert.deepEqual(app.likes, [{id: 2}]);
});
