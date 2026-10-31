// Run from the repository root against the isolated fresh test space described in README.md.
const fs = require('node:fs');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
(async () => {
  fs.mkdirSync('output/p0-09', { recursive: true });
  const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_BROWSER_CHANNEL || 'msedge', headless: true });
  const context = await browser.newContext({ timezoneId: 'Asia/Shanghai' });const page = await context.newPage();
  try {
    const run = eval('(' + fs.readFileSync('docs/fixtures/p0-09/browser-check.js', 'utf8') + ')');const result = await run(page);
    fs.writeFileSync('output/p0-09/browser-result.json', JSON.stringify(result, null, 2));console.log(JSON.stringify(result));
  } catch (error) {
    console.error(error);console.error((await page.locator('body').innerText()).slice(-5000));
    await page.screenshot({ path: 'output/p0-09/browser-failure.png', fullPage: true });process.exitCode = 1;
  } finally { await browser.close(); }
})();
