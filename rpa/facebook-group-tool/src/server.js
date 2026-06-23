const http = require('node:http');
const {
  config,
  ensureRuntimeDirs,
  jsonResponse,
  readJsonBody,
  sessionExists
} = require('./common');
const { postToFacebookGroup } = require('./facebook-group-post');

function authorized(req) {
  if (!config.authToken) {
    return true;
  }
  const header = req.headers.authorization || '';
  return header === `Bearer ${config.authToken}`;
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
