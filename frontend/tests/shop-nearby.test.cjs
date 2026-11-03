const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../js/shop-nearby.js'), 'utf8');
function fixture() {
  const reads = [], errors = [], window = {};
  const axios = {get: (url, config) => new Promise((resolve, reject) => reads.push({url, config, resolve, reject}))};
  vm.runInNewContext(source, {window, axios});
  const app = {shops: [], params: {typeId: 1, current: 1, radius: 5000, x: null, y: null},
    pending: false, loaded: false, hasMore: true, error: '', locationMode: 'classification', $message: {error: error => errors.push(error)}};
  const reply = (index, ids, hasMore = true) => reads[index].resolve({data: {list: ids.map(id => ({id, images: 'one.jpg,two.jpg'})), hasMore}});
  return {api: window.shopNearby, app, reads, errors, reply};
}
test('ordinary mode omits coordinates, duplicate bottom events do not duplicate requests, next page appends', async () => {
  const f = fixture(), first = f.api.load(f.app);
  await f.api.load(f.app); await f.api.load(f.app, true);
  assert.equal(f.reads.length, 1); assert.equal('x' in f.reads[0].config.params, false);
  assert.equal(f.reads[0].config.params.pageInfo, true);
  f.reply(0, [1, 2]); await first;
  const next = f.api.load(f.app); assert.equal(f.reads[1].config.params.current, 2);
  f.reply(1, [3], false); await next;
  assert.deepEqual(Array.from(f.app.shops, shop => shop.id), [1, 2, 3]);
  assert.equal(f.app.shops[0].img, 'one.jpg');
  await f.api.load(f.app); assert.equal(f.reads.length, 2);
});
test('category, coordinates and radius changes invalidate old responses and old finally cannot unlock a new request', async () => {
  const f = fixture(), old = f.api.load(f.app);
  f.app.params.typeId = 2; f.app.params.radius = 1000;
  const fresh = f.api.coordinates(f.app, 'manual', 0, 0);
  assert.equal(f.reads[1].config.params.current, 1); assert.equal(f.reads[1].config.params.x, 0);
  f.reply(0, [99]); await old; assert.equal(f.app.pending, true); assert.equal(f.app.shops.length, 0);
  f.reply(1, [2], false); await fresh;
  assert.deepEqual(Array.from(f.app.shops, shop => shop.id), [2]); assert.equal(f.app.pending, false);
});
test('late old result cannot overwrite already completed condition change', async () => {
  const f = fixture(), old = f.api.load(f.app);
  f.app.params.radius = 3000; const fresh = f.api.load(f.app, true);
  f.reply(1, [2], false); await fresh; f.reply(0, [99]); await old;
  assert.deepEqual(Array.from(f.app.shops, shop => shop.id), [2]); assert.equal(f.app.hasMore, false);
});
test('failed next page keeps content and retries the same page; failed refresh retries replacement', async () => {
  const f = fixture(), first = f.api.load(f.app); f.reply(0, [1]); await first;
  const failed = f.api.load(f.app); f.reads[1].reject(new Error('offline')); await failed;
  assert.equal(f.app.pending, false); assert.equal(f.app.shops.length, 1);
  const retry = f.api.load(f.app); assert.equal(f.reads[2].config.params.current, 2); f.reply(2, [2]); await retry;
  const refresh = f.api.load(f.app, true); f.reads[3].reject(new Error('offline')); await refresh;
  const refreshRetry = f.api.load(f.app); assert.equal(f.reads[4].config.params.current, 1);
  f.reply(4, [3], false); await refreshRetry;
  assert.deepEqual(Array.from(f.app.shops, shop => shop.id), [3]);
});
test('empty filtered page can still have more; page cap ends loading', async () => {
  const f = fixture(), first = f.api.load(f.app); f.reply(0, [], true); await first;
  assert.equal(f.app.hasMore, true);
  for (let page = 2; page <= 20; page++) { const load = f.api.load(f.app); f.reply(page - 1, [], true); await load; }
  await f.api.load(f.app); assert.equal(f.reads.length, 20); assert.equal(f.app.hasMore, false);
});
test('invalid manual input sends nothing; ordinary mode clears demo; zero distance displays', async () => {
  const f = fixture();
  for (const [x, y] of [['', 0], [0, ''], [Infinity, 0], [181, 0], [0, 86]]) {
    assert.equal(await f.api.coordinates(f.app, 'manual', x, y), false);
  }
  assert.equal(f.reads.length, 0);
  const demo = f.api.coordinates(f.app, 'demo', 120.149993, 30.334229); f.reply(0, [1]); await demo;
  const ordinary = f.api.coordinates(f.app, 'classification');
  assert.equal('x' in f.reads[1].config.params, false); f.reply(1, [], false); await ordinary;
  assert.equal(f.app.locationMode, 'classification'); assert.equal(f.api.distance(0), '0.0 m');
});
test('destroyed page ignores its response and malformed response can be retried', async () => {
  const f = fixture(), bad = f.api.load(f.app); f.reads[0].resolve({data: []}); await bad;
  assert.equal(f.app.pending, false); assert.equal(f.app.loaded, false);
  const pending = f.api.load(f.app); f.api.destroy(f.app); f.reply(1, [1]); await pending;
  assert.equal(f.app.shops.length, 0);
});
test('shared parameter serialization preserves legal zero coordinates and escapes values', () => {
  const axios = {defaults: {}, interceptors: {request: {use() {}}, response: {use() {}}}};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../js/common.js'), 'utf8'),
    {axios, sessionStorage: {getItem: () => null}, window: {location: {search: ''}}, console, setTimeout});
  const query = new URLSearchParams(axios.defaults.paramsSerializer({x: 0, y: 0, flag: false, name: 'a&b', absent: null, empty: ''}));
  assert.equal(query.get('x'), '0'); assert.equal(query.get('y'), '0'); assert.equal(query.get('flag'), 'false');
  assert.equal(query.get('name'), 'a&b'); assert.equal(query.has('absent'), false); assert.equal(query.has('empty'), false);
});
