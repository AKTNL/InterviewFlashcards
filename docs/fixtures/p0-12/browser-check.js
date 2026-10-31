// Destructive acceptance check: ONLY the isolated p012-browser account on port 5178.
// Run backup-browser-check.js first to import, confirm and restore the real PDF.
async page => {
  const base='http://127.0.0.1:5178',checks=[],runtimeErrors=[];
  const check=(ok,why)=>{if(!ok)throw new Error(why);};
  const button=(p,name)=>p.getByRole('button',{name,exact:true});
  const store=p=>p.evaluate(()=>{const s=document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('sync');return {state:s.state,items:s.items,busy:s.busy};});
  const refresh=p=>p.evaluate(()=>document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('sync').refresh(true));
  const idle=async p=>p.waitForFunction(()=>{const s=document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('sync');return s?.state&&!s.busy;});
  async function login(p){await p.goto(base+'/login');await p.getByLabel('账号',{exact:true}).fill('p012-browser');await p.getByLabel('密码',{exact:true}).fill('p012-browser-only');await button(p,'进入学习空间').click();await p.waitForURL('**/sources');await idle(p);}
  async function call(p,path,method='GET',body){return p.evaluate(async({path,method,body})=>{
    const headers={'Content-Type':'application/json'};
    if(method!=='GET'){const c=(await(await fetch('/api/auth/csrf')).json()).data;headers[c.headerName]=c.token;}
    const r=await fetch(path,{method,headers,...(body?{body:JSON.stringify(body)}:{})});const result=await r.json();if(!r.ok)throw new Error(JSON.stringify(result));return result.data;
  },{path,method,body});}
  const noOverflow=async(p,label)=>{await p.waitForLoadState('networkidle');check(!await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth),label+' overflow');};
  for(const context of page.context().browser().contexts()) if(context!==page.context()) await context.close();
  page.on('pageerror',e=>runtimeErrors.push(e.message));await page.setViewportSize({width:1280,height:900});await login(page);
  const snapshot=await call(page,'/api/sync/snapshot'), deck=snapshot.data.deck[0],card=snapshot.data.card[0],second=snapshot.data.card[1];
  check(!!card&&snapshot.sources[0].pageCount===222,'Run backup acceptance first');
  const mobile=await page.context().browser().newContext({viewport:{width:360,height:800},isMobile:true,hasTouch:true}),phone=await mobile.newPage();
  phone.on('pageerror',e=>runtimeErrors.push(e.message));await login(phone);
  for(const p of [page,phone]) {
    for(const [name,path] of [['today','/'],['sources','/sources'],['cards','/cards'],['settings','/settings'],['backup','/backups'],['data','/data']]) {
      await p.goto(base+path);await p.locator('h1').waitFor();await noOverflow(p,name);
      if(p===phone) await p.screenshot({path:`D:/InterviewFlashcards/output/p0-12/${name}-mobile.png`,fullPage:true});
    }
  }
  checks.push('首页、资料、题库、设置、同步、备份、数据操作在 1280px / 360px 均无页面横向溢出');
  await phone.goto(base+'/cards/'+card.id);await button(phone,'展开完整答案').click();await phone.getByRole('heading',{name:'完整答案',exact:true}).waitFor();await noOverflow(phone,'full answer');
  await phone.screenshot({path:'D:/InterviewFlashcards/output/p0-12/answer-mobile.png',fullPage:true});
  const firstSource=phone.locator('.card-sources a').first();await firstSource.click();await phone.waitForFunction(()=>Number(document.querySelector('.pdf-reader canvas')?.dataset.renderedPage)>0);await noOverflow(phone,'PDF');await phone.screenshot({path:'D:/InterviewFlashcards/output/p0-12/pdf-mobile.png',fullPage:true});
  await phone.goto(base+`/practice?cards=${card.id},${second.id}`);await idle(phone);await button(phone,'开始主动回忆').click();await phone.waitForURL(/\/practice\/[0-9a-f-]+/);await button(phone,'展开完整答案').click();
  for(const name of ['不会','模糊','记住了']) { const b=button(phone,name),box=await b.boundingBox();check(box&&box.height>=44&&box.width>=44,'Small rating target'); }
  await button(phone,'记住了').focus();await phone.keyboard.press('Tab');await phone.keyboard.press('Shift+Tab');check(await button(phone,'记住了').evaluate(el=>getComputedStyle(el).outlineStyle!=='none'),'Missing keyboard focus outline');
  // Same-day score of the previously learned card may prompt for early review.
  phone.on('dialog',d=>d.accept());await button(phone,'记住了').click();await phone.getByText(/已保存。下次复习/).first().waitFor();await idle(phone);
  checks.push('360px 完整答案阅读、来源 PDF 渲染、带文字的 44px 以上自评按钮与焦点、评分收据确认');
  await button(phone,'展开完整答案').click();const oldSessionId=phone.url().split('?')[0].split('/').pop();
  await mobile.setOffline(true);await button(phone,'不会').click();await phone.getByText(/操作已保留，请等待/).waitFor();
  await page.goto(base+'/data');await idle(page);await page.getByLabel('操作题库').selectOption(deck.id);await button(page,'读取影响预览').click();await page.getByRole('region',{name:'数据操作影响预览'}).waitFor();
  check(await button(page,'确认重置进度').isDisabled(),'Consent missing');
  await page.getByRole('checkbox',{name:/我已核对影响/}).check();
  const current=await call(page,`/api/cards/${card.id}`);
  await call(page,`/api/cards/${card.id}`,'POST',{operationId:await page.evaluate(()=>crypto.randomUUID()),dataEpoch:snapshot.dataEpoch,expectedVersion:current.annotation.version,action:'ANNOTATION',payload:{notes:'重置应保留的笔记',hint:'',memoryPoints:'',favorite:true}});
  await refresh(page);await page.getByText('数据已变化，请重新读取影响预览并确认。',{exact:true}).waitFor();check(await button(page,'确认重置进度').isDisabled(),'Stale consent accepted');
  await button(page,'读取影响预览').click();await page.getByRole('checkbox',{name:/我已核对影响/}).check();await button(page,'确认重置进度').click();
  await page.getByText('学习进度已重置，卡片、笔记和收藏已保留。',{exact:true}).waitFor();
  const reset=await call(page,'/api/sync/snapshot');check(reset.data.card.length===snapshot.data.card.length&&!reset.data.review_event.length&&!reset.data.review_state.length,'Reset scope wrong');check(reset.data.personal_annotation.find(a=>a.card_id===card.id).notes==='重置应保留的笔记','Reset removed note');
  await mobile.setOffline(false);await refresh(phone);
  await phone.getByText(/题库已删除或进度已重置，此会话已失效/).waitFor();
  await phone.waitForFunction(()=>document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('sync').items.some(i=>i.kind==='review.write'&&i.status==='needs-attention'));
  check(!(await call(phone,'/api/sync/snapshot')).data.review_event.length,'Old phone replay changed history');await phone.screenshot({path:'D:/InterviewFlashcards/output/p0-12/reset-old-device-mobile.png',fullPage:true});
  checks.push('预览期间另一设备修改使确认失效；重置保留内容/笔记/收藏，旧手机离线评分被拒绝并保留核对，旧会话无评分入口');
  await page.getByLabel('操作类型').selectOption('DELETE');await button(page,'读取影响预览').click();await page.getByRole('checkbox',{name:/我已核对影响/}).check();await button(page,'确认删除题库').click();await page.getByText('题库已删除，原 PDF 保留在我的资料。',{exact:true}).waitFor();
  await refresh(phone);await phone.goto(base+'/practice/'+oldSessionId);await phone.getByText(/题库已删除或进度已重置，此会话已失效/).waitFor();
  const deleted=await call(page,'/api/sync/snapshot');check(!deleted.data.deck.length&&!deleted.data.card.length&&!deleted.data.draft_card.length&&deleted.sources.length===snapshot.sources.length,'Delete scope wrong');
  await page.goto(base+'/cards');await page.getByText('还没有题库，从上传 PDF 并逐章检查开始。',{exact:true}).waitFor();await noOverflow(page,'empty library');
  await phone.goto(base+'/cards?q=does-not-exist');await phone.getByRole('link',{name:'上传或继续检查章节'}).waitFor();await noOverflow(phone,'empty mobile');await phone.screenshot({path:'D:/InterviewFlashcards/output/p0-12/deleted-empty-mobile.png',fullPage:true});
  checks.push('明确确认删除题库，原 PDF 保留，其他设备旧会话失效，无题库空态提供重新导入入口');
  check(runtimeErrors.length===0,runtimeErrors.join(';'));await mobile.close();return {checks,runtimeErrors};
}
