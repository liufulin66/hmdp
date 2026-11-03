const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../js/blog-comments.js'), 'utf8');
function view(id, options = {}) {
  return {id: String(id), blogId: '4', parentId: '0', answerId: '0', content: 'text', createTime: '2026-11-03T08:00:00.123456',
    author: {id: '1', nickName: 'author', icon: ''}, answerUser: null, deleted: false, canDelete: true, canReply: true, replyCount: 0, ...options};
}
function fixture(loggedIn = true) {
  const reads = [], posts = [], deletes = [], errors = [], warnings = []; let counter = 0;
  const window = {crypto: {randomUUID: () => 'request-' + ++counter}, location: {href: ''}};
  const axios = {
    get: (url, config) => new Promise((resolve, reject) => reads.push({url, config, resolve, reject})),
    post: (url, body) => new Promise((resolve, reject) => posts.push({url, body, resolve, reject})),
    delete: url => new Promise((resolve, reject) => deletes.push({url, resolve, reject}))
  };
  vm.runInNewContext(source, {window, axios, token: loggedIn ? 'token' : ''});
  const app = {blog: {}, $message: {error: message => errors.push(message), warning: message => warnings.push(message)}};
  const page = (index, list, options = {}) => reads[index].resolve({data: {list, nextCursor: null, hasMore: false, commentCount: list.length, countVersion: '1', root: null, ...options}});
  const result = (comment, root = comment, options = {}) => ({data: {comment, root, commentCount: 1, countVersion: '2', ...options}});
  return {api: window.blogComments, window, app, reads, posts, deletes, errors, warnings, page, result};
}
async function initialized(f, list = [], version = '1') {
  const pending = f.api.attach(f.app, '4'); f.page(0, list, {countVersion: version}); await pending;
}
test('root paging guards repeat loads, retains cursor after failure, retries and deduplicates', async () => {
  const f = fixture(), first = f.api.attach(f.app, '4');
  await f.api.loadRoots(f.app); await f.api.loadRoots(f.app, true); assert.equal(f.reads.length, 1);
  f.page(0, [view(1)], {hasMore: true, nextCursor: 'first'}); await first;
  const failed = f.api.loadRoots(f.app); f.reads[1].reject(new Error('offline')); await failed;
  assert.equal(f.app.commentState.nextCursor, 'first'); assert.equal(f.app.commentState.list.length, 1); assert.equal(f.app.commentState.pending, false);
  const retry = f.api.loadRoots(f.app); assert.equal(f.reads[2].config.params.cursor, 'first');
  f.page(2, [view(1), view(2)], {countVersion: '2', commentCount: 2}); await retry;
  assert.deepEqual(Array.from(f.app.commentState.list, row => row.id), ['1', '2']);
  await f.api.loadRoots(f.app); assert.equal(f.reads.length, 3);
});
test('timeout retry preserves the original submission id and payload; editing creates a new submission', async () => {
  const f = fixture(); await initialized(f); f.app.commentState.draft = 'first';
  const sent = f.api.submit(f.app); await f.api.submit(f.app); assert.equal(f.posts.length, 1);
  f.posts[0].reject(new Error('timeout')); await sent; assert.equal(f.app.commentState.draft, 'first'); assert.equal(f.app.commentState.submitPending, false);
  const retry = f.api.submit(f.app); assert.equal(JSON.stringify(f.posts[1].body), JSON.stringify(f.posts[0].body));
  f.posts[1].reject(new Error('uncertain')); await retry;
  f.app.commentState.draft = 'edited'; const edited = f.api.submit(f.app);
  assert.notEqual(f.posts[2].body.requestId, f.posts[0].body.requestId); f.posts[2].resolve(f.result(view(10, {content: 'edited'}))); await edited;
  assert.equal(f.app.commentState.draft, ''); assert.deepEqual(Array.from(f.app.commentState.list, row => row.id), ['10']);
});
test('an old page cannot remove a just-published root or roll back the count version', async () => {
  const f = fixture(), page = f.api.attach(f.app, '9007199254740993');
  f.app.commentState.draft = 'new'; const sent = f.api.submit(f.app);
  assert.equal(f.posts[0].body.blogId, '9007199254740993');
  f.posts[0].resolve(f.result(view('9007199254740997', {blogId: '9007199254740993'}), undefined, {countVersion: '9007199254740993'})); await sent;
  f.page(0, [], {commentCount: 0, countVersion: '9007199254740992'}); await page;
  assert.equal(f.app.commentState.list[0].id, '9007199254740997'); assert.equal(f.app.commentState.commentCount, 1);
});
test('observing replies does not let an old initial root page remove a newly published root', async () => {
  const f = fixture(), first = f.api.attach(f.app, '4'); f.app.commentState.draft = 'new';
  const sent = f.api.submit(f.app); f.posts[0].resolve(f.result(view(1))); await sent;
  const root = f.app.commentState.list[0], replies = f.api.toggleReplies(f.app, root);
  f.page(1, [], {root: view(1), countVersion: '2'}); await replies;
  f.page(0, [], {commentCount: 0, countVersion: '0'}); await first;
  assert.deepEqual(Array.from(f.app.commentState.list, row => row.id), ['1']);
});
test('switching blogs ignores old read/write responses and old finally cannot clear new pending', async () => {
  const f = fixture(); await initialized(f); f.app.commentState.draft = 'old'; const oldPost = f.api.submit(f.app);
  const newRead = f.api.attach(f.app, '5'); f.app.commentState.draft = 'new'; const newPost = f.api.submit(f.app);
  f.posts[0].resolve(f.result(view(1))); await oldPost; assert.equal(f.app.commentState.submitPending, true); assert.equal(f.app.commentState.draft, 'new');
  f.page(1, [], {commentCount: 0, countVersion: '0'}); await newRead;
  f.posts[1].resolve(f.result(view(2, {blogId: '5'}))); await newPost;
  assert.deepEqual(Array.from(f.app.commentState.list, row => row.blogId), ['5']); assert.equal(f.app.commentState.submitPending, false);
});
test('different roots have independent replies, and close/reopen rejects stale replies and finally', async () => {
  const f = fixture(); await initialized(f, [view(1), view(2)]); const [one, two] = f.app.commentState.list;
  const old = f.api.toggleReplies(f.app, one), other = f.api.toggleReplies(f.app, two);
  f.page(2, [view(20, {parentId: '2', answerId: '2'})], {root: view(2, {replyCount: 1})}); await other;
  await f.api.toggleReplies(f.app, one); const reopened = f.api.toggleReplies(f.app, one);
  f.page(1, [view(99, {parentId: '1', answerId: '1'})]); await old; assert.equal(one.replies.pending, true);
  f.page(3, [view(10, {parentId: '1', answerId: '1'})], {root: view(1, {replyCount: 1})}); await reopened;
  assert.deepEqual(Array.from(one.replies.list, row => row.id), ['10']); assert.deepEqual(Array.from(two.replies.list, row => row.id), ['20']);
});
test('reply success remains in its floor when an older root refresh completes', async () => {
  const f = fixture(); await initialized(f, [view(1)]); const root = f.app.commentState.list[0];
  const refresh = f.api.loadRoots(f.app, true); f.api.reply(f.app, root); f.app.commentState.draft = 'child'; const sent = f.api.submit(f.app);
  assert.equal(f.posts[0].body.answerId, '1');
  f.posts[0].resolve(f.result(view(2, {parentId: '1', answerId: '1'}), view(1, {replyCount: 1}), {commentCount: 2, countVersion: '2'})); await sent;
  f.page(1, [view(1)], {commentCount: 1, countVersion: '1'}); await refresh;
  const current = f.app.commentState.list[0]; assert.equal(current.replyCount, 1); assert.equal(current.replies.open, true);
  assert.deepEqual(Array.from(current.replies.list, row => row.id), ['2']); assert.equal(f.app.commentState.commentCount, 2);
});
test('deletion keeps reply placeholder and a stale reply refresh cannot restore deleted text', async () => {
  const f = fixture(); await initialized(f, [view(1, {replyCount: 1})]); const root = f.app.commentState.list[0];
  const opened = f.api.toggleReplies(f.app, root); f.page(1, [view(2, {parentId: '1', answerId: '1', content: 'secret'})], {root: view(1, {replyCount: 1})}); await opened;
  const reply = root.replies.list[0], stale = f.api.loadReplies(f.app, root, true), removed = f.api.remove(f.app, reply);
  await f.api.remove(f.app, reply); assert.equal(f.deletes.length, 1);
  f.deletes[0].resolve(f.result(view(2, {parentId: '1', answerId: '1', deleted: true, canDelete: false, canReply: false, content: '该评论已删除'}), view(1), {countVersion: '2'})); await removed;
  f.page(2, [view(2, {parentId: '1', answerId: '1', content: 'secret'})], {root: view(1, {replyCount: 1}), countVersion: '1'}); await stale;
  assert.equal(root.replies.list[0].content, '该评论已删除'); assert.equal(root.replyCount, 0); assert.equal(root.replies.list[0].deletePending, false);
});
test('deleted empty roots stay hidden after a late refresh; retry of deleted submission does not recreate', async () => {
  const f = fixture(); await initialized(f, [view(1)]); const stale = f.api.loadRoots(f.app, true);
  const removed = f.api.remove(f.app, f.app.commentState.list[0]); const tombstone = view(1, {deleted: true, canDelete: false, canReply: false, content: '该评论已删除'});
  f.deletes[0].resolve(f.result(tombstone, tombstone, {commentCount: 0, countVersion: '2'})); await removed;
  f.page(1, [view(1)], {countVersion: '1'}); await stale; assert.equal(f.app.commentState.list.length, 0);
  f.app.commentState.draft = 'same'; const retry = f.api.submit(f.app); f.posts[0].resolve(f.result(tombstone, tombstone, {commentCount: 0, countVersion: '2'})); await retry;
  assert.equal(f.app.commentState.list.length, 0); assert.equal(f.app.commentState.commentCount, 0);
});
test('refresh invalidates pending replies and a failed refresh retains the old root list and cursor', async () => {
  const f = fixture(); await initialized(f, [view(1)]); const root = f.app.commentState.list[0], old = f.api.toggleReplies(f.app, root);
  const refresh = f.api.loadRoots(f.app, true); f.reads[2].reject(new Error('offline')); await refresh;
  f.page(1, [view(99, {parentId: '1', answerId: '1'})]); await old;
  assert.equal(f.app.commentState.list.length, 1); assert.equal(root.replies.list.length, 0); assert.equal(root.replies.pending, false);
  const retried = f.api.loadRoots(f.app); assert.equal(f.reads[3].config.params.cursor, null); f.page(3, [view(2)]); await retried;
  assert.equal(f.app.commentState.list[0].id, '2');
});
test('reply input cancel and edits are new intents; success does not erase a draft changed during pending', async () => {
  const f = fixture(); await initialized(f, [view(1)]); const root = f.app.commentState.list[0];
  f.api.reply(f.app, root); assert.equal(f.app.commentState.target.name, 'author'); f.api.cancelReply(f.app); assert.equal(f.app.commentState.target, null);
  f.app.commentState.draft = 'sent'; const sent = f.api.submit(f.app); f.app.commentState.draft = 'new draft';
  f.posts[0].resolve(f.result(view(2))); await sent; assert.equal(f.app.commentState.draft, 'new draft');
  const next = f.api.submit(f.app); assert.notEqual(f.posts[1].body.requestId, f.posts[0].body.requestId); f.posts[1].reject(new Error('offline')); await next;
});
test('invalid result preserves the draft and same submission id; guests follow the existing login flow', async () => {
  const f = fixture(); await initialized(f); f.app.commentState.draft = 'text'; const first = f.api.submit(f.app);
  f.posts[0].resolve({data: {}}); await first; assert.equal(f.app.commentState.draft, 'text');
  const retry = f.api.submit(f.app); assert.equal(f.posts[1].body.requestId, f.posts[0].body.requestId); f.posts[1].reject(new Error('offline')); await retry;
  const guest = fixture(false); await initialized(guest); guest.app.commentState.draft = 'text'; await guest.api.submit(guest.app);
  assert.equal(guest.posts.length, 0); assert.equal(guest.window.location.href, '/login.html');
});
test('comments render as text and do not replace existing likes, follow or image interaction hooks', () => {
  const html = fs.readFileSync(path.join(__dirname, '../blog-detail.html'), 'utf8');
  const block = html.slice(html.indexOf('<div class="blog-comments"'), html.indexOf('<div class="foot">'));
  assert.equal(block.includes('v-html'), false); assert.ok(block.includes('{{comment.content}}')); assert.ok(block.includes('{{reply.content}}'));
  assert.ok(block.includes('v-if="canReplyTo(comment)"')); assert.ok(block.includes('v-if="canReplyTo(reply)"'));
  assert.equal(html.includes('（119）'), false); assert.equal(/\?{3,}/.test(html), false);
  for (const hook of ['blogLikes.watch', 'follows.toggle', '@touchstart="moveStart"', 'blogComments.destroy', 'this.blogRequestId !== requestId']) assert.ok(html.includes(hook));
});

test('deletes for different roots complete out of order without losing either result', async () => {
  const f = fixture(), first = f.api.attach(f.app, '4');
  f.page(0, [view(1, {replyCount: 1}), view(2, {replyCount: 1}), view(3)], {commentCount: 3, countVersion: '10'}); await first;
  const [one, two] = f.app.commentState.list;
  const deletingOne = f.api.remove(f.app, one), deletingTwo = f.api.remove(f.app, two);
  const tombstone = id => view(id, {deleted: true, canDelete: false, canReply: false, content: '该评论已删除', replyCount: 1});
  f.deletes[1].resolve(f.result(tombstone(2), tombstone(2), {commentCount: 1, countVersion: '12'})); await deletingTwo;
  f.deletes[0].resolve(f.result(tombstone(1), tombstone(1), {commentCount: 2, countVersion: '11'})); await deletingOne;
  assert.deepEqual(Array.from(f.app.commentState.list, row => row.id), ['1', '2', '3']);
  assert.equal(f.app.commentState.list[0].content, '该评论已删除');
  assert.equal(f.app.commentState.list[0].deleted, true);
  assert.equal(f.app.commentState.list[1].content, '该评论已删除');
  assert.equal(f.app.commentState.list[1].deleted, true);
  assert.equal(f.app.commentState.countVersion, '12');
  assert.equal(f.app.commentState.commentCount, 1);
});
test('replies under one root deleted out of order keep both placeholders, order and counts', async () => {
  const f = fixture(), first = f.api.attach(f.app, '4');
  f.page(0, [view(1, {replyCount: 2})], {commentCount: 3, countVersion: '10'}); await first;
  const root = f.app.commentState.list[0];
  const opened = f.api.toggleReplies(f.app, root);
  f.page(1, [view(12, {parentId: '1', answerId: '1'}), view(11, {parentId: '1', answerId: '1'})],
    {root: view(1, {replyCount: 2}), commentCount: 3, countVersion: '10', nextCursor: 'next', hasMore: true}); await opened;
  const [newerReply, olderReply] = root.replies.list;
  const deletingOlder = f.api.remove(f.app, olderReply), deletingNewer = f.api.remove(f.app, newerReply);
  const tombstone = id => view(id, {parentId: '1', answerId: '1', deleted: true, canDelete: false, canReply: false, content: '该评论已删除'});
  f.deletes[1].resolve(f.result(tombstone(12), view(1, {replyCount: 0}), {commentCount: 1, countVersion: '12'})); await deletingNewer;
  f.deletes[0].resolve(f.result(tombstone(11), view(1, {replyCount: 1}), {commentCount: 2, countVersion: '11'})); await deletingOlder;
  assert.deepEqual(Array.from(root.replies.list, row => row.id), ['12', '11']);
  assert.equal(root.replies.list[0].content, '该评论已删除');
  assert.equal(root.replies.list[0].deleted, true);
  assert.equal(root.replies.list[1].content, '该评论已删除');
  assert.equal(root.replies.list[1].deleted, true);
  assert.equal(root.replyCount, 0);
  assert.equal(f.app.commentState.commentCount, 1);
  assert.equal(f.app.commentState.countVersion, '12');
  assert.equal(root.replies.nextCursor, 'next');
});
test('a published reply still merges after a newer response for another comment', async () => {
  const f = fixture(), first = f.api.attach(f.app, '4');
  f.page(0, [view(1, {replyCount: 1})], {commentCount: 2, countVersion: '10'}); await first;
  const root = f.app.commentState.list[0];
  const opened = f.api.toggleReplies(f.app, root);
  f.page(1, [view(11, {parentId: '1', answerId: '1'})], {root: view(1, {replyCount: 1}), commentCount: 2, countVersion: '10'}); await opened;
  const existing = root.replies.list[0];
  f.api.reply(f.app, root); f.app.commentState.draft = 'new reply';
  const sent = f.api.submit(f.app), removed = f.api.remove(f.app, existing);
  const tombstone = view(11, {parentId: '1', answerId: '1', deleted: true, canDelete: false, canReply: false, content: '该评论已删除'});
  f.deletes[0].resolve(f.result(tombstone, view(1, {replyCount: 1}), {commentCount: 2, countVersion: '12'})); await removed;
  f.posts[0].resolve(f.result(view(12, {parentId: '1', answerId: '1', content: 'new reply'}), view(1, {replyCount: 2}),
    {commentCount: 3, countVersion: '11'})); await sent;
  assert.deepEqual(Array.from(root.replies.list, row => row.id), ['12', '11']);
  assert.equal(root.replies.list[0].content, 'new reply');
  assert.equal(root.replies.list[1].content, '该评论已删除');
  assert.equal(root.replyCount, 1);
  assert.equal(f.app.commentState.commentCount, 2);
  assert.equal(f.app.commentState.countVersion, '12');
});
test('a newer deletion is not restored by a late publish or reply page response', async () => {
  const f = fixture(), first = f.api.attach(f.app, '4');
  f.page(0, [view(1, {replyCount: 1})], {commentCount: 2, countVersion: '10'}); await first;
  const root = f.app.commentState.list[0];
  const opened = f.api.toggleReplies(f.app, root);
  f.page(1, [view(11, {parentId: '1', answerId: '1', content: 'original'})],
    {root: view(1, {replyCount: 1}), commentCount: 2, countVersion: '10'}); await opened;
  const reply = root.replies.list[0];
  f.app.commentState.draft = 'late publish'; const sent = f.api.submit(f.app);
  const removed = f.api.remove(f.app, reply);
  const tombstone = view(11, {parentId: '1', answerId: '1', deleted: true, canDelete: false, canReply: false, content: '该评论已删除'});
  f.deletes[0].resolve(f.result(tombstone, view(1, {replyCount: 0}), {commentCount: 1, countVersion: '12'})); await removed;
  f.posts[0].resolve(f.result(view(11, {parentId: '1', answerId: '1', content: 'original'}), view(1, {replyCount: 1}),
    {commentCount: 2, countVersion: '11'})); await sent;
  assert.equal(reply.deleted, true);
  assert.equal(reply.content, '该评论已删除');
  assert.equal(reply.canReply, false);
  assert.equal(root.replyCount, 0);
  assert.equal(f.app.commentState.commentCount, 1);
  assert.equal(f.app.commentState.countVersion, '12');
  const stale = f.api.loadReplies(f.app, root, true);
  f.page(2, [view(11, {parentId: '1', answerId: '1', content: 'original'})],
    {root: view(1, {replyCount: 1, canReply: true}), commentCount: 2, countVersion: '11'}); await stale;
  assert.equal(root.replies.list[0].content, '该评论已删除');
  assert.equal(root.replies.list[0].deleted, true);
  assert.equal(root.replies.list[0].canReply, false);
  assert.equal(root.replyCount, 0);
});
test('loaded replies cannot be reply targets after their root is deleted', async () => {
  const f = fixture(), first = f.api.attach(f.app, '4');
  f.page(0, [view(1, {replyCount: 1})], {commentCount: 2, countVersion: '10'}); await first;
  const root = f.app.commentState.list[0];
  const opened = f.api.toggleReplies(f.app, root);
  f.page(1, [view(11, {parentId: '1', answerId: '1'})], {root: view(1, {replyCount: 1}), commentCount: 2, countVersion: '10'}); await opened;
  const reply = root.replies.list[0];
  const removed = f.api.remove(f.app, root);
  const tombstone = view(1, {deleted: true, canDelete: false, canReply: false, content: '该评论已删除', replyCount: 1});
  f.deletes[0].resolve(f.result(tombstone, tombstone, {commentCount: 1, countVersion: '11'})); await removed;
  assert.equal(f.app.commentState.list.length, 1);
  assert.equal(f.app.commentState.list[0].content, '该评论已删除');
  assert.equal(root.replies.list.length, 1);
  assert.equal(f.api.canReply(f.app, reply), false);
  assert.equal(f.api.canReply(f.app, root), false);
  assert.equal(f.api.reply(f.app, reply), false);
  assert.equal(f.app.commentState.target, null);
});
test('a stale reply page cannot restore reply entry after the root is deleted', async () => {
  const f = fixture(), first = f.api.attach(f.app, '4');
  f.page(0, [view(1, {replyCount: 1})], {commentCount: 2, countVersion: '10'}); await first;
  const root = f.app.commentState.list[0];
  const opened = f.api.toggleReplies(f.app, root);
  f.page(1, [view(11, {parentId: '1', answerId: '1'})], {root: view(1, {replyCount: 1}), commentCount: 2, countVersion: '10'}); await opened;
  const reply = root.replies.list[0];
  const stale = f.api.loadReplies(f.app, root, true), removed = f.api.remove(f.app, root);
  const tombstone = view(1, {deleted: true, canDelete: false, canReply: false, content: '该评论已删除', replyCount: 1});
  f.deletes[0].resolve(f.result(tombstone, tombstone, {commentCount: 1, countVersion: '11'})); await removed;
  f.page(2, [view(11, {parentId: '1', answerId: '1', canReply: true, content: 'old'})],
    {root: view(1, {replyCount: 1, canReply: true}), commentCount: 2, countVersion: '10'}); await stale;
  assert.equal(root.canReply, false);
  assert.equal(root.deleted, true);
  assert.equal(root.replies.list[0].id, '11');
  assert.equal(f.api.canReply(f.app, reply), false);
  assert.equal(f.api.reply(f.app, reply), false);
});
test('an invalidated reply target is cancelled but the draft is kept', async () => {
  const f = fixture(), first = f.api.attach(f.app, '4');
  f.page(0, [view(1, {replyCount: 1})], {commentCount: 2, countVersion: '10'}); await first;
  const root = f.app.commentState.list[0];
  const opened = f.api.toggleReplies(f.app, root);
  f.page(1, [view(11, {parentId: '1', answerId: '1'})], {root: view(1, {replyCount: 1}), commentCount: 2, countVersion: '10'}); await opened;
  const reply = root.replies.list[0];
  assert.equal(f.api.reply(f.app, reply), true);
  f.app.commentState.draft = 'keep this draft';
  const removed = f.api.remove(f.app, root);
  const tombstone = view(1, {deleted: true, canDelete: false, canReply: false, content: '该评论已删除', replyCount: 1});
  f.deletes[0].resolve(f.result(tombstone, tombstone, {commentCount: 1, countVersion: '11'})); await removed;
  assert.equal(f.app.commentState.target, null);
  assert.equal(f.app.commentState.draft, 'keep this draft');
  assert.equal(f.warnings.length, 1);
  assert.match(f.warnings[0], /已取消回复目标/);
  assert.equal(f.api.reply(f.app, reply), false);
});
test('deleting a non-first reply keeps its position, cursor and collapsed state', async () => {
  const f = fixture(), first = f.api.attach(f.app, '4');
  f.page(0, [view(1, {replyCount: 3})], {commentCount: 4, countVersion: '10'}); await first;
  const root = f.app.commentState.list[0];
  const opened = f.api.toggleReplies(f.app, root);
  f.page(1, [view(13, {parentId: '1', answerId: '1'}), view(12, {parentId: '1', answerId: '1'}), view(11, {parentId: '1', answerId: '1'})],
    {root: view(1, {replyCount: 3}), commentCount: 4, countVersion: '10', nextCursor: 'cursor-2', hasMore: true}); await opened;
  const middle = root.replies.list[1];
  await f.api.toggleReplies(f.app, root);
  assert.equal(root.replies.open, false);
  const removed = f.api.remove(f.app, middle);
  f.deletes[0].resolve(f.result(view(12, {parentId: '1', answerId: '1', deleted: true, canDelete: false, canReply: false, content: '该评论已删除'}),
    view(1, {replyCount: 2}), {commentCount: 3, countVersion: '11'})); await removed;
  assert.deepEqual(Array.from(root.replies.list, row => row.id), ['13', '12', '11']);
  assert.equal(root.replies.list[1].content, '该评论已删除');
  assert.equal(root.replies.list[1].deleted, true);
  assert.equal(root.replyCount, 2);
  assert.equal(root.replies.open, false);
  assert.equal(root.replies.nextCursor, 'cursor-2');
  assert.equal(root.replies.hasMore, true);
  assert.equal(f.app.commentState.commentCount, 3);
  assert.equal(f.app.commentState.countVersion, '11');
});
test('a new reply is inserted on top and later pages do not duplicate it', async () => {
  const f = fixture(), first = f.api.attach(f.app, '4');
  f.page(0, [view(1)], {commentCount: 1, countVersion: '10'}); await first;
  const root = f.app.commentState.list[0];
  f.api.reply(f.app, root); f.app.commentState.draft = 'child';
  const sent = f.api.submit(f.app);
  f.posts[0].resolve(f.result(view(12, {parentId: '1', answerId: '1', content: 'child'}), view(1, {replyCount: 1}),
    {commentCount: 2, countVersion: '11'})); await sent;
  assert.deepEqual(Array.from(root.replies.list, row => row.id), ['12']);
  assert.equal(root.replies.open, true);
  const loading = f.api.loadReplies(f.app, root);
  f.page(1, [view(12, {parentId: '1', answerId: '1', content: 'child'}), view(11, {parentId: '1', answerId: '1'})],
    {root: view(1, {replyCount: 1}), commentCount: 2, countVersion: '11', nextCursor: 'next', hasMore: true}); await loading;
  assert.deepEqual(Array.from(root.replies.list, row => row.id), ['12', '11']);
  assert.equal(root.replies.list.filter(row => row.id === '12').length, 1);
  const more = f.api.loadReplies(f.app, root);
  f.page(2, [view(10, {parentId: '1', answerId: '1'})], {root: view(1, {replyCount: 1}), commentCount: 2, countVersion: '11'}); await more;
  assert.deepEqual(Array.from(root.replies.list, row => row.id), ['12', '11', '10']);
});

const vuePath = path.join(process.env.HMDP_FRONTEND_ROOT || 'D:/JavaCode/nginx-1.18.0/html/hmdp', 'js/vue.js');
test('actual Vue runtime observes submit pending, new roots and reply pending', {skip: !fs.existsSync(vuePath)}, async () => {
  const module = {exports: {}};
  vm.runInNewContext(fs.readFileSync(vuePath, 'utf8'), {module, exports: module.exports, Promise, setTimeout, clearTimeout, console, process: {env: {NODE_ENV: 'test'}}});
  const Vue = module.exports, f = fixture();
  const app = new Vue({data: {commentState: f.api.initial(), blog: {}}}); app.$message = f.app.$message; f.app = app;
  const attached = f.api.attach(app, '4'); f.page(0, []); await attached;
  const states = [], sizes = [];
  app.$watch('commentState.submitPending', value => states.push(value));
  app.$watch(() => app.commentState.list.length, value => sizes.push(value));
  app.commentState.draft = 'new'; const sent = f.api.submit(app); await Vue.nextTick();
  f.posts[0].resolve(f.result(view(1))); await sent; await Vue.nextTick();
  assert.deepEqual(states, [true, false]); assert.deepEqual(sizes, [1]);
  const root = app.commentState.list[0], pending = []; app.$watch(() => root.replies.pending, value => pending.push(value));
  const replies = f.api.toggleReplies(app, root); await Vue.nextTick(); f.page(1, [], {root: view(1), countVersion: '2'}); await replies; await Vue.nextTick();
  assert.deepEqual(pending, [true, false]); f.api.destroy(app); app.$destroy();
});

test('the actual Vue compiler accepts the complete detail template', {skip: !fs.existsSync(vuePath)}, () => {
  const module = {exports: {}}, warnings = [];
  const document = {createElement: () => ({set innerHTML(value) { this.textContent = value.replace(/&nbsp;/g, '\u00a0').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&'); }})};
  vm.runInNewContext(fs.readFileSync(vuePath, 'utf8'), {module, exports: module.exports, document, Promise, setTimeout, clearTimeout, console, process: {env: {NODE_ENV: 'test'}}});
  const html = fs.readFileSync(path.join(__dirname, '../blog-detail.html'), 'utf8');
  const template = html.slice(html.indexOf('<div id="app">'), html.indexOf('<script src='));
  const compiled = module.exports.compile(template, {warn: message => warnings.push(message)});
  assert.deepEqual(warnings, []); assert.equal(typeof compiled.render, 'function');
});
