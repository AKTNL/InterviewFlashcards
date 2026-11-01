// Runs existing business acceptance scenarios against the built HTTPS stack.
// Fixed loopback URL and disposable account; cannot target a real deployment.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = path.resolve(__dirname, '../../..');
process.chdir(root);
const output = path.join(root, 'output/p0-13');
fs.mkdirSync(output, { recursive: true });
const base = 'https://localhost:8443';
const mode = process.argv[2] || 'core';
if (!['core', 'persistence', 'destructive'].includes(mode)) throw new Error('Unknown phase');

(async () => {
  const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_BROWSER_CHANNEL || 'msedge', headless: true });
  // Only this local rehearsal ignores the self-signed certificate. release-check.py
  // separately checks trust using the explicit certificate, without bypassing TLS.
  const newContext = browser.newContext.bind(browser);
  browser.newContext = options => newContext({ ignoreHTTPSErrors: true, ...options });
  const results = [];
  let page;
  async function fresh() {
    for (const context of browser.contexts()) await context.close();
    const context = await browser.newContext({ timezoneId: 'Asia/Shanghai', viewport: { width: 1280, height: 900 } });
    page = await context.newPage();
    page.setDefaultTimeout(30000);
    return page;
  }
  async function login() {
    await page.goto(base + '/login');
    await page.getByLabel('账号', { exact: true }).fill('p013-local');
    await page.getByLabel('密码', { exact: true }).fill('p013-local-only');
    await page.getByRole('button', { name: '进入学习空间', exact: true }).click();
    await page.waitForURL('**/sources');
  }
  async function call(url, method = 'GET', body) {
    return page.evaluate(async ({ url, method, body }) => {
      const headers = { 'Content-Type': 'application/json' };
      if (method !== 'GET') { const c = (await (await fetch('/api/auth/csrf')).json()).data; headers[c.headerName] = c.token; }
      const response = await fetch(url, { method, headers, ...(body ? { body: JSON.stringify(body) } : {}) });
      const value = await response.json();
      if (!response.ok) throw new Error(JSON.stringify(value));
      return value.data;
    }, { url, method, body });
  }
  async function scenario(file) {
    await fresh();
    // Adapt infrastructure and the retry button renamed since P0-06/P0-08;
    // keep all original business assertions/actions.
    let code = fs.readFileSync(path.join(root, 'docs/fixtures', file), 'utf8')
      .replace(/http:\/\/127\.0\.0\.1:517[3458]/g, base)
      .replace(/p0(?:05|08|09|12)-browser-only/g, 'p013-local-only')
      .replace(/p0(?:06|08|09|12)-browser/g, 'p013-local')
      .replaceAll('重试连接', '刷新与重试')
      .replace(/(?:D:\/InterviewFlashcards\/)?output\/p0-(?:06|08|09|12)\//g, output.replaceAll('\\', '/') + '/')
      .replaceAll('D:/InterviewFlashcards/docs/', root.replaceAll('\\', '/') + '/docs/');
    if (file === 'p0-06/browser-check.js') {
      // P0-07 replaced the inline chapter preview with library -> card detail.
      const start = code.indexOf("  await page.getByRole('heading', { name: '本章正式题库' })");
      const end = code.indexOf("  checks.push('仅确认", start);
      assert.ok(start > 0 && end > start, 'Expected P0-06 chapter navigation block');
      code = code.slice(0, start) + `
        await page.getByRole('heading', {name:'找一道题，认真读懂'}).waitFor();
        await page.getByText('找到 16 题', {exact:false}).waitFor();
        if (await page.locator('.library-results ol li').count() !== 16) throw new Error('Formal library count');
        await page.locator('.library-results ol li a').first().click();
        await page.getByRole('button', {name:'展开完整答案', exact:true}).click();
        await page.locator('[aria-label="完整答案"]').waitFor();
        if (await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)) throw new Error('Mobile formal-card overflow');
        await page.screenshot({path:${JSON.stringify(path.join(output, 'cards-mobile.png'))},fullPage:true});
      ` + code.slice(end);
    }
    if (file === 'p0-09/browser-check.js') {
      // P0-12 disables an empty review entry instead of creating an empty round.
      const start = code.indexOf("await button('只复习').click();await page.waitForURL");
      const end = code.indexOf("  checks.push('新题目标", start);
      assert.ok(start > 0 && end > start, 'Expected P0-09 empty review block');
      code = code.slice(0, start) + `
        if (!await button('只复习').isDisabled()) throw new Error('Empty review entry enabled');
        if ((await api(page, '/api/today')).summary.todayNew !== 1) throw new Error('Goal/pause changed history');
      ` + code.slice(end).replace('空复习轮结束', '空复习入口禁用');
    }
    if (file === 'p0-12/final-browser-check.js') {
      // Earlier scenarios legitimately saved goal=0; make this unsaved-edit
      // check change the current value rather than submitting the same value.
      code = code.replace("await page.getByLabel('每日新题量').fill('0');",
        "const dirtyGoal=(await page.getByLabel('每日新题量').inputValue())==='0'?'1':'0';await page.getByLabel('每日新题量').fill(dirtyGoal);")
        .replace("inputValue()!=='0'", 'inputValue()!==dirtyGoal');
    }
    const result = await eval('(' + code + ')')(page);
    results.push({ scenario: file, result });
    console.log('PASS ' + file);
    fs.writeFileSync(path.join(output, mode + '-result.json'), JSON.stringify(results, null, 2));
  }
  try {
    if (mode === 'core') {
      await fresh(); await login();
      assert.equal((await call('/api/sync/snapshot')).sources.length, 0, 'Core phase requires a fresh disposable volume');
      const cookie = (await page.context().cookies()).find(c => c.name === 'JSESSIONID');
      assert.ok(cookie?.secure && cookie.httpOnly && cookie.sameSite === 'Lax', 'HTTPS session cookie flags');
      await scenario('p0-06/browser-check.js');
      await scenario('p0-09/browser-check.js');
      // Reset only this disposable deck between scenarios that assert first learning.
      await fresh(); await login();
      const deck = (await call('/api/deck-data'))[0];
      const preview = await call(`/api/deck-data/${deck.id}/preview`);
      await call(`/api/deck-data/${deck.id}`, 'POST', {
        operationId: crypto.randomUUID(), dataEpoch: preview.dataEpoch,
        expectedRevision: preview.revision, action: 'RESET', confirmed: true,
      });
      await scenario('p0-08/browser-check.js');
      await scenario('p0-12/backup-browser-check.js');
      await fresh(); await login();
      const snapshot = await call('/api/sync/snapshot');
      // Repeat upload uses the same source and does not create another deck.
      await page.locator('input[type=file]').setInputFiles('docs/Java+agent八股(高频版).pdf');
      await page.waitForFunction(() => {
        const s = document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('sync');
        return s.items.some(i => i.kind === 'source.upload') && s.items.every(i => i.status === 'synced') && !s.busy;
      });
      const duplicate = await call('/api/sync/snapshot');
      assert.equal(duplicate.sources.length, snapshot.sources.length);
      assert.equal(duplicate.data.deck.length, snapshot.data.deck.length);
      assert.equal(duplicate.data.card.length, snapshot.data.card.length);
      // GETs may refresh a session; persist a fresh baseline after the last read.
      fs.writeFileSync(path.join(output, 'before-recreate.json'), JSON.stringify(await call('/api/sync/snapshot')));
      results.push({ scenario: 'HTTPS cookie and duplicate upload', result: 'PASS' });
    } else if (mode === 'persistence') {
      await fresh(); await login();
      const before = JSON.parse(fs.readFileSync(path.join(output, 'before-recreate.json')));
      const after = await call('/api/sync/snapshot');
      assert.equal(after.dataEpoch, before.dataEpoch);
      assert.equal(after.revision, before.revision);
      // Row order is not an API guarantee; compare by stable IDs.
      const ordered = rows => [...rows].sort((a, b) => String(a.id).localeCompare(String(b.id)));
      for (const name of Object.keys(before.data)) assert.deepEqual(ordered(after.data[name]), ordered(before.data[name]), name);
      assert.deepEqual(ordered(after.sources), ordered(before.sources));
      for (const source of after.sources) {
        const response = await page.request.get(base + `/api/sources/${source.id}/content`);
        assert.ok(response.ok());
        assert.equal((await response.body()).length, source.byteSize);
      }
      const backups = await call('/api/backups');
      const backup = backups.find(b => b.status === 'READY');
      assert.ok(backup, 'Backup job survives recreation');
      assert.ok((await page.request.get(base + `/api/backups/${backup.id}/download`)).ok());
      results.push({ scenario: 'All structured rows, drafts, history, source PDF and backup survive container recreation', result: 'PASS' });
    } else {
      await scenario('p0-12/browser-check.js');
      await scenario('p0-12/final-browser-check.js');
      await scenario('p0-12/pdf-resize-browser-check.js');
    }
    fs.writeFileSync(path.join(output, mode + '-result.json'), JSON.stringify(results, null, 2));
    console.log(JSON.stringify(results, null, 2));
  } catch (error) {
    if (page && !page.isClosed()) {
      await page.screenshot({ path: path.join(output, mode + '-failure.png'), fullPage: true }).catch(() => {});
      console.error((await page.locator('body').innerText().catch(() => '')).slice(-4000));
    }
    console.error(error);
    process.exitCode = 1;
  } finally { await browser.close(); }
})();
