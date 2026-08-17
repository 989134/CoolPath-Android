# HeatSafe Agent

HeatSafe Agent 是一個 Android 智慧城市 MVP：一般導航找最快路線，本 App 結合即時天氣、步行路線與沿途補給／避暑點，先用可重現的 Kotlin heuristic 計算熱風險，再讓 Gemini Decision Agent 選擇保留、提醒或改走替代路線。

> This score is a prototype decision heuristic and is not a medical risk assessment.

## 功能

- Compose Material 3 首頁、實際分析進度、路線比較與 Google Map 結果頁
- 定位權限拒絕與 GPS 無資料時安全 fallback
- Google Weather API current conditions（溫度、體感、Heat Index、濕度、UV、風況）
- 以 GPS 為中心的 3×3 Google Weather current-conditions 即時採樣圖層（約 0.9 km 間距、最多 9 次請求）
- Routes API `WALK`、替代路線、精簡 response field mask
- encoded polyline decode，沿路線約每 300m 取樣，控制 Places 請求數量
- Places SDK for Android (New) `searchNearby()`；依 Place ID 去重，最多五個補給／避暑點
- deterministic Heat Risk Score + Firebase AI Logic/Gemini JSON 決策；AI 失敗時仍可使用
- WorkManager 出發前 20 分鐘工作與 HIGH/MEDIUM Android 通知
- 單一輕量工具 App：設定頁 + 背景分析 + 使用者可選的可拖曳懸浮球（不需第二個 App）
- 一鍵把推薦步行路線交給外部 Google Maps；Google Maps 在前景導航時，HeatSafe location foreground service 約每 5 分鐘同步更新分析
- 神盾式背景 HUD：GPS 約每 5 秒更新；雲端 Weather／Routes／Places／Gemini 每 5 分鐘更新，移動超過 250m 且滿 2 分鐘時可提前重算
- 未設定金鑰或網路/API 失敗時，自動使用完整 Mock Demo 並顯示 `DEMO MODE`

## Architecture

```text
Compose UI → TripViewModel → AnalyzeTripUseCase
                           ├─ LocationRepository
                           ├─ WeatherRepository → Weather API
                           ├─ RoutesRepository → Routes API
                           ├─ CoolingPlaceRepository → Places SDK (New)
                           ├─ HeatRiskCalculator
                           └─ GeminiDecisionService → Firebase AI Logic

TripScheduler → HeatRiskWorker → AnalyzeTripUseCase → Notification
```

主要程式位於 `app/src/main/java/com/heatsafe/agent/`，依 `data/`、`domain/`、`ui/`、`worker/`、`notification/`、`util/` 分層。UI 不直接呼叫網路 API。

## Google Technology Used

- Gemini（`gemini-2.5-flash` Decision Agent）
- Firebase AI Logic
- Google Maps SDK for Android / Maps Compose
- Google Routes API
- Google Weather API
- Places SDK for Android (New) 5.3
- Google Play Services Location
- Android WorkManager / Notification

## Google Cloud Setup

1. 在 [Google Cloud Console](https://console.cloud.google.com/) 建立或選擇專案並啟用 Billing。
2. 啟用以下 API：
   - Maps SDK for Android
   - Places API (New)
   - Routes API
   - Weather API
3. 建立兩把金鑰：
   - Android key：限制為 Android app `com.heatsafe.agent` + debug/release SHA-1；API 限制 Maps SDK for Android、Places API (New)。
   - Web service key（MVP）：API 限制 Routes API、Weather API。正式上線建議由受保護後端代理 REST 呼叫，避免可擷取的 client-side web service key。
4. 確認 Routes、Weather、Places 的 quota 與預算警示。沿途搜尋已限制最多 8 個 sample points、每點 5 筆並去重。

## Firebase Setup

1. 在 [Firebase Console](https://console.firebase.google.com/) 對既有 Google Cloud 專案「新增 Firebase」。
2. 新增 Android App，package 填 `com.heatsafe.agent`。
3. 下載 `google-services.json`，放到 `app/google-services.json`。檔案已列入 `.gitignore`。
4. 開啟 Firebase AI Logic，選 Gemini Developer API 或 Vertex AI Gemini API；不需將 Gemini API key 寫進 App。
5. 建議正式環境啟用 Firebase App Check。沒有 Firebase 設定時 Gemini 呼叫會安全失敗，Use Case 改用 deterministic 決策。

## API Key Setup

複製 `local.properties.example` 為 `local.properties`，保留正確 SDK 路徑並填入：

```properties
sdk.dir=C\:\\Users\\YOUR_NAME\\AppData\\Local\\Android\\Sdk
MAPS_API_KEY=YOUR_ANDROID_RESTRICTED_KEY
GOOGLE_MAPS_WEB_API_KEY=YOUR_ROUTES_WEATHER_RESTRICTED_KEY
```

金鑰不會 hardcode 於 Kotlin；`local.properties` 和 `google-services.json` 都不會 commit。變更 key 後需 Clean/Rebuild，因為它們在 build time 注入 manifest/BuildConfig。

## Run

1. 使用最新版穩定 Android Studio 開啟專案。
2. 使用 JDK 17，等待 Gradle sync。
3. 啟動 API 26+ 且含 Google Play services 的 emulator，或連接實機。
4. 執行 `app`，允許定位與通知權限。
5. 若要使用懸浮球，在首頁開啟開關並於系統頁允許「顯示在其他 App 上層」。懸浮球使用有常駐通知的 foreground service，可隨時從通知或首頁關閉。
6. 分析完成後點「在 Google Maps 開始步行導航」。HeatSafe 會留在背景，懸浮球會覆蓋於 Google Maps 上方；點擊懸浮球展開最新風險、原因與更新時間。
7. 收合 HUD 顯示風險圖示與體感溫度；展開後顯示 3×3 氣溫採樣、UV、GPS 速度、最近補給／避暑點，底部按鈕可把較安全路線重新交給 Google Maps。

命令列驗證：

```powershell
.\gradlew.bat assembleDebug
```

核心 heuristic 與 polyline 測試位於 `app/src/test/`。若在 Windows 中文路徑執行 `testDebugUnitTest` 遇到 Gradle test classpath 問題，可將專案移到純 ASCII 路徑後執行；APK build 不受影響。

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。

## Demo Mode

保持兩個 key 為空即可直接執行。首頁與結果頁顯示 `DEMO MODE`，使用台北信義區假位置、35°C、體感 39°C、Heat Index 40°C、UV 9、兩條路線及四個補給／避暑點。點「模擬出發前 20 分鐘」會立刻跑完整 orchestrated 流程。

正式模式中任何單一網路服務發生無網路、timeout、HTTP/金鑰錯誤、空回應、無替代路線、無附近地點或 Gemini JSON 錯誤，都不會造成 crash；MVP 會使用安全 fallback。畫面只稱「Heat Risk／高熱風險路段」，不宣稱具備人行道地表溫度。

## Scheduling limitation

WorkManager 是延遲且可由系統批次處理的背景工作，不是 exact alarm，因此「出發前 20 分鐘」不保證精準到秒。Hackathon 現場請使用 Demo 按鈕即時觸發。

導航中的同步分析由使用者主動開啟的 foreground service 執行，預設每 5 分鐘更新一次，以平衡即時性、電量與 Google API request 數量。Android 不允許第三方 App 修改 Google Maps 內部 UI 或 polyline；HeatSafe 透過官方 Maps URL 開啟步行方向，並用自己的系統 overlay 顯示分析結果。

## Next step

目前由 `AnalyzeTripUseCase` 穩定 orchestrate Weather → Routes → Places → Risk → Gemini。下一階段可把這些 repository 包裝為 Gemini function tools，並把 REST web-service key 移至 Cloud Run/Functions 後端。
