async page => {
  const base='http://127.0.0.1:5178';
  await page.addInitScript(()=>{window.p012Errors=[];window.addEventListener('error',e=>window.p012Errors.push(e.message));});
  await page.goto(base+'/login');await page.getByLabel('账号',{exact:true}).fill('p012-browser');await page.getByLabel('密码',{exact:true}).fill('p012-browser-only');await page.getByRole('button',{name:'进入学习空间',exact:true}).click();await page.waitForURL('**/sources');
  const source=await page.evaluate(async()=>(await(await fetch('/api/sync/snapshot')).json()).data.sources[0]);
  await page.setViewportSize({width:360,height:800});await page.goto(base+`/sources/${source.id}/read?page=8&bbox=0,80,612,792`);
  const rendered=async factor=>page.waitForFunction(factor=>{
    const canvas=document.querySelector('.pdf-reader canvas'),host=document.querySelector('.pdf-reader');
    return canvas?.dataset.renderedPage==='8'&&Math.abs(parseFloat(canvas.style.width)-Math.min(1224,host.clientWidth)*factor)<2;
  },factor);
  await rendered(1);await page.setViewportSize({width:1280,height:900});await rendered(1);
  await page.setViewportSize({width:360,height:800});await rendered(1);await page.getByLabel('缩放').selectOption('2');await rendered(2);
  await page.getByLabel('缩放').selectOption('1');await rendered(1);await page.screenshot({path:'D:/InterviewFlashcards/output/p0-12/pdf-mobile.png',fullPage:true});
  const errors=await page.evaluate(()=>window.p012Errors);if(errors.length)throw new Error(errors.join(';'));
  return {check:'真实222页PDF：360px→1280px→360px宽度切换、200%→适屏缩放，实际完成重绘且无ResizeObserver循环或其他window错误',windowErrors:errors};
}
