# Issue #51 — WeakHost 192.0.0.8 로컬 검증 절차 (debug 전용)

**목적:** 차량 테스트를 소모하기 전에, 폰 2대만으로 `192.0.0.8:9090` 도달 여부를 판정한다.
**규칙:** 여기서 통과한 빌드만 실차(pvi25) 테스트로 넘긴다. 실패 빌드는 넘기지 않는다.

## 준비
- 폰 A(호스트): debug APK 설치 (`./gradlew :app:installDebug`), VPN 없음, `192.0.0.8` 없음(CLAT 폰이면 토글이 ADDRESS_CONFLICT로 거부 → 다른 폰 사용).
- 폰 B(클라이언트): A의 핫스팟에 연결. `curl`이 되는 기기면 무엇이든 가능(termux / 노트북).

## 절차
1. 폰 A: 핫스팟 켜기 → 폰 B 연결 확인.
2. 폰 A: 앱에서 **미러링 시작** (서버 9090은 미러링 중에만 listen 한다).
3. 폰 B: `curl -v --max-time 5 http://<A의 핫스팟 IP>:9090/` → **200 확인**(기준선). 실패면 핫스팟/서버 문제이지 실험 대상이 아니다.
4. 폰 A: Settings → `WeakHost 192.0.0.8 (debug)` 토글 ON → VPN 동의 → logcat에 `[WEAKHOST_READY]` 확인.
   - `[WEAKHOST_ADDRESS_CONFLICT]` / `[WEAKHOST_VPN_BUSY]` / `[WEAKHOST_ESTABLISH_FAILED]` 가 뜨면 그 원인부터 해소한다(측정 무효).
5. 폰 B: `curl -v --max-time 5 http://192.0.0.8:9090/`
   - **폰 A 자신에서는 절대 테스트하지 않는다** (로컬 루프백으로 통과해 위양성이 된다).

## 판정
- **200 + 폰 A logcat `HTTP_FIRST_CONTACT`** → 통과. 원인은 응답 경로였음이 확정, Shizuku 불필요 → Phase 3(실차).
- **timeout** → inbound 배달 실패. VpnService 트랙 종료 → Phase 2(Shizuku `ip addr add`).
- **connection refused** → 폰 B가 다른 곳으로 보내고 있다. 폰 B의 라우팅/`ip neigh` 확인 후 재시도.

## 정리
토글 OFF → `[WEAKHOST_STOPPED]` 확인. VPN 아이콘이 사라져야 한다.

---

## 실험 기록 — 2026-09-01, SM-F741N (Android 16, KT)

클라이언트 B = 폰 핫스팟(swlan0 10.69.97.36)에 붙은 맥북(10.69.97.232), 기본 게이트웨이 = 폰.

| # | 조건 | 192.0.0.8 위치 | 결과 |
|---|---|---|---|
| 기준선 | 데이터 ON | — | `http://10.69.97.36:9090` → **200** |
| 1 | 데이터 ON (CLAT) | `rmnet_data9` (실제 iface, /27) | `http://192.0.0.8:9090` → **200** |
| 음성대조 | 데이터 OFF, VPN OFF | 없음 | **timeout** |
| 2 | 데이터 OFF, WeakHost VPN ON | `tun0` (/32, 합성) | **timeout** (같은 시각 10.69.97.36 → 200) |
| Phase 2 게이트 | shell UID(= Shizuku 권한) | — | `ip addr add 192.0.0.8/32 dev swlan0` → `Cannot talk to rtnetlink: Permission denied`, exit 2 |

실험 1·2 모두 서버는 **실제 앱의 MirrorServer**(0.0.0.0:9090). 실험 2에서 round 2 수정은 실제로 적용됐다 —
`ConnectivityService: ... Uids: <{0-10662, 10664-20662, 20664-99999}> OwnerUid: 10663` (앱 UID 10663이 VPN 대상에서 제외됨).
즉 `addDisallowedApplication`이 걸린 상태에서도 도달 불가.

**결론:** 실제 인터페이스에 얹힌 192.0.0.8은 LAN에서 weak-host로 도달 가능하다(가설 검증됨). tun 합성 주소는 도달 불가.
루트 없이 실제 인터페이스에 주소를 얹는 경로는 Shizuku(shell UID)로도 막혀 있다 → 남는 무권한 경로는 **CLAT 주소를 그대로 advertise** 하는 것뿐.

**알려진 버그:** debug 토글 OFF 시 VPN이 내려가지 않는다. UI는 OFF, "WeakHost OFF" 토스트도 뜨지만 `[WEAKHOST_STOPPED]` 로그가 없고 tun0이 남는다. 강제 종료(force-stop)로만 정리됨.
