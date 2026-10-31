import { login, postBatch, describeBatch } from "../api/client.js";
import { buildPayload, recordFromDetail, recordsFromTables } from "../content/extractor.js";

const $ = (id) => document.getElementById(id);
const say = (msg, error = false) => { $("status").textContent = msg; $("status").className = error ? "error" : ""; };

async function state() { return chrome.storage.local.get(["server", "token"]); }

async function render() {
  const { token, server } = await state();
  $("signin").hidden = !!token;
  $("import").hidden = !token;
  if (server) $("server").value = server;
}

$("login").addEventListener("click", async () => {
  try {
    const server = $("server").value.replace(/\/$/, "");
    const token = await login(server, $("email").value, $("password").value);
    await chrome.storage.local.set({ server, token });
    $("password").value = "";
    say("Signed in.");
    render();
  } catch (e) { say(e.message, true); }
});

$("logout").addEventListener("click", async () => { await chrome.storage.local.remove("token"); say(""); render(); });

$("run").addEventListener("click", async () => {
  try {
    const { server, token } = await state();
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    const [{ result }] = await chrome.scripting.executeScript({ target: { tabId: tab.id }, files: ["content/page_reader.js"] });
    let records = recordsFromTables(result.tables);
    if (!records.length) {
      const single = recordFromDetail(result.pairs, result.url, result.description);
      records = single ? [single] : [];
    }
    if (!records.length) { say("No postings were recognised on this page.", true); return; }
    say(`Importing ${records.length} posting(s)...`);
    say(describeBatch(await postBatch(server, token, buildPayload(records))));
  } catch (e) { say(e.message, true); }
});

render();
