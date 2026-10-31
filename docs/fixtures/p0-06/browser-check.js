// Run against an isolated empty p006-browser account (password: p005-browser-only).
async page => {
  const base = 'http://127.0.0.1:5173';
  const checks = [], errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.unroute('**/api/chapters/*/review');
  if (await page.getByRole('button', { name: '取消编辑', exact: true }).count()) await page.getByRole('button', { name: '取消编辑', exact: true }).click();
  await page.setViewportSize({ width: 1280, height: 900 });
  async function api(target, path) { return target.evaluate(async path => { const r = await fetch(path); if (!r.ok) throw new Error('HTTP ' + r.status); return (await r.json()).data; }, path); }
  async function login(target) {
    await target.goto(base + '/sources');
    await target.waitForFunction(() => location.pathname === '/login' || !!document.querySelector('input[type=file]'));
    if (target.url().endsWith('/login')) {
      await target.getByLabel('账号', { exact: true }).fill('p006-browser');
      await target.getByLabel('密码', { exact: true }).fill('p005-browser-only');
      await target.getByRole('button', { name: '进入学习空间' }).click();
    }
    await target.waitForURL('**/sources');
  }
  async function saved(target, chapter, predicate) {
    const matches = new Function('v', 'return ' + predicate);
    let complete = false;
    for (let attempt = 0; attempt < 100; attempt++) {
      if (matches(await api(target, `/api/chapters/${chapter}/review`))) { complete = true; break; }
      await target.waitForTimeout(100);
    }
    if (!complete) throw new Error('Server did not reach expected chapter state: ' + predicate);
    await target.waitForFunction(() => ![...document.querySelectorAll('.sync-label')].some(n => n.textContent.includes('待同步') || n.textContent.includes('同步中')));
    await target.getByRole('button', { name: '读取最新章节与检查位置' }).waitFor();
  }
  await login(page);
  await page.locator('input[type=file]').setInputFiles('docs/Java+agent八股(高频版).pdf');
  await page.getByRole('link', { name: '查看章节与草稿' }).first().waitFor({ timeout: 90000 });
  await page.getByRole('link', { name: '查看章节与草稿' }).first().click();
  await page.locator('.chapter-buttons button').filter({ hasText: 'Java 基础' }).click();
  await page.getByRole('heading', { name: /Java 基础 · (待检查|检查中)/ }).waitFor();
  const jobId = new URL(page.url()).pathname.split('/').pop();
  const chapters = await api(page, `/api/import-jobs/${jobId}/chapters`);
  const chapter = chapters.find(c => c.name === 'Java 基础').id;
  const original = await api(page, `/api/chapters/${chapter}/review`);
  const initialEdits = original.history.filter(h => h.action === 'EDIT').length;
  const initialHistory = original.history.length;
  const secondId = original.items[1].id;
  await page.locator('.draft-link').nth(1).click();
  await saved(page, chapter, `v.currentDraftId === '${secondId}' && v.version > ${original.version}`);
  await page.getByRole('button', { name: '编辑问题、内容与来源' }).click();
  await page.getByLabel('问题', { exact: true }).fill('P0-06 人工校对的问题');
  await page.getByLabel('本次调整原因').fill('对照 PDF 完整原文校正标题');
  // Lose the response after the server has committed. Reload must recover the same operation receipt.
  let lost = false;
  await page.route(`**/api/chapters/${chapter}/review`, async route => {
    if (!lost && route.request().method() === 'POST') { lost = true; await route.fetch(); await route.abort('failed'); }
    else await route.continue();
  });
  await page.getByRole('button', { name: '保存编辑', exact: true }).click();
  await page.waitForFunction(() => !document.querySelector('.review-editor'));
  await page.waitForFunction(() => document.querySelector('.sync-panel')?.textContent.includes('待同步'));
  page.once('dialog', dialog => dialog.accept());
  await page.reload();
  await page.locator('.chapter-buttons button').filter({ hasText: 'Java 基础' }).click();
  await page.unroute(`**/api/chapters/${chapter}/review`);
  await page.getByRole('button', { name: '重试连接' }).click();
  await saved(page, chapter, "v.items.some(d => d.question === 'P0-06 人工校对的问题')");
  const edited = await api(page, `/api/chapters/${chapter}/review`);
  if (edited.history.filter(h => h.action === 'EDIT').length !== initialEdits + 1 || edited.currentDraftId !== secondId) throw new Error('Lost-response replay/position mismatch: ' + JSON.stringify({ initialEdits, edits: edited.history.filter(h => h.action === 'EDIT').length, current: edited.currentDraftId, expected: secondId, questions: edited.items.slice(0,3).map(i => i.question) }));
  checks.push('完整编辑、响应丢失、刷新后按原收据恢复，仅写入一次');
  await page.getByRole('button', { name: '读取最新章节与检查位置' }).click();
  await page.getByRole('heading', { name: 'P0-06 人工校对的问题', exact: true }).waitFor();
  await page.locator('.draft-list input[type=checkbox]').nth(0).check();
  await page.locator('.draft-list input[type=checkbox]').nth(1).check();
  await page.getByLabel('本次调整原因').fill('合并两道候选并保留所有来源');
  await page.getByRole('button', { name: '合并选中题（2）' }).click();
  await saved(page, chapter, 'v.activeCount === 16 && v.userCountDelta === -1');
  await page.locator('.draft-link').first().click();
  await saved(page, chapter, `v.currentDraftId === '${original.items[0].id}'`);
  await page.getByRole('button', { name: '拆分当前题' }).click();
  await page.locator('#split-0-question').fill('拆分主问题');
  await page.locator('#split-1-question').fill('拆分关联问题');
  await page.getByLabel('本次调整原因').fill('拆分关联内容，保留来源供进一步核对');
  await page.getByRole('button', { name: '保存拆分为两题' }).click();
  await saved(page, chapter, 'v.activeCount === 17 && v.userCountDelta === 0');
  await page.getByLabel('本次调整原因').fill('主动排除暂不学习的问题');
  await page.getByRole('button', { name: '排除当前题' }).click();
  await saved(page, chapter, 'v.activeCount === 16 && v.userCountDelta === -1');
  checks.push('合并、拆分、排除的数量变化与原识别差异分开，保留原因');
  // A second real session resumes the saved position and edits, without sharing browser storage.
  const otherContext = await page.context().browser().newContext({ viewport: { width: 1280, height: 900 } });
  try {
    const other = await otherContext.newPage(); await login(other); await other.goto(base + `/imports/${jobId}`);
    await other.getByRole('button', { name: '继续上次检查：Java 基础' }).click();
    await other.getByRole('heading', { name: 'Java 基础 · 检查中' }).waitFor();
    const resumed = await api(other, `/api/chapters/${chapter}/review`);
    if (resumed.currentDraftId !== original.items[0].id || resumed.history.length !== initialHistory + 4) throw new Error('Second-session check state mismatch');
    checks.push('独立设备会话恢复服务端编辑、调整记录及检查位置');
  } finally { await otherContext.close(); }
  await page.getByRole('button', { name: '确认本章加入正式题库' }).click();
  const confirm = page.getByRole('button', { name: '确认导入 16 题', exact: true });
  if (await confirm.isEnabled()) throw new Error('Flagged import lacks explicit consent');
  await page.getByLabel('明确选择保留待检查标记后导入').check();
  await confirm.click();
  await saved(page, chapter, "v.checkStatus === 'IMPORTED' && v.cards.length === 16");
  await page.getByRole('link', { name: '查看本章正式题库' }).waitFor();
  const after = await api(page, `/api/import-jobs/${jobId}/chapters`);
  if (after.filter(c => c.checkStatus === 'IMPORTED').length !== 1 || after.filter(c => c.checkStatus === 'PENDING').length !== 12) throw new Error('Other drafts changed during chapter confirmation');
  await page.screenshot({ path: 'output/p0-06/review-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 320, height: 740 });
  if (await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)) throw new Error('Mobile review overflow');
  await page.screenshot({ path: 'output/p0-06/review-mobile.png', fullPage: true });
  await page.getByRole('link', { name: '查看本章正式题库' }).click();
  await page.getByRole('heading', { name: '本章正式题库' }).waitFor();
  await page.locator('.draft-preview').waitFor();
  if (await page.locator('.draft-list li').count() !== 16) throw new Error('Formal library count mismatch');
  if (await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)) throw new Error('Mobile formal-card overflow');
  await page.screenshot({ path: 'output/p0-06/cards-mobile.png', fullPage: true });
  checks.push('仅确认 Java 基础、显式带标记导入，12 章保持草稿；320px 正式题库全文可读');
  if (errors.length) throw new Error(errors.join('\n'));
  return { checks, runtimeErrors: errors.length };
}
