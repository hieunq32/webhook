const fs = require('node:fs');
const path = require('node:path');

const rootDir = path.resolve(__dirname, '..');
const storageDir = path.join(rootDir, 'storage');
const artifactsDir = path.join(rootDir, 'artifacts');

function env(name, fallback) {
  const value = process.env[name];
  return value === undefined || value === null || value === '' ? fallback : value;
}

function parseBoolean(value, fallback) {
  if (value === undefined || value === null || value === '') {
    return fallback;
  }
  return ['1', 'true', 'yes', 'y', 'on'].includes(String(value).trim().toLowerCase());
}

function parseInteger(value, fallback) {
  const parsed = Number.parseInt(String(value), 10);
  return Number.isFinite(parsed) ? parsed : fallback;
}

const config = {
  port: parseInteger(env('FACEBOOK_RPA_PORT', '18990'), 18990),
  storageStatePath: path.resolve(env('FACEBOOK_RPA_STORAGE_STATE', path.join(storageDir, 'facebook-session.json'))),
  headless: parseBoolean(env('FACEBOOK_RPA_HEADLESS', 'false'), false),
  slowMoMs: parseInteger(env('FACEBOOK_RPA_SLOW_MO_MS', '40'), 40),
  timeoutMs: parseInteger(env('FACEBOOK_RPA_TIMEOUT_MS', '90000'), 90000),
  postSubmitWaitMs: parseInteger(env('FACEBOOK_RPA_POST_SUBMIT_WAIT_MS', '7000'), 7000),
  authToken: env('FACEBOOK_RPA_AUTH_TOKEN', ''),
  locale: env('FACEBOOK_RPA_LOCALE', 'vi-VN'),
  timezoneId: env('FACEBOOK_RPA_TIMEZONE', 'Asia/Ho_Chi_Minh')
};

function ensureRuntimeDirs() {
  fs.mkdirSync(storageDir, { recursive: true });
  fs.mkdirSync(artifactsDir, { recursive: true });
}

function sessionExists() {
  return fs.existsSync(config.storageStatePath);
}

function normalizeGroupUrl(groupReference) {
  if (!groupReference || !String(groupReference).trim()) {
    throw new Error('Missing groupReference');
  }

  const value = String(groupReference).trim();
  if (/^https?:\/\//i.test(value)) {
    return value;
  }

  const cleaned = value.replace(/^\/+|\/+$/g, '');
  if (cleaned.startsWith('groups/')) {
    return `https://www.facebook.com/${cleaned}`;
  }
  return `https://www.facebook.com/groups/${cleaned}`;
}

function safeName(value) {
  return String(value || 'unknown')
    .replace(/[^a-zA-Z0-9._-]/g, '_')
    .slice(0, 80);
}

function artifactPath(prefix, ext) {
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  return path.join(artifactsDir, `${prefix}-${stamp}.${ext}`);
}

function jsonResponse(res, statusCode, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(statusCode, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body)
  });
  res.end(body);
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let raw = '';
    req.on('data', chunk => {
      raw += chunk;
      if (raw.length > 1024 * 1024) {
        reject(new Error('Request body is too large'));
        req.destroy();
      }
    });
    req.on('end', () => {
      if (!raw.trim()) {
        resolve({});
        return;
      }
      try {
        resolve(JSON.parse(raw));
      } catch (error) {
        reject(new Error(`Invalid JSON body: ${error.message}`));
      }
    });
    req.on('error', reject);
  });
}

module.exports = {
  artifactPath,
  config,
  ensureRuntimeDirs,
  jsonResponse,
  normalizeGroupUrl,
  readJsonBody,
  safeName,
  sessionExists
};
