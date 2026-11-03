/* One best-effort event after successful rendering. No login token or readable visitor cookie. */
(function (global) {
  'use strict';
  const allowed = new Set(['HOME', 'SHOP_LIST', 'SHOP_DETAIL', 'BLOG_DETAIL', 'MY_PROFILE', 'USER_PROFILE']);
  const reported = new Set();
  // Dedicated instance: statistics failures must never invoke the site's login redirect interceptor.
  const http = axios.create({baseURL: '/api', timeout: 2000});
  function report(pageType) {
    if (!allowed.has(pageType) || reported.has(pageType)) return Promise.resolve(false);
    reported.add(pageType);
    return Promise.resolve().then(() => http.post('/statistics/visits', {pageType}))
      .then(response => !!(response.data && response.data.success && response.data.data && response.data.data.collected))
      .catch(() => false);
  }
  function afterRender(vm, pageType, ready) {
    const rendered = () => {
      if (!vm._isDestroyed && !vm._isBeingDestroyed && (!ready || ready())) report(pageType);
    };
    if (typeof vm.$nextTick === 'function') vm.$nextTick(rendered);
    else rendered();
  }
  global.siteVisits = {report, afterRender};
})(window);
