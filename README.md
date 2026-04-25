# iDempiere Multi-Language Global Search

Search menu items across **all translated languages** from the global search box — no configuration required.

**Before:** Logged in as `zh_TW`, you can only find menu items by Chinese names.
**After:** Type `Purchase Order` and find `採購單`. Type `會計` from an English session and find `Accounting Rules`.

## For Users

### Requirements

- iDempiere 12 or later
- At least 2 active languages with translated menu items

### Installation

1. Download `p2-repository.zip` from [Releases](../../releases) and extract it

2. Deploy using iDempiere's standard deployment tool:
   ```bash
   cd /opt/idempiere
   ./update-prd.sh file:///path/to/extracted/repository org.idempiere.zk.multilang.search
   ```

3. Restart iDempiere:
   ```bash
   systemctl restart idempiere
   ```

4. Log in and test: type a menu name in another language in the search box

### Alternative: Install via Felix Web Console

1. Download the plugin JAR from [Releases](../../releases)
2. Open `http://<your-server>:8080/osgi/system/console/bundles` (login: `SuperUser` / `System`)
3. Click **Install/Update**, select the JAR, click **Install or Update**
4. Restart iDempiere

### Installation via CLI

```bash
# Using update-prd.sh (recommended)
cd /opt/idempiere
./update-prd.sh file:///path/to/p2/repository org.idempiere.zk.multilang.search
systemctl restart idempiere

# Or using Felix Web Console API
curl -u "SuperUser:System" \
  -F "bundlefile=@org.idempiere.zk.multilang.search_1.0.0.jar" \
  -F "action=install" -F "bundlestartlevel=4" \
  http://localhost:8080/osgi/system/console/bundles
systemctl restart idempiere
```

### Installation with Docker

Add the plugin JAR before iDempiere starts using a custom entrypoint:

```yaml
services:
  idempiere:
    image: idempiereofficial/idempiere:12-release
    entrypoint:
      - bash
      - -c
      - |
        cp /custom-plugins/*.jar /opt/idempiere/plugins/
        grep -q multilang /opt/idempiere/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info 2>/dev/null || \
          echo 'org.idempiere.zk.multilang.search,1.0.0,plugins/org.idempiere.zk.multilang.search_1.0.0.jar,4,false' >> /opt/idempiere/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info
        exec ./docker-entrypoint.sh idempiere
    volumes:
      - ./plugins:/custom-plugins:ro
```

Place the JAR in a local `plugins/` directory mounted as `/custom-plugins`.

### Verification

After login, check the server log for:
```
Multi-Language Global Search plugin initialized
Multi-Language Search: loaded N alternative labels for M menu items
Multi-Language Global Search patched successfully
```

### Uninstallation

```bash
cd /opt/idempiere
./update-prd.sh file:///path/to/p2/repository org.idempiere.zk.multilang.search
```

The first run of `update-prd.sh` uninstalls, the second installs. To uninstall only, run once with the same arguments — it will uninstall the existing version. Then restart iDempiere.

No database changes, no residual configuration.

### ⚠️ Important

- **Do NOT clear the OSGi cache** (`configuration/org.eclipse.osgi/`). This breaks iDempiere's classloader state.
- The plugin installs as an OSGi fragment — it requires a restart to take effect.

---

## For Developers

### How It Works

```
ZK WebApp starts
  → Scans metainfo/zk/config.xml → registers WebAppInit listener
    → WebAppInit registers UiLifeCycle listener
      → User logs in → Desktop created → GlobalSearch component attached
        → UiLifeCycle detects GlobalSearch via instanceof
          → Deferred patching via Events.echoEvent (avoids timing issues)
            → Replaces GlobalSearch with multi-language version
              → Loads AD_Menu_Trl translations into memory
                → Search now matches across all languages
```

### Architecture

| Component | Purpose |
|-----------|---------|
| `MultiLangSearchInit` | `WebAppInit` — registers UiLifeCycle listener on ZK startup |
| `MultiLangSearchPatcher` | `UiLifeCycle` — detects and replaces GlobalSearch at runtime |
| `MultiLangMenuSearchController` | Extended `MenuSearchController` with cross-language comparator |

The plugin is an **OSGi fragment** attached to `org.adempiere.ui.zk`. It shares the host bundle's classloader — no `Import-Package` or `Require-Bundle` needed (adding them breaks the host's package wiring).

### Key Design Decisions

**Runtime patching instead of ZUL override:**
Multiple OSGi fragments providing the same classpath resource have unpredictable ordering. Runtime patching via `UiLifeCycle` + `echoEvent` works with any theme.

**Deferred patching with echoEvent:**
`afterComponentAttached` fires during `insertBefore()`, before `setId()` completes. Direct DOM modification at that point corrupts the parent method's state. `Events.echoEvent` defers to the next client round-trip when the DOM is stable.

**No Import-Package in MANIFEST.MF:**
Fragment's `Import-Package` merges into the host's imports, changing package wiring and breaking ZK's class resolution (causes `CustomGridDataLoader` ClassNotFoundException). Fragment inherits all host dependencies automatically.

**Backward-compatible API access:**
`MenuItem(String)` and `Icon.getIconSclass()` exist only in iDempiere 14+. The plugin uses factory methods and reflection to work on iDempiere 12+.

### Building from Source

Requires Docker only — no local JDK or Maven.

1. Clone iDempiere and build the p2 target platform:
   ```bash
   git clone https://github.com/idempiere/idempiere.git
   docker run --rm -v "$(pwd)/idempiere":/src -v "$HOME/.m2":/root/.m2 \
     -w /src maven:3.9-eclipse-temurin-17 mvn verify -DskipTests
   ```

2. Clone and build this plugin:
   ```bash
   git clone https://github.com/anthropics/org.idempiere.zk.multilang.search.git
   docker run --rm \
     -v "$(pwd)/org.idempiere.zk.multilang.search":/plugin \
     -v "$(pwd)/idempiere":/idempiere \
     -v "$HOME/.m2":/root/.m2 \
     -w /plugin maven:3.9-eclipse-temurin-17 \
     mvn verify -Didempiere.repository=file:///idempiere/org.idempiere.p2/target/repository
   ```

3. Output: `org.idempiere.zk.multilang.search.p2/target/repository/plugins/*.jar`

### Project Structure

```
org.idempiere.zk.multilang.search/
├── META-INF/MANIFEST.MF
├── build.properties
├── pom.xml
└── src/
    ├── metainfo/zk/config.xml                          ← ZK listener registration
    └── tw/idempiere/multilang/search/
        ├── MultiLangSearchInit.java                    ← WebAppInit (40 lines)
        ├── MultiLangSearchPatcher.java                 ← UiLifeCycle + echoEvent (105 lines)
        └── MultiLangMenuSearchController.java          ← Search engine (930 lines)
```

### Compatibility

| iDempiere | ZK | Status |
|-----------|-----|--------|
| 14.x | 10.x | Primary build target |
| 12.x | 10.0.1 | ✅ Tested and working |
| 11.x | 9.6.x | Should work (untested) |

### Files to Diff on Upgrade

When iDempiere releases a new version, check these files for changes that may affect this plugin:

| File | Lines | What to check |
|------|-------|---------------|
| `MenuSearchController.java` | ~813 | Core logic — must sync if changed |
| `GlobalSearch.java` | ~278 | Constructor signature, field names |
| `HeaderPanel.java` | ~233 | `globalSearch` field name |
| `MenuItem.java` | ~124 | Constructor, fields |

### Known Limitations

- Copies ~900 lines from `MenuSearchController` — must sync on iDempiere upgrades
- Uses reflection for 3 private fields (`menuController`, `tree`, `globalSearch`)
- Brief visual flash during component replacement (~10-50ms on fast networks)
- Does not enhance the Document Search tab (Menu tab only)
- Minor memory leak: old `FavouriteController` callbacks persist until session ends

### Graceful Degradation

Every failure point falls back to the original search behavior:

| Failure | Behavior | User Impact |
|---------|----------|-------------|
| config.xml not scanned | Plugin not loaded | Original search |
| WebAppInit throws | Plugin not loaded | Original search |
| Reflection fails | Patching skipped | Original search |
| DB query fails | Empty alt labels | Current language only |
| DOM replacement fails | Old GlobalSearch kept | Original search |

## License

GPLv2 — same as iDempiere.

## Credits

Built by the [iDempiere Taiwan Community](https://www.idempiere.tw/).
