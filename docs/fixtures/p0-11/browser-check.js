// Run only against a fresh isolated p011-browser account; never point this at personal data.
async page => {
  const base='http://127.0.0.1:5177', checks=[], runtimeErrors=[];
  page.on('pageerror',e=>runtimeErrors.push(e.message));
  const button=(name,p=page)=>p.getByRole('button',{name,exact:true});
  async function login(p) {
    await p.goto(base+'/login');await p.getByLabel('账号',{exact:true}).fill('p011-browser');await p.getByLabel('密码',{exact:true}).fill('p011-browser-only');
    await button('进入学习空间',p).click();await p.waitForURL('**/sources');
    await p.waitForFunction(()=>document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('sync')?.state);
  }
  async function call(p,path,method='GET',body) {
    return p.evaluate(async({path,method,body})=>{
      const headers={'Content-Type':'application/json'};
      if(method!=='GET') { const c=(await(await fetch('/api/auth/csrf')).json()).data;headers[c.headerName]=c.token; }
      const r=await fetch(path,{method,headers,...(body?{body:JSON.stringify(body)}:{})});const result=await r.json();if(!r.ok)throw new Error(JSON.stringify(result));return result.data;
    },{path,method,body});
  }
  const uuid=()=>page.evaluate(()=>crypto.randomUUID());
  const refresh=p=>p.evaluate(()=>document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('sync').refresh(true));
  const check=(ok,why)=>{if(!ok)throw new Error(why);};
  for(const context of page.context().browser().contexts()) if(context!==page.context()) await context.close();
  await page.context().clearCookies();await page.setViewportSize({width:1280,height:900});await login(page);
  let snapshot=await call(page,'/api/sync/snapshot');
  if(!snapshot.sources.length) {
    await page.locator('input[type=file]').setInputFiles('D:/InterviewFlashcards/docs/Java+agent八股(高频版).pdf');
    await page.getByRole('link',{name:'查看章节与草稿'}).first().waitFor({timeout:120000});snapshot=await call(page,'/api/sync/snapshot');
  }
  const jobs=await call(page,`/api/import-jobs?sourceId=${snapshot.sources[0].id}`);
  await page.waitForFunction(async id=>(await(await fetch(`/api/import-jobs/${id}`)).json()).data.status==='READY',jobs[0].id,{timeout:120000});
  const chapters=await call(page,`/api/import-jobs/${jobs[0].id}/chapters`),chapter=chapters.find(c=>c.name==='Java 基础');
  const review=await call(page,`/api/chapters/${chapter.id}/review`);
  if(review.checkStatus!=='IMPORTED') await call(page,`/api/chapters/${chapter.id}/review`,'POST',{operationId:await uuid(),dataEpoch:snapshot.dataEpoch,expectedVersion:review.version,action:'CONFIRM',payload:{allowFlagged:true}});
  snapshot=await call(page,'/api/sync/snapshot');const card=snapshot.data.card[0],second=snapshot.data.card[1],epoch=snapshot.dataEpoch;
  const annotate=async(notes,version)=>call(page,`/api/cards/${card.id}`,'POST',{operationId:await uuid(),dataEpoch:epoch,expectedVersion:version,action:'ANNOTATION',payload:{notes,hint:'备份提示',memoryPoints:'完整恢复',favorite:true}});
  const initialVersion=snapshot.data.personal_annotation.find(a=>a.card_id===card.id)?.version??0;
  const annotation=await annotate('备份时的笔记',initialVersion),annotationVersion=annotation.card.version;
  const started=await call(page,'/api/learning-sessions','POST',{operationId:await uuid(),dataEpoch:epoch,cardIds:[card.id,second.id]});
  const session=started.session;
  await call(page,`/api/learning-sessions/${session.id}/review`,'POST',{operationId:await uuid(),dataEpoch:epoch,expectedVersion:session.version,action:'RATE',payload:{itemId:session.current.id,expectedReviewVersion:session.current.expectedVersion,answerRevealed:true,confirmEarly:true,rating:'REMEMBERED'}});
  const before=await call(page,'/api/sync/snapshot');
  const mobile=await page.context().browser().newContext({viewport:{width:360,height:800},timezoneId:'America/Los_Angeles'}),phone=await mobile.newPage();phone.on('pageerror',e=>runtimeErrors.push(e.message));await login(phone);
  await mobile.setOffline(true);
  await phone.evaluate(async({cardId,version})=>{const s=document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('sync');await s.enqueue({kind:'card.update',name:'恢复前离线笔记',cardId,action:'ANNOTATION',expectedVersion:version,payload:{notes:'旧手机离线笔记',hint:'',memoryPoints:'',favorite:false}});},{cardId:card.id,version:annotationVersion});
  await page.goto(base+'/backups');const exportResponse=page.waitForResponse(r=>r.url().endsWith('/api/backups')&&r.request().method()==='POST');await button('导出当前完整数据').click();
  const exported=(await(await exportResponse).json()).data;
  const link=page.locator(`a[href="/api/backups/${exported.id}/download"]`);await link.waitFor({timeout:120000});
  await page.screenshot({path:'D:/InterviewFlashcards/output/p0-11/export-desktop.png',fullPage:true});
  const downloadPromise=page.waitForEvent('download');await link.click();const download=await downloadPromise;await download.saveAs('D:/InterviewFlashcards/output/p0-11/roundtrip.zip');
  checks.push('真实 222 页 PDF 与未确认草稿、笔记提示收藏、评分历史和会话异步导出并实际下载 ZIP');
  await annotate('导出后继续修改的笔记',annotationVersion);
  await page.locator('input[type=file]').setInputFiles('D:/InterviewFlashcards/output/p0-11/roundtrip.zip');await button('上传并预检').click();
  await page.getByText('校验通过，等待替换确认',{exact:true}).waitFor({timeout:120000});
  check(await button('确认替换并恢复').isDisabled(),'Missing explicit confirmation');
  await page.screenshot({path:'D:/InterviewFlashcards/output/p0-11/restore-preview-desktop.png',fullPage:true});
  await page.setViewportSize({width:360,height:800});await page.screenshot({path:'D:/InterviewFlashcards/output/p0-11/restore-preview-mobile.png',fullPage:true});
  check(!await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),'Mobile overflow');
  await page.getByRole('checkbox',{name:/我已核对替换范围/}).check();await button('确认替换并恢复').click();
  await page.getByText(/恢复成功。复习日期、笔记、草稿和来源资源已恢复/).waitFor({timeout:120000});
  const after=await call(page,'/api/sync/snapshot');check(after.dataEpoch!==epoch,'Epoch not switched');
  for(const name of Object.keys(before.data))check(after.data[name].length===before.data[name].length,`Row count mismatch ${name}`);
  const oldState=before.data.review_state[0],newState=after.data.review_state[0];for(const key of ['due_date','first_learned_at','last_reviewed_at','stage','latest_rating','lapse_count'])check(oldState[key]===newState[key],`State mismatch ${key}`);
  check(after.data.personal_annotation[0].notes==='备份时的笔记'&&after.data.personal_annotation[0].favorite,'Annotation not restored');
  const source=after.sources[0],pdf=await page.request.get(base+`/api/sources/${source.id}/content`);check(pdf.ok()&&(await pdf.body()).length===source.byteSize,'Restored PDF unavailable');
  const createdAt=before.data.learning_session.find(s=>s.id===session.id).created_at;
  const recoveredSession=after.data.learning_session.find(s=>s.created_at===createdAt),resumed=await call(page,`/api/learning-sessions/${recoveredSession.id}`);check(resumed.current&&resumed.events.length===1,'Session not resumable');
  checks.push('导出后继续写入；恢复预检、数据数量预览与显式替换；原笔记、来源 PDF、草稿、历史、dueDate 和可恢复会话往返一致');
  await mobile.setOffline(false);await refresh(phone);
  // Reconnection also starts the normal online listener; wait for that refresh if it won the race.
  await phone.waitForFunction(epoch=>{const s=document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('sync');return !s.busy&&s.state?.dataEpoch===epoch&&s.items.some(i=>i.name==='恢复前离线笔记'&&i.status==='needs-attention');},after.dataEpoch);
  const state=await phone.evaluate(()=>{const s=document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('sync');return{epoch:s.state.dataEpoch,items:s.items.map(i=>({name:i.name,status:i.status,code:i.error?.code,payload:i.payload}))};});
  check(state.epoch===after.dataEpoch&&state.items.some(i=>i.name==='恢复前离线笔记'&&i.status==='needs-attention'&&i.code==='EPOCH_EXPIRED'),'Old offline edit was replayed or lost');
  const authoritative=await call(phone,'/api/sync/snapshot');check(authoritative.data.personal_annotation[0].notes==='备份时的笔记','Old phone overwrote restored annotation');
  const oldWrite=await phone.evaluate(async({epoch,id})=>{const c=(await(await fetch('/api/auth/csrf')).json()).data;const r=await fetch('/api/learning-sessions',{method:'POST',headers:{'Content-Type':'application/json',[c.headerName]:c.token},body:JSON.stringify({operationId:crypto.randomUUID(),dataEpoch:epoch,cardIds:[id]})});return{status:r.status,code:(await r.json()).error.code};},{epoch,id:card.id});
  check(oldWrite.status===409&&oldWrite.code==='EPOCH_EXPIRED','Old epoch accepted');
  await phone.screenshot({path:'D:/InterviewFlashcards/output/p0-11/old-device-mobile.png',fullPage:true});
  checks.push('360px、洛杉矶时区独立手机会话离线编辑；恢复后自动刷新代次、隔离旧队列并保留核对内容；旧代次请求返回 409');
  await page.screenshot({path:'D:/InterviewFlashcards/output/p0-11/restore-result-mobile.png',fullPage:true});
  check(runtimeErrors.length===0,runtimeErrors.join(';'));await mobile.close();
  return{checks,runtimeErrors,sourcePages:222,cardCount:after.data.card.length,draftCount:after.data.draft_card.length,oldEpoch:epoch,newEpoch:after.dataEpoch};
}
