// Fixed loopback site and disposable identities only. Requires a fresh local test schema.
const fs=require('node:fs');
const path=require('node:path');
const { chromium }=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
process.chdir(path.resolve(__dirname,'../../..'));
(async()=>{
 const browser=await chromium.launch({channel:process.env.PLAYWRIGHT_BROWSER_CHANNEL || 'msedge',headless:true});
 const context=await browser.newContext({viewport:{width:1280,height:900},timezoneId:'Asia/Shanghai'});
 const page=await context.newPage();
 try {
  await page.goto('http://127.0.0.1:5178/login');
  await eval('('+fs.readFileSync(path.join(__dirname,'setup-browser.js'),'utf8')+')')(page);
  const result=await eval('('+fs.readFileSync(path.join(__dirname,'browser-check.js'),'utf8')+')')(page);
  fs.writeFileSync(path.join(__dirname,'browser-result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
 } catch(error) {console.error(error);process.exitCode=1;}
 finally {await browser.close();}
})();
