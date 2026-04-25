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
  - 0.2.2.1 在 listener 中 log GlobalSearch 的子元件數量
    - 若 > 0 → GlobalSearch 已完全初始化，時機正確
    - 若 = 0 → 觸發太早，需改用其他 hook 或延遲 patching
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
    - 用 static boolean flag 或 WebApp attribute 標記
    - 多次呼叫 addListener 同一 class 是否安全？需驗證
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
  - 3.1.1.1 `comp instanceof GlobalSearch`
    - GlobalSearch 在 org.adempiere.webui.apps package
    - Fragment 共享 classloader，instanceof 可正常運作
  - 3.1.1.2 `"menuLookup".equals(comp.getId())`
    - 雙重檢查避免誤判（可能有其他 GlobalSearch 實例）
  - 3.1.1.3 檢查是否已被 patch 過（避免重複替換）
    - 用 Component attribute 標記：`comp.setAttribute("multilang.patched", true)`
    - 或檢查 controller 是否已是 MultiLangMenuSearchController
- 3.1.2 效能考量
  - 3.1.2.1 afterComponentAttached 對每個元件觸發
    - instanceof + equals 是 O(1)，不影響效能
  - 3.1.2.2 Desktop 建立完成後不再有新的 GlobalSearch
    - 實際只觸發一次 patching

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

### 3.3 建立新 GlobalSearch 並替換 DOM
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
  - 3.3.2.3 從舊 GlobalSearch 複製 placeholder 和 tooltip 屬性
    - 不硬編碼 "Alt+G"，避免覆蓋使用者自訂值
    - `newGs.setPlaceHolderText(oldGs.getPlaceHolderText())` 或 reflection 取值
- 3.3.3 DOM 替換
  - 3.3.3.1 oldGs.getParent().insertBefore(newGs, oldGs)
    - 在舊元件前面插入新元件
  - 3.3.3.2 oldGs.detach()
    - 移除舊元件（觸發 GC）
  - 3.3.3.3 確認替換後 DOM 結構正確
    - 新 GlobalSearch 應在 hbox > desktop-header-left 內

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
    - 基礎語系的名稱不在 Trl 表中（或 IsTranslated='N'）
    - 需要額外查：SELECT AD_Menu_ID, Name FROM AD_Menu
    - 避免重複：排除與當前語系 label 相同的名稱
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
  - 4.2.3.1 方案 A：用 Map<MenuItem, List<String>> 側邊存儲
    - 不修改 MenuItem class，最安全
    - Comparator 需要存取這個 Map
  - 4.2.3.2 方案 B：subclass MenuItem 增加 alternativeLabels field
    - 更乾淨但需要確認 MenuItem 的使用方式不會被影響
  - 4.2.3.3 選擇方案 A（Map 側邊存儲）
    - 理由：MenuItem 是 public class，其他 code 可能 instanceof 檢查

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
- 4.3.3 Comparator 存取 altLabelsMap 的方式
  - 4.3.3.1 Comparator 作為 controller 的 inner class
    - 可直接存取外部 class 的 altLabelsMap field
  - 4.3.3.2 或在 constructor 中傳入 altLabelsMap reference
    - 更明確的依賴關係，但需要每次 onSearchEcho() 建立時傳入
- 4.3.2 搜尋文字處理
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

## 8. 長期路線圖

### 8.1 貢獻回 iDempiere Core
- 8.1.1 向 iDempiere JIRA 提 Feature Request
  - 標題：Multi-language menu search in Global Search Box
  - 附上 plugin 的設計文件和實測結果作為 POC
- 8.1.2 準備 Core PR
  - 直接在 MenuSearchController 中加入多語系支援（不需 reflection、不需複製）
  - 在 MenuListComparator 中增加 alternativeLabels 比對
  - 在 refreshModel() 中載入 AD_Menu_Trl
- 8.1.3 若 PR 被接受
  - Plugin 可退役（或轉為只提供 CJK 增強等額外功能）
  - 維護成本歸零

### 8.2 v0.2 功能規劃
- 8.2.1 CJK 搜尋增強（7.1）
- 8.2.2 搜尋結果排序優化（當前語系匹配優先）
- 8.2.3 Tooltip 顯示匹配的語系名稱
- 8.2.4 SysConfig 開關（允許管理員停用多語系搜尋）

### 8.3 維護計劃
- 8.3.1 每次 iDempiere 升版時 diff 以下檔案
  - MenuSearchController.java（813 行，核心依賴）
  - GlobalSearch.java（278 行，constructor + field names）
  - HeaderPanel.java（233 行，globalSearch field name）
- 8.3.2 建立自動化 diff 腳本
  - 比對 iDempiere release tag 之間的變更
  - 標記影響本 plugin 的改動
