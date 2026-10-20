#!/usr/bin/env node
// Render site/icons/icon.svg to the PNG sizes the manifest and iOS need, with headless Edge/Chrome.
// Usage: node tools/make-icons.mjs
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const icons = path.join(root, 'site', 'icons');
const svg = fs.readFileSync(path.join(icons, 'icon.svg'), 'utf8');
const browser = [
  process.env.PWA_BROWSER,
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  '/usr/bin/google-chrome', '/usr/bin/chromium',
].find((p) => p && fs.existsSync(p));
if (!browser) { console.error('no Edge/Chrome found'); process.exit(1); }

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'atlas-icons-'));
try {
  for (const size of [180, 192, 512]) {
    const html = path.join(tmp, `i${size}.html`);
    fs.writeFileSync(html, `<!doctype html><html><body style="margin:0;background:#121417">` +
      svg.replace('<svg ', `<svg width="${size}" height="${size}" `) + '</body></html>');
    const png = path.join(icons, `icon-${size}.png`);
    execFileSync(browser, ['--headless=new', '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=1',
      `--window-size=${size},${size}`, `--screenshot=${png}`, 'file:///' + html.replace(/\\/g, '/')], { stdio: 'ignore', timeout: 60000 });
    console.log('icons/' + path.basename(png));
  }
} finally {
  fs.rmSync(tmp, { recursive: true, force: true });
}
