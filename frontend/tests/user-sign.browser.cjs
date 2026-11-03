/* Optional real Nginx/backend/Edge acceptance test. Uses only an isolated Redis login identity. */
const assert = require('node:assert/strict');
const {execFileSync} = require('node:child_process');
const {randomBytes} = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.HMDP_PLAYWRIGHT_MODULE || 'playwright');

const base = process.env.HMDP_SIGN_BASE_URL || 'http://localhost:8080';
const cli = process.env.HMDP_REDIS_CLI || 'D:/Redis/redis-cli.exe';
const edge = process.env.HMDP_EDGE_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const redis = (...args) => execFileSync(cli, ['--raw', ...args.map(String)], {encoding: 'utf8'}).trim();
const userId = String(7000000000000 + randomBytes(4).readUInt32BE());
const token = 'hmdp-sign-browser-' + randomBytes(24).toString('hex');
const loginKey = 'login:token:' + token;
const ownedKeys = new Set();
const output = path.resolve('target/sign-browser-result.json');

(async () => {
  let browser, loginCreated = false, resultRecord;
  try {
    assert.equal(redis('PING'), 'PONG');
    assert.equal(redis('EXISTS', loginKey), '0');
    // 不创建真实用户或修改已有会话；沿用登录拦截器真实读取的 Redis 会话格式。
    redis('HSET', loginKey, 'id', userId, 'nickName', 'sign_test_user', 'icon', ''); loginCreated = true;
    redis('EXPIRE', loginKey, 120);
    browser = await chromium.launch({headless: true, executablePath: edge});
    const context = await browser.newContext({viewport: {width: 390, height: 844}, timezoneId: 'America/Los_Angeles'});
    const authorization = {authorization: token};
    for (const resource of ['/info.html', '/js/user-sign.js?v=20261103-sign-2', '/css/user-sign.css?v=20261103-sign-2']) {
      const response = await context.request.get(base + resource); assert.equal(response.status(), 200, resource);
    }
    for (const route of ['/api/user/sign/status', '/api/user/sign/calendar']) {
      assert.equal((await context.request.get(base + route)).status(), 401, route);
    }
    assert.equal((await context.request.post(base + '/api/user/sign')).status(), 401);
    const initial = await (await context.request.get(base + '/api/user/sign/status', {headers: authorization})).json();
    assert.equal(initial.success, true); assert.equal(initial.data.signed, false);
    const date = initial.data.date, month = date.slice(0, 7), day = Number(date.slice(8));
    const signKey = 'sign:' + userId + ':' + month.replace('-', '');
    assert.equal(redis('EXISTS', signKey), '0'); ownedKeys.add(signKey);
    await context.addInitScript(value => sessionStorage.setItem('token', value), token);
    const page = await context.newPage(), pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.message));
    await page.goto(base + '/info.html?v=20261103-sign-2');
    await page.waitForFunction(() => app.signState.today && app.signState.calendar && app.signState.signed === false);
    assert.equal(await page.evaluate(() => app.signState.today), date);
    const posted = page.waitForResponse(response => response.url().endsWith('/api/user/sign') && response.request().method() === 'POST');
    await page.getByRole('button', {name: '今日签到', exact: true}).click();
    const result = await (await posted).json(); assert.equal(result.success, true); assert.equal(result.data.newSign, true);
    await page.waitForFunction(day => app.signState.signed === true && app.signState.calendar.signedDays.includes(day) && !app.signState.signPending, day);
    assert.equal(redis('GETBIT', signKey, day - 1), '1'); assert.equal(redis('TTL', signKey), '-1');
    assert.equal(await page.getByRole('button', {name: '今日已签到', exact: true}).isDisabled(), true);
    const duplicate = await (await context.request.post(base + '/api/user/sign?userId=1&date=1999-01-01', {
      headers: authorization, data: {userId: 1, date: '1999-01-01'}
    })).json();
    assert.equal(duplicate.data.newSign, false); assert.equal(duplicate.data.date, date);
    await page.reload();
    await page.waitForFunction(() => app.signState.signed === true && app.signState.calendar && !app.signState.calendarPending);
    assert.equal(await page.locator('.sign-summary').innerText(), '本月已签到1天');
    const [year, monthNumber] = month.split('-').map(Number), previousDate = new Date(Date.UTC(year, monthNumber - 2, 1));
    const previous = previousDate.toISOString().slice(0, 7);
    await page.getByRole('button', {name: '上个月', exact: true}).click();
    await page.waitForFunction(month => app.signState.calendar && app.signState.calendar.month === month && !app.signState.calendarPending, previous);
    assert.equal(await page.locator('.sign-summary').innerText(), previous + '已签到0天');
    await page.locator('.sign-day').filter({hasText: /^1$/}).click();
    assert.equal(redis('EXISTS', 'sign:' + userId + ':' + previous.replace('-', '')), '0');
    await page.getByRole('button', {name: '返回本月', exact: true}).click();
    await page.waitForFunction(month => app.signState.calendar && app.signState.calendar.month === month && !app.signState.calendarPending, month);
    assert.equal(await page.locator('.sign-day.signed').count(), 1);
    assert.deepEqual(pageErrors, []);
    await page.screenshot({path: 'target/sign-profile-browser.png', fullPage: true});
    resultRecord = {passed: true, baseUrl: base, businessDate: date, browserTimezone: 'America/Los_Angeles',
      checks: ['Nginx page/script/style HTTP 200', 'anonymous sign routes HTTP 401', 'real page first sign',
        'atomic duplicate sign', 'refresh preserves signed state', 'historical month', 'return current month',
        'no retroactive sign', 'zero browser script errors']};
  } finally {
    if (browser) await browser.close();
    if (loginCreated) redis('DEL', loginKey);
    for (const key of ownedKeys) {
      assert.ok(key.startsWith('sign:' + userId + ':')); redis('DEL', key);
    }
  }
  assert.equal(redis('EXISTS', loginKey, ...ownedKeys), '0');
  resultRecord.cleanedTestKeys = true;
  fs.writeFileSync(output, JSON.stringify(resultRecord, null, 2));
  console.log(JSON.stringify(resultRecord, null, 2));
})().catch(error => {console.error(error); process.exitCode = 1;});
