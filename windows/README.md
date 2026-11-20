# AI 額度口袋 Windows 0.2.2

顯示 ChatGPT 帳號的 Codex 額度和 Claude 額度。小視窗靠在 Windows 工具列上方，可收合至通知區。

## 第一次使用

1. [下載 ZIP](../downloads/ai-quota-pocket-windows-0.2.2.zip?raw=true)，解壓後開啟 `QuotaPocket.exe`。若舊版還在執行，先從 **⋯ → 結束** 關閉舊版。
2. 先安裝要連接的命令列工具。Codex 使用 [官方 Codex CLI](https://learn.chatgpt.com/docs/cli)。Claude 請依下方步驟裝好命令列版。
3. 從 **⋯** 選 **連接 Codex** 或 **連接 Claude**，依官方工具的指示登入。首次授權可能會開啟瀏覽器。若命令列已登入，開啟小工具便會讀取。

### Claude Code 命令列版

即使 Claude 桌面版已有「Code」分頁，小工具仍需要另外安裝 Windows 原生的 **Claude Code CLI**。請從開始功能表以一般權限開啟 Windows 終端機或 PowerShell，再執行下方指令。小工具的安裝提示可以直接複製。

```powershell
winget install --id Anthropic.ClaudeCode --exact --source winget
```

若 WinGet 提示「系統管理員內容不允許要求的作業」，請關閉該視窗，再以一般權限開啟 PowerShell 重試。`--source winget` 指定使用 WinGet 來源。沒有 WinGet 時，可以使用 [官方 Windows PowerShell 安裝方式](https://code.claude.com/docs/en/setup#install-on-native-windows)。

裝好後，回到小工具點 **⋯ → 連接 Claude**。

本版本使用原生 `codex.exe` 和 `claude.exe`。若安裝後仍顯示「未安裝」，可重新開啟小工具。自訂安裝位置可用 `QUOTA_POCKET_CODEX` 和 `QUOTA_POCKET_CLAUDE` 指定執行檔的完整路徑。

## 額度更新

- 開啟時讀取，之後每 5 分鐘更新。選 **⋯ → 立即更新** 可手動讀取。瀏覽器授權完成後，平時可關閉瀏覽器。
- Codex 透過本機 App Server 的 `account/rateLimits/read` 取得整體額度。Claude 使用原生 Claude Code 的 `/usage`，依訂閱回傳的週期顯示資料。
- 缺少的週期會顯示「—」。暫時斷線時保留這次啟動期間最後讀到的數字，超過 20 分鐘會標成過期。程式在記憶體保留比例及時間，登入憑證由各家官方工具管理。

## 開發

在 Windows PowerShell 執行 `./windows/build.ps1`。建置使用 Windows 隨附的 .NET Framework C# 編譯器，輸出到 `windows/dist/QuotaPocket.exe`。

執行 `./windows/test.ps1` 可用模擬的官方工具回覆檢查配額換算、登入狀態、讀取逾時與小視窗顯示。
