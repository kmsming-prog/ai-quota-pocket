# AI 額度口袋 Windows 0.2.0

顯示 ChatGPT 帳號的 Codex 額度和 Claude 額度。小視窗靠在 Windows 工具列上方，可收合至通知區。

## 第一次使用

1. [下載 ZIP](../downloads/ai-quota-pocket-windows-0.2.0.zip?raw=true)，解壓後開啟 `QuotaPocket.exe`。
2. 從 **⋯** 選 **連接 Codex** 或 **連接 Claude**。需要先安裝各家的官方工具。若電腦找不到工具，小視窗會顯示官網安裝說明。
3. 依官方工具的指示登入自己的訂閱帳號。首次授權可能會開啟瀏覽器。若官方工具已登入，啟動小視窗便會讀取。

安裝來源可見 [Codex CLI](https://learn.chatgpt.com/docs/cli) 和 [Claude Code for Windows](https://code.claude.com/docs/en/setup)。本版本使用原生 `codex.exe` 和 `claude.exe`。若安裝後小工具仍顯示「未安裝」，可重新開啟小工具。自訂安裝位置可用 `QUOTA_POCKET_CODEX` 和 `QUOTA_POCKET_CLAUDE` 指定執行檔的完整路徑。

## 額度更新

- 開啟時讀取，之後每 5 分鐘更新。選 **⋯ → 立即更新** 可手動讀取。瀏覽器授權完成後，平時可關閉瀏覽器。
- Codex 透過本機 App Server 的 `account/rateLimits/read` 取得整體額度。Claude 使用原生 Claude Code 的 `/usage`，依訂閱回傳的週期顯示資料。
- 缺少的週期會顯示「—」。暫時斷線時保留這次啟動期間最後讀到的數字，超過 20 分鐘會標成過期。程式在記憶體保留比例及時間，登入憑證由各家官方工具管理。

## 開發

在 Windows PowerShell 執行 `./windows/build.ps1`。建置使用 Windows 隨附的 .NET Framework C# 編譯器，輸出到 `windows/dist/QuotaPocket.exe`。

執行 `./windows/test.ps1` 可用模擬的官方工具回覆檢查配額換算、登入狀態、讀取逾時與小視窗顯示。
