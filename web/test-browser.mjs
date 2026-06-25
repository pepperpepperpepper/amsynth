// Browser smoke test: serve web/, open the page in Chromium, click "Start
// audio", and assert the AudioWorklet boots and the page reaches "running".
//
// This catches browser-worklet-environment bugs the Node tests can't (the Node
// tests run amsynth-processor.js in a Node global scope, which — unlike a real
// AudioWorkletGlobalScope — provides things like TextEncoder/TextDecoder).
//
// Requires Playwright + its Chromium:
//   cd web && npm install && npx playwright install chromium
//   node web/test-browser.mjs
// If Playwright/Chromium aren't available it SKIPS (exit 0) rather than failing.
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { extname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL(".", import.meta.url));
const MIME = { ".html": "text/html", ".js": "text/javascript", ".mjs": "text/javascript", ".wasm": "application/wasm" };

const server = createServer(async (req, res) => {
  try {
    let p = decodeURIComponent(req.url.split("?")[0]);
    if (p === "/") p = "/index.html";
    const data = await readFile(join(root, p));
    res.writeHead(200, { "content-type": MIME[extname(p)] || "application/octet-stream" });
    res.end(data);
  } catch {
    res.writeHead(404); res.end("not found");
  }
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const url = `http://127.0.0.1:${server.address().port}/`;

const skip = (why) => { console.log("SKIP:", why); server.close(); process.exit(0); };

let chromium;
try { ({ chromium } = await import("playwright")); }
catch { skip("playwright not installed (cd web && npm install && npx playwright install chromium)"); }

let browser;
try { browser = await chromium.launch({ args: ["--no-sandbox"] }); }
catch (e) { skip("could not launch chromium: " + e.message); }

const page = await browser.newPage();
let status = "(none)", ok = false;
try {
  await page.goto(url, { waitUntil: "load" });
  await page.click("#start");
  // Resolve as soon as the worklet boots (running) or reports an error.
  await page.waitForFunction(() => {
    const t = document.querySelector("#status").textContent;
    return t.includes("running") || t.startsWith("engine error");
  }, { timeout: 20000 });
  status = await page.textContent("#status");
  ok = status.includes("running");
} catch (e) {
  status = await page.textContent("#status").catch(() => "(unreachable: " + e.message + ")");
}
await browser.close();
server.close();

console.log("status:", status);
console.log(ok ? "PASS: worklet booted and reached running" : "FAIL: page did not reach running");
process.exit(ok ? 0 : 1);
