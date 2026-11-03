const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.join(__dirname, '..');
const linksSource = fs.readFileSync(path.join(root, 'js/blog-links.js'), 'utf8');

function linksFixture() {
  const location = {href: ''}, window = {location};
  vm.runInNewContext(linksSource, {window});
  return {api: window.blogLinks, location};
}

function page(name) {
  const html = fs.readFileSync(path.join(root, name), 'utf8');
  const inline = [...html.matchAll(/<script>\s*([\s\S]*?)<\/script>/g)][0][1];
  let options;
  const location = {href: ''}, calls = [], notifications = [], window = {location};
  vm.runInNewContext(linksSource, {window});
  const context = {
    Vue: function (value) { options = value; }, location, blogLinks: window.blogLinks,
    util: {getUrlParam: () => '2'},
    axios: {get: async () => ({data: []})},
    blogLikes: {toggle: async (app, blog) => { calls.push(['like', String(blog.id)]); }},
    feed: {load: async (app, clear) => { calls.push(['feed', clear]); }, destroy() {}},
    follows: {toggle: async () => {}, refresh: async () => {}, label: () => ''},
    blogOfUser: {load: async (app, id, reset) => { calls.push(['blogs', String(id), reset]); }},
    blogComments: {initial: () => ({})},
    token: ''
  };
  vm.runInNewContext(inline, context);
  const app = {...options.data, $message: {error: message => notifications.push(message), warning: message => notifications.push(message)}};
  for (const [key, method] of Object.entries(options.methods)) app[key] = method.bind(app);
  return {app, html, location, calls, notifications, options, window};
}

const BASE = '/blog-detail.html?v=20261103-3&id=';
const LARGE = '9007199254740993';

test('the shared link builder keeps string ids, encodes both entry modes and rejects invalid ids', () => {
  const f = linksFixture();
  assert.equal(f.api.detailUrl('4'), BASE + '4');
  assert.equal(f.api.detailUrl('4', true), BASE + '4&focus=comments');
  assert.equal(f.api.detailUrl(LARGE), BASE + LARGE);
  assert.equal(f.api.detailUrl(LARGE, true), BASE + LARGE + '&focus=comments');
  assert.equal(f.api.detailUrl(42), BASE + '42');
  for (const id of ['', '0', '-1', '1.5', 'abc', null, undefined]) {
    assert.equal(f.api.detailUrl(id), '', String(id));
    assert.equal(f.api.open(id), '');
  }
  assert.equal(f.location.href, '');
  assert.equal(f.api.open('7'), BASE + '7');
  assert.equal(f.location.href, BASE + '7');
  assert.equal(f.api.open(LARGE, true), BASE + LARGE + '&focus=comments');
  assert.equal(f.location.href, BASE + LARGE + '&focus=comments');
});

test('index, own notes, follow feed and another profile all use the same detail protocol', () => {
  for (const name of ['index.html', 'info.html', 'other-info.html']) {
    const p = page(name);
    assert.equal(p.app.blogUrl({id: '101'}), BASE + '101', name);
    assert.equal(p.app.commentUrl({id: '102'}), BASE + '102&focus=comments', name);
    assert.equal(p.app.openBlog({id: LARGE}), BASE + LARGE, name);
    assert.equal(p.location.href, BASE + LARGE, name);
  }
  const info = page('info.html');
  assert.equal(info.app.toBlogDetail({id: '103'}), BASE + '103');
  assert.equal(info.location.href, BASE + '103');
  assert.equal(info.app.blogUrl({id: '103'}) === info.app.blogUrl({id: '104'}), false);
});

test('the home page card, image, title and comment link all target the same blog id', () => {
  const p = page('index.html'), id = LARGE;
  assert.match(p.html, /class="blog-box"[^>]*@click="openBlog\(b\)"/);
  assert.match(p.html, /class="blog-img"[^>]*:href="blogUrl\(b\)"[^>]*@click\.stop/);
  assert.match(p.html, /class="blog-title"[^>]*:href="blogUrl\(b\)"[^>]*@click\.stop/);
  assert.match(p.html, /class="blog-comments"[^>]*:href="commentUrl\(b\)"[^>]*@click\.stop/);
  assert.ok(p.html.includes('{{b.comments == null ? 0 : b.comments}}'));
  assert.equal(p.app.blogUrl({id}), BASE + id);
  assert.equal(p.app.commentUrl({id}), BASE + id + '&focus=comments');
});

test('the profile and another profile list cards expose normal and comment links with counts', () => {
  for (const name of ['info.html', 'other-info.html']) {
    const p = page(name);
    assert.match(p.html, /class="blog-item"[^>]*@click="openBlog\(b\)"/);
    assert.match(p.html, /class="blog-img"[^>]*:href="blogUrl\(b\)"[^>]*@click\.stop/);
    assert.match(p.html, /class="blog-title"[^>]*:href="blogUrl\(b\)"[^>]*@click\.stop/);
    assert.match(p.html, /class="blog-comments"[^>]*:href="commentUrl\(b\)"[^>]*@click\.stop/);
    assert.ok(p.html.includes('{{b.comments == null ? 0 : b.comments}}'));
  }
  const info = page('info.html');
  assert.match(info.html, /v-for="b in blogs2"[^>]*@click="openBlog\(b\)"/);
  assert.match(info.html, /class="blog-img2"[^>]*:href="blogUrl\(b\)"[^>]*@click\.stop/);
  assert.match(info.html, /class="blog-liked feed-like"[^>]*@click\.stop="addLike\(b\)"/);
});

test('like buttons only call the shared like module and never navigate', async () => {
  for (const name of ['index.html', 'info.html']) {
    const p = page(name);
    p.location.href = '';
    await p.app.addLike({id: '77'});
    assert.deepEqual(p.calls, [['like', '77']], name);
    assert.equal(p.location.href, '', name);
  }
  const index = page('index.html');
  assert.match(index.html, /class="blog-liked"[^>]*@click\.stop="addLike\(b\)"/);
});

test('anchor entries are keyboard reachable and have explicit accessible names', () => {
  for (const name of ['index.html', 'info.html', 'other-info.html']) {
    const p = page(name);
    const anchors = [...p.html.matchAll(/<a\b[^>]*class="blog-(?:img2?|title|comments)"[^>]*>/g)].map(match => match[0]);
    assert.ok(anchors.length >= 3, name);
    for (const anchor of anchors) {
      assert.ok(anchor.includes('href='), name + ': ' + anchor);
      assert.ok(anchor.includes('click.stop'), name + ': ' + anchor);
    }
  }
});

const vuePath = path.join(process.env.HMDP_FRONTEND_ROOT || 'D:/JavaCode/nginx-1.18.0/html/hmdp', 'js/vue.js');
test('the actual Vue compiler accepts the three list templates', {skip: !fs.existsSync(vuePath)}, () => {
  const module = {exports: {}}, warnings = [];
  const document = {createElement: () => ({set innerHTML(value) { this.textContent = value.replace(/&nbsp;/g, '\u00a0').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&'); }})};
  vm.runInNewContext(fs.readFileSync(vuePath, 'utf8'), {module, exports: module.exports, document, Promise, setTimeout, clearTimeout, console, process: {env: {NODE_ENV: 'test'}}});
  for (const name of ['index.html', 'info.html', 'other-info.html']) {
    const html = fs.readFileSync(path.join(root, name), 'utf8');
    const template = html.slice(html.indexOf('<div id="app">'), html.indexOf('<script src='));
    const compiled = module.exports.compile(template, {warn: message => warnings.push(name + ': ' + message)});
    assert.equal(typeof compiled.render, 'function', name);
    // 编译后的内部控件必须真的调用 stopPropagation，避免外层卡片二次跳转。
    assert.match(compiled.render.toString(), /stopPropagation/, name);
  }
  assert.deepEqual(warnings, []);
});
