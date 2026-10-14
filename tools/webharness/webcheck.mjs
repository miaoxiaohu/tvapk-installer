/**
 * Headless smoke test for the real res/raw/index.html served by the JVM harness.
 *
 * Uses the Chrome DevTools Protocol over a raw WebSocket so there is no npm install
 * step:  node webcheck.mjs <url> <shot.png>
 *
 * What it checks that curl cannot:
 *   - the page actually parses and runs (console errors / exceptions)
 *   - the PIN gate appears when no token is stored
 *   - entering the PIN dismisses the gate
 *   - both switches can be clicked without throwing   <- the bug fixed last round
 *   - the pending list renders what the server really returned
 */
import { spawn, spawnSync } from "node:child_process";
import { writeFileSync } from "node:fs";
import { createHash } from "node:crypto";
import http from "node:http";

const CHROME = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
// Default must match start-harness.ps1's default port. 10080 is on Chromium's unsafe-port
// blacklist, so the old default could only ever end at ERR_UNSAFE_PORT. No ?k= here on purpose:
// test 1 asserts the PIN gate is up before any code is submitted.
const url = process.argv[2] || "http://127.0.0.1:23456/";
const shot = process.argv[3] || "";
const PIN = process.argv[4] || "824193";
const PORT = 9333;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Module scope so the FATAL path can clean up too. Left behind, an orphaned Chrome keeps a
// connection open against the single-client harness and starves the next run.
let chrome = null;

/**
 * Windows child.kill() only terminates the browser process; renderers and the
 * zygote survive, stay connected to the single-client harness and starve the next run.
 * Taskkill /T takes the whole tree.
 */
function killChrome() {
  if (!chrome) return;
  if (process.platform === "win32") {
    spawnSync("taskkill", ["/PID", String(chrome.pid), "/T", "/F"], { stdio: "ignore" });
  } else {
    try { chrome.kill(); } catch { /* already gone */ }
  }
}

function get(path) {
  return new Promise((resolve, reject) => {
    http.get({ host: "127.0.0.1", port: PORT, path }, (res) => {
      let b = "";
      res.on("data", (d) => (b += d));
      res.on("end", () => resolve(b));
    }).on("error", reject);
  });
}

// --- minimal CDP websocket client (no deps) -------------------------------
class CDP {
  constructor(ws) {
    this.ws = ws;
    this.id = 0;
    this.pending = new Map();
    this.events = [];
    // Node's built-in WebSocket is a standard EventTarget, not an EventEmitter.
    ws.addEventListener("message", (ev) => {
      const msg = JSON.parse(ev.data);
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
      } else if (msg.method) {
        this.events.push(msg);
      }
    });
  }
  raw(text) {
    this.ws.send(text);
  }
  send(method, params = {}) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.raw(JSON.stringify({ id, method, params }));
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id);
          reject(new Error("timeout " + method));
        }
      }, 30000);
    });
  }
  async eval(expression) {
    const r = await this.send("Runtime.evaluate", {
      expression, awaitPromise: true, returnByValue: true
    });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.text + " " + (r.exceptionDetails.exception?.description || ""));
    return r.result.value;
  }
}

async function main() {
  const userDir = process.env.TEMP + "\\tvapk-chrome-profile";
  chrome = spawn(CHROME, [
    "--headless=new", "--disable-gpu", "--no-sandbox", "--hide-scrollbars",
    `--remote-debugging-port=${PORT}`, `--user-data-dir=${userDir}`,
    "--window-size=430,932",          // phone-shaped: this page is for phones
    "about:blank"
  ], { stdio: "ignore" });

  // wait for the debugger endpoint
  let ver = null;
  for (let i = 0; i < 60; i++) {
    try { ver = JSON.parse(await get("/json/version")); break; } catch { await sleep(250); }
  }
  if (!ver) throw new Error("chrome did not expose a debugging port");

  const ws = new WebSocket(ver.webSocketDebuggerUrl);
  await new Promise((res, rej) => {
    ws.addEventListener("open", res);
    ws.addEventListener("error", rej);
  });
  const browser = new CDP(ws);

  const { targetId } = await browser.send("Target.createTarget", { url: "about:blank" });
  const { sessionId } = await browser.send("Target.attachToTarget", { targetId, flatten: true });

  // per-session wrapper
  const page = {
    send: (m, p = {}) => {
      const id = ++browser.id;
      return new Promise((resolve, reject) => {
        browser.pending.set(id, { resolve, reject });
        browser.raw(JSON.stringify({ id, method: m, params: p, sessionId }));
        setTimeout(() => { if (browser.pending.has(id)) { browser.pending.delete(id); reject(new Error("timeout " + m)); } }, 30000);
      });
    }
  };
  const evalJs = async (expr) => {
    const r = await page.send("Runtime.evaluate", { expression: expr, awaitPromise: true, returnByValue: true });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.text + " | " + (r.exceptionDetails.exception?.description || ""));
    return r.result.value;
  };

  await page.send("Page.enable");
  await page.send("Runtime.enable");
  await page.send("Log.enable");

  const consoleErrors = [];
  ws.addEventListener("message", (ev) => {
    const m = JSON.parse(ev.data);
    if (m.sessionId !== sessionId) return;
    if (m.method === "Runtime.exceptionThrown") {
      consoleErrors.push("EXCEPTION " + (m.params.exceptionDetails?.exception?.description || m.params.exceptionDetails?.text));
    }
    if (m.method === "Runtime.consoleAPICalled" && (m.params.type === "error" || m.params.type === "warning")) {
      consoleErrors.push(m.params.type.toUpperCase() + " " + m.params.args.map(a => a.value ?? a.description ?? "").join(" "));
    }
    if (m.method === "Log.entryAdded" && m.params.entry.level === "error") {
      consoleErrors.push("LOG " + m.params.entry.text);
    }
  });

  console.log("打开 " + url);

  // The page deliberately remembers the PIN in localStorage, so a reused Chrome
  // profile would skip the gate and make step 1 pass for the wrong reason.
  await page.send("Storage.clearDataForOrigin", {
    origin: new URL(url).origin, storageTypes: "all"
  }).catch(() => {});

  await page.send("Page.navigate", { url });

  // Headless Chrome cold-starts slowly on this box; poll instead of guessing a delay.
  let ready = false;
  for (let i = 0; i < 80; i++) {
    await sleep(250);
    try {
      ready = await evalJs(`!!(document.getElementById("gate") && document.getElementById("pinInput") && document.getElementById("swPin"))`);
    } catch { /* page still swapping documents */ }
    if (ready) break;
  }
  if (!ready) {
    let diag = "?";
    try {
      diag = await evalJs(`JSON.stringify({
        href: location.href, ready: document.readyState, title: document.title,
        bodyLen: document.body ? document.body.innerHTML.length : -1,
        ids: document.body ? Array.from(document.querySelectorAll("[id]")).map(e=>e.id).slice(0,20) : []
      })`);
    } catch (e) { diag = "eval failed: " + e.message; }
    console.log("诊断: " + diag);
    console.log("控制台: " + (consoleErrors.join(" | ") || "(空)"));
    try {
      const { data } = await page.send("Page.captureScreenshot", { format: "png" });
      writeFileSync(shot || "diag.png", Buffer.from(data, "base64"));
      console.log("已存诊断截图 -> " + (shot || "diag.png"));
    } catch { /* ignore */ }
    throw new Error("页面在 20 秒内没有渲染出 #gate / #pinInput / #swPin");
  }
  await sleep(600);

  const results = [];
  const check = (name, ok, detail = "") => {
    results.push({ name, ok, detail });
    console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  -> " + detail : ""}`);
  };

  // 1. gate should be up (no token yet)
  const gateOn = await evalJs(`document.getElementById("gate").classList.contains("on")`);
  check("验证码门出现（未输入时）", gateOn === true, `gate.on=${gateOn}`);

  // 2. wrong PIN must be rejected
  await evalJs(`document.getElementById("pinInput").value = "000000";
                document.getElementById("pinSubmit").click();`);
  await sleep(1200);
  const stillGated = await evalJs(`document.getElementById("gate").classList.contains("on")`);
  check("错误验证码被拒（门不消失）", stillGated === true, `gate.on=${stillGated}`);

  // 3. correct PIN
  await evalJs(`document.getElementById("pinInput").value = "${PIN}";
                document.getElementById("pinSubmit").click();`);
  await sleep(1500);
  const gateGone = await evalJs(`!document.getElementById("gate").classList.contains("on")`);
  check("正确验证码放行", gateGone === true, `gate.on=${!gateGone}`);

  // The wrong-PIN step above produces a 401 network log by design. Start the
  // console audit after it so only genuine errors are counted.
  consoleErrors.length = 0;

  // 4. the switches - this is what broke before
  const before = await evalJs(`document.getElementById("swPin").getAttribute("aria-checked")`);
  await evalJs(`document.getElementById("swPin").click()`);
  await sleep(1500);
  const after = await evalJs(`document.getElementById("swPin").getAttribute("aria-checked")`);
  check("验证码开关点击后状态翻转（不抛异常）", before !== after, `${before} -> ${after}`);

  const b2 = await evalJs(`document.getElementById("swAuto").getAttribute("aria-checked")`);
  await evalJs(`document.getElementById("swAuto").click()`);
  await sleep(1500);
  const a2 = await evalJs(`document.getElementById("swAuto").getAttribute("aria-checked")`);
  check("自动安装开关点击后状态翻转", b2 !== a2, `${b2} -> ${a2}`);

  // put them back
  await evalJs(`document.getElementById("swPin").click()`); await sleep(800);
  await evalJs(`document.getElementById("swAuto").click()`); await sleep(800);

  // 5. bulk-uninstall button must be gone
  const bulk = await evalJs(`!!document.getElementById("uninstallAllBtn")`);
  check("网页已无「卸载全部」按钮", bulk === false, `uninstallAllBtn=${bulk}`);

  // 6. pending list reflects what the server really holds
  //    Seed one APK first. The harness wipes its inbox on every start, so without this the
  //    assertion silently depended on somebody having pushed a file by hand beforehand.
  //    Seeded through the page rather than Node's fetch: the harness serves one client at a
  //    time, so a second connection from Node competes with the browser for it.
  const seeded = JSON.parse(await evalJs(
    `fetch("/api/upload?filename=probe.apk&k=${PIN}", { method: "POST",
        headers: { "Content-Type": "application/octet-stream" },
        body: new Uint8Array([0x50, 0x4b, 0x03, 0x04]) })
      .then(function(r){ return r.text(); })`));
  check("测试台自举上传成功", seeded.ok === true, JSON.stringify(seeded));
  await evalJs(`document.getElementById("refreshBtn").click()`);
  await sleep(1200);

  const pendingRows = await evalJs(`document.querySelectorAll("#pending .row").length`);
  const pendingText = await evalJs(`document.getElementById("pending").innerText.replace(/\\s+/g," ").slice(0,120)`);
  check("待装列表已渲染", pendingRows > 0, `${pendingRows} 行: ${pendingText}`);

  // 7. installed list rendered
  const installedRows = await evalJs(`document.querySelectorAll("#installed .row").length`);
  check("已装列表已渲染", installedRows > 0, `${installedRows} 行`);

  // 8. file input exists and is label-driven
  const fileInput = await evalJs(`(function(){
    const f = document.getElementById("file");
    const l = document.getElementById("drop");
    return JSON.stringify({ exists: !!f, tag: f && f.tagName, type: f && f.type,
                            accept: f && f.getAttribute("accept"),
                            labelFor: l && l.getAttribute("for"), labelTag: l && l.tagName });
  })()`);
  const fi = JSON.parse(fileInput);
  check("文件选择是 <label for=file>", fi.labelFor === "file", fileInput);

  // 8b. The reported bug: tapping the zone opened an EMPTY picker on Android because
  //     accept=".apk" is an extension-only filter with no matching DocumentsUI category.
  //     Drag-and-drop bypasses accept, which is why only dragging ever worked.
  check("input 无 accept 过滤（Android 选择器不会变空）",
        !fi.accept || fi.accept === "*/*", `accept=${JSON.stringify(fi.accept)}`);

  // 8c. Actually click the zone and confirm a file chooser opens. This is the only
  //     assertion that catches "tapping does nothing" - inspecting the DOM cannot.
  let chooserOpened = false;
  const onChooser = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.sessionId === sessionId && m.method === "Page.fileChooserOpened") chooserOpened = true;
  };
  ws.addEventListener("message", onChooser);
  await page.send("Page.setInterceptFileChooserDialog", { enabled: true });
  const dropRef = await evalJs(`(function(){
    const r = document.getElementById("drop").getBoundingClientRect();
    return JSON.stringify({x: Math.round(r.left + r.width/2), y: Math.round(r.top + r.height/2)});
  })()`);
  const pos = JSON.parse(dropRef);
  await page.send("Input.dispatchMouseEvent", {
    type: "mousePressed", x: pos.x, y: pos.y, button: "left", clickCount: 1
  });
  await page.send("Input.dispatchMouseEvent", {
    type: "mouseReleased", x: pos.x, y: pos.y, button: "left", clickCount: 1
  });
  for (let i = 0; i < 20 && !chooserOpened; i++) await sleep(150);
  ws.removeEventListener("message", onChooser);
  check("点击投放区会弹出文件选择器", chooserOpened, chooserOpened ? "fileChooserOpened 已触发" : "点击后没有任何反应");

  // 8d. The chooser assertion above CANNOT catch this, because desktop Chrome happily opens
  //     a picker for a 1x1 fully-transparent input. Mobile engines refuse to, so "tap does
  //     nothing" passes 8c on the harness while failing on every real phone. Assert the input
  //     is genuinely rendered instead of hidden with opacity/display.
  const rendered = JSON.parse(await evalJs(`(function(){
    const f = document.getElementById("file");
    const cs = getComputedStyle(f);
    const r = f.getBoundingClientRect();
    return JSON.stringify({ display: cs.display, visibility: cs.visibility,
                            opacity: cs.opacity, w: Math.round(r.width), h: Math.round(r.height) });
  })()`));
  check("文件输入框真实可见（移动端才会开选择器）",
        rendered.display !== "none" && rendered.visibility !== "hidden" &&
        parseFloat(rendered.opacity) > 0 && rendered.w > 0 && rendered.h > 0,
        JSON.stringify(rendered));

  // 9. console clean
  check("无控制台错误 / 未捕获异常", consoleErrors.length === 0, consoleErrors.join(" | ") || "干净");

  if (shot) {
    const { data } = await page.send("Page.captureScreenshot", { format: "png" });
    writeFileSync(shot, Buffer.from(data, "base64"));
    console.log("截图 -> " + shot);
  }

  const failed = results.filter(r => !r.ok);
  console.log(`\n通过 ${results.length - failed.length} / ${results.length}`);
  if (consoleErrors.length) {
    console.log("\n--- 控制台输出 ---");
    consoleErrors.forEach(e => console.log("  " + e));
  }

  killChrome();
  process.exit(failed.length ? 1 : 0);
}

main().catch(e => {
  console.error("FATAL " + e.message);
  killChrome();
  process.exit(2);
});