# MenuSearchController 修改計劃

> 基於 reference/MenuSearchController.java（813 行，iDempiere 14.0 master）
> 目標：建立 MultiLangMenuSearchController.java

## 修改策略

**extends MenuSearchController**（滿足 GlobalSearch 的型別要求），但重寫所有 public methods（父類 private fields 不可存取）。

## 逐段修改清單

### 保留不動（直接複製）

| 行範圍 | 內容 | 原因 |
|--------|------|------|
| 17-73 | import 區塊 | 全部需要，另加 DB/Env/PreparedStatement imports |
| 80-100 | 常數宣告（INITIAL_LOADING_SIZE, Z_ICON_*, event names） | 邏輯不變 |
| 101-108 | instance fields（tree, listbox, model, layout, fullModel, inStarEvent, highlightText, recentMenuItemIds） | 重新宣告為自己的 fields |
| 155-175 | loadRecentItems() | 邏輯不變 |
| 176-195 | updateRecentItems() | 邏輯不變 |
| 196-210 | sortMenuItemModel() | 邏輯不變 |
| 211-240 | moveRecentItems() | 邏輯不變 |
| 241-260 | addTreeItem(DefaultTreeNode) | 邏輯不變 |
| 261-275 | isFolder(Treeitem) | 邏輯不變 |
| 276-300 | addTreeItem(Treeitem) | 邏輯不變 |
| 301-325 | getLabel(Treeitem), getImage(Treeitem) | 邏輯不變 |
| 326-400 | create(Component) | 邏輯不變 |
| 401-500 | onEvent(Event) | 邏輯不變 |
| 501-530 | onInsertedCallback, onDeletedCallback | 邏輯不變 |
| 531-570 | onSelect, loadMore | 邏輯不變 |
| 571-620 | selectTreeitem, select, onPostSelectTreeitem | 邏輯不變 |
| 621-640 | search(String) | 邏輯不變 |
| 660-700 | updateListboxModel | 邏輯不變 |
| 701-730 | selectPrior, selectNext | 邏輯不變 |
| 731-770 | onOk(Textbox) | 邏輯不變 |
| 771-780 | setHighlightText | 邏輯不變 |
| 781-813 | MenuItemRenderer inner class | 邏輯不變 |

### 需要修改

| 行範圍 | 原始內容 | 修改內容 |
|--------|---------|---------|
| 74-78 | class 宣告 `public class MenuSearchController implements EventListener<Event>` | 改為 `public class MultiLangMenuSearchController extends MenuSearchController` |
| 109-112 | constructor `public MenuSearchController(Tree tree)` | 改為 `public MultiLangMenuSearchController(Tree tree) { super(tree); this.tree = tree; }` |
| 130-155 | refreshModel() | 在原始邏輯後加入 `loadAlternativeLabels()` |
| 641-660 | onSearchEcho() — `new MenuListComparator()` | 改為 `new MultiLangMenuListComparator()` |

### 需要新增

| 內容 | 說明 |
|------|------|
| `private Map<Integer, List<String>> altLabelsMap` | 多語系標籤 Map（AD_Menu_ID → 其他語系名稱） |
| `private void loadAlternativeLabels()` | 查詢 AD_Menu_Trl + AD_Menu，建立 altLabelsMap |
| `private int getMenuId(MenuItem item)` | 從 MenuItem.getData() 取 AD_Menu_ID（處理 DefaultTreeNode 和 Treeitem 兩種型別） |
| `private class MultiLangMenuListComparator` | 擴充比對邏輯：先比對當前語系 label，再比對 altLabelsMap |
| 額外 imports | `java.sql.PreparedStatement`, `java.sql.ResultSet`, `java.util.HashMap`, `java.util.Map`, `org.compiere.util.DB` |

## 新增程式碼草稿

### loadAlternativeLabels()

```java
private void loadAlternativeLabels() {
    altLabelsMap = new HashMap<>();
    String currentLang = Env.getAD_Language(Env.getCtx());
    
    // 1. 查 AD_Menu_Trl（排除當前語系）
    String sql = "SELECT AD_Menu_ID, Name FROM AD_Menu_Trl "
        + "WHERE IsTranslated='Y' AND IsActive='Y' AND AD_Language != ?";
    PreparedStatement pstmt = null;
    ResultSet rs = null;
    try {
        pstmt = DB.prepareStatement(sql, null);
        pstmt.setString(1, currentLang);
        rs = pstmt.executeQuery();
        while (rs.next()) {
            int menuId = rs.getInt(1);
            String name = rs.getString(2);
            if (!Util.isEmpty(name))
                altLabelsMap.computeIfAbsent(menuId, k -> new ArrayList<>()).add(name);
        }
    } catch (Exception e) {
        log.log(Level.WARNING, "Failed to load AD_Menu_Trl", e);
    } finally {
        DB.close(rs, pstmt);
    }
    
    // 2. 查 AD_Menu 基礎表（當使用者語系非基礎語系時）
    if (!Env.isBaseLanguage(Env.getCtx(), "AD_Menu")) {
        sql = "SELECT AD_Menu_ID, Name FROM AD_Menu WHERE IsActive='Y'";
        pstmt = null; rs = null;
        try {
            pstmt = DB.prepareStatement(sql, null);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                int menuId = rs.getInt(1);
                String name = rs.getString(2);
                if (!Util.isEmpty(name))
                    altLabelsMap.computeIfAbsent(menuId, k -> new ArrayList<>()).add(name);
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to load AD_Menu base names", e);
        } finally {
            DB.close(rs, pstmt);
        }
    }
    
    log.info("MultiLang Search: loaded " + altLabelsMap.size() + " menu items with alternative labels");
}
```

### getMenuId(MenuItem)

```java
private int getMenuId(MenuItem item) {
    Object data = item.getData();
    if (data instanceof DefaultTreeNode) {
        Object nodeData = ((DefaultTreeNode<?>) data).getData();
        if (nodeData instanceof MTreeNode)
            return ((MTreeNode) nodeData).getNode_ID();
    } else if (data instanceof Treeitem) {
        Object attr = ((Treeitem) data).getAttribute(M_TREE_NODE_ATTR);
        if (attr instanceof MTreeNode)
            return ((MTreeNode) attr).getNode_ID();
    }
    return -1;
}
```

### MultiLangMenuListComparator

```java
private class MultiLangMenuListComparator implements Comparator<MenuItem> {
    @Override
    public int compare(MenuItem o1, MenuItem o2) {
        if (o1 == null || o2 == null) return -1;
        
        String compare = Util.deleteAccents(o1.getLabel().toLowerCase());
        
        // 1. 先比對當前語系 label（原始邏輯）
        String label2 = Util.deleteAccents(o2.getLabel().toLowerCase());
        boolean match;
        if (compare.length() < 3)
            match = label2.startsWith(compare);
        else
            match = label2.contains(compare);
        if (match) return 0;
        
        // 2. 再比對其他語系 labels
        int menuId = getMenuId(o2);
        if (menuId > 0 && altLabelsMap != null) {
            List<String> altLabels = altLabelsMap.get(menuId);
            if (altLabels != null) {
                for (String alt : altLabels) {
                    String altNorm = Util.deleteAccents(alt.toLowerCase());
                    if (compare.length() < 3)
                        match = altNorm.startsWith(compare);
                    else
                        match = altNorm.contains(compare);
                    if (match) return 0;
                }
            }
        }
        
        return -1;
    }
}
```

## 驗證 Checklist

複製完成後逐項確認：

- [ ] class 宣告 extends MenuSearchController
- [ ] constructor 呼叫 super(tree) 且設定自己的 tree field
- [ ] 所有 private fields 重新宣告（不依賴父類的 private fields）
- [ ] refreshModel() 最後呼叫 loadAlternativeLabels()
- [ ] onSearchEcho() 使用 MultiLangMenuListComparator
- [ ] getMenuId() 處理 DefaultTreeNode 和 Treeitem 兩種型別
- [ ] loadAlternativeLabels() 的 PreparedStatement 在 finally 中 close
- [ ] 所有 import 都存在
- [ ] GPLv2 license header
- [ ] FavouriteController callback 邏輯保持不變
- [ ] MenuItemRenderer 邏輯保持不變
- [ ] M_TREE_NODE_ATTR 使用父類的 public static field（不重新宣告）
