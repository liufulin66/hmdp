/* Scroll to the comment area after rendering; never focuses the input automatically. */
(function (global) {
  'use strict';

  function focusComments(vm, options) {
    const settings = options || {};
    const tick = settings.nextTick || (fn => vm.$nextTick(fn));
    const setTimer = settings.setTimeout || ((fn, ms) => global.setTimeout(fn, ms));
    const clearTimer = settings.clearTimeout || (id => global.clearTimeout(id));
    const retries = settings.retries == null ? 3 : settings.retries;
    const timeout = settings.timeout == null ? 3000 : settings.timeout;
    let cancelled = false, cleanup = null, attempts = 0;

    function cancel() {
      cancelled = true;
      if (cleanup) { const finish = cleanup; cleanup = null; finish(); }
    }
    function scroll() {
      if (cancelled) return false;
      const section = vm.$refs && vm.$refs.commentsSection;
      if (!section || typeof section.scrollIntoView !== 'function') return false;
      section.scrollIntoView({block: 'start'});
      return true;
    }
    function watchImages() {
      const container = vm.$refs && vm.$refs.detailScroll;
      if (!container || typeof container.querySelectorAll !== 'function') return;
      const images = Array.from(container.querySelectorAll('img')).filter(image => image && !image.complete);
      if (!images.length) return;
      let finished = false, timer = null;
      const onLoad = () => { if (!finished) scroll(); };
      const finish = () => {
        if (finished) return;
        finished = true;
        images.forEach(image => image.removeEventListener && image.removeEventListener('load', onLoad));
        if (container.removeEventListener) {
          container.removeEventListener('wheel', finish);
          container.removeEventListener('touchstart', finish);
        }
        if (timer != null) clearTimer(timer);
      };
      images.forEach(image => image.addEventListener && image.addEventListener('load', onLoad));
      if (container.addEventListener) {
        container.addEventListener('wheel', finish, {passive: true});
        container.addEventListener('touchstart', finish, {passive: true});
      }
      timer = setTimer(finish, timeout);
      cleanup = finish;
    }
    function attempt() {
      if (cancelled) return;
      // 评论区可能比首次 nextTick 更晚出现，最多重试几次，不触发任何网络请求。
      if (scroll()) { watchImages(); return; }
      if (++attempts <= retries) tick(attempt);
    }

    tick(attempt);
    return {cancel};
  }

  global.blogDetail = {focusComments};
})(window);
