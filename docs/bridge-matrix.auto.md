# Bridge 매트릭스 (자동 생성)

> 이 파일은 `tools/gen-bridge-matrix.sh`가 생성합니다. 수동 편집 금지.
> 큐레이션된 분석본은 [bridge-matrix.md](./bridge-matrix.md) 참고.
>
> Generated: 2026-04-13 11:25:22

## A. bridge-flow.html 카드 → Android

| ID | 액션 | 카테고리 | Android | 위치/비고 |
|---|---|---|---|---|
| — | `login` | auth | 전용 | BridgeDispatcher.kt:130 |
| — | `logout` | auth | 전용 | BridgeDispatcher.kt:145 |
| — | `saveCredential` | auth | 전용 | BridgeDispatcher.kt:150 |
| OG-LS-001 | `getOrgList` | org | 전용 | BridgeDispatcher.kt:153 |
| OG-LS-002 | `getOrgSubList` | org | 전용 | BridgeDispatcher.kt:153 |
| — | `searchUsers` | org | 전용 | BridgeDispatcher.kt:153 |
| — | `syncBuddy` | buddy | 전용 | BridgeDispatcher.kt:154 |
| COM-UC-008 | `addUserToMyList` | buddy | 전용 | BridgeDispatcher.kt:154 |
| — | `syncChannel` | chat | 전용 | BridgeDispatcher.kt:158 |
| COM-UC-001 / COM-CH-001 | `createChatRoom` | chat | 전용 | BridgeDispatcher.kt:159 |
| COM-CH-002 | `createGroupChatRoom` | chat | 전용 | BridgeDispatcher.kt:159 |
| — | `openChannelRoom` | chat | 전용 | BridgeDispatcher.kt:599 |
| COM-UC-010 | `searchChatListByUser` | chat | REST forward | /comm/searchchatlistbyuser |
| COM-UC-002 / COM-CH-003 | `openNoteSendWindow` | note | 전용 | BridgeDispatcher.kt:648 |
| COM-UC-011 | `searchNoteListByUser` | note | **미구현** | — |
| COM-ST-001 | `changeStatus` | status | 전용 | BridgeDispatcher.kt:190 |
| COM-ST-004 | `changeStatusMessage` | status | REST forward | /status/setpresence |
| COM-SB-001 | `Subscribe` | status | (서버 push) | PushEventHandler 참고 |
| COM-UC-007 | `openUserDetail` | profile | 전용 | BridgeDispatcher.kt:153 |
| COM-PF-004 | `updateProfile` | profile | REST forward | /org/updateprofile |
| COM-UC-003 | `openSmsSendPage` | menu | Stub | BridgeDispatcher.kt:664 (no-op resolve) |
| COM-UC-004 | `requestRemoteControl` | menu | Stub | BridgeDispatcher.kt:660 (no-op resolve) |
| COM-UC-005 | `makeCall` | menu | Stub | BridgeDispatcher.kt:656 (no-op resolve) |
| COM-UC-006 | `transferCall` | menu | Stub | BridgeDispatcher.kt:656 (no-op resolve) |
| COM-VW | `setUserDisplayMode` | view | Stub | BridgeDispatcher.kt:668 (no-op resolve) |
| COM-VW | `setUserSortMode` | view | Stub | BridgeDispatcher.kt:668 (no-op resolve) |
| COM-WN | `windowDrag` | window | N/A (desktop) | — |
| — | `neoSend` | generic | 전용 | BridgeDispatcher.kt:441 |
| — | `httpRequest` | generic | 전용 | BridgeDispatcher.kt:441 |
| — | `push` | push | (서버 push) | PushEventHandler 참고 |

## B. 역방향 gap (Android에만 있고 명세에 없는 액션)

BridgeDispatcher + 핸들러 when-case 라벨 중, 위 카드 목록에 없는 것들.

- `addChannelFavorite`
- `addChannelMember`
- `addLocalSystemChat`
- `agreePrivacyPolicy`
- `apiPost`
- `autoLogin`
- `backPressed`
- `broadcastSkin`
- `broadcastTheme`
- `closeApp`
- `closeVote`
- `closeWindow`
- `createCalEvent`
- `createCall`
- `createConference`
- `createTodo`
- `dbQuery`
- `deleteCalEvent`
- `deleteChat`
- `deleteMessage`
- `deleteNoti`
- `deleteTodo`
- `destroyChannel`
- `downloadFile`
- `endCall`
- `exitApp`
- `findChannelByMembers`
- `finishApp`
- `focusChannel`
- `forwardChat`
- `fullSync`
- `getAllIssues`
- `getAppInfo`
- `getCalEvents`
- `getCalEventsByMonth`
- `getChannel`
- `getChannelList`
- `getChannelMuteState`
- `getChannelSummaries`
- `getChatList`
- `getClientState`
- `getCredential`
- `getMessageCounts`
- `getMessageDetail`
- `getMyPart`
- `getNotiCounts`
- `getPopupContext`
- `getStatusMobile`
- `getSyncStatus`
- `getThreadComments`
- `getThreadsByChannel`
- `getThreadsByIssue`
- `getTodos`
- `getUnreadCount`
- `getUserConfig`
- `getUserInfo`
- `hardReload`
- `inviteConference`
- `joinCall`
- `joinConference`
- `listConference`
- `loadMoreMessages`
- `loadMoreNotis`
- `modChat`
- `muteChannel`
- `openChannel`
- `openFile`
- `openMessageSendWindow`
- `openWindow`
- `pickFile`
- `pinMessage`
- `readChat`
- `readMessage`
- `readNoti`
- `relocateFiles`
- `removeChannel`
- `removeChannelFavorite`
- `removeChannelMember`
- `requestPermission`
- `retrieveMessage`
- `searchChannelRoom`
- `searchMessageListByUser`
- `sendChat`
- `sendMessage`
- `setActiveChannel`
- `setNick`
- `setUserConfig`
- `setViewingChannel`
- `subscribeUsers`
- `syncConfig`
- `syncMessage`
- `syncNoti`
- `syncThread`
- `toggleCamera`
- `toggleMic`
- `toggleReaction`
- `toggleScreenShare`
- `toggleVote`
- `typingChat`
- `unmuteChannel`
- `unpinMessage`
- `updateCalEvent`
- `updateStatusBar`
- `updateTodo`
- `uploadFile`
- `uploadProfilePhoto`
- `waitAutoLogin`

## C. 집계

| 항목 | 건수 |
|---|---|
| 명세 카드 | 30 |
| Android when-case 액션 총합 | 133 |
| REST forward | 24 |
| Stub (no-op resolve) | 8 |
| 역방향 gap (android-only) | 107 |
