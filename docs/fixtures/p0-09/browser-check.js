// Run only against a fresh isolated p009-browser account, never personal data.
async page => {
  const base = 'http://127.0.0.1:5175', checks = [], errors = [];
  page.on('pageerror', e => errors.push(e.message));
  const button = (name, target = page) => target.getByRole('button', { name, exact: true });
  async function api(target, path) { return target.evaluate(async path => { const r = await fetch(path); if (!r.ok) throw new Error('HTTP ' + r.status); return (await r.json()).data; }, path); }
  async function write(path, data) {
    return page.evaluate(async ({ path, data }) => {
      const csrf = (await (await fetch('/api/auth/csrf')).json()).data;
      const r = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify(data) });
      if (!r.ok) throw new Error(await r.text());return (await r.json()).data;
    }, { path, data });
  }
  async function login(target) {
    await target.goto(base + '/settings');await target.getByLabel('账号', { exact: true }).fill('p009-browser');
    await target.getByLabel('密码', { exact: true }).fill('p009-browser-only');await button('进入学习空间', target).click();await target.waitForURL('**/sources');
  }
  async function settled(target = page) { await target.waitForFunction(() => ![...document.querySelectorAll('.sync-label')].some(n => /待同步|同步中/.test(n.textContent))); }
  async function settings() { await page.goto(base + '/settings');await button('保存学习设置').waitFor(); }
  async function save() { await button('保存学习设置').click();await page.getByText('学习设置已同步。已保存的学习历史与到期日期保持不变。').waitFor();await settled(); }
  async function current(target = page) { return api(target, '/api/learning-sessions/' + target.url().split('?')[0].split('/').pop()); }
  await page.context().clearCookies();await page.setViewportSize({ width: 1280, height: 900 });await login(page);
  let snapshot = await api(page, '/api/sync/snapshot');
  if (!snapshot.sources.length) {
    await page.locator('input[type=file]').setInputFiles('docs/Java+agent八股(高频版).pdf');
    await page.getByRole('link', { name: '查看章节与草稿' }).first().waitFor({ timeout: 90000 });snapshot = await api(page, '/api/sync/snapshot');
  }
  const jobs = await api(page, `/api/import-jobs?sourceId=${snapshot.sources[0].id}`);
  await page.waitForFunction(async id => (await (await fetch(`/api/import-jobs/${id}`)).json()).data.status === 'READY', jobs[0].id);
  const chapters = await api(page, `/api/import-jobs/${jobs[0].id}/chapters`), chapter = chapters.find(c => c.name === 'Java 基础');
  const review = await api(page, `/api/chapters/${chapter.id}/review`);
  if (review.checkStatus !== 'IMPORTED') await write(`/api/chapters/${chapter.id}/review`, { operationId: await page.evaluate(() => crypto.randomUUID()), dataEpoch: snapshot.dataEpoch, expectedVersion: review.version, action: 'CONFIRM', payload: { allowFlagged: true } });
  await settings();
  if (await page.getByLabel('每日新题量', { exact: true }).inputValue() !== '5') throw new Error('Default goal');
  await page.getByLabel('每日新题量', { exact: true }).fill('3');await page.getByLabel('每轮复习批量', { exact: true }).fill('1');
  await page.getByLabel('每日参考时长（分钟，可选）', { exact: true }).fill('15');await page.locator('input[type=checkbox]').first().check();await save();
  await page.goto(base + '/');await page.getByRole('heading', { name: '今天，从一次回忆开始。' }).waitFor();
  await button('混合学习').click();await page.waitForURL(/\/practice\/[a-f0-9-]+$/);await button('展开完整答案').waitFor();
  const initial = await current(), sessionUrl = page.url();if (initial.items.length !== 3 || initial.review !== null) throw new Error('Planned queue');
  await button('展开完整答案').click();await button('模糊').click();await page.getByText(/已保存。下次复习/).waitFor();await settled();
  const first = await current();if (first.round.newCount !== 1 || first.round.attention.length !== 1) throw new Error('Round statistics');
  await page.goto(base + '/');await page.getByRole('link', { name: /继续混合学习/ }).click();await page.waitForURL(sessionUrl);await button('展开完整答案').waitFor();
  await page.reload();await button('展开完整答案').waitFor();const resumed = await current();
  if (resumed.current.id !== first.current.id || await button('模糊').count()) throw new Error('Resume changed position or revealed ratings');
  checks.push('真实 PDF 确认章节、首次修改学习默认值、生成有序计划、评分后本轮统计、离开/重开/刷新继续同一未完成条目');
  const other = await page.context().browser().newContext({ timezoneId: 'America/Los_Angeles', viewport: { width: 360, height: 800 } });
  const phone = await other.newPage();phone.on('pageerror', e => errors.push(e.message));await login(phone);await phone.goto(base + '/');
  await phone.getByRole('heading', { name: '今天，从一次回忆开始。' }).waitFor();
  const a = await api(page, '/api/today'), b = await api(phone, '/api/today');
  if (a.learningDate !== b.learningDate || b.summary.todayNew !== 1 || b.remainingNew !== 2) throw new Error('Server day / device summary');
  await phone.getByRole('link', { name: /继续混合学习/ }).click();await button('展开完整答案', phone).waitFor();
  if ((await current(phone)).current.id !== first.current.id) throw new Error('Device continuation');
  checks.push('上海与洛杉矶浏览器时区显示同一服务端学习日、今日新学与剩余额度；第二会话接续同一条目');
  await settings();await page.getByLabel('每日新题量', { exact: true }).fill('0');await page.locator('input[type=checkbox]').first().uncheck();await save();
  await page.goto(sessionUrl);await page.getByText('本轮练习结束。已保存的评分将用于后续复习安排。', { exact: true }).waitFor();
  const paused = await current();if (paused.current || paused.round.newCount !== 1) throw new Error('Pause or history');
  await settings();await page.locator('input[type=checkbox]').first().check();await save();
  await page.goto(base + '/');await page.getByRole('heading', { name: '今天，从一次回忆开始。' }).waitFor();await button('刷新今日计划').click();
  await page.getByText(/今日剩余新题额度 0/).waitFor();await button('只复习').click();await page.waitForURL(/\/practice\/[a-f0-9-]+$/);
  await page.getByText('本轮练习结束。已保存的评分将用于后续复习安排。', { exact: true }).waitFor();
  if ((await api(page, '/api/today')).summary.todayNew !== 1) throw new Error('Goal/pause changed history');
  checks.push('新题目标为 0、暂停章节跳过旧计划、恢复章节保留已学和原日期、空复习轮结束且不宣称全部掌握');
  await page.goto(base + '/');await page.getByRole('heading', { name: '今天，从一次回忆开始。' }).waitFor();
  await page.screenshot({ path: 'output/p0-09/today-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 320, height: 780 });await page.screenshot({ path: 'output/p0-09/today-mobile.png', fullPage: true });
  if (await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)) throw new Error('Home mobile overflow');
  await settings();await page.screenshot({ path: 'output/p0-09/settings-mobile.png', fullPage: true });
  if (await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)) throw new Error('Settings mobile overflow');
  await other.close();if (errors.length) throw new Error(errors.join('; '));
  checks.push('1280px 桌面、320px 首页与设置无横向溢出；页面运行时异常 0');
  return { checks, runtimeErrors: errors };
}
