// Run from the repository root against the disposable p005-browser account.
async page => {
  const base = 'http://127.0.0.1:5173';
  await page.setViewportSize({ width: 1280, height: 900 });
  const errors = []; const checks = [];
  page.on('pageerror', error => errors.push(error.message));
  async function signIn(target) {
    await target.goto(base + '/sources');
    await target.waitForFunction(() => location.pathname === '/login' || !!document.querySelector('input[type=file]'));
    if (target.url().endsWith('/login')) {
      await target.getByLabel('账号', { exact: true }).fill('p005-browser');
      await target.getByLabel('密码', { exact: true }).fill('p005-browser-only');
      await target.getByRole('button', { name: '进入学习空间' }).click();
    }
    await target.waitForURL('**/sources');
    await target.waitForFunction(() => { const input = document.querySelector('input[type=file]'); return input && !input.disabled; });
  }
  async function api(target, path) {
    return target.evaluate(async path => { const result = await fetch(path); if (!result.ok) throw new Error('HTTP ' + result.status); return (await result.json()).data; }, path);
  }
  await signIn(page);
  await page.locator('input[type=file]').setInputFiles('docs/Java+agent八股(高频版).pdf');
  await page.getByRole('link', { name: '查看章节与草稿' }).first().waitFor({ timeout: 60000 });
  const sources = await api(page, '/api/sources');
  if (sources.length !== 1 || sources[0].pageCount !== 222) throw new Error('Source count/page count mismatch');
  const jobs = await api(page, '/api/import-jobs');
  const job = jobs.find(j => j.sourceId === sources[0].id);
  if (jobs.length !== 1 || job.status !== 'READY' || job.report.candidateCount !== 158 || job.report.chapters.length !== 13) throw new Error('Task/report mismatch');
  checks.push('真实 PDF 自动后台解析：222 页、158 草稿、13 章');
  await page.locator('input[type=file]').setInputFiles('docs/Java+agent八股(高频版).pdf');
  await page.getByText('相同 PDF 已存在，已关联原有资料。').first().waitFor();
  if ((await api(page, '/api/import-jobs')).length !== 1) throw new Error('Duplicate import task');
  checks.push('重复上传复用来源和任务');
  await page.getByRole('link', { name: '查看章节与草稿' }).click();
  await page.getByRole('heading', { name: '章节识别与数量核对' }).waitFor();
  if (await page.locator('tbody tr').count() !== 13) throw new Error('Missing chapter report rows');
  await page.locator('.chapter-buttons button').filter({ hasText: 'Java 基础' }).click();
  await page.getByRole('button', { name: 'HashMap 底层原理和扩容机制', exact: false }).click();
  await page.getByRole('article', { name: '草稿全文' }).getByRole('heading', { level: 2 }).waitFor();
  const text = await page.locator('.draft-preview').innerText();
  if (!text.includes('物理页 10') || !text.includes('物理页 11') || !text.includes('物理页 12') || !text.includes('追加追问（4）')) throw new Error('Cross-page answer/followups missing');
  await page.getByText('查看第 10 页原图（整页，含周边内容）', { exact: true }).click();
  await page.locator('.source-page-image').first().waitFor();
  await page.waitForFunction(() => { const img = document.querySelector('.source-page-image'); return img?.complete && img.naturalWidth > 0; });
  checks.push('HashMap 全文、三页来源、4 个追问和鉴权原图');
  await page.screenshot({ path: 'output/p0-05/draft-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 320, height: 740 });
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth);
  if (overflow) throw new Error('Mobile page overflow');
  await page.screenshot({ path: 'output/p0-05/draft-mobile.png', fullPage: true });
  checks.push('320px 草稿阅读、表格和代码局部滚动');
  const path = new URL(page.url()).pathname;
  await page.reload();
  await page.getByRole('heading', { name: '章节识别与数量核对' }).waitFor();
  checks.push('刷新后从持久化任务继续读取草稿');
  const context = await page.context().browser().newContext({ viewport: { width: 1280, height: 900 } });
  try {
    const second = await context.newPage(); await signIn(second); await second.goto(base + path);
    await second.getByRole('heading', { name: '章节识别与数量核对' }).waitFor();
    if (await second.locator('tbody tr').count() !== 13) throw new Error('Second device chapters mismatch');
    checks.push('独立会话可读取同一持久化任务和 13 章报告');
  } finally { await context.close(); }
  if (errors.length) throw new Error(errors.join('\n'));
  return { passed: true, checks, pageErrors: errors };
}
