const fs = require('node:fs');
const { chromium } = require('playwright');
const {
  artifactPath,
  config,
  ensureRuntimeDirs,
  normalizeGroupUrl,
  safeName,
  sessionExists
} = require('./common');

const COMPOSER_TEXT = /write something|what's on your mind|create post|create a public post|viết gì|ban viet gi|bạn viết gì|tạo bài viết|tao bai viet|bạn đang nghĩ gì|ban dang nghi gi/i;
const POST_BUTTON_TEXT = /^(post|publish|đăng|dang)$/i;
const COOKIE_TEXT = /allow all cookies|accept all|chấp nhận tất cả|chap nhan tat ca|cho phép tất cả|cho phep tat ca/i;

const COMPOSER_PLACEHOLDER_TEXT = /write something|what's on your mind|create a public post|tao bai viet|ban viet gi|tạo bài viết|bạn viết gì/i;

async function isLoginPage(page) {
  const url = page.url();
  if (/\/login|checkpoint|privacy\/consent/i.test(url)) {
    return true;
  }
  const emailInputs = await page.locator('input[name="email"], input#email').count().catch(() => 0);
  return emailInputs > 0;
}

async function acceptCookieDialog(page) {
  const button = page.getByRole('button', { name: COOKIE_TEXT }).first();
  if (await button.count().catch(() => 0)) {
    await button.click({ timeout: 3000 }).catch(() => undefined);
  }
}

async function assertLoggedIn(page) {
  await page.goto('https://www.facebook.com/me', { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => undefined);
  await acceptCookieDialog(page);
  if (await isLoginPage(page)) {
    throw new Error('Facebook session is not logged in or requires checkpoint. Run npm run login again.');
  }
}

async function clickFirstVisible(locators) {
  for (const locator of locators) {
    const count = await locator.count().catch(() => 0);
    if (!count) {
      continue;
    }
    const first = locator.first();
    if (await first.isVisible().catch(() => false)) {
      await first.click({ timeout: 10000 });
      return true;
    }
  }
  return false;
}

async function openComposer(page) {
  await acceptCookieDialog(page);
  const candidates = [
    page.getByRole('button', { name: COMPOSER_TEXT }),
    page.locator('div[role="button"]').filter({ hasText: COMPOSER_TEXT }),
    page.locator('span').filter({ hasText: COMPOSER_TEXT })
  ];

  if (await clickFirstVisible(candidates)) {
    return;
  }
  throw new Error('Cannot find Facebook group post composer. Check group permission or UI language.');
}

async function composerHasText(dialog, content) {
  const preview = content.slice(0, Math.min(content.length, 40));
  if (preview && await dialog.getByText(preview).first().isVisible().catch(() => false)) {
    return true;
  }

  const editors = dialog.locator('[contenteditable="true"][data-lexical-editor="true"], [contenteditable="true"]');
  const count = await editors.count().catch(() => 0);
  for (let index = 0; index < count; index++) {
    const editorText = await editors.nth(index).innerText().catch(() => '');
    if (preview && editorText.includes(preview)) {
      return true;
    }
  }
  return false;
}

async function typeComposerText(page, dialog, target, content) {
  await target.scrollIntoViewIfNeeded().catch(() => undefined);
  await target.click({ timeout: 10000, force: true });
  await page.waitForTimeout(300);

  await target.fill(content, { timeout: 10000 }).catch(() => undefined);
  await page.waitForTimeout(700);
  if (await composerHasText(dialog, content)) {
    return true;
  }

  await target.click({ timeout: 10000, force: true }).catch(() => undefined);
  await page.keyboard.press(process.platform === 'darwin' ? 'Meta+A' : 'Control+A').catch(() => undefined);
  await page.keyboard.insertText(content);
  await page.waitForTimeout(700);
  if (await composerHasText(dialog, content)) {
    return true;
  }

  await target.evaluate((element, text) => {
    element.focus();
    document.execCommand('selectAll', false, null);
    document.execCommand('insertText', false, text);
    element.dispatchEvent(new InputEvent('input', {
      bubbles: true,
      cancelable: true,
      inputType: 'insertText',
      data: text
    }));
  }, content).catch(() => undefined);
  await page.waitForTimeout(900);
  return composerHasText(dialog, content);
}

async function clickComposerBody(page, dialog) {
  const box = await dialog.boundingBox().catch(() => null);
  if (!box) {
    return false;
  }
  await page.mouse.click(box.x + (box.width * 0.5), box.y + Math.min(260, box.height * 0.45));
  await page.waitForTimeout(500);
  return true;
}

async function forceFillComposerByDom(page, content) {
  const focused = await page.evaluate((text) => {
    const editors = Array.from(document.querySelectorAll('[contenteditable="true"][data-lexical-editor="true"], [contenteditable="true"]'));
    const candidates = editors
      .map((element) => {
        const rect = element.getBoundingClientRect();
        const label = `${element.getAttribute('aria-label') || ''} ${element.getAttribute('aria-placeholder') || ''}`.toLowerCase();
        return { element, rect, label, area: rect.width * rect.height };
      })
      .filter(({ rect, label }) => {
        const visible = rect.width > 120
          && rect.height > 20
          && rect.bottom > 0
          && rect.right > 0
          && rect.top < window.innerHeight
          && rect.left < window.innerWidth;
        return visible
          && !label.includes('comment')
          && !label.includes('bình luận')
          && !label.includes('binh luan');
      })
      .sort((left, right) => right.area - left.area);

    const target = candidates[0]?.element;
    if (!target) {
      return false;
    }

    target.focus();
    document.execCommand('selectAll', false, null);
    document.execCommand('insertText', false, text);
    target.dispatchEvent(new InputEvent('input', {
      bubbles: true,
      cancelable: true,
      inputType: 'insertText',
      data: text
    }));
    return true;
  }, content).catch(() => false);

  if (!focused) {
    return false;
  }

  await page.waitForTimeout(900);
  return composerHasText(page, content);
}

async function fillComposer(page, content) {
  const dialog = page.locator('div[role="dialog"]').last();
  await dialog.waitFor({ state: 'visible', timeout: 30000 });

  const deadline = Date.now() + 45000;
  while (Date.now() < deadline) {
    if (await forceFillComposerByDom(page, content)) {
      return;
    }

    const candidates = [
      dialog.locator('div[role="textbox"][contenteditable="true"][data-lexical-editor="true"]'),
      dialog.locator('div[role="textbox"][contenteditable="true"]'),
      dialog.locator('[contenteditable="true"][data-lexical-editor="true"]'),
      dialog.locator('[contenteditable="true"]'),
      page.locator('div[role="textbox"][contenteditable="true"][data-lexical-editor="true"]'),
      page.locator('div[role="textbox"][contenteditable="true"]'),
      page.locator('[contenteditable="true"][data-lexical-editor="true"]'),
      page.locator('[contenteditable="true"]')
    ];

    for (const locator of candidates) {
      const count = await locator.count().catch(() => 0);
      for (let index = 0; index < count; index++) {
        const target = locator.nth(index);
        if (!(await target.isVisible().catch(() => false))) {
          continue;
        }

        const aria = await target.getAttribute('aria-label').catch(() => '') || '';
        const placeholder = await target.getAttribute('aria-placeholder').catch(() => '') || '';
        const label = `${aria} ${placeholder}`.toLowerCase();
        if (label.includes('comment') || label.includes('bình luận') || label.includes('binh luan')) {
          continue;
        }

        if (await typeComposerText(page, dialog, target, content)) {
          return;
        }
      }
    }

    const placeholder = dialog.getByText(COMPOSER_PLACEHOLDER_TEXT).first();
    if (await placeholder.isVisible().catch(() => false)) {
      await placeholder.scrollIntoViewIfNeeded().catch(() => undefined);
      await placeholder.click({ timeout: 10000, force: true });
      await page.waitForTimeout(700);
      continue;
    }

    if (await clickComposerBody(page, dialog)) {
      await page.keyboard.insertText(content).catch(() => undefined);
      await page.waitForTimeout(900);
      if (await composerHasText(dialog, content)) {
        return;
      }
    }

    await page.waitForTimeout(750);
  }

  throw new Error('Cannot find editable post textbox inside composer dialog.');
}

async function submitPost(page) {
  const dialog = page.locator('div[role="dialog"]').last();
  const candidates = [
    dialog.getByRole('button', { name: POST_BUTTON_TEXT }),
    page.getByRole('button', { name: POST_BUTTON_TEXT })
  ];

  for (const locator of candidates) {
    const count = await locator.count().catch(() => 0);
    for (let index = count - 1; index >= 0; index--) {
      const button = locator.nth(index);
      if (!(await button.isVisible().catch(() => false))) {
        continue;
      }
      const ariaDisabled = await button.getAttribute('aria-disabled').catch(() => null);
      const disabled = await button.getAttribute('disabled').catch(() => null);
      if (ariaDisabled === 'true' || disabled !== null) {
        continue;
      }
      await button.waitFor({ state: 'visible', timeout: 10000 });
      await button.click({ timeout: 15000 });
      await page.waitForLoadState('networkidle', { timeout: 30000 }).catch(() => undefined);
      await page.waitForTimeout(config.postSubmitWaitMs);
      return;
    }
  }

  throw new Error('Cannot find enabled Post/Dang button in composer.');
}

async function saveFailureArtifacts(page, groupId) {
  ensureRuntimeDirs();
  const prefix = `facebook-group-post-${safeName(groupId)}`;
  const screenshotPath = artifactPath(prefix, 'png');
  const htmlPath = artifactPath(prefix, 'html');
  await page.screenshot({ path: screenshotPath, fullPage: true }).catch(() => undefined);
  const html = await page.content().catch(() => '');
  if (html) {
    fs.writeFileSync(htmlPath, html, 'utf8');
  }
  return { screenshotPath, htmlPath };
}

async function postToFacebookGroup(args) {
  ensureRuntimeDirs();

  if (!sessionExists()) {
    throw new Error(`Missing Facebook session file. Run npm run login first. Expected: ${config.storageStatePath}`);
  }
  if (!args || !args.content || !String(args.content).trim()) {
    throw new Error('Missing args.content');
  }

  const groupUrl = normalizeGroupUrl(args.groupReference);
  const browser = await chromium.launch({
    headless: config.headless,
    slowMo: config.slowMoMs,
    args: ['--disable-blink-features=AutomationControlled']
  });
  const context = await browser.newContext({
    storageState: config.storageStatePath,
    locale: config.locale,
    timezoneId: config.timezoneId,
    viewport: { width: 1366, height: 900 }
  });
  const page = await context.newPage();
  page.setDefaultTimeout(config.timeoutMs);

  try {
    await assertLoggedIn(page);
    await page.goto(groupUrl, { waitUntil: 'domcontentloaded', timeout: config.timeoutMs });
    await page.waitForLoadState('networkidle', { timeout: 30000 }).catch(() => undefined);
    await openComposer(page);
    await fillComposer(page, String(args.content).trim());
    await submitPost(page);

    return {
      success: true,
      message: 'Facebook group post submitted',
      postUrl: page.url(),
      groupUrl
    };
  } catch (error) {
    const artifacts = await saveFailureArtifacts(page, args.groupId || args.groupReference).catch(() => ({}));
    throw new Error(`${error.message}${artifacts.screenshotPath ? ` | screenshot=${artifacts.screenshotPath}` : ''}`);
  } finally {
    await context.close().catch(() => undefined);
    await browser.close().catch(() => undefined);
  }
}

module.exports = { postToFacebookGroup };
