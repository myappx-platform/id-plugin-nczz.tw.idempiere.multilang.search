# iDempiere Multi-Language Global Search Plugin — 開發計劃

> 專案：`org.idempiere.zk.multilang.search`
> 類型：OSGi Fragment（Fragment-Host: org.adempiere.ui.zk）
> 目標：讓 iDempiere 上方搜尋框支援跨語系搜尋，使用者輸入任何已翻譯語系的選單名稱都能找到對應項目
> 版本：v1.0 — 五層完整展開 + Spike + 檢視補充 + 長期路線圖

---

## 0. Spike/POC（在正式開發前驗證核心假設）

### 0.1 驗證 config.xml listener 註冊機制
- 0.1.1 建立最小 fragment（只有 MANIFEST.MF + config.xml + 一個 WebAppInit class）
  - 0.1.1.1 WebAppInit.init() 中只做 `logger.info("MultiLang Search plugin loaded")`
    - 若 log 出現 → config.xml 機制可行
    - 若 log 未出現 → 整個 runtime patching 方案需重新設計
  - 0.1.1.2 部署到 iDempiere，重啟，檢查 server.log
    - 確認 ZK ConfigParser 有掃描到 metainfo/zk/config.xml
- 0.1.2 驗證 UiLifeCycle 動態註冊
  - 0.1.2.1 在 WebAppInit.init() 中呼叫 wapp.getConfiguration().addListener()
    - 確認 addListener 不拋 Exception
  - 0.1.2.2 在 UiLifeCycle.afterComponentAttached() 中 log comp.getClass().getName()
    - 確認 listener 被觸發，觀察觸發頻率

### 0.2 驗證 GlobalSearch 替換可行性
- 0.2.1 建立 dummy controller（複製原始邏輯，不加多語系）
  - 0.2.1.1 確認 `new GlobalSearch(dummyController)` 不拋 Exception
    - 驗證 IS-A MenuSearchController 的型別檢查通過
  - 0.2.1.2 確認替換後的 GlobalSearch 功能完整
    - 搜尋、選擇、開啟視窗、收藏、最近項目、Document Search tab
    - 鍵盤操作：↑↓ 選擇、Enter 開啟、Alt+G 聚焦
- 0.2.2 驗證 afterComponentAttached 觸發時機
  - 0.2.2.1 在 listener 中 log GlobalSearch 的 getId() 和子元件數量
    - 預期：getId() == null（因為 setId 在 insertBefore 之後）
    - 預期：子元件數量 > 0（GlobalSearch.init() 在 constructor 中已執行）
  - 0.2.2.2 驗證 echoEvent 延遲 patching 的時序
    - 在 echoEvent handler 中 log getId()
    - 預期：getId() == "menuLookup"（createSearchPanel 已完成）
  - 0.2.2.3 驗證延遲 patch 後 createSearchPanel() 的狀態
    - HeaderPanel.globalSearch field 應指向舊 GlobalSearch（patch 前）
    - patch 後用 reflection 更新為新 GlobalSearch
- 0.2.3 量測雙重建立的效能開銷
  - 0.2.3.1 計時：原始 Desktop 建立 vs. 加上 patching 的 Desktop 建立
    - 差異 < 200ms → 可接受
    - 差異 > 500ms → 需考慮優化（如重用舊 controller 的 model）

### 0.3 驗證「不複製 MenuSearchController」的替代方案（半天 spike）
- 0.3.1 嘗試 reflection patch 現有 controller 的 model
  - 0.3.1.1 取得 menuController.model（private ListModelList<MenuItem>）
    - 遍歷 model，用 MTreeNode.getNode_ID() 建立 altLabelsMap
  - 0.3.1.2 嘗試替換 model field 為自訂 ListModelList subclass
    - 自訂 subclass 在被 ListModels.toListSubModel() 使用時注入多語系 comparator
  - 0.3.1.3 評估結果
    - 若可行 → 不需要複製 813 行，大幅降低維護成本
    - 若不可行（預判：ListModels.toListSubModel 的 comparator 是外部傳入，無法攔截）→ 確認必須複製，記錄原因

### 0.4 Spike 結論與決策
- 0.4.1 記錄每個假設的驗證結果
- 0.4.2 若 0.1 失敗 → 需尋找替代的 hook 機制（可能回到 ZUL 覆蓋方案）
- 0.4.3 若 0.3 成功 → 調整 WBS 第 4 節，改用 model-patching 方案
- 0.4.4 若全部通過 → 進入正式開發

---

## 1. 專案骨架

### 1.1 MANIFEST.MF 設定
- 1.1.1 設定 Fragment-Host: org.adempiere.ui.zk;bundle-version="11.0.0"
  - 1.1.1.1 bundle-version 下限選 11.0.0（涵蓋 iDempiere 11+）
    - 確認 MenuSearchController 在 11.0 的 API 是否與 14.0 一致
  - 1.1.1.2 不設上限（允許未來版本）
    - 升版時需人工驗證相容性
- 1.1.2 設定 Bundle-ActivationPolicy: lazy
  - 1.1.2.1 Fragment 不能有 Bundle-Activator，確認無此 header
    - 若誤加 Activator，OSGi 會拒絕 resolve
- 1.1.3 設定 Require-Bundle 依賴
  - 1.1.3.1 org.adempiere.base（DB 查詢需要）
    - 確認 DB.getSQLValueEx、Env.getCtx 等 API 可用
  - 1.1.3.2 ZK bundles（zk, zul, zcommon）
    - 版本對齊 iDempiere 使用的 ZK 版本（10.0.1+）
  - 1.1.3.3 不加不必要的依賴（最小化）
    - 逐一確認每個 import 來自哪個 bundle
- 1.1.4 設定 Import-Package
  - 1.1.4.1 org.zkoss.zk.ui.util（WebAppInit, UiLifeCycle）
    - 確認這些 interface 在 ZK CE 版本中可用（非 EE only）
  - 1.1.4.2 org.compiere.model（MTreeNode）
    - 確認從 org.adempiere.base export
  - 1.1.4.3 org.compiere.util（DB, Env）
    - 確認從 org.adempiere.base export

### 1.2 build.properties 設定
- 1.2.1 source.. = src/
  - 1.2.1.1 確認 src/ 同時包含 Java source 和 metainfo/ 目錄
    - Tycho 會自動編譯 .java 並複製非 Java 資源
- 1.2.2 bin.includes = META-INF/, .
  - 1.2.2.1 不需要 OSGI-INF/（本專案不用 Declarative Services）
    - 我們用 config.xml + WebAppInit 而非 @Component

### 1.3 目錄結構建立
- 1.3.1 建立 src/metainfo/zk/ 目錄
  - 1.3.1.1 放置 config.xml
    - ZK 啟動時自動掃描 classpath 的 metainfo/zk/config.xml
- 1.3.2 建立 src/org/adempiere/webui/apps/ 目錄
  - 1.3.2.1 與 host bundle 同 package（共享 classloader）
    - 可存取 package-private class（如有需要）
- 1.3.3 建立 META-INF/ 目錄
  - 1.3.3.1 放置 MANIFEST.MF

### 1.4 pom.xml（Maven/Tycho）
- 1.4.1 packaging = eclipse-plugin
  - 1.4.1.1 Tycho 會讀取 MANIFEST.MF 解析依賴
    - 不需要在 pom.xml 重複宣告依賴
- 1.4.2 parent pom 指向 iDempiere 的 target platform
  - 1.4.2.1 idempiere.core.repository.url 指向 iDempiere p2 repository
    - 需要先 build iDempiere core（mvn verify）
  - 1.4.2.2 Tycho 版本對齊 iDempiere（4.0.8）
    - 版本不一致會導致 resolve 失敗

---

## 2. ZK 生命週期掛載

### 2.1 config.xml 註冊 WebAppInit listener
- 2.1.1 建立 src/metainfo/zk/config.xml
  - 2.1.1.1 config-name 設為 "multilang-search"
    - 用於 ZK 內部的依賴解析
  - 2.1.1.2 depends 設為 "zk"
    - 確保在 ZK core 初始化之後才載入
  - 2.1.1.3 listener-class 指向 MultiLangSearchInit
    - 完整 class name: org.adempiere.webui.apps.MultiLangSearchInit
- 2.1.2 驗證 config.xml 被 ZK 正確載入
  - 2.1.2.1 啟動 iDempiere 後檢查 log 無 "Unable to load a listener" 錯誤
    - ZK ConfigParser 載入失敗會 log ERROR
  - 2.1.2.2 在 init() 中加 log 確認被呼叫
    - 使用 CLogger 或 java.util.logging

### 2.2 MultiLangSearchInit（WebAppInit 實作）
- 2.2.1 實作 WebAppInit.init(WebApp wapp)
  - 2.2.1.1 透過 wapp.getConfiguration().addListener() 註冊 UiLifeCycle
    - addListener 接受 Class<?>，不是 instance
  - 2.2.1.2 只註冊一次（防止重複）
    - 用 WebApp attribute 標記：`wapp.setAttribute("multilang.search.registered", true)`
    - ❌ 不用 static boolean flag（WebApp 重啟時 JVM 不重啟，static 不會重置，但 Configuration 已清空，listener 消失）
    - WebApp attribute 隨 WebApp 重啟自動清除，確保重新註冊
- 2.2.2 錯誤處理
  - 2.2.2.1 init() 拋出 Exception 時 ZK 會 log 但不會阻止啟動
    - 確認不會影響 iDempiere 正常運作
  - 2.2.2.2 ClassNotFoundException 處理（fragment 未正確 resolve 時）
    - 在 config.xml 的 listener-class 找不到 class 時 ZK 的行為

### 2.3 UiLifeCycle listener 動態註冊
- 2.3.1 MultiLangSearchPatcher 實作 UiLifeCycle
  - 2.3.1.1 實作 afterComponentAttached(Component comp, Page page)
    - 這是唯一需要實作的 method（其他 4 個 method 留空）
  - 2.3.1.2 其他 method（afterComponentDetached, afterComponentMoved, afterPageAttached, afterPageDetached）留空實作
    - 不需要監聽這些事件
- 2.3.2 確認 listener 生命週期
  - 2.3.2.1 UiLifeCycle listener 是全域的（所有 Desktop 共享）
    - 不是 per-session，所以 patching 邏輯必須是 thread-safe
  - 2.3.2.2 listener 在 WebApp 存活期間持續有效
    - 不需要重新註冊

---

## 3. Runtime Patching

### 3.1 GlobalSearch 元件偵測邏輯
- 3.1.1 在 afterComponentAttached() 中檢查
  - 3.1.1.1 `comp instanceof GlobalSearch`（只用 instanceof，不檢查 ID）
    - ⚠️ 此時 comp.getId() 仍為 null（setId 在 insertBefore 之後才呼叫）
    - 因此不能用 `"menuLookup".equals(comp.getId())` 做判斷
  - 3.1.1.2 **不立即 patch**，改為排程延遲事件
    - `Events.echoEvent("onMultiLangPatch", comp, null)`
    - 同時註冊一次性 event listener 處理 patch 邏輯
    - echoEvent 會在當前 server execution 完成後、下一次 client round trip 時觸發
    - 此時 createSearchPanel() 已完全執行完畢，ID 已設定，DOM 穩定
  - 3.1.1.3 在延遲 handler 中做完整檢查
    - `"menuLookup".equals(comp.getId())` — 確認是搜尋框的 GlobalSearch
    - `comp.getAttribute("multilang.patched") != null` — 避免重複 patch
    - 兩個條件都通過才執行 patchGlobalSearch()
- 3.1.2 為什麼不能在 afterComponentAttached 中立即 patch
  - 3.1.2.1 createSearchPanel() 的執行順序：
    ```
    insertBefore(globalSearch, stub)  ← afterComponentAttached 在這裡觸發
    stub.detach()                     ← 還沒跑
    globalSearch.setId("menuLookup")  ← 還沒跑
    globalSearch.setPlaceHolderText() ← 還沒跑
    ```
  - 3.1.2.2 若此時替換 DOM：
    - createSearchPanel() 後續的 setId/setPlaceHolderText 會設定到已被 detach 的舊元件
    - HeaderPanel.globalSearch field 指向舊元件
    - 導致 Alt+G、closeSearchPopup 等功能異常
- 3.1.3 效能考量
  - 3.1.3.1 afterComponentAttached 對每個元件觸發
    - instanceof 是 O(1)，不影響效能
  - 3.1.3.2 echoEvent 增加一次 client round trip
    - 延遲約 10-50ms，使用者無感（Desktop 建立過程中）
  - 3.1.3.3 每個 Desktop 只觸發一次 patching
    - 多 tab 場景：每個 tab 獨立 patch，互不影響
  - 3.1.3.4 echoEvent 造成的視覺延遲
    - 初始頁面載入時使用者先看到原始搜尋框，echoEvent 回來後替換
    - 本地網路延遲 10-50ms（無感），慢網路可能 200-500ms
    - 期間搜尋框可用但只支援當前語系，不影響功能
    - 記錄在 README 的已知行為中

### 3.2 從舊 GlobalSearch 提取 Tree（reflection）
- 3.2.1 取得 GlobalSearch.menuController field
  - 3.2.1.1 Field f = GlobalSearch.class.getDeclaredField("menuController")
    - field name 硬編碼，升版時需確認
  - 3.2.1.2 f.setAccessible(true)
    - Java 17 module system：iDempiere 未啟用 module，setAccessible 可用
    - 若未來啟用 module，需加 --add-opens
  - 3.2.1.3 MenuSearchController oldCtrl = (MenuSearchController) f.get(oldGs)
    - 取得舊 controller 實例
- 3.2.2 取得 MenuSearchController.tree field
  - 3.2.2.1 Field tf = MenuSearchController.class.getDeclaredField("tree")
    - tree 是 org.zkoss.zul.Tree 型別
  - 3.2.2.2 Tree tree = (Tree) tf.get(oldCtrl)
    - 這個 Tree 是 MenuTreePanel 的 menu tree，包含所有選單節點
- 3.2.3 Reflection 失敗處理
  - 3.2.3.1 NoSuchFieldException → field name 變了（升版問題）
    - log WARNING 並放棄 patching（fallback 到原始搜尋）
  - 3.2.3.2 IllegalAccessException → module system 限制
    - log WARNING 並放棄 patching
  - 3.2.3.3 任何 reflection 失敗都不應影響 iDempiere 正常運作
    - 用 try-catch 包裹整個 patching 邏輯

### 3.3 建立新 GlobalSearch 並替換 DOM（在延遲 handler 中執行）
- 3.3.0 前置條件（由 3.1 的延遲 handler 保證）
  - createSearchPanel() 已完全執行完畢
  - comp.getId() == "menuLookup"
  - HeaderPanel.globalSearch field 已指向 comp
  - comp 未被 patch 過
- 3.3.1 建立 MultiLangMenuSearchController
  - 3.3.1.1 new MultiLangMenuSearchController(tree)
    - 傳入從舊 controller 取得的 Tree
  - 3.3.1.2 constructor 呼叫 super(tree) 初始化父類
    - 父類的 private fields 會被初始化但不使用
- 3.3.2 建立新 GlobalSearch
  - 3.3.2.1 new GlobalSearch(newController)
    - GlobalSearch constructor 接受 MenuSearchController（IS-A 關係成立）
  - 3.3.2.2 設定 id = "menuLookup"
    - HeaderPanel.closeSearchPopup() 和 onClientInfo() 用 getFellow("menuLookup") 找元件
  - 3.3.2.3 設定 placeholder = "Alt+G"，tooltip = "Alt+G"
    - GlobalSearch 有 public setPlaceHolderText() 但沒有 getPlaceHolderText()
    - HeaderPanel 硬編碼 "Alt+G"，不存在使用者自訂值的情況
    - 直接硬編碼即可
- 3.3.3 DOM 替換
  - 3.3.3.1 oldGs.getParent().insertBefore(newGs, oldGs)
    - 在舊元件前面插入新元件
  - 3.3.3.2 oldGs.detach()
    - 移除舊元件（觸發 GC）
  - 3.3.3.3 確認替換後 DOM 結構正確
    - 新 GlobalSearch 應在 hbox > desktop-header-left 內

### 3.5 FavouriteController callback 洩漏處理
- 3.5.1 問題描述
  - 舊 controller 在 create() 中註冊了 FavouriteController.addDeletedCallback / addInsertedCallback
  - 替換後新 controller 又註冊一組 → FavouriteController 持有兩組 callback
  - 舊 callback 引用已 detach 的 listbox → 操作無效但不報錯
  - 舊 callback 持有舊 controller 的 this reference → 阻止 GC（每 session 洩漏幾 KB）
- 3.5.2 v0.1 決策：接受洩漏，記錄為已知限制
  - 每 session 洩漏量極小（一個 MenuSearchController instance + 一個 detached Listbox）
  - Session 結束時全部回收
  - 記錄在 README 的已知限制中
- 3.5.3 v0.2 改進方向
  - 檢查 FavouriteController 是否有 removeCallback API
  - 若有 → patching 前先移除舊 callback
  - 若無 → 考慮用 WeakReference 包裝 callback

### 3.6 echoEvent 時序與 GlobalSearch 的 ON_CREATE_ECHO_EVENT
- 3.6.1 GlobalSearch 自己也用 echoEvent
  - init() 中註冊 ON_CREATE_ECHO_EVENT（用於 client info 回傳後調整 popup 高度）
  - 替換時的完整時序：
    ```
    1. 原始 GlobalSearch 建立 → 註冊 ON_CREATE_ECHO_EVENT
    2. afterComponentAttached → 排程 onMultiLangPatch echoEvent
    3. 原始 ON_CREATE_ECHO_EVENT 觸發（調整 popup）  ← 順序不保證
    4. onMultiLangPatch 觸發 → 替換 GlobalSearch       ← 順序不保證
    5. 新 GlobalSearch 建立 → 註冊自己的 ON_CREATE_ECHO_EVENT
    6. 新 ON_CREATE_ECHO_EVENT 觸發
    ```
  - 步驟 3 和 4 的順序取決於 echoEvent 的排隊順序
- 3.6.2 兩種情況都安全
  - 若 3 先於 4：原始 GlobalSearch 正常初始化，然後被替換。新的也會正常初始化。
  - 若 4 先於 3：原始 ON_CREATE_ECHO_EVENT 在已 detach 的元件上觸發，無害（ZK 不會報錯）。
- 3.6.3 在 Spike 0.2 中驗證實際順序
  - 在兩個 echoEvent handler 中都加 log，觀察觸發順序

### 3.4 更新 HeaderPanel.globalSearch field（reflection）
- 3.4.1 找到 HeaderPanel 祖先元件
  - 3.4.1.1 從 newGs 向上遍歷 parent 直到找到 HeaderPanel instance
    - while (hp != null && !(hp instanceof HeaderPanel)) hp = hp.getParent()
  - 3.4.1.2 若找不到 HeaderPanel → log WARNING
    - 不應發生，但防禦性處理
- 3.4.2 設定 private globalSearch field
  - 3.4.2.1 Field gsf = HeaderPanel.class.getDeclaredField("globalSearch")
    - 只有 Alt+G 快捷鍵需要這個 field
  - 3.4.2.2 gsf.setAccessible(true); gsf.set(hp, newGs)
    - 寫入新的 GlobalSearch 實例
  - 3.4.2.3 若 reflection 失敗 → Alt+G 不可用但其他功能正常
    - closeSearchPopup() 和 onClientInfo() 用 getFellow，不受影響

---

## 4. 多語系搜尋引擎

### 4.1 AD_Menu_Trl 多語系標籤載入
- 4.1.1 SQL 查詢設計
  - 4.1.1.1 查詢所有已翻譯的選單名稱（排除當前語系）
    ```sql
    SELECT AD_Menu_ID, AD_Language, Name
    FROM AD_Menu_Trl
    WHERE IsTranslated = 'Y' AND IsActive = 'Y'
      AND AD_Language != ?
    ```
    - 參數：Env.getAD_Language(Env.getCtx())
  - 4.1.1.2 一次查全部，建成 Map<Integer, List<String>>
    - key = AD_Menu_ID, value = 其他語系的 Name 列表
    - 不需要存 AD_Language（搜尋時不區分來源語系）
  - 4.1.1.3 也查基礎表 AD_Menu.Name（當使用者語系非基礎語系時）
    - 基礎語系的名稱可能在 Trl 表中 IsTranslated='N'，被上面的 WHERE 排除
    - 需要額外查：SELECT AD_Menu_ID, Name FROM AD_Menu
    - v0.1 不做去重（與當前語系 label 重複的名稱讓 comparator 多比對一次，無害）
- 4.1.2 載入時機
  - 4.1.2.1 在 refreshModel() 中，建完 model 後立即載入
    - refreshModel() 在 create() 中被呼叫，只執行一次
  - 4.1.2.2 使用 PreparedStatement + ResultSet（標準 iDempiere DB API）
    - DB.prepareStatement() / DB.close()
  - 4.1.2.3 查詢在 session 建立時執行一次
    - 不需要 cache invalidation（選單翻譯很少變動）
- 4.1.3 資料量評估
  - 4.1.3.1 典型系統：~300 選單項 × 2-3 語系 = 600-900 筆
    - Map 記憶體佔用 < 100KB
  - 4.1.3.2 查詢時間 < 50ms（單表掃描，有 AD_Menu_ID index）
    - 不需要額外建 index

### 4.2 MultiLangMenuSearchController（extends MenuSearchController）
- 4.2.1 繼承策略
  - 4.2.1.1 extends MenuSearchController（滿足 GlobalSearch 的型別要求）
    - GlobalSearch constructor 接受 MenuSearchController
  - 4.2.1.2 重寫所有 public methods（父類 private fields 不可存取）
    - 需要重寫的 methods：create, refreshModel, search, onSearchEcho, onEvent, selectPrior, selectNext, onOk, updateRecentItems, setHighlightText
  - 4.2.1.3 自己維護 model, listbox, layout, fullModel 等欄位
    - 與父類的 private fields 同名但獨立
- 4.2.2 refreshModel() 改動
  - 4.2.2.1 呼叫原始邏輯遍歷 Tree 建立 MenuItem list
    - 複製父類的 refreshModel 邏輯
  - 4.2.2.2 遍歷完成後，載入 AD_Menu_Trl 多語系標籤
    - 呼叫 4.1 的載入邏輯
  - 4.2.2.3 將多語系標籤關聯到對應的 MenuItem
    - 用 MTreeNode.getNode_ID() 作為 AD_Menu_ID
    - 存入 Map<MenuItem, List<String>> 或擴充 MenuItem
- 4.2.3 MenuItem 擴充策略
  - 4.2.3.1 方案 A：用 Map<Integer, List<String>> 側邊存儲（key = AD_Menu_ID）
    - 不修改 MenuItem class，最安全
    - 與 4.1.1.2 的 Map 結構一致
    - Comparator 透過 MenuItem.getData() → MTreeNode.getNode_ID() 查 Map
  - 4.2.3.2 方案 B：subclass MenuItem 增加 alternativeLabels field
    - 更乾淨但需要確認 MenuItem 的使用方式不會被影響
  - 4.2.3.3 選擇方案 A（Map<Integer, List<String>>）
    - 理由 1：MenuItem 沒有 override equals/hashCode，用 instance 做 key 在 model 重建時會失效
    - 理由 2：AD_Menu_ID 是穩定的 key，不受 MenuItem 實例生命週期影響
    - 理由 3：MenuItem 是 public class，其他 code 可能 instanceof 檢查

### 4.3 多語系 Comparator 邏輯
- 4.3.1 擴充比對範圍
  - 4.3.1.1 先比對當前語系 label（與原始邏輯一致）
    - accent-insensitive, case-insensitive
    - <3 字元：startsWith / ≥3 字元：contains
  - 4.3.1.2 若 label 不匹配，再比對 alternativeLabels
    - 遍歷 List<String>，任一匹配即算命中
    - 同樣 accent-insensitive, case-insensitive
  - 4.3.1.3 匹配優先順序：當前語系 > 其他語系
    - 排序時當前語系匹配的排前面（可選，v0.1 不實作）
- 4.3.2 Comparator 存取 altLabelsMap 的方式
  - 4.3.2.1 Comparator 作為 controller 的 inner class
    - 可直接存取外部 class 的 altLabelsMap field
  - 4.3.2.2 或在 constructor 中傳入 altLabelsMap reference
    - 更明確的依賴關係，但需要每次 onSearchEcho() 建立時傳入
  - 4.3.2.3 Comparator 中取得 AD_Menu_ID 的方式
    - ⚠️ 只能從 o2（候選 MenuItem）取，o1（搜尋文字）的 getData() == null
    - o1 是 `new MenuItem(value)` 建立的，只有 label，無 data
    - 從 o2 取 AD_Menu_ID：
      - `MenuItem.getData()` → cast to `DefaultTreeNode<?>` → `getData()` → cast to `MTreeNode` → `getNode_ID()`
      - 若 getData() 回傳 Treeitem（非 model-based tree），改用 `Treeitem.getAttribute(M_TREE_NODE_ATTR)` 取 MTreeNode
    - 若取不到 AD_Menu_ID（getData() 型別不符）→ 跳過多語系比對，只比對當前語系 label
- 4.3.3 搜尋文字處理
  - 4.3.2.1 使用 Util.deleteAccents() 去除重音符號
    - 與原始邏輯一致
  - 4.3.2.2 toLowerCase() 統一大小寫
    - 注意 Locale 問題：Turkish İ/i 等特殊情況
    - 使用 Locale.ROOT 或不指定（與原始邏輯一致）

### 4.4 搜尋結果顯示
- 4.4.1 Highlight 邏輯
  - 4.4.1.1 當前語系 label 匹配時：highlight 匹配文字（與原始一致）
    - MenuItemRenderer 已有 highlight 邏輯
  - 4.4.1.2 其他語系匹配時：label 不 highlight（因為 label 是當前語系）
    - 使用者看到的是當前語系名稱，搜尋文字可能不在其中
  - 4.4.1.3 考慮在 tooltip 中顯示匹配的語系名稱（v0.2 功能）
    - 例如：tooltip = "Purchase Order (en_US)" 
- 4.4.2 排序邏輯
  - 4.4.2.1 v0.1：保持原始排序（字母順序 + 最近使用置頂）
    - 不改變排序邏輯，降低風險
  - 4.4.2.2 v0.2：當前語系匹配優先於其他語系匹配
    - 需要自訂排序 Comparator

---

## 5. 測試與驗證

### 5.1 功能測試
- 5.1.1 基本多語系搜尋
  - 5.1.1.1 zh_TW session 輸入 "Purchase" → 找到「採購單」
    - 驗證英文名稱可搜到中文選單
  - 5.1.1.2 en_US session 輸入 "採購" → 找到 "Purchase Order"
    - 驗證中文名稱可搜到英文選單
  - 5.1.1.3 輸入部分文字 "Purch" → 找到所有含 "Purch" 的項目
    - substring 匹配在其他語系也生效
  - 5.1.1.4 輸入 1-2 字元 → 只做 prefix 匹配
    - 與原始邏輯一致（<3 字元用 startsWith）
  - 5.1.1.5 輸入不存在的文字 → 顯示空結果
    - 不應 crash 或顯示錯誤
- 5.1.2 原始功能不受影響
  - 5.1.2.1 當前語系搜尋仍正常
    - zh_TW 輸入 "採購" 仍能找到
  - 5.1.2.2 Document Search（第二個 tab）不受影響
    - GlobalSearch 有兩個 tab：Menu 和 Search
  - 5.1.2.3 最近使用項目仍置頂
    - updateRecentItems() 邏輯不變
  - 5.1.2.4 星號收藏功能正常
    - FavouriteController 互動不變
  - 5.1.2.5 鍵盤操作正常（↑↓ 選擇、Enter 開啟、Alt+G 聚焦）
    - selectPrior/selectNext/onOk/globalSearch.setFocus

### 5.2 效能測試
- 5.2.1 載入時間
  - 5.2.1.1 AD_Menu_Trl 查詢時間 < 100ms
    - 在 refreshModel() 中計時
  - 5.2.1.2 Desktop 建立總時間增加 < 200ms
    - 包含 patching 和 model 重建
    - ⚠️ 已知 tradeoff：GlobalSearch 會被建立兩次（原始 + 替換）
    - 若超過 200ms，考慮優化：重用舊 controller 的 Tree model 而非重新遍歷
  - 5.2.1.3 記憶體增加 < 1MB per session
    - Map<Integer, List<String>> 的大小
- 5.2.2 搜尋延遲
  - 5.2.2.1 每次搜尋的 comparator 執行時間 < 10ms
    - 300 items × 3 languages = 900 次字串比對
  - 5.2.2.2 使用者感知無延遲（與原始搜尋體驗一致）
    - ZK 的 onChanging 事件有 debounce

### 5.3 相容性測試
- 5.3.1 Theme 相容性
  - 5.3.1.1 iceblue_c theme → 正常運作
    - 預設 theme，必須支援
  - 5.3.1.2 breeze/default theme → 正常運作
    - 第二個內建 theme
  - 5.3.1.3 自訂 theme → 正常運作
    - runtime patching 不依賴 theme
- 5.3.2 iDempiere 版本相容性
  - 5.3.2.1 iDempiere 12 → 驗證
    - 目前開發目標版本
  - 5.3.2.2 iDempiere 11 → 驗證 MenuSearchController API 一致性
    - 比對 11.0 和 14.0 的 MenuSearchController.java
  - 5.3.2.3 未來版本 → 記錄需要 diff 的檔案清單
    - MenuSearchController.java, GlobalSearch.java, HeaderPanel.java

### 5.4 回歸測試
  - 5.4.1 移除 plugin 後系統恢復正常
    - 5.4.1.1 uninstall fragment → 重啟 → 搜尋框回到原始行為
      - UiLifeCycle listener 隨 WebApp 重啟消失
    - 5.4.1.2 無殘留資料或設定
      - plugin 不修改 DB、不寫 SysConfig

---

## 6. 打包與部署

### 6.1 Maven/Tycho build 設定
- 6.1.1 Root pom.xml
  - 6.1.1.1 modules 列出 plugin 和 p2 子專案
    - 標準 Tycho multi-module 結構
  - 6.1.1.2 Tycho version = 4.0.8（對齊 iDempiere）
    - 在 pluginManagement 中設定
- 6.1.2 Plugin pom.xml
  - 6.1.2.1 packaging = eclipse-plugin
    - Tycho 從 MANIFEST.MF 解析依賴
  - 6.1.2.2 idempiere.core.repository.url 指向 iDempiere p2
    - 可透過 -D 參數覆蓋
- 6.1.3 p2 pom.xml + category.xml
  - 6.1.3.1 packaging = eclipse-repository
    - 產出 p2 update site
  - 6.1.3.2 category.xml 定義 feature category
    - 用於 iDempiere 的 update-rest-extensions.sh

### 6.2 p2 repository 產出
- 6.2.1 mvn verify 產出 target/repository/
  - 6.2.1.1 包含 plugins/ 和 features/ 目錄
    - 標準 p2 repository 結構
  - 6.2.1.2 content.xml 和 artifacts.xml 自動產生
    - Tycho 處理

### 6.3 安裝與解除安裝流程
- 6.3.1 安裝
  - 6.3.1.1 執行 update-rest-extensions.sh <p2-repo-path>
    - iDempiere 標準 plugin 安裝方式
  - 6.3.1.2 重啟 iDempiere
    - Fragment 需要重啟才能 resolve
  - 6.3.1.3 驗證：登入後搜尋框支援多語系
    - 無需任何設定
- 6.3.2 解除安裝
  - 6.3.2.1 從 Felix console 移除 bundle
    - 或刪除 plugins/ 目錄下的 fragment
  - 6.3.2.2 重啟 iDempiere
    - 搜尋框恢復原始行為

### 6.4 README 文件
- 6.4.1 功能說明
  - 6.4.1.1 支援的搜尋場景（附範例）
  - 6.4.1.2 不影響的功能清單
- 6.4.2 安裝說明
  - 6.4.2.1 前置條件（iDempiere 版本）
  - 6.4.2.2 安裝步驟
  - 6.4.2.3 驗證方式
- 6.4.3 已知限制
  - 6.4.3.1 使用 reflection（升版需驗證）
  - 6.4.3.2 MenuSearchController 完整複製（升版需同步）
  - 6.4.3.3 不支援 Document Search tab 的多語系（scope 限制）

---

## 7. 完善性檢視 — 補充條件

> 以下是五層展開後重新檢視發現的不足條件，需納入對應的開發項目中。

### 7.1 CJK 搜尋特性（影響 4.3）— 延至 v0.2

原始 `MenuListComparator` 的規則是 `<3 字元用 startsWith，≥3 字元用 contains`。但 CJK（中日韓）字元每個字都有意義：

- 輸入「採」（1 字元）→ startsWith 匹配「採購單」→ ✅ 原始邏輯已可用
- 輸入「購」（1 字元）→ startsWith 不匹配「採購單」→ ❌ 需要 contains

**v0.1 決策**：不改動，保持與原始行為一致，降低風險。
**v0.2 決策**：偵測 CJK 字元，改用 contains。這是獨立的 enhancement，不只影響多語系搜尋，也改善原始的單語系搜尋體驗。

### 7.2 Thread Safety（影響 3.1, 2.3）

`UiLifeCycle` listener 是全域的（所有 Desktop 共享同一個 instance）。`afterComponentAttached()` 可能被多個 thread 同時呼叫（多使用者同時登入）。

**需確認**：
- patching 邏輯只操作傳入的 `comp` 參數（per-desktop），不共享 mutable state → 天然 thread-safe
- `altLabelsMap` 在 `refreshModel()` 中建立，是 per-controller instance → 不共享

**結論**：目前設計已是 thread-safe，不需額外同步。但需在 code review 時確認。

### 7.3 Graceful Degradation（影響 3.2, 3.3, 4.1）

任何環節失敗都不應影響 iDempiere 正常運作：

| 失敗點 | 行為 | 使用者影響 |
|--------|------|-----------|
| config.xml 載入失敗 | ZK log ERROR，繼續啟動 | 搜尋框維持原始行為 |
| WebAppInit.init() 拋 Exception | ZK log ERROR，繼續啟動 | 同上 |
| Reflection 取 field 失敗 | log WARNING，放棄 patching | 同上 |
| AD_Menu_Trl 查詢失敗 | log WARNING，altLabelsMap 為空 | 搜尋框可用但只搜當前語系 |
| DOM 替換失敗 | log ERROR，不 detach 舊元件 | 搜尋框維持原始行為 |
| echoEvent 觸發時元件已 detach | 檢查 `comp.getPage() != null`，若 null 則跳過 | 無影響 |

**實作**：整個 patching 邏輯用 try-catch 包裹，catch 中只 log 不 throw。

### 7.4 AD_Menu_Trl 資料品質（影響 4.1）

- **IsTranslated='N' 的記錄**：已在 SQL WHERE 中排除
- **與當前語系 label 相同的名稱**：不需要排除（重複比對不影響正確性，只是多一次無意義的比對）
- **空白或 null Name**：在建 Map 時過濾 `Util.isEmpty(name)` 的記錄

### 7.5 UserDef 覆蓋（影響 4.1, 4.2）

`MTree.getNodeDetail()` 可能用 `MUserDefWin`/`MUserDefProc`/`MUserDefInfo` 覆蓋選單名稱。我們的 `AD_Menu_Trl` 查詢不包含這些覆蓋。

**決策**：v0.1 不處理 UserDef 覆蓋。原因：
- UserDef 是 per-user/per-role 的自訂名稱，不屬於「多語系翻譯」範疇
- 處理 UserDef 需要額外查詢 3 張表，複雜度高
- 影響範圍極小（很少有人用 UserDef 改選單名稱）

### 7.6 Logging 策略（新增項目）

| 層級 | 內容 |
|------|------|
| INFO | Plugin 初始化成功、載入了 N 個語系 M 筆翻譯 |
| WARNING | Reflection 失敗、DB 查詢失敗（graceful degradation） |
| FINE | 每次 patching 的詳細步驟（debug 用） |

使用 `java.util.logging.Logger`（iDempiere 標準）或 `CLogger`。

### 7.7 授權（新增項目）

iDempiere 使用 GPLv2。本 plugin 作為 OSGi fragment 附加到 GPLv2 的 host bundle，且複製了 `MenuSearchController` 的邏輯，應使用 **GPLv2** 授權。

在每個 Java 檔案頭部加入 GPLv2 license header。

### 7.8 版本相容性矩陣（影響 5.3）

| iDempiere | ZK | MenuSearchController 行數 | 狀態 |
|-----------|-----|--------------------------|------|
| 14.x (master) | 10.0.1 | 813 | 開發目標 |
| 12.x | 10.0.1 | 需確認 | 待驗證 |
| 11.x | 9.6.x | 需確認 | 待驗證 |

**開發時**：先支援 14.x，再回溯驗證 12.x 和 11.x。

---

## 8. 領域知識需求與專家建議

> 本節列出實作本 plugin 所需的領域知識，並以各領域專家的角度提出注意事項與驗證要求。
> 開發者在動手前應逐項確認自己是否具備對應知識，不足的部分先補課再開工。

### 9.1 OSGi Framework

**需要程度**：必須
**對應 WBS**：0, 1

**需要的知識**：
- Fragment-Host 機制：fragment 沒有自己的 classloader，共享 host bundle 的 classloader
- Fragment 不能有 Bundle-Activator（OSGi 規範禁止）
- Fragment 的 classpath 資源會 merge 到 host bundle，但多個 fragment 提供同路徑資源時順序不可控
- Declarative Services（@Component + OSGI-INF XML）是 fragment 註冊服務的標準方式
- Bundle lifecycle：fragment 在 host bundle resolve 時一起 resolve，無法獨立啟停

**⚠️ 專家建議**：

1. **不要假設 fragment 的 class 能覆蓋 host 的同名 class。** Equinox 的行為是 host class 優先。如果你想「替換」MenuSearchController.class，這條路走不通。只能建立新 class（不同名）。

2. **Fragment resolve 失敗是靜默的。** 如果 MANIFEST.MF 的 Require-Bundle 版本不匹配，fragment 不會 resolve，但 iDempiere 照常啟動。你不會看到明顯的錯誤訊息。**驗證方式**：安裝後在 Felix console 執行 `lb | grep multilang`，確認 bundle 狀態是 `Resolved`（不是 `Installed`）。

3. **Fragment 的 Import-Package 和 Require-Bundle 是給 Tycho 編譯用的。** 執行期 fragment 共享 host 的所有依賴，不需要自己 resolve。但如果編譯期缺少依賴宣告，Tycho 會報錯。

### 9.2 ZK Framework

**需要程度**：必須（本專案最核心的領域知識）
**對應 WBS**：0, 2, 3

**需要的知識**：
- `metainfo/zk/config.xml`：ZK 啟動時掃描 classpath 的 JAR/bundle，自動載入此檔案註冊 listener
- `WebAppInit` 介面：在 ZK WebApp 初始化時觸發，可取得 `WebApp` 和 `Configuration` 物件
- `UiLifeCycle` 介面：全域 listener，5 個 callback（afterComponentAttached 等）
- `Events.echoEvent`：將事件送到 client 再回傳 server，用於延遲執行（下一次 request）
- `ListModels.toListSubModel`：ZK 的 list 過濾機制，接受 Comparator 做 filter
- Component 生命週期：`insertBefore` 觸發 `afterComponentAttached`，但此時 parent 的後續程式碼可能還沒跑完

**⚠️ 專家建議**：

1. **`config.xml` 在 OSGi 環境中是否被掃描，是本專案最大的未知數。** ZK 的 `ConfigParser` 掃描 classpath 的 `metainfo/zk/config.xml`，但在 OSGi 中 classpath 的概念不同於傳統 WAR。iDempiere 的 core 從未使用過 `config.xml`（只用 `lang-addon.xml`）。**Spike 0.1 是 go/no-go 的關鍵門檻，必須第一個做。**

2. **`afterComponentAttached` 的觸發時機比你想的更早。** 它在 `insertBefore`/`appendChild` 的那一刻就觸發，不是在整個 method 執行完之後。這就是為什麼我們需要 `echoEvent` 延遲。**絕對不要在 afterComponentAttached 中直接修改 DOM。**

3. **`echoEvent` 需要 client 存在。** 它的機制是 server → client → server。如果在 Desktop 建立的極早期（client 還沒連上）觸發，echoEvent 可能不會被處理。**驗證方式**：在 Spike 0.2 中確認 echoEvent handler 確實被呼叫。

4. **ZK CE vs EE 的 API 差異。** iDempiere 使用 ZK CE（Community Edition）。部分 ZK API 只在 EE 中可用。`WebAppInit` 和 `UiLifeCycle` 都是 CE API，但請在 Spike 中實際驗證，不要只看 JavaDoc。

5. **`ListModels.toListSubModel` 的 Comparator 是外部傳入的。** 這意味著你無法透過替換 model 來注入自訂 comparator——comparator 是在 `onSearchEcho()` 中 `new` 出來的。這就是為什麼必須複製 `MenuSearchController`。

### 9.3 iDempiere 內部架構

**需要程度**：必須
**對應 WBS**：3, 4

**需要的知識**：
- `HeaderPanel`：`createSearchPanel()` 是 protected，`globalSearch` 和 `menuTreePanel` 是 private
- `GlobalSearch`：constructor 接受 concrete `MenuSearchController`（無 interface），內部有 menuController 和 docController 兩個 tab
- `MenuSearchController`：813 行，所有內部狀態（model, listbox, layout, fullModel）都是 private，10+ 個 public methods
- `MenuItem`：獨立 public class，有 label/description/image/type/data 五個欄位
- `MTree.getNodeDetails()`：根據 session language JOIN AD_Menu_Trl 取翻譯名稱
- `MTreeNode.getNode_ID()`：在 menu tree 中等於 AD_Menu_ID

**⚠️ 專家建議**：

1. **複製 MenuSearchController 是最大的技術債。** 813 行程式碼，每次 iDempiere 升版都要 diff 同步。**建議在複製時加上明確的註解標記**：
   ```java
   // === COPIED FROM MenuSearchController.java (iDempiere 14.0, commit xxxxx) ===
   // === MODIFICATION START: multi-lang comparator ===
   // === MODIFICATION END ===
   ```
   這樣升版時可以快速定位哪些是原始碼、哪些是修改。

2. **不要假設 MenuItem.getData() 的型別。** 根據 tree 的實作方式，`getData()` 可能回傳 `DefaultTreeNode<?>` 或 `Treeitem`。兩種情況取 MTreeNode 的方式不同：
   - `DefaultTreeNode` → `((MTreeNode) treeNode.getData()).getNode_ID()`
   - `Treeitem` → `((MTreeNode) treeItem.getAttribute("MTreeNode")).getNode_ID()`
   
   **必須處理兩種情況，否則會 ClassCastException。**

3. **GlobalSearch 的 DocumentSearchController 會被重建。** 替換 GlobalSearch 時，新的 GlobalSearch 會建立一個全新的 DocumentSearchController。舊的 DocumentSearchController 的狀態（如果有）會丟失。目前 DocumentSearchController 是 stateless 的，但未來版本可能改變。**驗證方式**：替換後測試 Document Search tab 的搜尋功能。

4. **`FavouriteController` 的 callback 綁定在舊 controller 上。** `MenuSearchController.create()` 中註冊了 `FavouriteController.addDeletedCallback` 和 `addInsertedCallback`。替換後，舊 controller 的 callback 仍然存在但指向已廢棄的 listbox。新 controller 會註冊自己的 callback。**需確認不會導致重複觸發或 NPE。**

### 9.4 Java Reflection

**需要程度**：必須
**對應 WBS**：3

**需要的知識**：
- `getDeclaredField` vs `getField`：前者可取 private field，後者只取 public
- `setAccessible(true)`：繞過 Java 存取控制
- Java 17 module system 對 reflection 的影響
- OSGi 環境中 reflection 的 classloader 考量

**⚠️ 專家建議**：

1. **iDempiere 目前沒有啟用 Java module system（沒有 module-info.java）。** 所以 `setAccessible(true)` 可以正常運作。但 Java 17+ 會在 stderr 印出 `WARNING: An illegal reflective access operation has occurred`。這不影響功能但會污染 log。**如果未來 iDempiere 啟用 module system，所有 reflection 都會失敗。** 這是推動 Section 9.1（貢獻回 core）的最強理由。

2. **Reflection 的 field name 是字串硬編碼。** 如果 iDempiere 重構改了 field name（例如 `menuController` 改成 `menuCtrl`），reflection 會靜默失敗（NoSuchFieldException）。**建議把所有 reflection 的 field name 集中定義為常數**，升版時只需改一處。

3. **不要 cache Field 物件跨 request。** 雖然 `getDeclaredField` 有一定開銷，但 Field 物件綁定到特定的 Class 物件。在 OSGi 中，bundle 更新後 Class 物件會變，cached Field 會失效。每次 patching 都重新取 Field 是最安全的做法（反正每個 Desktop 只做一次）。

### 9.5 iDempiere DB / SQL

**需要程度**：必須
**對應 WBS**：4

**需要的知識**：
- `AD_Menu_Trl` 表結構：AD_Menu_ID, AD_Language, Name, IsTranslated, IsActive
- `AD_Menu` 基礎表：AD_Menu_ID, Name（base language 的名稱）
- `DB.prepareStatement(sql, trxName)` / `ResultSet` / `DB.close(rs, pstmt)` 的標準用法
- `Env.getAD_Language(Env.getCtx())` 取得當前 session 語系
- `Env.isBaseLanguage(ctx, tableName)` 判斷是否為基礎語系

**⚠️ 專家建議**：

1. **SQL 查詢必須用 PreparedStatement，不要拼字串。** iDempiere 的 DB API 已經封裝好了，直接用 `DB.prepareStatement`。這不只是安全問題，也是 iDempiere 的 coding convention。

2. **trxName 傳 null。** 我們的查詢是唯讀的，不需要事務。傳 null 讓 iDempiere 使用 autocommit connection。

3. **一定要在 finally 中 close ResultSet 和 PreparedStatement。** iDempiere 的 connection pool 有限，leak 會導致系統卡死。用 `DB.close(rs, pstmt)` 一次關閉。

4. **AD_Menu_Trl 的 IsTranslated 欄位很重要。** 有些語系的翻譯記錄存在但 IsTranslated='N'（表示只是從基礎語系複製過來，還沒真正翻譯）。這些記錄的 Name 和基礎語系相同，搜尋時會產生無意義的重複匹配。雖然不影響正確性，但 WHERE IsTranslated='Y' 可以減少無效資料。

### 9.6 Maven / Tycho Build

**需要程度**：必須
**對應 WBS**：1, 6

**需要的知識**：
- Tycho 的 `eclipse-plugin` packaging：從 MANIFEST.MF 解析依賴
- Target platform：Tycho 需要 iDempiere 的 p2 repository 來 resolve OSGi 依賴
- `build.properties`：控制哪些資源被包含在 bundle 中
- p2 repository 產出：`eclipse-repository` packaging + `category.xml`

**⚠️ 專家建議**：

1. **必須先 build iDempiere core。** Tycho 需要 iDempiere 的 p2 repository 來 resolve Fragment-Host 和 Require-Bundle。在 iDempiere source 目錄執行 `mvn verify` 產出 `org.idempiere.p2/target/repository/`。**這一步可能需要 30-60 分鐘，且需要 JDK 17+。**

2. **Tycho 版本必須和 iDempiere 一致（4.0.8）。** 版本不一致會導致 target platform resolve 失敗，錯誤訊息通常很難理解。

3. **`build.properties` 的 `bin.includes` 必須包含 `.`（當前目錄）。** 否則 `metainfo/zk/config.xml` 不會被包含在 bundle 中，ZK 就掃描不到。這是一個常見的遺漏。

4. **Fragment 的 packaging 是 `eclipse-plugin`，不是 `eclipse-fragment`。** Tycho 沒有 `eclipse-fragment` packaging type。Fragment 和 regular bundle 都用 `eclipse-plugin`，差別只在 MANIFEST.MF 有沒有 `Fragment-Host`。

### 9.7 Java 併發

**需要程度**：需要
**對應 WBS**：7.2

**需要的知識**：
- ZK 的 threading model：每個 Desktop 的事件在同一個 thread 中順序處理
- `UiLifeCycle` listener 是全域 singleton，但 callback 在各 Desktop 的 event thread 中執行
- `afterComponentAttached` 的 thread context

**⚠️ 專家建議**：

1. **不需要加 synchronized。** 每次 `afterComponentAttached` 呼叫都操作不同的 Component（屬於不同 Desktop），沒有共享 mutable state。加 synchronized 反而會造成不必要的 contention。

2. **但要注意 static 變數。** 如果你在 Patcher class 中使用 static field（例如 cache），就需要考慮 thread safety。**建議完全不用 static mutable state。**

### 9.8 Unicode / i18n

**需要程度**：需要
**對應 WBS**：4, 7.1

**需要的知識**：
- `Util.deleteAccents()`：iDempiere 的去重音工具（用 `java.text.Normalizer`）
- `String.toLowerCase()` 的 Locale 問題（Turkish İ/i）
- CJK Unicode Script 偵測（`Character.UnicodeScript.HAN` 等）

**⚠️ 專家建議**：

1. **v0.1 不要動 `toLowerCase()` 的行為。** 原始 `MenuListComparator` 用的是 `toLowerCase()` 不帶 Locale（等同 `Locale.getDefault()`）。改成 `Locale.ROOT` 雖然更正確，但會改變行為。保持一致，降低風險。

2. **CJK 偵測延到 v0.2。** v0.1 的目標是「多語系搜尋」，不是「改善 CJK 搜尋體驗」。混在一起會增加測試範圍和風險。

### 9.9 軟體設計模式

**需要程度**：需要
**對應 WBS**：3, 7.3

**需要的知識**：
- Graceful degradation：任何環節失敗都 fallback 到原始行為
- Runtime patching / monkey-patch：在不修改原始碼的情況下改變行為
- Comparator 設計：inner class 存取外部 field 的模式

**⚠️ 專家建議**：

1. **Graceful degradation 的 try-catch 要包在最外層。** 不要在每個小步驟都 try-catch（會吞掉有用的 stack trace）。在 `patchGlobalSearch()` 的最外層包一個 try-catch，catch 中 log 完整 exception 並 return。

2. **patching 失敗時不要嘗試「部分 patch」。** 要嘛完整替換成功，要嘛完全不動。如果在 DOM 替換到一半失敗（例如 insertBefore 成功但 detach 失敗），會留下兩個 GlobalSearch，造成更大的問題。**建議先建好新 GlobalSearch，確認無誤後再一次性替換。**

3. **為 patching 加上「開關」。** 雖然 v0.1 不做 SysConfig 開關（7.8.2.4），但至少在 code 中預留一個 `private static final boolean ENABLED = true` 常數。緊急情況下可以快速 rebuild 一個停用版本。

### 9.10 iDempiere 部署

**需要程度**：需要
**對應 WBS**：6

**需要的知識**：
- `update-rest-extensions.sh`：iDempiere 的標準 plugin 安裝腳本
- Felix console：OSGi bundle 管理介面（`lb`, `start`, `stop`, `uninstall`）
- iDempiere 的 plugins/ 目錄結構

**⚠️ 專家建議**：

1. **Fragment 安裝後必須重啟 iDempiere。** 不像 regular bundle 可以 hot deploy，fragment 需要 host bundle 重新 resolve。`update-rest-extensions.sh` 會提示重啟。

2. **解除安裝也需要重啟。** 從 Felix console `uninstall` fragment 後，host bundle 的 classloader 仍然持有舊的 class。必須重啟才能完全清除。

3. **測試時用 Felix console 的 `diag` 命令排查問題。** 如果 fragment 沒有 resolve，`diag <bundle-id>` 會告訴你缺少哪些依賴。

### 9.11 授權法律

**需要程度**：了解
**對應 WBS**：7.7

**⚠️ 專家建議**：

1. **複製 MenuSearchController 的程式碼使本 plugin 成為 GPLv2 的衍生作品。** 必須以 GPLv2 授權發布，且必須提供原始碼。如果你打算商業發布（不公開原始碼），這條路走不通。

2. **每個 Java 檔案都要加 GPLv2 license header。** 參考 iDempiere core 的任何 Java 檔案的頭部格式。

---

## 9. 長期路線圖

### 9.1 貢獻回 iDempiere Core
- 9.1.1 向 iDempiere JIRA 提 Feature Request
  - 標題：Multi-language menu search in Global Search Box
  - 附上 plugin 的設計文件和實測結果作為 POC
- 9.1.2 準備 Core PR
  - 直接在 MenuSearchController 中加入多語系支援（不需 reflection、不需複製）
  - 在 MenuListComparator 中增加 alternativeLabels 比對
  - 在 refreshModel() 中載入 AD_Menu_Trl
- 9.1.3 若 PR 被接受
  - Plugin 可退役（或轉為只提供 CJK 增強等額外功能）
  - 維護成本歸零

### 9.2 v0.2 功能規劃
- 9.2.1 CJK 搜尋增強（7.1）
- 9.2.2 搜尋結果排序優化（當前語系匹配優先）
- 9.2.3 Tooltip 顯示匹配的語系名稱
- 9.2.4 SysConfig 開關（允許管理員停用多語系搜尋）

### 9.3 維護計劃
- 9.3.1 每次 iDempiere 升版時 diff 以下檔案
  - MenuSearchController.java（813 行，核心依賴）
  - GlobalSearch.java（278 行，constructor + field names）
  - HeaderPanel.java（233 行，globalSearch field name）
- 9.3.2 建立自動化 diff 腳本
  - 比對 iDempiere release tag 之間的變更
  - 標記影響本 plugin 的改動
