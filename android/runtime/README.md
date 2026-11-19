# Android Linux 執行層

此目錄包含 DSH Mobile Runtime [2026.11.15-2](https://github.com/sorsama/dsh-mobile-runtime/releases/tag/2026.11.15-2) 發布的 Android PRoot 與 loader，分為 `arm64-v8a` 及 `x86_64`。二進位使用該版本的 SHA-256 校驗，對應的 PRoot 及 talloc 原始碼隨 APK 放在 `assets/`。相同版本的 Android 建置設定與流程位於 [上游專案](https://github.com/sorsama/dsh-mobile-runtime)。PRoot 採 GPL-2.0，talloc 採 LGPL-3.0，授權內容也在隨附的原始碼內。

Alpine 最小系統和 Claude Code 在使用者按下 Claude 登入後才下載。系統映像與兩種架構的 Claude Code 套件都使用固定的 SHA-256。`claude-apkindex-*.tar.gz` 是 Anthropic 官方套件庫簽署的索引，`claude-code.rsa.pub` 是驗證索引的官方公開金鑰。手機從本機暫存的原始索引及同版本套件，以套件庫模式安裝並驗證 Claude Code。
