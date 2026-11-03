/* Shared blog-detail links: one URL protocol for every list page. */
(function (global) {
  'use strict';
  const version = '20261103-3';
  const idPattern = /^[1-9][0-9]*$/;

  // ID 始终按字符串拼接；不能先转 Number，否则超过 2^53 的 Long 会丢精度。
  function detailUrl(id, focusComments) {
    const value = id == null ? '' : String(id);
    if (!idPattern.test(value)) return '';
    let url = '/blog-detail.html?v=' + encodeURIComponent(version) + '&id=' + encodeURIComponent(value);
    if (focusComments) url += '&focus=comments';
    return url;
  }

  function open(id, focusComments) {
    const url = detailUrl(id, focusComments);
    if (url) global.location.href = url;
    return url;
  }

  global.blogLinks = {version, detailUrl, open};
})(window);
