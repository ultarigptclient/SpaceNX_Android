# Bridge 매트릭스 — bridge-flow.html 카드 ↔ Android 핸들러

> 기준 명세: `scalper@neo.ultari.co.kr:/Users/scalper/project/NeoServerNX/deploy/default/docs/bridge-flow.html`
> Android 구현: `app/src/main/java/net/spacenx/messenger/ui/bridge/`
> 이 문서는 **수동 큐레이션본**입니다. stub/REST/전용 판별과 배경 설명 포함.
> 원시 자동 생성본(grep 결과)은 `docs/bridge-matrix.auto.md` 참고.
> 재생성 스크립트: `tools/gen-bridge-matrix.bat` (또는 `.sh`)

## 상태 범례

- **전용** — `BridgeDispatcher.kt` 또는 핸들러에서 직접 구현
- **전용(그룹)** — 여러 액션이 한 `when` 블록에서 핸들러로 위임
- **REST forward** — `handleRestForward`로 단순 포워딩
- **Stub** — 분기는 있으나 resolve만 하고 no-op (Android 미지원 기능)
- **N/A** — Windows/Mac 데스크탑 전용, Android 무관
- **미구현** — 분기 없음. 호출 시 `unhandled action` reject

---

## A. bridge-flow.html 카드 → Android (정방향 gap)

| ID | 액션 | 카테고리 | Android | 위치 / 비고 |
|---|---|---|---|---|
| — | `login` | auth | 전용 | `BridgeDispatcher.kt:130` |
| — | `logout` | auth | 전용 | `BridgeDispatcher.kt:145` |
| — | `saveCredential` | auth | 전용 | `:150` → `AppHandler.handleSaveCredential` |
| OG-LS-001 | `getOrgList` | org | 전용(그룹) | `:153` → `OrgHandler` |
| OG-LS-002 | `getOrgSubList` | org | 전용(그룹) | `:153` → `OrgHandler` |
| — | `searchUsers` | org | 전용(그룹) | `:153` → `OrgHandler` |
| — | `syncBuddy` | buddy | 전용(그룹) | `:154` → `OrgHandler` |
| COM-UC-008 | `addUserToMyList` | buddy | 전용(그룹) | `:154` → `OrgHandler` |
| — | `syncChannel` | chat | 전용(그룹) | `:158` → `ChannelActionHandler` |
| COM-UC-001 / COM-CH-001 | `createChatRoom` | chat | 전용(그룹) | `:159` → `ChannelActionHandler` |
| COM-CH-002 | `createGroupChatRoom` | chat | 전용(그룹) | `:159` → `ChannelActionHandler` |
| — | `openChannelRoom` | chat | 전용(레거시) | `:599` → `AppHandler.handleLegacyOpenChannelRoom` |
| COM-UC-010 | `searchChatListByUser` | chat | REST forward | `:506` → `/comm/searchchatlistbyuser` |
| COM-UC-002 / COM-CH-003 | `openNoteSendWindow` | note | 전용 | `:648` → `AppHandler.handleOpenNoteSendWindow` |
| COM-UC-011 | `searchNoteListByUser` | note | **❌ 미구현** | dispatcher 분기 없음 — 호출 시 reject |
| COM-ST-001 | `changeStatus` | status | 전용 | `:190` (REST+socket 이중화, self-presence echo) |
| COM-ST-004 | `changeStatusMessage` | status | REST forward | `:248` → `/status/setpresence` |
| COM-SB-001 | subscribe / `_onPresenceUpdate` | status | 전용(Push 수신) | `PushEventHandler` + `subscribeUsers` (`:343`) |
| COM-UC-007 | `openUserDetail` | profile | 전용(그룹) | `:153` → `OrgHandler` |
| COM-PF-004 | `updateProfile` | profile | REST forward | `:270` → `/org/updateprofile` |
| COM-UC-003 | `openSmsSendPage` | menu | **Stub** | `:664` — resolve OK, no-op |
| COM-UC-004 | `requestRemoteControl` | menu | **Stub** | `:660` — resolve OK, no-op |
| COM-UC-005 | `makeCall` | menu | **Stub** | `:656` — resolve OK, no-op (LiveKit은 `createCall`/`joinCall`로 별도) |
| COM-UC-006 | `transferCall` | menu | **Stub** | `:656` — resolve OK, no-op |
| COM-VW | `setUserDisplayMode` | view | **Stub** | `:668` — UI-only preference, 영속화 없음 |
| COM-VW | `setUserSortMode` | view | **Stub** | `:668` — UI-only preference, 영속화 없음 |
| COM-WN | `windowDrag` | window | **N/A** | 데스크탑 전용 |
| COM-WN | `openWindow` | window | 전용 | `:647` → `AppHandler.handleOpenWindow` (팝업 WebView) |
| COM-WN | `closeWindow` | window | 전용 | `:123` → `activity.finish()` |
| COM-WN | `finishApp` | window | 전용 | `:123` → `activity.finish()` |
| — | `neoSend` | generic | 전용 | `:441` → `NeoSendHandler` |
| — | `httpRequest` | generic | 전용 | `:441` → `NeoSendHandler` |
| — | push event / `neoPush` | push | 전용 | `PushEventHandler` + `BridgeDispatcher.forwardPushToReact` |

### 정방향 gap 요약
- **명세 30 카드 중 실제 누락 1건**: `searchNoteListByUser`
- **의도적 stub 6건**: 모바일에서 의미 없거나(makeCall/transferCall/requestRemoteControl/openSmsSendPage) UI-only(setUserDisplayMode/setUserSortMode). 호출 측 에러 방지용으로 `errorCode:0` resolve만 하는 설계.
- **N/A 1건**: `windowDrag` (데스크탑 전용).

---

## B. Android → bridge-flow.html (역방향 gap = 명세 누락 후보)

Android에 구현되어 있으나 `bridge-flow.html`에 카드가 없는 액션. 명세를 현실에 맞추려면 카드 추가가 필요한 후보군.

### B-1. 인증/세션 관련
- `autoLogin` / `waitAutoLogin` — 자동로그인 플로우
- `agreePrivacyPolicy` — 개인정보 동의
- `requestPermission` — 런타임 권한 요청
- `getCredential` — 저장된 자격증명 반환 (로그인 화면 복원용)
- `getClientState` — 클라이언트 상태 조회
- `getAppInfo` — 앱 버전/정보
- `getSyncStatus` — sync 진행 상태

### B-2. 채팅/채널 세부 액션
- `getChatList` — 채팅 목록 조회
- `sendChat` / `readChat` / `deleteChat` / `modChat` — 기본 CRUD
- `toggleReaction` / `toggleVote` / `closeVote` — 반응/투표
- `pinMessage` / `unpinMessage` — 고정
- `addLocalSystemChat` — 로컬 시스템 메시지 추가
- `getUnreadCount` — 채널별 미읽음 수 (최근 추가, nx dca2a32)
- `destroyChannel` / `typingChat` / `forwardChat`
- `getChannelList` / `getChannelSummaries` / `getChannel`
- `findChannelByMembers`
- `addChannelMember` / `removeChannelMember` / `removeChannel`
- `addChannelFavorite` / `removeChannelFavorite`
- `openChannel`
- `searchChannelRoom`

### B-3. 메시지/쪽지
- `sendMessage` / `readMessage` / `deleteMessage` / `retrieveMessage`
- `syncMessage` / `fullSync` / `loadMoreMessages`
- `getMessageDetail` / `getMessageCounts`
- `searchMessageListByUser`

### B-4. 알림
- `syncNoti` / `loadMoreNotis` / `getNotiCounts` / `readNoti` / `deleteNoti`

### B-5. 파일
- `uploadFile` / `pickFile` / `downloadFile` / `openFile` / `relocateFiles`
- `uploadProfilePhoto`

### B-6. 프로젝트/이슈/스레드
- `syncThread` / `getThreadsByChannel` / `getThreadsByIssue` / `getThreadComments`
- `getAllIssues`
- `apiPost` (범용 `/comm/*` 포워딩)

### B-7. 투두/캘린더
- `getTodos` / `createTodo` / `updateTodo` / `deleteTodo`
- `createCalEvent` / `updateCalEvent` / `deleteCalEvent` / `getCalEvents` / `getCalEventsByMonth`

### B-8. 회의/통화 (LiveKit)
- `createConference` / `joinConference` / `listConference` / `inviteConference`
- `createCall` / `joinCall` / `endCall`
- `toggleMic` / `toggleCamera` / `toggleScreenShare`

### B-9. 상태/프로필
- `subscribeUsers` — Presence 구독
- `setNick` — 닉네임 변경
- `getStatusMobile` — 모바일 상태 조회
- `getUserInfo` — 사용자 정보 일괄 조회

### B-10. 설정/테마/UX
- `getUserConfig` / `setUserConfig`
- `syncConfig` — 서버 설정 동기화
- `broadcastTheme` / `broadcastSkin`
- `setActiveChannel` / `focusChannel` / `setViewingChannel` — 현재 열린 채널 추적 (in-app 알림 억제)
- `muteChannel` / `unmuteChannel` / `getChannelMuteState`
- `updateStatusBar` — 네이티브 상태바 색상

### B-11. 앱 제어
- `exitApp` / `hardReload` / `backPressed`

### B-12. 디버그
- `dbQuery` — DB 직접 조회

### B-13. 팝업
- `getPopupContext` — 서브 WebView 팝업 컨텍스트

### 역방향 gap 요약
`bridge-flow.html`은 약 30 카드, 실제 Android는 **90+ 액션**을 처리. 명세가 현실 구현의 1/3 수준. 명세 업데이트가 필요하다면 위 B-1 ~ B-13을 기준으로 카드 추가를 건의할 수 있습니다.

---

## C. 결론

| 구분 | 건수 |
|---|---|
| 명세 기준 실제 누락 | 1 (`searchNoteListByUser`) |
| 의도적 stub | 6 |
| 데스크탑 전용 N/A | 1 |
| 명세 없는 Android 구현 | 90+ |

**우선순위가 있는 조치는 `searchNoteListByUser` 구현 1건뿐**입니다. 나머지는 명세가 현실을 못 따라가는 형태라 bridge-flow.html 업데이트가 개선 포인트.
