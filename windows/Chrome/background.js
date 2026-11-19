const HOST = 'tw.kang.quota_pocket_windows';
const ALARM = 'quota-pocket-windows';
const compare = {
  'chatgpt.com': { provider: 'chatgpt', usagePath: /^\/(?:(?:codex\/)?settings\/usage|codex\/cloud\/settings\/analytics)\/?$/ },
  'claude.ai': { provider: 'claude', usagePath: /^\/settings\/usage\/?$/ },
};
let writeQueue = Promise.resolve();

async function ensureAlarm() {
  if (!await chrome.alarms.get(ALARM)) {
    await chrome.alarms.create(ALARM, { periodInMinutes: 5 });
  }
}

function storeAndSend(snapshot) {
  const write = writeQueue.then(async () => {
    const { snapshots = {} } = await chrome.storage.local.get('snapshots');
    if ((snapshots[snapshot.provider]?.observedAt ?? 0) <= snapshot.observedAt) {
      snapshots[snapshot.provider] = snapshot;
    }
    await chrome.storage.local.set({ snapshots });
    try {
      const result = await chrome.runtime.sendNativeMessage(HOST, { snapshots: Object.values(snapshots) });
      await chrome.storage.local.set({ linked: result?.ok === true });
    } catch {
      await chrome.storage.local.set({ linked: false });
    }
    return { ok: true };
  });
  writeQueue = write.catch(() => {});
  return write;
}

async function refreshPages() {
  const sourceTabs = await chrome.storage.session.get(['tab-chatgpt', 'tab-claude']);
  let refreshed = 0;
  for (const tabId of Object.values(sourceTabs)) {
    try {
      const reply = await chrome.tabs.sendMessage(tabId, { type: 'watch-page' }, { frameId: 0 });
      if (reply?.usagePage) {
        await chrome.tabs.reload(tabId);
        refreshed++;
      }
    } catch {
      // A closed or navigated tab will send a fresh registration when reopened.
    }
  }
  return { refreshed };
}

chrome.runtime.onMessage.addListener((message, sender, reply) => {
  if (sender.id !== chrome.runtime.id) return;
  if (!sender.tab && message.type === 'refresh') {
    refreshPages().then(reply, () => reply({ refreshed: 0 }));
    return true;
  }
  if (sender.frameId !== 0 || !sender.tab?.id || !sender.url) return;
  const url = new URL(sender.url);
  const service = compare[url.hostname];
  if (url.protocol !== 'https:' || !service?.usagePath.test(url.pathname)) return;
  if (message.type === 'watch') {
    chrome.storage.session.set({ ['tab-' + service.provider]: sender.tab.id })
      .then(() => reply({ ok: true }), () => reply({ ok: false }));
    return true;
  }
  if (message.type === 'capture' && message.snapshot?.provider === service.provider) {
    const { provider, observedAt, windows } = message.snapshot;
    if (!Number.isSafeInteger(observedAt) || !Array.isArray(windows)) return;
    const quota = windows.slice(0, 2).map(({ id, remainingPct, resetAt, resetText }) => ({
      id, remainingPct, resetAt, resetText,
    }));
    storeAndSend({ provider, observedAt, windows: quota })
      .then(reply, () => reply({ ok: false }));
    return true;
  }
});

chrome.alarms.onAlarm.addListener(alarm => {
  if (alarm.name === ALARM) refreshPages().catch(() => {});
});
chrome.runtime.onInstalled.addListener(() => ensureAlarm().catch(() => {}));
chrome.runtime.onStartup.addListener(() => ensureAlarm().catch(() => {}));
ensureAlarm().catch(() => {});
