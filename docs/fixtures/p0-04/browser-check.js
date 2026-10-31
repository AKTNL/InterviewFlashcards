// Run from the repository root with playwright-cli run-code --filename=docs/fixtures/p0-04/browser-check.js.
// Only use with the disposable p004-browser account; see README.md for setup.
async page => {
  const base = 'http://127.0.0.1:5173';
  const checks = []; const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  async function signIn(target) {
    await target.goto(base + '/sources');
    await target.waitForFunction(() => location.pathname === '/login' || !!document.querySelector('input[type=file]'));
    if (target.url().endsWith('/login')) {
      await target.getByLabel('账号', { exact: true }).fill('p004-browser');
      await target.getByLabel('密码', { exact: true }).fill('p003-browser-only');
      await target.getByRole('button', { name: '进入学习空间' }).click();
    }
    await target.waitForURL('**/sources');
    await target.waitForFunction(() => { const input = document.querySelector('input[type=file]'); return input && !input.disabled; });
  }
  async function edit(target, name) {
    await target.getByRole('button', { name: '修改名称', exact: true }).click();
    await target.getByLabel('资料名称', { exact: true }).fill(name);
    await target.getByRole('button', { name: '保存名称', exact: true }).click();
  }
  async function synced(target, name) {
    await target.locator('.sync-items li').filter({ hasText: name }).filter({ has: target.locator('.sync-label.synced') }).first().waitFor();
  }
  async function rows(target) {
    return target.evaluate(() => new Promise((resolve, reject) => {
      const opening = indexedDB.open('interview-flashcards-sync', 1);
      opening.onerror = () => reject(opening.error);
      opening.onsuccess = () => {
        const request = opening.result.transaction('mutations').objectStore('mutations').getAll();
        request.onsuccess = () => { resolve(request.result.map(row => ({ ...row, file: row.file ? { size: row.file.size, blob: row.file instanceof Blob } : null }))); opening.result.close(); };
      };
    }));
  }
  const snapshot = target => target.evaluate(async () => (await (await fetch('/api/sync/snapshot')).json()).data);
  await signIn(page);
  await page.locator('input[type=file]').setInputFiles('docs/Java+agent八股(高频版).pdf');
  await page.locator('.source-row').waitFor();
  await page.locator('.sync-label.synced').first().waitFor();
  if (!(await page.locator('.source-row').innerText()).includes('222 页')) throw new Error('Real PDF page count');
  checks.push('real PDF upload with receipt');

  const beforeLost = await snapshot(page); let patchCalls = 0;
  await page.route('**/api/sources/*', async route => {
    if (route.request().method() !== 'PATCH') return route.continue();
    patchCalls++;
    await route.fetch(); // Commit the write, then drop only the response.
    await route.abort('connectionreset');
  });
  await edit(page, '丢失响应.pdf');
  await page.getByText('待同步', { exact: true }).waitFor();
  const uncertain = (await rows(page)).find(row => row.name === '丢失响应.pdf');
  await page.reload();
  await synced(page, '丢失响应.pdf');
  await page.unroute('**/api/sources/*');
  const afterLost = await snapshot(page);
  const acknowledged = (await rows(page)).find(row => row.name === '丢失响应.pdf');
  if (patchCalls !== 1 || afterLost.revision !== beforeLost.revision + 1 || acknowledged.operationId !== uncertain.operationId) throw new Error('Lost response replay wrote twice or changed operation ID');
  checks.push('lost response + reload resolves original receipt, exactly one PATCH and revision');

  await page.route('**/api/sync/**', route => route.abort('internetdisconnected'));
  await edit(page, '离线保留.pdf');
  await page.getByText('待同步', { exact: true }).waitFor();
  const offline = (await rows(page)).find(row => row.name === '离线保留.pdf');
  // Queue the actual PDF Blob too; neither enqueue depends on a successful network request.
  await page.locator('input[type=file]').setInputFiles('docs/Java+agent八股(高频版).pdf');
  await page.waitForFunction(() => document.querySelectorAll('.sync-label.pending').length === 2);
  await page.reload();
  await page.waitForFunction(() => document.querySelectorAll('.sync-label.pending').length === 2);
  const persisted = await rows(page);
  if (!persisted.some(row => row.operationId === offline.operationId && row.status === 'pending')) throw new Error('Offline edit lost on reload');
  if (!persisted.some(row => row.status === 'pending' && row.file?.blob && row.file.size > 1000)) throw new Error('IndexedDB PDF Blob lost on reload');
  await page.setViewportSize({ width: 320, height: 900 });
  await page.screenshot({ path: 'output/p0-04/pending-mobile.png', fullPage: true });
  if (await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)) throw new Error('320px pending layout overflow');
  await page.unroute('**/api/sync/**');
  await page.getByRole('button', { name: '重试连接', exact: true }).click();
  await synced(page, '离线保留.pdf');
  await page.waitForFunction(() => !document.querySelector('.sync-label.pending'));
  await page.getByRole('heading', { name: '离线保留.pdf', exact: true }).waitFor();
  checks.push('real IndexedDB persists edit + PDF Blob across offline reload; reconnect confirms both');

  const mobile = await page.context().browser().newContext({ viewport: { width: 320, height: 900 }, isMobile: true, hasTouch: true });
  try {
    const phone = await mobile.newPage(); phone.on('pageerror', error => errors.push(error.message));
    await signIn(phone);
    await phone.getByRole('button', { name: '修改名称', exact: true }).click();
    await phone.getByLabel('资料名称', { exact: true }).fill('手机候选.pdf');
    await edit(page, '电脑生效.pdf'); await synced(page, '电脑生效.pdf');
    await phone.getByRole('button', { name: '保存名称', exact: true }).click();
    await phone.getByText('待处理', { exact: true }).waitFor();
    await phone.getByText('服务端名称：电脑生效.pdf', { exact: true }).waitFor();
    const conflict = (await rows(phone)).find(row => row.name === '手机候选.pdf');
    await phone.reload();
    await phone.getByText('服务端名称：电脑生效.pdf', { exact: true }).waitFor();
    await phone.getByRole('button', { name: '重试连接', exact: true }).click();
    if ((await snapshot(phone)).sources[0].originalFilename !== '电脑生效.pdf') throw new Error('Conflict silently overwritten');
    await phone.screenshot({ path: 'output/p0-04/conflict-mobile.png', fullPage: true });
    if (await phone.evaluate(() => document.documentElement.scrollWidth > innerWidth)) throw new Error('320px conflict layout overflow');
    await phone.getByRole('button', { name: '使用本地名称重新保存', exact: true }).click();
    await synced(phone, '手机候选.pdf');
    const resolved = (await rows(phone)).find(row => row.name === '手机候选.pdf');
    if (resolved.operationId === conflict.operationId) throw new Error('Conflict resolution reused old operation');
    await page.getByRole('button', { name: '刷新', exact: true }).click();
    await page.getByRole('heading', { name: '手机候选.pdf', exact: true }).waitFor();
    checks.push('two independent sessions preserve conflict values across reload; explicit resubmit uses new ID/version');
  } finally { await mobile.close(); }

  await edit(page, 'bad/name.pdf');
  await page.getByText('失败', { exact: true }).waitFor();
  if (!(await rows(page)).some(row => row.name === 'bad/name.pdf' && row.status === 'failed')) throw new Error('Validation failure lost local content');
  checks.push('validation failure remains visible and retained');

  await page.route('**/api/sync/**', route => route.abort('internetdisconnected'));
  await edit(page, '恢复前未同步.pdf');
  await page.getByText('待同步', { exact: true }).waitFor();
  await page.unroute('**/api/sync/**');
  await page.route('**/api/sync/snapshot', async route => {
    const response = await route.fetch(); const body = await response.json();
    body.data.dataEpoch = '11111111-1111-1111-1111-111111111111'; body.data.sources = [];
    await route.fulfill({ response, json: body });
  });
  await page.getByRole('button', { name: '重试连接', exact: true }).click();
  await page.getByText('数据已替换，本地内容保留供核对，请勿直接重试。', { exact: true }).first().waitFor();
  if (await page.locator('.source-row').count()) throw new Error('Old server cache survived epoch change');
  if (!(await rows(page)).some(row => row.name === '恢复前未同步.pdf' && row.status === 'needs-attention')) throw new Error('Old operation not quarantined');
  checks.push('simulated restore snapshot clears cache and quarantines old epoch edits without replay');
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.screenshot({ path: 'output/p0-04/epoch-desktop.png', fullPage: true });
  await page.unroute('**/api/sync/snapshot');
  if (errors.length) throw new Error(errors.join('\n'));
  return { passed: true, checks, pageErrors: errors };
}
