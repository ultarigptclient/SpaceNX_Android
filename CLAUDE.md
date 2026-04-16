# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**Hybrid-Talk_v2** ("웹앳톡" / Web@Talk) — an Android hybrid messaging app. Native Kotlin handles auth, encrypted databases, and binary socket communication; a WebView layer renders the UI and communicates with native code via a JavaScript bridge.

## Build & Run

```bash
./gradlew assembleDebug      # Debug APK
./gradlew assembleRelease    # Release APK (signed, minified, ARM-only)
./gradlew clean              # Clean build
```

- Gradle 8.11.1, Kotlin 2.1.10, Java 17 target
- compileSdk 35, minSdk 29, targetSdk 34
- Version catalog in `gradle/libs.versions.toml`
- Only the `:app` module is active (other modules commented out in `settings.gradle.kts`; they reference `../sCallingCore/` sibling repo)
- Room schemas exported to `app/schemas/`
- No unit or instrumented tests exist yet (test dependencies are declared but no test files)
- Release signing config is hardcoded in `app/build.gradle.kts` — treat as sensitive

## Architecture

### Hybrid Pattern
- **Native core**: Activities, Services, Room databases, binary socket, REST APIs
- **WebView UI**: HTML/CSS/JS assets served from `app/src/main/assets/dist/` via `WebViewAssetLoader`
- **Bridge layer**: `WebAppBridge` (`@JavascriptInterface`) receives JS calls → `BridgeDispatcher.dispatch(action, params)` routes to domain handlers

### Bridge Dispatch Flow
`BridgeDispatcher` uses a `when`-statement to route actions to 8 domain-specific handlers:
- `AppHandler` — app state, login, privacy, permissions, device sync readiness
- `ChatHandler` — send/read/delete chat, reactions, votes, pin/unpin
- `ChannelActionHandler` — channel/room creation, member management, layout queries
- `MessageHandler` — message CRUD, sync, detail retrieval, count tracking
- `OrgHandler` — organization data, buddy list, subscriptions
- `NotiHandler` — notifications
- `FileHandler` — file upload/download
- `NeoSendHandler` — generic REST forwarding (fallback via `NEOSEND_REST_MAP` in `BridgeDispatcher`)

Each handler receives a `BridgeContext` interface providing shared services (repositories, config, scope). Actions launch coroutines via `scope.launch`.

Communication directions:
- Web→Android: JS calls `WebAppBridge` methods
- Android→Web: `webView.evaluateJavascript()`

### Dependency Injection
Hilt is used throughout:
- `HybridWebMessengerApp` — `@HiltAndroidApp`; initializes SQLCipher passphrase warmup and FCM token prefetch
- `MainActivity` — `@AndroidEntryPoint`
- `LoginViewModel`, `MainViewModel` — `@HiltViewModel` with `@Inject` constructor
- `di/` — Hilt `@Module @InstallIn(SingletonComponent)` modules split by domain:
  - `CoreModule` — `DatabaseProvider`, `AppConfig`
  - `NetworkModule` — `SocketSessionManager`
  - `RepositoryModule` — 9 repositories + `UserNameCache`
  - `PushModule` — `PushEventHandler`, `GroupNotificationManager`

### Data Layer

**Remote:**
- `remote/api/` — Retrofit interfaces (`AuthApi`, `BuddyApi`, `ChannelApi`, `CommApi`, `OrgApi`, `StatusApi`, `UpdateApi`) via `ApiClient`
- `remote/socket/` — Custom binary socket protocol (`BinarySocketClient`, `BinaryFrameCodec`, `SocketSessionManager`)

**Local — 6 SQLCipher-encrypted Room databases:**

| Database | Scope | Key Entities |
|----------|-------|-------------|
| common.db | Global | CommonEntity, SyncMetaEntity |
| org.db | Per-user | DeptEntity, UserEntity, BuddyEntity |
| chat.db | Per-user | ChannelEntity, ChannelMemberEntity, ChatEntity, ChatEventEntity |
| message.db | Per-user | MessageEntity, MessageEventEntity |
| noti.db | Per-user | NotiEntity, NotiEventEntity |
| setting.db | Per-user | SettingEntity |

All databases use SQLCipher with passphrase from `AmCodec.decryptSeed()`. Room is configured with destructive migration fallback.

**Repositories:** `AuthRepository`, `BuddyRepository`, `ChannelRepository`, `MessageRepository`, `NotiRepository`, `OrgRepository`, `ProjectRepository`, `PubSubRepository`, `StatusRepository`

### Binary Socket Protocol
- Delimiter: `\u000C` (form feed)
- `BinaryFrameCodec` handles encode/decode
- `SocketSessionManager` manages connection lifecycle, HI handshake, token refresh, keep-alives
- Server ports: 18000 (binary), 18019 (REST API), 18001 (proxy)

### Key Async Patterns
- `CompletableDeferred` for token and handshake readiness (`jwtTokenDeferred`, `hiCompletedDeferred`)
- `MutableSharedFlow` in `PubSubRepository` for event subscriptions
- `loginSessionScope` ties coroutine lifecycles to login sessions

## Important Implementation Notes

- SSL certificate validation is disabled (trust manager stubbed) — development-only config
- `AppConfig` — centralized config: JWT management, encrypted credentials (AES256-GCM via `EncryptedSharedPreferences`), REST/socket URL resolution with DB fallback to `strings.xml`
- `Constants` — message types: TALK, MSG, MCU, NOTI, CUSTOM, MAIL
- Firebase integration: Messaging (push), Crashlytics, Analytics (BOM 33.1.1)
- Crashlytics mapping upload is disabled (host resolution issue in build environment)
- Theme colors extracted from CSS variables in the web frontend to update Android status bar

## Package Structure

All source under `net.spacenx.messenger`:
```
common/          — AppConfig, Constants, JsEscapeUtil
data/local/      — Room databases, DAOs, entities
data/remote/     — API interfaces, socket implementation
data/repository/ — data access repositories (+ SocketSessionManager, PushEventHandler, UserNameCache)
di/              — Hilt modules (CoreModule, NetworkModule, RepositoryModule, PushModule)
service/         — SessionService, SyncService, push/
ui/              — Activities, ViewModels, Bridge (handlers), call/
util/            — WebFileManager, helpers
```
