const readline = require('node:readline/promises');
const { stdin: input, stdout: output } = require('node:process');
const { chromium } = require('playwright');
const { config, ensureRuntimeDirs } = require('./common');

async function isLoginPage(page) {
  const url = page.url();
  if (/\/login|checkpoint|privacy\/consent/i.test(url)) {
    return true;
  }
  const emailInputs = await page.locator('input[name="email"], input#email').count().catch(() => 0);
  return emailInputs > 0;
}

async function main() {
  ensureRuntimeDirs();

  const browser = await chromium.launch({
    headless: false,
    slowMo: config.slowMoMs
  });
  const context = await browser.newContext({
    locale: config.locale,
    timezoneId: config.timezoneId,
    viewport: { width: 1366, height: 900 }
  });
  const page = await context.newPage();
  page.setDefaultTimeout(config.timeoutMs);

  console.log('Opening Facebook login page...');
  await page.goto('https://www.facebook.com/', { waitUntil: 'domcontentloaded' });

  console.log('');
  console.log('Login with the Facebook account that is allowed to post in target groups.');
  console.log('After login succeeds and you can see Facebook home/profile, return here and press Enter.');

  const rl = readline.createInterface({ input, output });
  await rl.question('Press Enter after Facebook login is complete...');
  rl.close();

  await page.goto('https://www.facebook.com/me', { waitUntil: 'domcontentloaded' }).catch(() => undefined);
  await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => undefined);

  if (await isLoginPage(page)) {
    await browser.close();
    throw new Error('Facebook still shows login/checkpoint. Finish login or verification before saving session.');
  }

  await context.storageState({ path: config.storageStatePath });
  await browser.close();
  console.log(`Facebook session saved: ${config.storageStatePath}`);
}

main().catch(error => {
  console.error(`Login failed: ${error.message}`);
  process.exit(1);
});
