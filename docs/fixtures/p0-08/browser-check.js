// Run only against the isolated p008-browser test account (password p005-browser-only).
// playwright-cli -s=p008 run-code --filename=docs/fixtures/p0-08/browser-check.js
async page => {
  const base = 'http://127.0.0.1:5174', checks = [], errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('dialog', dialog => dialog.accept());
  const button = name => page.getByRole('button', { name, exact: true });
  async function api(target, path) {
    return target.evaluate(async path => { const r = await fetch(path); if (!r.ok) throw new Error('HTTP ' + r.status); return (await r.json()).data; }, path);
  }
  async function write(target, path, data) {
    return target.evaluate(async ({ path, data }) => {
      const csrf = (await (await fetch('/api/auth/csrf')).json()).data;
      const r = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify(data) });
      if (!r.ok) throw new Error('Write ' + r.status + ': ' + await r.text()); return (await r.json()).data;
    }, { path, data });
  }
  async function login(target) {
    await target.goto(base + '/sources');
    await target.getByLabel('账号', { exact: true }).fill('p008-browser');
    await target.getByLabel('密码', { exact: true }).fill('p005-browser-only');
    await target.getByRole('button', { name: '进入学习空间' }).click();await target.waitForURL('**/sources');
  }
  async function settled(target = page) {
    await target.waitForFunction(() => ![...document.querySelectorAll('.sync-label')].some(n => /待同步|同步中/.test(n.textContent)));
  }
  async function current(target = page) { return api(target, '/api/learning-sessions/' + target.url().split('?')[0].split('/').pop()); }
  async function newRound(cardId) {
    await page.goto(base + '/practice?cards=' + cardId);await button('开始主动回忆').click();await page.waitForURL(/\/practice\/[a-f0-9-]+$/);
    await button('展开完整答案').waitFor();return current();
  }
  async function rate(name) {
    await button('展开完整答案').click();await button(name).click();await settled();
    await page.getByText(/已保存。下次复习：/).waitFor();return current();
  }
  await page.context().clearCookies();
  await page.setViewportSize({ width: 1280, height: 900 });await login(page);
  let snapshot = await api(page, '/api/sync/snapshot');
  if (!snapshot.sources.length) {
    await page.locator('input[type=file]').setInputFiles('docs/Java+agent八股(高频版).pdf');
    await page.getByRole('link', { name: '查看章节与草稿' }).first().waitFor({ timeout: 90000 });snapshot = await api(page, '/api/sync/snapshot');
  }
  const jobs = await api(page, `/api/import-jobs?sourceId=${snapshot.sources[0].id}`);
  await page.waitForFunction(async id => (await (await fetch(`/api/import-jobs/${id}`)).json()).data.status === 'READY', jobs[0].id);
  const chapters = await api(page, `/api/import-jobs/${jobs[0].id}/chapters`), chapter = chapters.find(c => c.name === 'Java 基础');
  const review = await api(page, `/api/chapters/${chapter.id}/review`);
  if (review.checkStatus !== 'IMPORTED') await write(page, `/api/chapters/${chapter.id}/review`, { operationId: await page.evaluate(() => crypto.randomUUID()), dataEpoch: snapshot.dataEpoch, expectedVersion: review.version, action: 'CONFIRM', payload: { allowFlagged: true } });
  const cards = await api(page, '/api/cards?q=HashMap'), cardId = cards.items[0].id;
  await page.goto(base + `/cards/${cardId}`);await page.getByRole('link', { name: '主动回忆与评分' }).click();await button('开始主动回忆').click();
  await page.waitForURL(/\/practice\/[a-f0-9-]+$/);await button('展开完整答案').waitFor();
  if (await button('不会').count() || await page.locator('[aria-label="完整答案"]').count()) throw new Error('Ratings/answer initially revealed');
  await button('查看提示与要点').click();
  const before = await api(page, '/api/review-events/summary');if (before.learned !== 0) throw new Error('Browsing changed learned count');
  await button('展开完整答案').click();
  const detail = await api(page, '/api/cards/' + cardId), answer = await page.locator('[aria-label="完整答案"]').innerText();
  for (const block of detail.contentBlocks) { const tail = block.textSpans.at(-1)?.text;if (tail && !answer.includes(tail)) throw new Error('Missing full answer tail'); }
  await page.screenshot({ path: 'output/p0-08/recall-desktop.png', fullPage: true });
  let lost = false;
  await page.route('**/api/learning-sessions/*/review', async route => {
    if (!lost && route.request().postDataJSON().action === 'RATE') { lost = true;await route.fetch();await route.abort('failed'); } else await route.continue();
  });
  await button('不会').click();await page.waitForFunction(() => [...document.querySelectorAll('.sync-label')].some(n => n.textContent === '待同步'));
  await page.reload();await page.unroute('**/api/learning-sessions/*/review');await button('重试连接').click();await settled();
  await page.getByText(/本轮追加重练/).waitFor();let s = await current();
  if (s.events.length !== 1 || s.review.stage !== -1 || s.review.lapseCount !== 1 || !s.current.isReplay) throw new Error('Response loss duplicated scoring');
  checks.push('默认隐藏答案/评分、跨页答案全文、浏览提示不写入；评分丢响应重载后按原收据只确认一次');
  await button('撤销最近一次评分').click();await page.getByText('已撤销，评分前的状态和队列已恢复。').waitFor();await settled();
  s = await current();const undone = await api(page, '/api/review-events/summary');
  if (s.review !== null || s.current.isReplay || s.items.length !== 1 || undone.todayNew !== 0 || undone.learned !== 0) throw new Error('First-score undo failed');
  await rate('模糊');await button('撤销最近一次评分').click();await page.getByText('已撤销，评分前的状态和队列已恢复。').waitFor();await settled();
  s = await rate('记住了');const firstDate = s.events[0].afterState.dueDate, firstTime = s.events[0].afterState.firstLearnedAt;
  if (s.events[0].afterState.stage !== 0 || s.current) throw new Error('Remembered first stage mismatch');
  checks.push('三档评分与日期反馈；首次评分撤销恢复未学、统计和原队列，已结束会话仍可撤销');
  await newRound(cardId);s = await rate('记住了');
  if (!s.events[0].isReplay || s.events[0].afterState.stage !== 0 || s.events[0].afterState.dueDate !== firstDate) throw new Error('Same-day practice promoted stage');
  await newRound(cardId);s = await rate('不会');
  if (!s.current?.isReplay) throw new Error('Missing bounded replay');await button('跳过重练').click();await page.getByText('已跳过重练，保留复习安排。').waitFor();await settled();
  await newRound(cardId);s = await rate('不会');
  if (s.current || s.items.length !== 1) throw new Error('Reopening session bypassed daily replay cap');
  const summary = await api(page, '/api/review-events/summary');if (summary.todayNew !== 1 || summary.todayReview !== 0) throw new Error('Replays inflated summary');
  checks.push('同日反复练习不升级、不重复计新学；自动重练可跳过，重建会话不能绕过每日上限');
  await newRound(cardId);await button('重新学习此题').click();await page.getByText(/已保存。下次复习：/).waitFor();await settled();s = await current();
  if (s.review.dueDate !== s.today || s.review.firstLearnedAt !== firstTime || s.review.stage !== -1) throw new Error('Relearn lost first history');
  await page.setViewportSize({ width: 320, height: 740 });await button('展开完整答案').click();await page.screenshot({ path: 'output/p0-08/recall-mobile.png', fullPage: true });
  if (await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1)) throw new Error('Mobile document overflow');
  checks.push('重新学习明确确认后到期设今日，保留首次时间；320px 全文与评分布局无页面横向溢出');
  const otherContext = await page.context().browser().newContext({ viewport: { width: 1280, height: 900 }, timezoneId: 'America/Los_Angeles' });
  try {
    const other = await otherContext.newPage();other.on('dialog', d => d.accept());other.on('pageerror', e => errors.push(e.message));await login(other);
    await newRound(cardId);const a = await current();
    const b = await write(other, '/api/learning-sessions', { operationId: await other.evaluate(() => crypto.randomUUID()), dataEpoch: snapshot.dataEpoch, cardIds: [cardId] });
    await other.goto(base + '/practice/' + b.session.id);await other.getByRole('button', { name: '展开完整答案' }).waitFor();
    if (a.learningDate !== b.session.learningDate) throw new Error('Device timezone changed server learning day');
    await rate('模糊');await other.getByRole('button', { name: '展开完整答案' }).click();await other.getByRole('button', { name: '不会', exact: true }).click();
    await other.locator('.sync-label.needs-attention').waitFor();await other.getByText('核对评分与当前状态').click();
    const evidence = await other.locator('.sync-items').innerText();if (!evidence.includes('FORGOT') || !evidence.includes('VAGUE')) throw new Error('Conflict evidence missing');
    const state = await current(other);if (state.events.length !== 0) throw new Error('Concurrent stale score became effective');
    checks.push('双独立会话、不同设备时区学习日相同；旧状态评分冲突保留双方证据，不生成第二次有效评分');
  } finally { await otherContext.close(); }
  if (errors.length) throw new Error(errors.join('\n'));return { checks, runtimeErrors: errors.length, cardId, learningDate: s.today };
}
