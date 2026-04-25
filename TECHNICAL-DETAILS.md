# 技術細節驗證報告

> 來源：深度分析 iDempiere 14.0 master branch 原始碼 + ZK 官方文件
> 日期：2026-04-25

本文件記錄 WBS 中所有技術假設的驗證結果，供開發時直接參考。

---

## 1. Reflection Field Names（確認）

| 目標 class | field name | 型別 | 用途 |
|-----------|-----------|------|------|
| `GlobalSearch` | `menuController` | `MenuSearchController` | 取得舊 controller → 取 Tree |
| `GlobalSearch` | `docController` | `DocumentSearchController` | 不需要存取 |
| `GlobalSearch` | `bandbox` | `Bandbox` | 不需要存取（有 public setPlaceHolderText） |
| `GlobalSearch` | `tabbox` | `Tabbox` | 不需要存取 |
| `MenuSearchController` | `tree` | `Tree` | 取得 menu tree 傳給新 controller |
| `MenuSearchController` | `model` | `ListModelList<MenuItem>` | Spike 0.3 可能需要 |
| `HeaderPanel` | `globalSearch` | `GlobalSearch` | 更新為新 GlobalSearch（Alt+G 需要） |
| `HeaderPanel` | `menuTreePanel` | `MenuTreePanel` | 不需要存取（從 menuController.tree 取） |

**實際需要 reflection 的只有 3 個 field**：`GlobalSearch.menuController`、`MenuSearchController.tree`、`HeaderPanel.globalSearch`。

---

## 2. GlobalSearch Public API（確認）

```java
// Constructor
public GlobalSearch(MenuSearchController menuController)

// Public methods
public void setPlaceHolderText(String placeHolder)  // ✅ 存在，委託給 bandbox.setPlaceholder()
public void setTooltipText(String tooltipText)       // ✅ 存在
public void closePopup()                              // ✅ 存在
public void onClientInfo()                            // ✅ 存在
public void onEvent(Event event)                      // ✅ EventListener 實作

// ❌ 不存在
// getPlaceHolderText() — 沒有 getter
```

**WBS 3.3.2.3 的修正**：因為沒有 `getPlaceHolderText()`，無法從舊 GlobalSearch 複製 placeholder。但 placeholder 固定是 `"Alt+G"`（HeaderPanel 硬編碼），直接設定即可。

---

## 3. GlobalSearch.init() 內部結構（確認）

```
GlobalSearch (Div)
└── Bandbox (id="globalSearchBox", autodrop=true)
    └── Bandpopup (height = desktopHeight - 100)
        └── Tabbox
            ├── Tab[0]: "Menu"     → menuController.create(tabPanel)
            └── Tab[1]: "search"   → docController.create(tabPanel)
```

**關鍵**：`menuController.create(tabPanel)` 在 `init()` 中被呼叫。這意味著當我們 `new GlobalSearch(newController)` 時，newController 的 `create()` 和 `refreshModel()` 會自動被呼叫。不需要手動呼叫。

---

## 4. MenuSearchController 完整結構（確認）

### Private Fields（需要在複製版本中重新宣告）
```java
private Tree tree;
private Listbox listbox;
private ListModelList<MenuItem> model;
private Vlayout layout;
private ListModelList<MenuItem> fullModel;
private boolean inStarEvent;
private String highlightText = null;
private List<String> recentMenuItemIds = new ArrayList<>();
```

### Public Static Field
```java
public static final String M_TREE_NODE_ATTR = "MTreeNode";
```

### Private Methods（全部需要複製）
| 方法 | 說明 |
|------|------|
| `loadRecentItems()` | 從 MPreference 載入最近使用的 menu ID |
| `sortMenuItemModel()` | 按 label 字母排序 |
| `moveRecentItems()` | 最近使用的項目移到頂部 |
| `addTreeItem(list, DefaultTreeNode)` | 從 model-based tree 加入 MenuItem |
| `addTreeItem(list, Treeitem)` | 從 component-based tree 加入 MenuItem |
| `isFolder(Treeitem)` | 判斷是否為資料夾 |
| `getLabel(Treeitem)` | 取得 treeItem 的 label |
| `getImage(Treeitem)` | 取得 treeItem 的 image |
| `onInsertedCallback(MTreeNode)` | FavouriteController 新增 callback |
| `onDeletedCallback(Integer)` | FavouriteController 刪除 callback |
| `onSelect(ListItem, Boolean)` | 處理選擇事件 |
| `loadMore()` | 載入超過 50 筆的剩餘項目 |
| `selectTreeitem(Object, Boolean)` | 選擇 tree item 並觸發導航 |
| `select(Treeitem)` | 展開父節點並選擇 |
| `onPostSelectTreeitem(Boolean)` | 發送 ON_CLICK 到 tree row |
| `updateListboxModel(ListModelList)` | 更新 listbox，處理分頁 |

### Inner Classes（需要複製）
- `MenuListComparator` — 搜尋比對邏輯（**這是我們要修改的核心**）
- `MenuItemRenderer` — 3 欄 listbox 渲染（label+icon / New 按鈕 / Star 按鈕）

### FavouriteController 互動
```java
// 在 create() 中註冊
FavouriteController controller = FavouriteController.getInstance(Executions.getCurrent().getSession());
controller.addDeletedCallback(t -> onDeletedCallback(t));
controller.addInsertedCallback(t -> onInsertedCallback(t));
```

**注意**：替換 GlobalSearch 時，舊 controller 的 callback 仍然存在於 FavouriteController 中。新 controller 會再註冊一組。需確認 FavouriteController 是否支援多組 callback（或舊的會被 GC 回收）。

### MenuItem.getData() 的兩種型別
```java
// Model-based tree（DefaultTreeNode 路徑）
MTreeNode mNode = (MTreeNode) ((DefaultTreeNode<?>) menuItem.getData()).getData();
int menuId = mNode.getNode_ID();

// Component-based tree（Treeitem 路徑）
Treeitem ti = (Treeitem) menuItem.getData();
MTreeNode mNode = (MTreeNode) ti.getAttribute(M_TREE_NODE_ATTR);
int menuId = mNode.getNode_ID();
```

**Comparator 中取 AD_Menu_ID 必須處理兩種情況。**

### type 欄位設定
- `addTreeItem(DefaultTreeNode)` 路徑：**不設定 type**
- `addTreeItem(Treeitem)` 路徑：`item.setType((String) treeItem.getAttribute(AbstractMenuPanel.MENU_TYPE_ATTRIBUTE))`

**影響**：model-based tree 的 MenuItem 沒有 type，renderer 中 `isWindow` 判斷會是 false（不顯示 New 按鈕）。這是原始行為，我們不需要改。

---

## 5. config.xml 機制（✅ 已驗證）

**ZK 官方機制**：`metainfo/zk/config.xml` 放在 classpath 中，ZK 的 `ConfigParser` 啟動時自動掃描。

**原始碼驗證**（ZK ConfigParser.java + WebManager.java）：
- `parseConfigXml()` 使用 `XMLResourcesLocator.getDependentXMLResources("metainfo/zk/config.xml", ...)`
- 這和 `lang-addon.xml` 使用的是**完全相同的 `XMLResourcesLocator` 機制**
- `lang-addon.xml` 已被 iDempiere 的 6 個 fragment 驗證可用 → config.xml 使用同一掃描器，也一定可用
- `parseConfigXml()` 內部呼叫 `parseListeners(config, el)` → 解析 `<listener>` 元素 → `config.addListener(class)`

**實證**：ZK 論壇錯誤訊息顯示 `bundleresource://818.fwk76432244/metainfo/zk/config.xml:29:12`，證明 ConfigParser 確實掃描 OSGi bundle resource。

**iDempiere 現狀**：
- ✅ `metainfo/zk/lang-addon.xml` 被 6 個 fragment 使用（同一掃描機制）
- ⚠️ `metainfo/zk/config.xml` 沒有 fragment 使用過（但機制相同）
- ⚠️ `Configuration.addListener()` 沒有 iDempiere 程式碼呼叫過（但 ZK 官方 API）

**風險等級**：低。掃描機制已驗證相同，Spike 0.1 仍建議做但不再是 go/no-go 門檻。

---

## 6. echoEvent 可靠性（確認）

- iDempiere 中 **49 個檔案、89 處** 使用 `Events.echoEvent`
- **GlobalSearch 自己就用了** `ON_CREATE_ECHO_EVENT`
- 標準用途：deferred init、focus 管理、post-action 處理
- 機制：server → client（包含在 HTTP response 中）→ client 立即回傳 → server 處理
- **在 Desktop 建立期間使用是安全的**

---

## 7. Host Bundle MANIFEST.MF（確認）

```
Eclipse-ExtensibleAPI: true    ← Fragment 可存取 host 的所有 package（含未 export 的）
Eclipse-BundleShape: dir       ← 以目錄形式部署
singleton:=true
```

**Export-Package 包含**：`org.adempiere.webui.apps`（MenuItem、GlobalSearch、MenuSearchController 所在 package）

**意義**：我們的 fragment 不需要特殊設定就能存取所有需要的 class。`Eclipse-ExtensibleAPI: true` 確保即使某些 package 沒有 export，fragment 也能存取。
