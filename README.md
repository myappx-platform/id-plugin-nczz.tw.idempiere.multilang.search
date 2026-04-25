# iDempiere Multi-Language Global Search

An iDempiere plugin that enables searching menu items across **all translated languages** in the global search box.

**Before:** A user logged in with `zh_TW` can only find menu items by their Chinese names.
**After:** The same user can type `Purchase Order` and find `採購單`, or type `仕入` to find the Japanese translation — all from the same search box.

## How It Works

This plugin is an OSGi fragment that attaches to `org.adempiere.ui.zk`. It uses ZK's `UiLifeCycle` mechanism to automatically detect and enhance the global search component at runtime — **no theme modification, no core changes, no configuration required**.

```
User logs in → Desktop created → GlobalSearch component attached
  → Plugin detects it via UiLifeCycle listener
    → Replaces with multi-language-aware version
      → Loads all AD_Menu_Trl translations into memory
        → Search now matches across all languages
```

## Requirements

- iDempiere 11.0 or later
- At least 2 active languages with translated menu items (`AD_Menu_Trl.IsTranslated = 'Y'`)

## Installation

### Option A: From Pre-built Release

1. Download the latest `p2-repository.zip` from [Releases](../../releases)
2. Extract to a directory on your iDempiere server
3. Run:
   ```bash
   cd /opt/idempiere
   ./update-rest-extensions.sh /path/to/extracted/repository/
   ```
4. Restart iDempiere
5. Log in and test: type a menu name in another language in the search box

### Option B: Build from Source with Docker

No JDK or Maven installation required — only Docker.

1. Clone this repository:
   ```bash
   git clone https://github.com/user/org.idempiere.zk.multilang.search.git
   cd org.idempiere.zk.multilang.search
   ```

2. Clone and build iDempiere (needed for the p2 target platform):
   ```bash
   git clone https://github.com/idempiere/idempiere.git ../idempiere
   docker run --rm -v "$(pwd)/../idempiere":/src -w /src \
     maven:3.9-eclipse-temurin-17 mvn verify -DskipTests
   ```

3. Build the plugin:
   ```bash
   docker run --rm \
     -v "$(pwd)":/plugin -v "$(pwd)/../idempiere":/idempiere -w /plugin \
     maven:3.9-eclipse-temurin-17 \
     mvn verify -Didempiere.core.repository.url=file:///idempiere/org.idempiere.p2/target/repository
   ```

4. The p2 repository is at:
   ```
   org.idempiere.zk.multilang.search.p2/target/repository/
   ```

5. Deploy to iDempiere:
   ```bash
   # Copy to server, then:
   cd /opt/idempiere
   ./update-rest-extensions.sh /path/to/repository/
   systemctl restart idempiere
   ```

## Verification

After installation and restart:

1. Log in with a non-English locale (e.g., `zh_TW`)
2. Click the global search box (or press `Alt+G`)
3. Type an English menu name like `Purchase`
4. You should see Chinese menu items that match the English translation

Check the server log for:
```
INFO: Multi-Language Global Search plugin initialized
INFO: Multi-Language Search: loaded N alternative labels for M menu items
INFO: Multi-Language Global Search patched successfully
```

## Uninstallation

1. Remove the bundle from Felix console or delete from `plugins/` directory
2. Restart iDempiere
3. The search box returns to its original single-language behavior
4. No database changes, no residual configuration

## Technical Details

- **Type:** OSGi Fragment (`Fragment-Host: org.adempiere.ui.zk`)
- **Hook mechanism:** `metainfo/zk/config.xml` → `WebAppInit` → `UiLifeCycle` → `Events.echoEvent`
- **Search data:** Loaded from `AD_Menu_Trl` + `AD_Menu` tables once per session
- **Performance:** ~50ms additional load time per session, zero impact on search speed
- **Graceful degradation:** If any component fails, the search box falls back to default behavior

## Known Limitations

- Uses Java reflection to access 3 private fields (upgrade-sensitive)
- Copies `MenuSearchController` logic (~900 lines) — must be synced on iDempiere upgrades
- Does not enhance the Document Search tab (only the Menu tab)
- Brief visual flash on slow networks during the component replacement (~10-50ms)
- Minor memory leak: old `FavouriteController` callbacks persist until session ends

## Compatibility

| iDempiere | Status |
|-----------|--------|
| 14.x | Primary target |
| 12.x | Should work (same ZK version) |
| 11.x | Should work (verify `MenuSearchController` API) |

## Files to Diff on Upgrade

When upgrading iDempiere, check these files for changes:

| File | Lines | Impact |
|------|-------|--------|
| `MenuSearchController.java` | 813 | Core dependency — must sync |
| `GlobalSearch.java` | 278 | Constructor + field names |
| `HeaderPanel.java` | 233 | `globalSearch` field name |

## License

GPLv2 — same as iDempiere. See [LICENSE](LICENSE).
