// playwright-cli run-code --filename=docs/fixtures/p0-07/browser-check.js
// Isolated schema/account: p007-browser / p005-browser-only; never use a personal account.
async page => {
  const base = 'http://127.0.0.1:5173', checks = [], errors = [];
  page.on('pageerror', e => errors.push(e.message));
  async function api(target, path) {
    return target.evaluate(async path => { const r = await fetch(path); if (!r.ok) throw new Error('HTTP ' + r.status); return (await r.json()).data; }, path);
  }
  async function write(target, path, data) {
    return target.evaluate(async ({ path, data }) => {
      const csrf = (await (await fetch('/api/auth/csrf')).json()).data;
      const r = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify(data) });
      if (!r.ok) throw new Error('Write HTTP ' + r.status + ': ' + await r.text()); return (await r.json()).data;
    }, { path, data });
  }
  async function login(target) {
    await target.goto(base + '/sources');
    await target.waitForFunction(() => location.pathname === '/login' || !!document.querySelector('input[type=file]'));
    if (target.url().endsWith('/login')) {
      await target.getByLabel('账号', { exact: true }).fill('p007-browser');
      await target.getByLabel('密码', { exact: true }).fill('p005-browser-only');
      await target.getByRole('button', { name: '进入学习空间' }).click();
    }
    await target.waitForURL('**/sources');
  }
  async function saved(target, id, predicate) {
    await target.waitForFunction(async ({ id, predicate }) => {
      const value = (await (await fetch(`/api/cards/${id}`)).json()).data;
      return new Function('v', 'return ' + predicate)(value);
    }, { id, predicate });
    await target.waitForFunction(() => ![...document.querySelectorAll('.sync-label')].some(n => /待同步|同步中/.test(n.textContent)));
  }
  await page.setViewportSize({ width: 1280, height: 900 }); await login(page);
  let snapshot = await api(page, '/api/sync/snapshot');
  if (!snapshot.sources.length) {
    await page.locator('input[type=file]').setInputFiles('docs/Java+agent八股(高频版).pdf');
    await page.getByRole('link', { name: '查看章节与草稿' }).first().waitFor({ timeout: 90000 });
    snapshot = await api(page, '/api/sync/snapshot');
  }
  const source = snapshot.sources[0], jobs = await api(page, `/api/import-jobs?sourceId=${source.id}`);
  await page.waitForFunction(async id => (await (await fetch(`/api/import-jobs/${id}`)).json()).data.status === 'READY', jobs[0].id);
  const chapters = await api(page, `/api/import-jobs/${jobs[0].id}/chapters`);
  for (const chapter of chapters.filter(c => ['Java 基础', 'JVM'].includes(c.name))) {
    const review = await api(page, `/api/chapters/${chapter.id}/review`);
    if (review.checkStatus !== 'IMPORTED') await write(page, `/api/chapters/${chapter.id}/review`, { operationId: await page.evaluate(() => crypto.randomUUID()), dataEpoch: snapshot.dataEpoch, expectedVersion: review.version, action: 'CONFIRM', payload: { allowFlagged: true } });
  }
  await page.goto(base + '/cards');await page.getByRole('region', { name: '题库结果' }).waitFor();
  const firstPage = await api(page, '/api/cards'), lastPage = await api(page, '/api/cards?offset=20');
  if (firstPage.total < 23 || firstPage.items.length !== 20 || !lastPage.items.length) throw new Error('Pagination mismatch');
  await page.getByRole('button', { name: '下一页', exact: true }).click();await page.waitForURL('**offset=20*');await page.getByText('第 2 页', { exact: true }).waitFor();
  await page.getByLabel('题目关键词').fill('不存在的关键词');await page.getByRole('button', { name: '搜索与筛选' }).click();await page.getByText('没有符合条件的题目。可以清除条件，或先确认导入章节。').waitFor();
  await page.getByRole('button', { name: '清除条件' }).click();await page.getByRole('region', { name: '题库结果' }).waitFor();
  await page.getByLabel('题目关键词').fill('HashMap');await page.getByRole('button', { name: '搜索与筛选' }).click();
  await page.getByRole('link', { name: /HashMap/ }).click();await page.getByRole('button', { name: '展开完整答案' }).waitFor();
  const id = page.url().split('/').pop(), initial = await api(page, `/api/cards/${id}`);
  if (await page.locator('[aria-label="完整答案"]').count() || await page.locator('[aria-label="回忆提示与要点"]').count()) throw new Error('Recall content initially visible');
  await page.getByRole('button', { name: '展开完整答案' }).click();
  for (const block of initial.contentBlocks) {
    const lastText = block.textSpans.at(-1)?.text;
    if (lastText && !(await page.locator('[aria-label="完整答案"]').innerText()).includes(lastText)) throw new Error('Full answer tail missing');
  }
  if (await page.locator('.card-sources li').count() !== initial.sources.length || !initial.needsCheck) throw new Error('Missing sources/check reminder');
  await page.getByRole('button', { name: '查看提示与要点' }).click();await page.getByText('使用提示后，请按本次独立回忆的程度评价自己。').waitFor();
  await page.screenshot({ path: 'output/p0-07/detail-desktop.png', fullPage: true });
  checks.push('正式题库分页、空搜索清除、HashMap 跨页全文尾部、默认回忆与持续标记、全部来源范围');
  // A committed write whose HTTP response is lost must recover from the original receipt.
  const notes = 'P0-07 笔记 ' + Date.now(); let lost = false;
  await page.getByRole('button', { name: '编辑笔记、提示与要点' }).click();
  await page.getByRole('textbox', { name: '个人笔记', exact: true }).fill(notes);await page.getByLabel('回忆提示', { exact: true }).fill('扩容与树化');
  await page.route(`**/api/cards/${id}`, async route => {
    if (!lost && route.request().method() === 'POST') { lost = true;await route.fetch();await route.abort('failed'); } else await route.continue();
  });
  await page.getByRole('button', { name: '保存编辑', exact: true }).click();await page.waitForFunction(() => !document.querySelector('.review-editor'));
  await page.reload();await page.unroute(`**/api/cards/${id}`);
  await page.getByRole('button', { name: '重试连接' }).click();await saved(page, id, `v.annotation.notes === '${notes}'`);
  const afterLoss = await api(page, `/api/cards/${id}`);
  if (afterLoss.annotation.version !== initial.annotation.version + 1) throw new Error('Duplicate annotation mutation');
  checks.push('笔记经 IndexedDB 保留，服务端已写但丢响应后重载只确认一次');
  const otherContext = await page.context().browser().newContext({ viewport: { width: 1280, height: 900 } });
  try {
    const other = await otherContext.newPage();await login(other);await other.goto(base + `/cards/${id}`);
    await other.getByRole('button', { name: '编辑笔记、提示与要点' }).click();await other.getByRole('textbox', { name: '个人笔记', exact: true }).fill('设备 B 本地笔记');
    await page.getByRole('button', { name: '编辑笔记、提示与要点' }).click();await page.getByRole('textbox', { name: '个人笔记', exact: true }).fill('设备 A 已保存笔记');await page.getByRole('button', { name: '保存编辑', exact: true }).click();
    await saved(page, id, "v.annotation.notes === '设备 A 已保存笔记'");
    await other.getByRole('button', { name: '保存编辑', exact: true }).click();await other.locator('.sync-label.needs-attention').waitFor();
    await other.getByText('核对本地操作与服务端内容', { exact: true }).click();
    const conflict = await other.locator('.sync-items').innerText();
    if (!conflict.includes('设备 A 已保存笔记') || !conflict.includes('设备 B 本地笔记')) throw new Error('Conflict evidence missing');
    await other.getByRole('button', { name: '核对后使用本地编辑重新保存' }).click();await saved(other, id, "v.annotation.notes === '设备 B 本地笔记'");
    checks.push('双独立会话笔记冲突保留双方，明确重提后服务端确认');
  } finally { await otherContext.close(); }
  await page.getByRole('button', { name: '读取最新卡片' }).click();await page.getByText('设备 B 本地笔记', { exact: true }).waitFor();
  const beforeFavorite = (await api(page, `/api/cards/${id}`)).annotation;
  if (!beforeFavorite.favorite) { await page.getByRole('button', { name: '收藏题目', exact: true }).click();await saved(page, id, `v.annotation.favorite === true && v.annotation.version > ${beforeFavorite.version}`); }
  const favorites = await api(page, '/api/cards?filter=FAVORITE');if (!favorites.items.some(c => c.id === id)) throw new Error('Favorite filter lost card: ' + JSON.stringify({ id, favorites }));
  const correction = '个人核对：以 JDK 实际实现为准 ' + Date.now();
  await page.getByRole('button', { name: '编辑技术纠错、版本与标签' }).click();await page.getByLabel('个人技术纠错', { exact: true }).fill(correction);await page.getByLabel('适用版本（未知请留空）').fill('JDK 21');await page.getByLabel('标签（以逗号分隔，最多 30 个）').fill('Java，集合');await page.getByRole('button', { name: '保存编辑', exact: true }).click();await saved(page, id, `v.technicalCorrection === '${correction}'`);
  await page.getByRole('button', { name: '展开完整答案' }).click();await page.getByRole('heading', { name: '个人技术纠错（人工补充）' }).waitFor();
  const beforeEdit = await api(page, `/api/cards/${id}`);
  await page.getByRole('button', { name: '修正内容与待检查标记' }).click();await page.getByLabel('本次调整原因').fill('P0-07 验收：保留来源、原文与待检查');
  await page.locator('#card-edit-question').fill(initial.question);await page.getByRole('button', { name: '保存编辑', exact: true }).click();await saved(page, id, `v.displayRevision === ${beforeEdit.displayRevision + 1}`);
  const corrected = await api(page, `/api/cards/${id}`);
  if (corrected.id !== initial.id || JSON.stringify(corrected.originalContent) !== JSON.stringify(initial.originalContent) || JSON.stringify(corrected.review) !== JSON.stringify(initial.review)) throw new Error('Reading/editing changed identity/evidence/schedule');
  checks.push('收藏、技术纠错、版本、标签及内容修正保留卡片、原文与复习状态');
  await page.locator('.card-sources a').first().click();await page.waitForFunction(() => Number(document.querySelector('canvas')?.getAttribute('data-rendered-page')) > 0);
  await page.locator('.pdf-highlight').waitFor();
  const originalPage = Number(await page.getByLabel('物理页', { exact: true }).inputValue());
  if (originalPage !== initial.sources[0].page) throw new Error('PDF physical page mismatch');
  const ink = await page.locator('canvas').evaluate(c => [...c.getContext('2d').getImageData(0,0,c.width,c.height).data].some((n,i) => i % 4 !== 3 && n < 200));
  if (!ink) throw new Error('Empty PDF rendering');
  await page.getByLabel('物理页', { exact: true }).fill(String(originalPage + 1));await page.getByRole('button', { name: '跳转', exact: true }).click();
  await page.waitForFunction(expected => Number(document.querySelector('canvas')?.getAttribute('data-rendered-page')) === expected, originalPage + 1);
  await page.screenshot({ path: 'output/p0-07/pdf-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 320, height: 740 });
  await page.getByLabel('缩放').selectOption('1');await page.waitForTimeout(200);await page.waitForFunction(expected => Number(document.querySelector('canvas')?.getAttribute('data-rendered-page')) === expected, originalPage + 1);
  if (await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)) throw new Error('PDF mobile overflow');
  await page.screenshot({ path: 'output/p0-07/pdf-mobile.png', fullPage: true });
  await page.getByRole('link', { name: '← 返回卡片题库' }).click();await page.getByRole('button', { name: '展开完整答案' }).click();
  // Deliberately use a very wide source line to verify scrolling stays inside the answer.
  await page.locator('[aria-label="完整答案"] .source-text').first().evaluate(el => { el.textContent += '\n' + 'wide-code-table-'.repeat(100); });
  if (await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)) throw new Error('Answer mobile overflow');
  const scrolls = await page.locator('[aria-label="完整答案"] .source-text').first().evaluate(el => el.scrollWidth > el.clientWidth);
  if (!scrolls) throw new Error('Wide answer does not scroll locally');
  await page.screenshot({ path: 'output/p0-07/detail-mobile.png', fullPage: true });
  checks.push('内置 PDF.js 实际绘制、物理页跳转、来源高亮；320px PDF 和宽内容局部滚动无页面溢出');
  // The only raster image in this PDF is on physical page 44, in the JUC chapter.
  const imageChapter = chapters.find(c => c.name.startsWith('JUC'));
  if (imageChapter) {
    const review = await api(page, `/api/chapters/${imageChapter.id}/review`);
    if (review.checkStatus !== 'IMPORTED') await write(page, `/api/chapters/${imageChapter.id}/review`, { operationId: await page.evaluate(() => crypto.randomUUID()), dataEpoch: snapshot.dataEpoch, expectedVersion: review.version, action: 'CONFIRM', payload: { allowFlagged: true } });
  }
  const imagePage = await api(page, `/api/cards?chapterId=${imageChapter?.id}&limit=100`);
  const details = await Promise.all(imagePage.items.map(c => api(page, `/api/cards/${c.id}`)));
  const illustrated = details.find(c => c.contentBlocks.some(b => b.images.length));
  if (illustrated) {
    await page.goto(base + `/cards/${illustrated.id}`);await page.getByRole('button', { name: '展开完整答案' }).click();
    await page.locator('.card-answer img').first().scrollIntoViewIfNeeded();
    await page.waitForFunction(() => [...document.querySelectorAll('.card-answer img')].some(i => i.complete && i.naturalWidth > 0));
    if (await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)) throw new Error('Source image mobile overflow');
    await page.screenshot({ path: 'output/p0-07/image-mobile.png', fullPage: true });checks.push('真实含图卡片加载整页原图并保留来源，320px 页面无溢出');
  } else throw new Error('Expected real image card from physical page 44');
  if (errors.length) throw new Error('Page errors: ' + errors.join('; '));
  return { checks, errors, cardId: id, sourceId: source.id, cardCount: firstPage.total };
}
