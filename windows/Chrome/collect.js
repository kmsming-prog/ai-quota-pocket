(() => {
  const pages = {
    'chatgpt.com': /^\/(?:(?:codex\/)?settings\/usage|codex\/cloud\/settings\/analytics)\/?$/,
    'claude.ai': /^\/settings\/usage\/?$/,
  };
  const onUsagePage = () => pages[location.hostname]?.test(location.pathname) === true;
  if (!onUsagePage()) return;

  let reading;
  function collect() {
    if (reading) return reading;
    reading = (async () => {
      for (let attempt = 0; attempt < 32 && onUsagePage(); attempt++) {
        try {
          const snapshot = globalThis.QuotaReader.extract();
          return await chrome.runtime.sendMessage({ type: 'capture', snapshot });
        } catch {
          await new Promise(resolve => setTimeout(resolve, 750));
        }
      }
      return { ok: false };
    })().finally(() => { reading = null; });
    return reading;
  }

  chrome.runtime.onMessage.addListener((message, sender, reply) => {
    if (sender.id !== chrome.runtime.id || message.type !== 'watch-page') return;
    reply({ usagePage: onUsagePage() });
  });

  chrome.runtime.sendMessage({ type: 'watch' }).catch(() => {});
  collect();
})();
