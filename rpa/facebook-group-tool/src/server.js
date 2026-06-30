const http = require('node:http');
const { chromium } = require('playwright');
const {
  config,
  ensureRuntimeDirs,
  jsonResponse,
  readJsonBody,
  sessionExists
} = require('./common');
const { postToFacebookGroup } = require('./facebook-group-post');

let loginRuntime = null;

function authorized(req) {
  if (!config.authToken) {
    return true;
  }
  const header = req.headers.authorization || '';
  return header === `Bearer ${config.authToken}`;
}

async function isLoginPage(page) {
  const url = page.url();
  if (/\/login|checkpoint|privacy\/consent/i.test(url)) {
    return true;
  }
  const emailInputs = await page.locator('input[name="email"], input#email').count().catch(() => 0);
  return emailInputs > 0;
}

function rejectUnauthorized(req, res) {
  if (authorized(req)) {
    return false;
  }
  jsonResponse(res, 401, {
    ok: false,
    success: false,
    error: 'Unauthorized'
  });
  return true;
}

function handleSessionStatus(req, res) {
  if (rejectUnauthorized(req, res)) {
    return;
  }
  jsonResponse(res, 200, {
    ok: true,
    success: true,
    sessionExists: sessionExists(),
    loginInProgress: loginRuntime !== null,
    storageStatePath: config.storageStatePath,
    loginStartPath: '/session/login/start',
    loginCompletePath: '/session/login/complete',
    message: sessionExists()
      ? 'Facebook session is ready'
      : 'Facebook login is required before posting to groups'
  });
}

function isValidStorageState(storageState) {
  if (!storageState || typeof storageState !== 'object' || Array.isArray(storageState)) {
    return false;
  }
  if (!Array.isArray(storageState.cookies) || !Array.isArray(storageState.origins)) {
    return false;
  }
  return true;
}

async function handleLoginStart(req, res) {
  if (rejectUnauthorized(req, res)) {
    return;
  }
  if (loginRuntime) {
    jsonResponse(res, 200, {
      ok: true,
      success: true,
      sessionExists: sessionExists(),
      loginInProgress: true,
      message: 'Facebook login browser is already open. Complete login, then call /session/login/complete.'
    });
    return;
  }

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
  await page.goto('https://www.facebook.com/', { waitUntil: 'domcontentloaded' });

  loginRuntime = { browser, context, page };
  jsonResponse(res, 200, {
    ok: true,
    success: true,
    sessionExists: sessionExists(),
    loginInProgress: true,
    message: 'Facebook login browser opened. Login manually, then call /session/login/complete.'
  });
}

async function handleSessionImport(req, res) {
  if (rejectUnauthorized(req, res)) {
    return;
  }

  const body = await readJsonBody(req);
  const storageState = body?.storageState ?? body;
  if (!isValidStorageState(storageState)) {
    jsonResponse(res, 400, {
      ok: false,
      success: false,
      error: 'Invalid storageState. Expected JSON with cookies[] and origins[].'
    });
    return;
  }

  if (loginRuntime) {
    await loginRuntime.context.close().catch(() => undefined);
    await loginRuntime.browser.close().catch(() => undefined);
    loginRuntime = null;
  }

  ensureRuntimeDirs();
  require('node:fs').writeFileSync(
    config.storageStatePath,
    JSON.stringify(storageState, null, 2),
    'utf8'
  );

  jsonResponse(res, 200, {
    ok: true,
    success: true,
    sessionExists: true,
    loginInProgress: false,
    storageStatePath: config.storageStatePath,
    message: 'Facebook session imported successfully'
  });
}

async function handleLoginComplete(req, res) {
  if (rejectUnauthorized(req, res)) {
    return;
  }
  if (!loginRuntime) {
    jsonResponse(res, 409, {
      ok: false,
      success: false,
      sessionExists: sessionExists(),
      loginInProgress: false,
      error: 'No Facebook login browser is currently open. Call /session/login/start first.'
    });
    return;
  }

  const runtime = loginRuntime;
  await runtime.page.goto('https://www.facebook.com/me', { waitUntil: 'domcontentloaded' }).catch(() => undefined);
  await runtime.page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => undefined);
  if (await isLoginPage(runtime.page)) {
    jsonResponse(res, 409, {
      ok: false,
      success: false,
      sessionExists: false,
      loginInProgress: true,
      error: 'Facebook still shows login/checkpoint. Finish login or verification in the browser first.'
    });
    return;
  }

  await runtime.context.storageState({ path: config.storageStatePath });
  await runtime.context.close().catch(() => undefined);
  await runtime.browser.close().catch(() => undefined);
  loginRuntime = null;

  jsonResponse(res, 200, {
    ok: true,
    success: true,
    sessionExists: true,
    loginInProgress: false,
    storageStatePath: config.storageStatePath,
    message: 'Facebook session saved successfully'
  });
}

async function handleToolsInvoke(req, res) {
  if (!authorized(req)) {
    jsonResponse(res, 401, {
      ok: false,
      success: false,
      error: 'Unauthorized'
    });
    return;
  }

  let body;
  try {
    body = await readJsonBody(req);
  } catch (error) {
    jsonResponse(res, 400, {
      ok: false,
      success: false,
      error: error.message
    });
    return;
  }

  if (body.tool !== 'facebookGroupPost') {
    jsonResponse(res, 404, {
      ok: false,
      success: false,
      error: `Unsupported tool: ${body.tool || '(missing)'}`
    });
    return;
  }

  if (body.action && body.action !== 'post') {
    jsonResponse(res, 400, {
      ok: false,
      success: false,
      error: `Unsupported action for facebookGroupPost: ${body.action}`
    });
    return;
  }

  try {
    const result = await postToFacebookGroup(body.args || {});
    jsonResponse(res, 200, {
      ok: true,
      success: true,
      result
    });
  } catch (error) {
    console.error(`[facebookGroupPost] ${error.stack || error.message}`);
    jsonResponse(res, 200, {
      ok: false,
      success: false,
      error: error.message,
      result: {
        ok: false,
        success: false,
        error: error.message
      }
    });
  }
}

function handleHealth(req, res) {
  jsonResponse(res, 200, {
    ok: true,
    service: 'facebook-group-rpa-tool',
    tool: 'facebookGroupPost',
    sessionExists: sessionExists(),
    storageStatePath: config.storageStatePath,
    selectorsConfigPath: config.selectorsConfigPath,
    headless: config.headless
  });
}

async function requestListener(req, res) {
  if (req.method === 'GET' && (req.url === '/' || req.url === '/health')) {
    handleHealth(req, res);
    return;
  }
  if (req.method === 'GET' && req.url === '/session/status') {
    handleSessionStatus(req, res);
    return;
  }
  if (req.method === 'POST' && req.url === '/session/login/start') {
    await handleLoginStart(req, res);
    return;
  }
  if (req.method === 'POST' && req.url === '/session/login/complete') {
    await handleLoginComplete(req, res);
    return;
  }
  if (req.method === 'POST' && req.url === '/session/import') {
    await handleSessionImport(req, res);
    return;
  }
  if (req.method === 'POST' && req.url === '/tools/invoke') {
    await handleToolsInvoke(req, res);
    return;
  }
  jsonResponse(res, 404, {
    ok: false,
    error: 'Not found'
  });
}

ensureRuntimeDirs();
const server = http.createServer((req, res) => {
  requestListener(req, res).catch(error => {
    console.error(error);
    jsonResponse(res, 500, {
      ok: false,
      success: false,
      error: error.message
    });
  });
});

server.listen(config.port, '127.0.0.1', () => {
  console.log(`Facebook Group RPA tool is listening on http://127.0.0.1:${config.port}`);
  console.log(`Tool endpoint: http://127.0.0.1:${config.port}/tools/invoke`);
  console.log(`Session file: ${config.storageStatePath}`);
});
