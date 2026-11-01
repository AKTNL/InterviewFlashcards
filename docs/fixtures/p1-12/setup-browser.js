async page => {
  await page.getByLabel('账号', {exact:true}).fill('p112_manager');
  await page.getByLabel('密码', {exact:true}).fill('p112-local-only-password');
  await page.getByRole('button',{name:'进入学习空间',exact:true}).click();
  await page.getByRole('link',{name:'管理后台',exact:true}).click();
  await page.getByRole('heading',{name:'管理后台',exact:true}).waitFor();
  await page.getByLabel('用户名',{exact:true}).fill('p112_friend');
  await page.getByLabel('昵称（可选）',{exact:true}).fill('验收朋友');
  await page.getByLabel('一次性初始密码',{exact:true}).fill('p112-initial-password');
  await page.getByRole('button',{name:'创建普通用户',exact:true}).click();
  await page.getByRole('heading',{name:'管理 p112_friend',exact:true}).waitFor();
  page.once('dialog',d=>d.accept());
  await page.getByLabel('开放个人邀请',{exact:true}).check();
  await page.getByLabel('累计成功总额度',{exact:true}).fill('2');
  await page.getByRole('button',{name:'保存邀请权限',exact:true}).click();
  await page.getByRole('heading',{name:'管理 p112_friend',exact:true}).waitFor();
  await page.setViewportSize({width:1280,height:900});
  await page.screenshot({path:'docs/fixtures/p1-12/admin-desktop.png',fullPage:true});
  await page.setViewportSize({width:390,height:844});
  if(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth))throw new Error('mobile overflow');
  await page.getByRole('button',{name:'权限管理',exact:true}).click();
  await page.screenshot({path:'docs/fixtures/p1-12/admin-mobile.png',fullPage:true});
  return {created:true,invitationQuota:2,mobileWidth:390};
}