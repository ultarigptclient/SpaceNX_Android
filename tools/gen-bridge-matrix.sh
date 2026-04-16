#!/usr/bin/env bash
# ═════════════════════════════════════════════════════════════════
# gen-bridge-matrix.sh
#
# bridge-flow.html 명세 카드 ↔ Android Bridge 구현 매트릭스
# 빠른 재생성용 (raw grep 기반).
#
# 수동 큐레이션본(stub/REST/전용 판별 + 설명)은 docs/bridge-matrix.md 에
# 별도 유지합니다. 이 스크립트는 원시 대조 결과만 만듭니다.
#
# 입력:
#   - 원격: scalper@neo.ultari.co.kr:
#           /Users/scalper/project/NeoServerNX/deploy/default/docs/bridge-flow.html
#   - 로컬: app/src/main/java/net/spacenx/messenger/ui/bridge/*.kt
#
# 출력:
#   docs/bridge-matrix.auto.md
#
# 필요 환경:
#   - bash, ssh, grep, sed, awk, sort, comm
#   - SSH 접속이 key-based 자동 인증되어야 함
#
# 사용법:
#   bash tools/gen-bridge-matrix.sh
#   cmd>  tools\gen-bridge-matrix.bat
# ═════════════════════════════════════════════════════════════════
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT_FILE="$REPO_ROOT/docs/bridge-matrix.auto.md"
BRIDGE_DIR="$REPO_ROOT/app/src/main/java/net/spacenx/messenger/ui/bridge"
DISPATCHER="$BRIDGE_DIR/BridgeDispatcher.kt"
HANDLER_DIR="$BRIDGE_DIR/handler"

REMOTE_HOST="scalper@neo.ultari.co.kr"
REMOTE_SPEC="/Users/scalper/project/NeoServerNX/deploy/default/docs/bridge-flow.html"

mkdir -p "$REPO_ROOT/docs"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

CARDS_RAW="$TMP_DIR/cards.raw"
CARDS_TSV="$TMP_DIR/cards.tsv"           # id<TAB>category<TAB>action
DISPATCHER_CASES="$TMP_DIR/dispatcher-cases.txt"
HANDLER_CASES="$TMP_DIR/handler-cases.txt"
REST_FORWARD="$TMP_DIR/rest-forward.tsv" # action<TAB>path
STUB_ACTIONS="$TMP_DIR/stub-actions.txt"

echo "[1/5] Fetching bridge-flow.html cards from $REMOTE_HOST ..."
ssh -o ConnectTimeout=10 "$REMOTE_HOST" \
    "grep -E 'data-c=\"[^\"]+\"[^>]*data-s=\"[^\"]+\"' '$REMOTE_SPEC'" > "$CARDS_RAW"

# ─── 카드 파싱: id / category / action 추출 ───
awk '
{
  cat = ""; s = "";
  if (match($0, /data-c="[^"]+"/))  cat = substr($0, RSTART+8, RLENGTH-9);
  if (match($0, /data-s="[^"]+"/))  s   = substr($0, RSTART+8, RLENGTH-9);
  n = split(s, t, " ");
  id = ""; action = "";
  for (i=1; i<=n; i++) {
    tok = t[i];
    if (tok ~ /^[A-Z]+-[A-Z]+(-[0-9]+)?$/) {
      id = (id == "") ? tok : (id " / " tok);
      continue;
    }
    if (tok ~ /^[a-zA-Z][a-zA-Z0-9_]+$/) {
      action = tok;
      break;
    }
  }
  if (action == "") action = "(unnamed)";
  if (id == "") id = "—";
  printf "%s\t%s\t%s\n", id, cat, action;
}
' "$CARDS_RAW" > "$CARDS_TSV"

CARD_COUNT=$(wc -l < "$CARDS_TSV" | tr -d ' ')
echo "    → $CARD_COUNT cards parsed"

echo "[2/5] Extracting Kotlin when-case labels ..."
# ─── BridgeDispatcher.kt 전용 when-case 라벨만 추출 ───
# 패턴:
#   "action" ->
#   "a", "b", "c" -> scope.launch { ... }
#   "a",
#   "b",
#   "c"
#     -> handler.handle(...)
#
# 전략: when(action) { ... else -> { } } 블록 내에서,
#       줄에 "->" 가 있거나 다음 줄에 "->" 가 있는 "xxx" 문자열만 포획.

awk '
BEGIN { inWhen=0; depth=0 }
/when\s*\(\s*action\s*\)\s*\{/ {
  inWhen=1; depth=1; next
}
inWhen {
  # brace depth 추적: 문자열 안 무시 간소화 버전
  n_open  = gsub(/\{/, "{")
  n_close = gsub(/\}/, "}")
  depth  += n_open - n_close
  if (depth <= 0) { inWhen=0; next }
  print
}
' "$DISPATCHER" > "$TMP_DIR/when-block.txt"

# when-block에서 "xxx" 토큰 중 "->" 근처에 위치한 것만 뽑기.
# 간단한 휴리스틱: 각 "xxx",  또는 "xxx" -> 또는 라인 끝 ",\n" 뒤 다음라인 ->
# 먼저 "xxx"(?=\s*(->|,))  패턴 모두 수집한 뒤,
# 맵 값 패턴 "xxx" to "yyy" 는 제외.
# when-block에서 case-label만 정확히 추출:
#  - 기준선: `->` 가 포함된 라인
#  - 그 앞의 그룹-연속 라인(`"xxx",` 또는 `"xxx"` 끝 없음)도 함께 모아서
#  - `->` 앞부분에서 모든 "xxx" 토큰을 케이스로 간주
awk '
function flush_buf(buf,   combined, pos, tok) {
  combined = buf
  pos = index(combined, "->")
  if (pos > 0) combined = substr(combined, 1, pos-1)
  while (match(combined, /"[a-zA-Z_][a-zA-Z0-9_]*"/)) {
    tok = substr(combined, RSTART+1, RLENGTH-2)
    print tok
    combined = substr(combined, RSTART+RLENGTH)
  }
}

BEGIN { buf = "" }

# case label 연속 라인 판별: "xxx"(, "yyy")* 만 있고 다른 Kotlin 식별자는 없음
# (단순 휴리스틱: 라인 전체가 quoted strings + 쉼표 + 공백만 이뤄졌는지 확인)
{
  if (index($0, "->") > 0) {
    flush_buf(buf " " $0)
    buf = ""
    next
  }
  # 그룹 연속 라인: 화이트스페이스 + "xxx",[공백] 반복 + 선택적 trailing 쉼표
  if ($0 ~ /^[[:space:]]*"[a-zA-Z_][a-zA-Z0-9_]*"([[:space:]]*,[[:space:]]*"[a-zA-Z_][a-zA-Z0-9_]*")*[[:space:]]*,?[[:space:]]*$/) {
    buf = buf " " $0
    next
  }
  # 나머지 라인은 케이스 컨텍스트 종료
  buf = ""
}
' "$TMP_DIR/when-block.txt" \
  | sort -u > "$DISPATCHER_CASES"

DISP_COUNT=$(wc -l < "$DISPATCHER_CASES" | tr -d ' ')

# ─── Handler when-case 라벨: 각 Handler.kt의 `handle()` 내부 `when (action)` ───
: > "$HANDLER_CASES"
if [ -d "$HANDLER_DIR" ]; then
  for f in "$HANDLER_DIR"/*.kt; do
    awk '
      BEGIN { inWhen=0; depth=0 }
      /when\s*\(\s*action\s*\)\s*\{/ { inWhen=1; depth=1; next }
      inWhen {
        n_open  = gsub(/\{/, "{")
        n_close = gsub(/\}/, "}")
        depth  += n_open - n_close
        if (depth <= 0) { inWhen=0; next }
        print
      }
    ' "$f" \
    | { grep -oE '"[a-zA-Z_][a-zA-Z0-9_]*"\s*->' || true; } \
    | { grep -oE '"[a-zA-Z_][a-zA-Z0-9_]*"' || true; } \
    | tr -d '"' >> "$HANDLER_CASES"
  done
fi
sort -u -o "$HANDLER_CASES" "$HANDLER_CASES"
HAND_COUNT=$(wc -l < "$HANDLER_CASES" | tr -d ' ')

echo "    → dispatcher cases: $DISP_COUNT, handler cases: $HAND_COUNT"

echo "[3/5] Detecting REST forward + stub actions ..."
# ─── REST forward: handleRestForward("xxx", "/path", ...) ───
{ grep -hoE 'handleRestForward\("[a-zA-Z_][a-zA-Z0-9_]*",\s*"[^"]+"' \
    "$DISPATCHER" "$HANDLER_DIR"/*.kt 2>/dev/null || true; } \
  | sed -E 's/handleRestForward\("([^"]+)",[[:space:]]*"([^"]+)".*/\1\t\2/' \
  | sort -u > "$REST_FORWARD"

# ─── Stub: "not supported on Android" 또는 "UI-only preference" 코멘트 주변 case ───
# 코멘트 라인 번호를 찾아, 그 위 최대 5줄에서 등장하는 "xxx" -> 액션명 수집
: > "$STUB_ACTIONS"
awk '
  /not supported on Android|UI-only preference/ {
    for (i = NR-5; i <= NR+2; i++) {
      if (i > 0 && i in lines) {
        line = lines[i]
        while (match(line, /"[a-zA-Z_][a-zA-Z0-9_]*"/)) {
          print substr(line, RSTART+1, RLENGTH-2)
          line = substr(line, RSTART+RLENGTH)
        }
      }
    }
  }
  { lines[NR] = $0 }
' "$DISPATCHER" | sort -u > "$STUB_ACTIONS"

STUB_COUNT=$(wc -l < "$STUB_ACTIONS" | tr -d ' ')
echo "    → REST forwards: $(wc -l < "$REST_FORWARD" | tr -d ' '), stub actions: $STUB_COUNT"

echo "[4/5] Classifying each card ..."

classify_action() {
  local action="$1" category="$2"
  local in_disp in_hand rest_path stub

  in_disp=$(grep -qx "$action" "$DISPATCHER_CASES" && echo 1 || echo 0)
  in_hand=$(grep -qx "$action" "$HANDLER_CASES" && echo 1 || echo 0)
  rest_path=$(awk -v a="$action" -F'\t' '$1==a { print $2; exit }' "$REST_FORWARD")
  stub=$(grep -qx "$action" "$STUB_ACTIONS" && echo 1 || echo 0)

  # 분류 우선순위
  if [ "$stub" = "1" ]; then
    local line
    line=$(grep -nE "\"${action}\"" "$DISPATCHER" | head -1 | cut -d: -f1)
    echo -e "Stub\tBridgeDispatcher.kt:${line:-?} (no-op resolve)"
    return
  fi
  if [ -n "$rest_path" ]; then
    echo -e "REST forward\t${rest_path}"
    return
  fi
  if [ "$in_disp" = "1" ]; then
    local line
    line=$(grep -nE "\"${action}\"" "$DISPATCHER" | head -1 | cut -d: -f1)
    echo -e "전용\tBridgeDispatcher.kt:${line:-?}"
    return
  fi
  if [ "$in_hand" = "1" ]; then
    local hfile
    hfile=$(grep -lE "\"${action}\"\s*->" "$HANDLER_DIR"/*.kt 2>/dev/null | head -1 | xargs -I{} basename {} 2>/dev/null || true)
    echo -e "전용(핸들러)\thandler/${hfile:-?}"
    return
  fi
  if [ "$category" = "window" ] && echo "$action" | grep -qE '^window[A-Z]'; then
    echo -e "N/A (desktop)\t—"
    return
  fi
  # 카드 "push" 같은 non-action은 별도 표시
  if [ "$action" = "push" ] || [ "$action" = "Subscribe" ]; then
    echo -e "(서버 push)\tPushEventHandler 참고"
    return
  fi
  echo -e "**미구현**\t—"
}

echo "[5/5] Writing $OUT_FILE"

{
  cat <<HDR
# Bridge 매트릭스 (자동 생성)

> 이 파일은 \`tools/gen-bridge-matrix.sh\`가 생성합니다. 수동 편집 금지.
> 큐레이션된 분석본은 [bridge-matrix.md](./bridge-matrix.md) 참고.
>
> Generated: $(date '+%Y-%m-%d %H:%M:%S')

## A. bridge-flow.html 카드 → Android

| ID | 액션 | 카테고리 | Android | 위치/비고 |
|---|---|---|---|---|
HDR

  while IFS=$'\t' read -r id cat action; do
    IFS=$'\t' read -r status location < <(classify_action "$action" "$cat")
    printf '| %s | `%s` | %s | %s | %s |\n' "$id" "$action" "$cat" "$status" "$location"
  done < "$CARDS_TSV"

  cat <<'FOOTER'

## B. 역방향 gap (Android에만 있고 명세에 없는 액션)

BridgeDispatcher + 핸들러 when-case 라벨 중, 위 카드 목록에 없는 것들.

FOOTER

  cut -f3 "$CARDS_TSV" | sort -u > "$TMP_DIR/card-actions.txt"
  cat "$DISPATCHER_CASES" "$HANDLER_CASES" | sort -u > "$TMP_DIR/android-actions.txt"
  comm -23 "$TMP_DIR/android-actions.txt" "$TMP_DIR/card-actions.txt" \
    | awk '{ print "- `" $0 "`" }'

  echo ""
  echo "## C. 집계"
  echo ""
  echo "| 항목 | 건수 |"
  echo "|---|---|"
  echo "| 명세 카드 | $CARD_COUNT |"
  echo "| Android when-case 액션 총합 | $(wc -l < "$TMP_DIR/android-actions.txt" | tr -d ' ') |"
  echo "| REST forward | $(wc -l < "$REST_FORWARD" | tr -d ' ') |"
  echo "| Stub (no-op resolve) | $STUB_COUNT |"
  echo "| 역방향 gap (android-only) | $(comm -23 "$TMP_DIR/android-actions.txt" "$TMP_DIR/card-actions.txt" | wc -l | tr -d ' ') |"
} > "$OUT_FILE"

echo ""
echo "Done."
echo "  output: $OUT_FILE"
