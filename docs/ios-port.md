# iPhone 移植起點

先用 [範例 JSON](quota-snapshot.example.json) 建立 SwiftUI 畫面與 WidgetKit 小工具，再接上已驗證的用量來源。建議第一版先完成 ChatGPT（Codex）和 Claude 的顯示與更新狀態。

## 畫面與資料

- 小尺寸小工具顯示單一服務，可用中尺寸並排顯示兩個服務。每個服務保留當期與每週額度的各自記錄。
- 把 `remainingPct` 解讀為 0 到 100 的**剩餘**百分比。若來源提供已用比例，匯入時換算一次。空結果與真實的 0% 要分別處理。
- `observedAt` 及選填的 `resetAt` 都以 Unix 毫秒表示。缺少精確重置時間時，保留 `resetText` 供顯示。經過重置時間後可顯示「待更新」，取得新快照才替換比例。
- 範例使用 `chatgpt` 與 `claude`。Android 的 Codex 直連結果使用 `codex`，匯入時可正規化成 `chatgpt`。Codex 是 ChatGPT 訂閱下的開發工具額度。

## iOS 實作

1. 透過 App Groups，讓主程式寫入兩個 target 共用的最新額度快照。由 WidgetKit 讀取快照並產生時間軸，App 讀到新資料後可請求重新載入小工具。
2. 第一版可在 App 中匯入 JSON 測試。加入同步時，可讓桌面端傳送最小的額度快照，並在 iOS 主程式保管同步憑證。小工具只需要比例及時間，不需要接觸登入資料。
3. 用最後更新時間表示新舊。iOS 決定背景執行和小工具重載時機，App 可以在前景提供重新整理。
4. Android 的 PRoot 與 Claude Code 執行層是 Android 專用。若 iOS 採用桌面同步，先把資料顯示、同步和背景更新跑通，直連可另外評估。

可參考 [Android 小工具的顯示邏輯](../android/src/app/quotapocket/widget/QuotaWidget.java)、[Windows 資料模型](../windows/src/QuotaData.cs) 和 [Apple 的 WidgetKit 更新機制](https://developer.apple.com/documentation/widgetkit/keeping-a-widget-up-to-date/)。
