const statusLine = document.getElementById('status');
const refresh = document.getElementById('refresh');

async function render() {
  const { linked } = await chrome.storage.local.get('linked');
  statusLine.textContent = linked === true ? '已送出額度資料至 Windows'
    : linked === false ? '請先開啟 Windows 小工具並選取「設定 Chrome」'
    : '等待讀取官方用量頁';
}

refresh.addEventListener('click', async () => {
  refresh.disabled = true;
  try {
    const result = await chrome.runtime.sendMessage({ type: 'refresh' });
    statusLine.textContent = result?.refreshed ? '用量頁正在更新…' : '請先開啟上方的用量頁';
  } catch {
    statusLine.textContent = '更新失敗，請重新開啟用量頁';
  } finally {
    refresh.disabled = false;
  }
});
chrome.storage.onChanged.addListener(render);
render();
