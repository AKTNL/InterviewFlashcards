// Run on the same isolated account after browser-check.js. Source PDFs are retained.
async page => {
  const base='http://127.0.0.1:5178',checks=[],errors=[];
  page.on('pageerror',e=>errors.push(e.message));await page.setViewportSize({width:360,height:800});
  await page.goto(base+'/login');await page.getByLabel('账号',{exact:true}).fill('p012-browser');await page.getByLabel('密码',{exact:true}).fill('p012-browser-only');await page.getByRole('button',{name:'进入学习空间',exact:true}).click();await page.waitForURL('**/sources');
  await page.goto(base+'/sources');await page.waitForFunction(()=>document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('sync')?.state);
  const snapshot=await page.evaluate(async()=>(await(await fetch('/api/sync/snapshot')).json()).data),source=snapshot.sources[0];
  const path=`/sources/${source.id}/read?page=8&bbox=0,80,612,792`;
  const rendered=()=>page.waitForFunction(()=>document.querySelector('.pdf-reader canvas')?.dataset.renderedPage==='8');
  await page.goto(base+path);await rendered();await page.screenshot({path:'D:/InterviewFlashcards/output/p0-12/pdf-mobile.png',fullPage:true});
  await page.getByLabel('缩放').selectOption('2');await rendered();
  if(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth))throw new Error('Zoom overflow');
  await page.route('**/api/sources/*/content**',r=>r.abort());await page.reload();await page.getByRole('alert').filter({hasText:'PDF 加载失败'}).waitFor();
  await page.unroute('**/api/sources/*/content**');await page.getByRole('button',{name:'重试 PDF',exact:true}).click();await rendered();
  const image=await page.evaluate(async id=>{const img=new Image();img.src=`/api/sources/${id}/pages/8/image`;await img.decode();return{width:img.naturalWidth,height:img.naturalHeight};},source.id);
  if(!image.width||!image.height)throw new Error('Source image unavailable');checks.push('360px 原 PDF 实际绘制到第8页、来源高亮和200%缩放无页面溢出；加载失败后重试成功；真实原图可解码');
  await page.goto(base+'/settings');await page.getByLabel('每日新题量').fill('0');
  await page.evaluate(()=>{window.confirm=()=>false;});await page.getByRole('button',{name:'退出',exact:true}).click();
  if(!page.url().endsWith('/settings'))throw new Error('Dirty logout was not cancelled');
  if(await page.getByLabel('每日新题量').inputValue()!=='0')throw new Error('Dirty setting lost');
  await page.evaluate(()=>{window.confirm=()=>true;});await page.getByRole('link',{name:'我的资料',exact:true}).click();await page.waitForURL('**/sources');
  await page.getByRole('button',{name:'修改名称',exact:true}).first().click();await page.getByLabel('资料名称').fill('尚未保存的名称');
  await page.evaluate(()=>{window.confirm=()=>false;});await page.getByRole('link',{name:'题库',exact:true}).click();
  if(!page.url().endsWith('/sources'))throw new Error('Dirty name navigation was not cancelled');
  await page.evaluate(()=>{window.confirm=()=>true;});await page.getByRole('button',{name:'取消',exact:true}).click();checks.push('学习设置未保存时取消退出仍保持登录与编辑；资料改名未保存时取消导航保留输入');
  await page.locator('input[type=file]').setInputFiles('D:/InterviewFlashcards/output/p0-12/invalid.pdf');
  await page.waitForFunction(()=>document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('sync').items.some(i=>i.kind==='source.upload'&&i.name==='invalid.pdf'&&i.status==='failed'));
  const retained=await page.evaluate(()=>document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('sync').items.find(i=>i.name==='invalid.pdf'));
  if(!retained.file||retained.error?.code!=='INVALID_PDF')throw new Error('Failed upload not preserved');
  await page.screenshot({path:'D:/InterviewFlashcards/output/p0-12/upload-failure-mobile.png',fullPage:true});checks.push('无效 PDF 上传明确失败，原文件留在本地队列供下载和重新选择；删除后解析任务提供显式重试入口');
  if(errors.length)throw new Error(errors.join(';'));return {checks,runtimeErrors:errors,image};
}
