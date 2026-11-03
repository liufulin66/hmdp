(function (global) {
  'use strict';
  const contexts = new WeakMap();
  const version = value => String(value == null ? '0' : value);
  const compare = (a, b) => a.length === b.length ? (a === b ? 0 : a > b ? 1 : -1) : a.length > b.length ? 1 : -1;
  const validMeta = data => data && typeof data.countVersion === 'string' && /^(0|[1-9][0-9]*)$/.test(data.countVersion)
    && Number.isSafeInteger(data.commentCount) && data.commentCount >= 0;
  const validRow = value => value && ['id', 'blogId', 'parentId', 'answerId'].every(key => typeof value[key] === 'string' && /^(0|[1-9][0-9]*)$/.test(value[key]))
    && value.author && typeof value.author.id === 'string' && typeof value.content === 'string'
    && typeof value.deleted === 'boolean' && typeof value.canDelete === 'boolean' && typeof value.canReply === 'boolean'
    && Number.isSafeInteger(value.replyCount) && value.replyCount >= 0;
  function pageState() {
    return {list: [], nextCursor: null, hasMore: true, pending: false, loaded: false, error: '',
      generation: 0, request: 0, replace: true, refreshing: false, open: false};
  }
  function initial() {
    return {...pageState(), blogId: '', draft: '', target: null, submitPending: false, submitError: '', commentCount: 0, countVersion: '0'};
  }
  function row(ctx, value) {
    const existing = find(ctx, String(value.id));
    return {...value, id: String(value.id), blogId: String(value.blogId), parentId: String(value.parentId), answerId: String(value.answerId),
      deletePending: ctx.deleting.has(String(value.id)), replies: existing ? existing.replies : pageState()};
  }
  function find(ctx, id) {
    id = String(id);
    for (const root of ctx.state.list) {
      if (root.id === id) return root;
      const reply = root.replies.list.find(value => value.id === id);
      if (reply) return reply;
    }
    return null;
  }
  function merge(ctx, value, updateVersion) {
    const id = String(value.id), old = find(ctx, id);
    const newer = ctx.local.get(id);
    if (newer && compare(newer.version, version(updateVersion)) > 0) return old || row(ctx, newer.value);
    ctx.local.set(id, {value, version: version(updateVersion), local: true});
    if (old) { const replies = old.replies; Object.assign(old, row(ctx, value)); old.replies = replies; return old; }
    return row(ctx, value);
  }
  function counts(vm, ctx, data) {
    if (contexts.get(vm) !== ctx || compare(version(data.countVersion), ctx.state.countVersion) < 0) return;
    ctx.state.countVersion = version(data.countVersion); ctx.state.commentCount = data.commentCount;
    if (vm.blog) vm.blog.comments = data.commentCount;
  }
  function unique(values) { const seen = new Set(); return values.filter(value => { if (seen.has(value.id)) return false; seen.add(value.id); return true; }); }
  function visibleRoot(value) { return !value.deleted || value.replyCount > 0; }
  // 回复是否可点，必须同时看回复自身和它当前所属的一级评论；旧回复对象上的 canReply 可能已经过期。
  function canReplyTo(ctx, value) {
    if (!ctx || !value || !value.canReply || value.deleted) return false;
    const root = String(value.parentId) === '0' ? value : ctx.state.list.find(item => item.id === String(value.parentId));
    return !!root && root.canReply && !root.deleted;
  }
  // 只比较单条评论自己的已知版本；整篇笔记的 countVersion 只约束总计数，不能代替单条状态。
  function hasNewerLocal(ctx, id, updateVersion) {
    const local = ctx.local.get(String(id));
    return !!local && compare(local.version, version(updateVersion)) > 0;
  }
  function reconcileTarget(vm, ctx) {
    const target = ctx.state.target;
    if (!target) return false;
    const value = find(ctx, target.answerId);
    const valid = value ? canReplyTo(ctx, value)
      : ctx.state.list.some(root => root.id === target.rootId && root.canReply && !root.deleted);
    if (valid) return false;
    ctx.state.target = null;
    if (vm.$message && typeof vm.$message.warning === 'function') {
      vm.$message.warning('该评论已删除或不可回复，已取消回复目标');
    }
    return true;
  }
  function invalidate(page) { page.generation++; page.request++; page.pending = false; page.refreshing = false; page.replace = true; }
  function attach(vm, blogId) {
    const state = initial(); state.blogId = String(blogId); vm.commentState = state;
    contexts.set(vm, {state, local: new Map(), deleting: new Map(), submission: null, submitRequest: 0});
    return loadRoots(vm, true);
  }
  async function loadPage(vm, root, reset = false) {
    const ctx = contexts.get(vm); if (!ctx) return false;
    const page = root ? root.replies : ctx.state;
    if (page.pending && (!reset || page.refreshing)) return false;
    if (!page.hasMore && !reset && !page.replace) return false;
    if (reset) {
      page.generation++; page.replace = true;
      if (!root) for (const old of ctx.state.list) { invalidate(old.replies); old.replies.open = false; }
    }
    const replace = page.replace, cursor = replace ? null : page.nextCursor;
    const generation = page.generation, request = ++page.request;
    const active = () => contexts.get(vm) === ctx && page.generation === generation && page.request === request;
    page.pending = true; page.refreshing = replace; page.error = '';
    try {
      const url = root ? '/blog-comments/' + encodeURIComponent(root.id) + '/replies' : '/blog-comments/of/blog/' + encodeURIComponent(ctx.state.blogId);
      const {data} = await axios.get(url, {params: {cursor, size: 10}, headers: {'Cache-Control': 'no-cache'}});
      if (!active()) return false;
      if (!validMeta(data) || !Array.isArray(data.list) || data.list.length > 10 || !data.list.every(validRow) || typeof data.hasMore !== 'boolean'
          || (data.root != null && !validRow(data.root))
          || (data.hasMore && (typeof data.nextCursor !== 'string' || !data.nextCursor || data.nextCursor === cursor))) {
        throw new Error('评论分页返回异常，请重试');
      }
      const responseVersion = version(data.countVersion);
      const values = data.list.map(value => {
        const local = ctx.local.get(String(value.id));
        if (local && compare(local.version, responseVersion) > 0) return row(ctx, local.value);
        ctx.local.set(String(value.id), {value, version: responseVersion, local: !!(local && local.local)});
        return row(ctx, value);
      });
      if (root && data.root) {
        const local = ctx.local.get(root.id);
        if (!local || compare(local.version, responseVersion) <= 0) {
          const replies = root.replies; Object.assign(root, row(ctx, data.root)); root.replies = replies;
          ctx.local.set(root.id, {value: data.root, version: responseVersion, local: !!(local && local.local)});
        }
      }
      // 保留比本次数据库快照更新的本地发表结果，避免加载响应把刚发表的评论抹掉。
      const concurrent = [];
      for (const entry of ctx.local.values()) {
        const value = entry.value;
        if (entry.local && compare(entry.version, responseVersion) > 0 && (root ? String(value.parentId) === root.id : String(value.parentId) === '0')) {
          concurrent.push(row(ctx, value));
        }
      }
      page.list = unique(concurrent.concat(replace ? [] : page.list, values)).filter(value => root || visibleRoot(value));
      page.nextCursor = data.nextCursor; page.hasMore = data.hasMore; page.loaded = true; page.replace = false;
      counts(vm, ctx, data); reconcileTarget(vm, ctx); return true;
    } catch (error) {
      if (active()) page.error = String(error && error.message || error || '评论加载失败，请重试');
      return false;
    } finally { if (active()) { page.pending = false; page.refreshing = false; } }
  }
  function loadRoots(vm, reset = false) { return loadPage(vm, null, reset); }
  function loadReplies(vm, root, reset = false) { return loadPage(vm, root, reset); }
  function toggleReplies(vm, root) {
    if (root.replies.open) { root.replies.open = false; invalidate(root.replies); return Promise.resolve(false); }
    root.replies.open = true; return loadReplies(vm, root, true);
  }
  function reply(vm, value) {
    const ctx = contexts.get(vm); if (!ctx || ctx.state.submitPending || !canReplyTo(ctx, value)) return false;
    ctx.state.target = {answerId: String(value.id), rootId: String(value.parentId) === '0' ? String(value.id) : String(value.parentId), name: value.author.nickName};
    return true;
  }
  function cancelReply(vm) { const ctx = contexts.get(vm); if (ctx && !ctx.state.submitPending) ctx.state.target = null; }
  function requestId() {
    if (global.crypto && global.crypto.randomUUID) return global.crypto.randomUUID();
    if (global.crypto && global.crypto.getRandomValues) return Array.from(global.crypto.getRandomValues(new Uint8Array(16)), n => n.toString(16).padStart(2, '0')).join('');
    return Date.now().toString(36) + '-' + Math.random().toString(36).slice(2) + '-' + Math.random().toString(36).slice(2);
  }
  function payload(ctx) { return {blogId: ctx.state.blogId, content: ctx.state.draft.trim(), answerId: ctx.state.target ? ctx.state.target.answerId : '0'}; }
  function mutation(vm, ctx, data, kind) {
    if (!validMeta(data) || !validRow(data.comment) || !validRow(data.root)) throw new Error('评论提交结果异常，请保留原内容重试');
    // 不同评论各按自己的已知版本合并：整篇笔记计数版本更旧，不代表这条评论的结果已经过期。
    const commentStale = hasNewerLocal(ctx, data.comment.id, data.countVersion);
    const rootStale = hasNewerLocal(ctx, data.root.id, data.countVersion);
    const comment = merge(ctx, data.comment, data.countVersion), root = merge(ctx, data.root, data.countVersion);
    const currentRoot = ctx.state.list.find(value => value.id === root.id);
    if (kind === 'create' && !rootStale && !currentRoot && visibleRoot(root)) ctx.state.list.unshift(root);
    if (comment.parentId !== '0') {
      const displayed = ctx.state.list.find(value => value.id === root.id);
      if (displayed && kind === 'create' && !commentStale) {
        if (!displayed.replies.list.some(value => value.id === comment.id)) {
          displayed.replies.list = unique([comment].concat(displayed.replies.list));
        }
        displayed.replies.open = true;
      }
    }
    ctx.state.list = ctx.state.list.filter(visibleRoot); counts(vm, ctx, data); reconcileTarget(vm, ctx);
  }
  async function submit(vm) {
    const ctx = contexts.get(vm); if (!ctx || ctx.state.submitPending) return false;
    if (typeof token === 'undefined' || !token) { vm.$message.error('请先登录'); global.location.href = '/login.html'; return false; }
    const body = payload(ctx), signature = JSON.stringify(body);
    if (!body.content || Array.from(body.content).length > 500) { ctx.state.submitError = '评论内容不能为空且不能超过500个字符'; return false; }
    if (!ctx.submission || ctx.submission.signature !== signature) ctx.submission = {signature, body: {...body, requestId: requestId()}};
    const submission = ctx.submission, request = ++ctx.submitRequest;
    const active = () => contexts.get(vm) === ctx && ctx.submitRequest === request;
    ctx.state.submitPending = true; ctx.state.submitError = '';
    try {
      const {data} = await axios.post('/blog-comments', {...submission.body});
      if (!active()) return false;
      mutation(vm, ctx, data, 'create');
      if (JSON.stringify(payload(ctx)) === signature) { ctx.state.draft = ''; ctx.state.target = null; ctx.submission = null; }
      return true;
    } catch (error) {
      if (active()) ctx.state.submitError = String(error && error.message || error || '提交失败，请用原内容重试');
      return false;
    } finally { if (active()) ctx.state.submitPending = false; }
  }
  async function remove(vm, value) {
    const ctx = contexts.get(vm), id = String(value.id);
    if (!ctx || !value.canDelete || ctx.deleting.has(id)) return false;
    const ticket = {}; ctx.deleting.set(id, ticket); value.deletePending = true;
    try {
      const {data} = await axios.delete('/blog-comments/' + encodeURIComponent(id));
      if (contexts.get(vm) !== ctx || ctx.deleting.get(id) !== ticket) return false;
      mutation(vm, ctx, data, 'delete'); return true;
    } catch (error) { if (contexts.get(vm) === ctx) vm.$message.error(error && error.message || error); return false; }
    finally {
      if (contexts.get(vm) === ctx && ctx.deleting.get(id) === ticket) {
        ctx.deleting.delete(id); value.deletePending = false; const current = find(ctx, id); if (current) current.deletePending = false;
      }
    }
  }
  global.blogComments = {initial, attach, loadRoots, loadReplies, toggleReplies, canReply: (vm, value) => canReplyTo(contexts.get(vm), value),
    reply, cancelReply, submit, remove, destroy: vm => contexts.delete(vm)};
})(window);
