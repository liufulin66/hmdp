/* Optional real Nginx/Edge acceptance. Backend must use target/uv-browser-config.json's isolated prefix/admin. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {execFileSync} = require('node:child_process');
const {randomBytes} = require('node:crypto');
const {chromium} = require(process.env.HMDP_PLAYWRIGHT_MODULE || 'playwright');
const config = JSON.parse(fs.readFileSync('target/uv-browser-config.json', 'utf8'));
assert.match(config.prefix, /^hmdp:test:uv-browser:[0-9a-f-]+:$/);
const base = process.env.HMDP_UV_BASE_URL || 'http://localhost:8080';
const cli = process.env.HMDP_REDIS_CLI || 'D:/Redis/redis-cli.exe';
const redis = (...args) => execFileSync(cli, ['--raw', ...args.map(String)], {encoding: 'utf8'}).trim();
const adminToken = 'uv-browser-admin-' + randomBytes(24).toString('hex');
const memberToken = 'uv-browser-member-' + randomBytes(24).toString('hex');
const sessions = [], uvKeys = new Set();
const cookieName = 'hmdp_visitor_id';

(async () => {
  let browser, result;
  try {
    for (const [token, id, name] of [[adminToken, config.adminId, 'uv_test_admin'], [memberToken, config.memberId, 'uv_test_member']]) {
      const key = 'login:token:' + token; assert.equal(redis('EXISTS', key), '0');
      redis('HSET', key, 'id', id, 'nickName', name, 'icon', ''); sessions.push(key); redis('EXPIRE', key, 120);
    }
    browser = await chromium.launch({headless: true, executablePath: process.env.HMDP_EDGE_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
    const context = await browser.newContext({viewport: {width: 1100, height: 850}, timezoneId: 'America/Los_Angeles'});
    const request = context.request, headers = {authorization: adminToken};
    async function data(url, auth = headers) {const response = await request.get(base + '/api' + url, {headers: auth}); assert.equal(response.status(), 200, url); const body = await response.json(); assert.equal(body.success, true, url); return body.data;}
    const before = await data('/statistics/uv/daily');
    uvKeys.add(config.prefix + before.date.replaceAll('-', '')); assert.equal(before.uv, 0);
    for (const resource of ['/statistics.html','/js/site-visits.js?v=20261103-uv-1','/js/site-statistics.js?v=20261103-uv-1','/css/statistics.css?v=20261103-uv-1']) {
      assert.equal((await request.get(base + resource)).status(), 200, resource);
    }
    for (const url of ['/statistics/uv/daily', '/statistics/uv/recent', '/statistics;mode=public/uv/daily']) {
      assert.equal((await request.get(base + '/api' + url)).status(), 401);
      assert.equal((await request.get(base + '/api' + url, {headers: {authorization: memberToken}})).status(), 403);
    }
    const types = await data('/shop-type/list', {}), type = types[0].id;
    const shops = await data('/shop/of/type?typeId=' + type + '&current=1&pageInfo=true', {});
    const blogs = await data('/blog/hot?current=1', {});
    assert.ok(shops.list.length && blogs.length, 'real page fixtures are required');
    const page = await context.newPage(), events = [], errors = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('request', request => {if (new URL(request.url()).pathname === '/api/statistics/visits') events.push(request);});
    let abortFirst = true;
    await page.route('**/api/statistics/visits', route => {
      if (abortFirst) {abortFirst = false; return route.abort('failed');} return route.continue();
    });
    const failedEvent = page.waitForRequest(request => new URL(request.url()).pathname === '/api/statistics/visits');
    await page.goto(base + '/index.html'); await failedEvent;
    await page.locator('.blog-box').first().waitFor({state: 'visible'});
    await page.evaluate(() => {app.current = 2; return app.queryHotBlogsScroll();});
    assert.equal(events.length, 1); assert.equal((await data('/statistics/uv/daily')).uv, 0);
    async function visit(path, type) {
      const collected = page.waitForResponse(response => new URL(response.url()).pathname === '/api/statistics/visits');
      await page.goto(base + path); const response = await collected;
      assert.equal(response.status(), 200); assert.equal((await response.json()).data.collected, true);
      assert.equal(events.at(-1).postDataJSON().pageType, type);
    }
    await visit('/index.html', 'HOME');
    const visitor = (await context.cookies()).find(cookie => cookie.name === cookieName);
    assert.ok(visitor && visitor.httpOnly && visitor.sameSite === 'Lax' && visitor.path === '/' && !visitor.secure);
    await visit('/shop-list.html?type=' + type, 'SHOP_LIST');
    await visit('/shop-detail.html?id=' + shops.list[0].id, 'SHOP_DETAIL');
    await visit('/blog-detail.html?id=' + blogs[0].id, 'BLOG_DETAIL');
    const failedBlogStart = events.length;
    await page.goto(base + '/blog-detail.html?id=9223372036854775807');
    await page.waitForFunction(() => app.blogError); assert.equal(events.length, failedBlogStart);
    await page.goto(base + '/index.html');
    await page.evaluate(value => sessionStorage.setItem('token', value), memberToken);
    await visit('/info.html', 'MY_PROFILE');
    await visit('/other-info.html?id=' + blogs[0].userId, 'USER_PROFILE');
    assert.equal((await context.cookies()).find(cookie => cookie.name === cookieName).value, visitor.value);
    await page.goto(base + '/statistics.html');
    await page.waitForFunction(() => app.statisticsState.dailyError && app.statisticsState.recentError);
    assert.match(await page.locator('main').innerText(), /需要管理员权限/);
    await page.goto(base + '/info.html'); await page.waitForFunction(() => app.user && app.user.id);
    const signedOut = page.waitForURL('**/'); await page.locator('.logout-btn').click(); await signedOut;
    assert.equal((await context.cookies()).find(cookie => cookie.name === cookieName).value, visitor.value);
    assert.equal((await data('/statistics/uv/daily')).uv, 1);
    const second = await browser.newContext(); const secondPage = await second.newPage();
    const secondCollected = secondPage.waitForResponse(response => new URL(response.url()).pathname === '/api/statistics/visits');
    await secondPage.goto(base + '/index.html'); assert.equal((await secondCollected).status(), 200); await second.close();
    assert.equal((await data('/statistics/uv/daily')).uv, 2);
    await page.evaluate(value => sessionStorage.setItem('token', value), adminToken);
    await page.goto(base + '/statistics.html');
    await page.waitForFunction(() => app.statisticsState.daily && app.statisticsState.recent);
    assert.match(await page.locator('.daily-result').innerText(), /约 2 UV/);
    assert.equal(await page.locator('tbody tr').count(), 7);
    const recent = await data('/statistics/uv/recent'); assert.equal(recent.list.length, 7); assert.equal(recent.rangeUv, 2);
    assert.equal(recent.complete, false); assert.equal(recent.list.filter(day => day.status === 'NOT_COLLECTED').length, 6);
    assert.equal(recent.coverageFrom, before.date); assert.equal(recent.coverageTo, before.date);
    const after = await data('/statistics/uv/daily'); uvKeys.add(config.prefix + after.date.replaceAll('-', ''));
    assert.equal(redis('PFCOUNT', config.prefix + after.date.replaceAll('-', '')), '2', 'backend must use the isolated test prefix');
    for (const event of events) assert.equal(event.headers().authorization, undefined);
    assert.deepEqual(errors, []);
    await page.screenshot({path: 'target/uv-statistics-browser.png', fullPage: true});
    result = {passed: true, staticResources: 'HTTP 200', anonymousCollection: 'passed', anonymousQueries: 401, memberQueries: 403,
      administratorQueries: 'HTTP 200', sixPageTypes: 'passed', stableCookieAcrossAuthAndLogout: true,
      collectionFailureKeepsContent: true, missingBlogDoesNotReport: true, repeatPaginationDoesNotReport: true,
      dailyUv: after.uv, recentUnionUv: recent.rangeUv, coveredFrom: recent.coverageFrom, coveredTo: recent.coverageTo,
      complete: recent.complete, uncollectedDays: 6, pageErrors: errors.length};
  } finally {
    if (browser) await browser.close();
    for (const key of sessions) redis('DEL', key);
    for (const key of uvKeys) {assert.ok(key.startsWith(config.prefix)); redis('DEL', key);}
  }
  assert.equal(redis('EXISTS', ...sessions, ...uvKeys), '0'); result.cleanedTestKeys = true;
  fs.writeFileSync('target/uv-browser-result.json', JSON.stringify(result, null, 2)); console.log(JSON.stringify(result, null, 2));
})().catch(error => {console.error(error); process.exitCode = 1;});
