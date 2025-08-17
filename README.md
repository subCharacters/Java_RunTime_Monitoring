# Java_RunTime_Monitoring(JDK 17, CLI)

> 외부 도구 없이 **JDK 17 기본 명령어**만으로 실행 중인 자바 프로그램을 모니터링하는 방법을 정리했습니다.  
> **순서**: (1) 실행용 샘플 앱 실행 → (2) 명령어로 모니터링.

---

## 1) 실행용 샘플 앱

**`TargetApp.java`**
```java
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class TargetApp {
    static final List<byte[]> LEAK = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        long pid = ProcessHandle.current().pid();
        System.out.println("[TargetApp] PID=" + pid);
        System.out.println("[TargetApp] Running. Press Ctrl+C to exit.");
        while (true) {
            // 관찰이 잘 되도록 메모리 할당/해제 패턴
            byte[] block = new byte[ThreadLocalRandom.current().nextInt(256 * 1024, 512 * 1024)];
            LEAK.add(block);
            if (LEAK.size() > 200) LEAK.clear(); // 과도 누적 방지 + GC 유도
            Thread.sleep(1000);
        }
    }
}
```

**컴파일 & 실행**
```bash
# JDK 17 필요
javac TargetApp.java
# 힙을 작게 잡으면 변화를 관찰하기 쉽습니다
java -Xms256m -Xmx256m TargetApp
# 콘솔에 [TargetApp] PID=12345 와 같이 PID가 표시됩니다.
```

---

## 2) 표준 모니터링 명령 세트 (한눈에 보기 + 해석/핵심)

> `<PID>`는 위에서 출력된 PID로 바꿔 실행하세요.  
> 출력이 길면 Windows: `| more`, macOS/Linux: `| less` 권장.

### 2.1 프로세스 식별
```bash
jcmd -l        # 모든 JVM의 PID와 메인클래스
jps -lv        # JVM 목록(+ 주요 인자/클래스패스)
```
- **보는 법**: 대상 앱이 실제로 떠 있고, **정확한 PID**를 확인합니다.
- **핵심**: 동일 이름 JVM이 있을 수 있으므로 **PID 기반**으로 이후 명령을 실행합니다.

---

### 2.2 실시간 GC/메모리 사용률 (누수/Full GC 1차 판단의 중심)
```bash
jstat -gcutil <PID> 1000 10    # 1초 간격 10번: 영역별 사용률(%), GC 횟수/시간(초)
jstat -gc     <PID> 1000 10    # 바이트/KB 상세(각 영역 용량/사용량, YGC/FGC 포함)
jstat -gccause <PID> 1000 10   # 마지막/현재 GC 원인 문자열까지 함께 표시
```
- **보는 법(주요 컬럼)**
    - `E,S0,S1,O`: Eden/Survivor/Old **사용률(%)** 또는 `*U`(Used)
    - `YGC/YGCT`: Young GC **횟수/누적시간(초)**
    - `FGC/FGCT`: **Full GC** **횟수/누적시간(초)** ← **Full GC 판단 지표**
    - `LastGC Cause / CurrentGC Cause`(with `-gccause`): 예) `Allocation Failure`, `G1 Humongous Allocation`, `Metadata GC Threshold`, `System.gc()` 등
- **핵심 신호**
    - **Full GC 발생**: `FGC`가 0→1처럼 **증가**(동시에 `FGCT`도 증가). 바로 전후로 `O`(Old) 사용률이 **뚝 떨어지면** 정황 강화.
    - **Full GC 대량 발생**: **분 단위로 FGC가 계속 증가**, `FGCT` 증가 속도가 빠름, `-gccause`의 원인 문자열이 **반복**.
    - **메모리 누수 의심**: Young GC가 여러 번 도는 동안 **Old 사용률/사용량의 저점이 계단식 상승**(Full GC 이후에도 저점이 서서히 위로).

---

### 2.3 힙/플래그/프로퍼티 요약 (상태 스냅샷)
```bash
jcmd <PID> GC.heap_info             # 힙 레이아웃/최대/현재 사용량 요약
jcmd <PID> VM.flags                 # JVM 시작 플래그(GC 종류, NMT, 크기 등)
jcmd <PID> VM.system_properties     # 시스템 프로퍼티(환경 교차 확인)
jcmd <PID> GC.run                   # 강제 GC 1회(운영 영향 주의)
```
- **보는 법**: `Max/Committed/Used`로 여유폭 파악, 사용 GC 종류·크기 정책 확인.
- **핵심**: 의심될 때 `GC.run` 전후 **Old 사용량 저점 비교** → 강제 GC 이후에도 저점이 유지/상승하면 **누수 정황**.

---

### 2.4 스레드/락 상태 (GC 이외 병목 구분)
```bash
jcmd <PID> Thread.print | more   # 스레드 덤프(락 대기/소유 포함)
# (대안) jstack <PID>
```
- **보는 법**: 각 스레드 상태(RUNNABLE/WAITING/BLOCKED)와 스택 트레이스, 데드락 탐지 결과 확인.
- **핵심**:
    - **BLOCKED 다수**·같은 락에서 정체 → **락 경합/교착**으로 인한 지연(메모리 문제 아님).
    - **RUNNABLE + Socket.read/DB 호출** 대기 다수 → 외부 IO 병목 가능.
    - **데드락 발견** 문구가 있으면 즉시 원인 스레드 추적.

---

### 2.5 힙 점검/덤프 (누수 2차 확인: “누가 먹는가”)
```bash
# 라이브 객체 히스토그램(Full GC 후 생존 객체만 집계; STW 발생)
jmap -histo:live <PID> | head -n 50

# 시간차 스냅샷 비교(증가 클래스 식별)
jmap -histo:live <PID> > histo_t0.txt
sleep 10; jmap -histo:live <PID> > histo_t1.txt
diff histo_t0.txt histo_t1.txt   # Windows는 fc 명령 사용

# 전체 힙 덤프(Hprof; 용량 큼/민감정보 포함 가능)
jcmd <PID> GC.heap_dump filename=./heap.hprof
```
- **보는 법**: `[B`(byte[]), `char[]`, `java.lang.String`, `HashMap$Node`, 프레임워크 캐시류 등 **상위 항목의 #instances/#bytes 증가** 확인.
- **핵심**: 특정 클래스군이 **반복 스냅샷에서 지속 증가**하면 누수 후보.  
  (덤프는 STW·대용량 파일 생성 → **저부하 시간** + **민감정보 취급 주의**)

---

### 2.6 JFR(저오버헤드 이벤트 프로파일링: GC/할당/락/파킹)
```bash
jcmd <PID> JFR.start name=mon settings=profile filename=./mon.jfr
jcmd <PID> JFR.check
jcmd <PID> JFR.dump name=mon filename=./mon-now.jfr
jcmd <PID> JFR.stop name=mon

# CLI 빠른 확인
jfr summary ./mon-now.jfr
jfr print --events GarbageCollection,CPULoad ./mon-now.jfr | head
# (선택) 할당/락 이벤트
jfr print --events AllocationInNewTLAB,AllocationOutsideTLAB ./mon-now.jfr | head
jfr print --events JavaMonitorEnter,ThreadPark ./mon-now.jfr | head
```
- **보는 법**: `GarbageCollection` 이벤트로 **Pause Time/주기**, `Allocation*`으로 **할당 레이트·핫 스택**, `JavaMonitorEnter`로 **락 경합** 확인.
- **핵심**:
    - **Full GC가 잦거나 Pause가 길다** → 튜닝 필요(힙/GC 정책/할당 패턴).
    - **Allocation Rate 과다** → 누수/과할당 경로 식별(클래스·스택 기준).
    - **락 경합↑** → GC 이슈가 아니라 **동시성 병목**일 수 있음.

---

### 2.7 (선택) 네이티브 메모리(NMT: 자바 힙 외 누수)
```bash
# JVM 시작 시 옵션 필요: -XX:NativeMemoryTracking=summary  (또는 detail)
jcmd <PID> VM.native_memory summary
```
- **보는 법**: Class/Thread/Code/Internal 등 **네이티브 영역** 사용량을 요약 확인.
- **핵심**: `Thread`/`Internal` 등의 **비정상 증가** → 스레드 과다 생성/네이티브 자원 누수 의심.  
  (미활성 메시지가 나오면 **재기동 후** 측정)

---

## 3) 빠른 진단 루틴(실전 체크리스트)

1. **빠른 스캔**:  
   `jstat -gcutil <PID> 1000 10` + `jstat -gccause <PID> 1000 10`
    - `FGC/FGCT` 증가 → **Full GC 발생**
    - `O`(Old) 저점 상승 ↗ → **누수 의심**

2. **증거 보강(누수 2차)**:  
   `jmap -histo:live` **시간차 2~3회** → 특정 클래스 **증가 추세** 식별

3. **원인 좁히기(저오버헤드)**:  
   `JFR.start` 1~5분 → `GarbageCollection / Allocation* / JavaMonitorEnter` 분석

4. **교차 검증**:  
   `jcmd GC.run` 전후 Old 사용량 저점 비교, 필요 시 `GC.heap_dump`로 오프라인 정밀 분석

---

## 4) 안전/운영 주의

- `heap_dump`, `-histo:live`, `Thread.print` 등은 **일시 정지(STW)** 또는 **부하**를 유발할 수 있습니다.  
  → **저부하 시간대**에 실행 권장.
- 힙덤프/JFR 파일은 **민감정보**가 포함될 수 있으니 **접근 통제/암호화/폐기 정책**을 지키십시오.
- 컨테이너 환경에서는 **컨테이너 내부 PID**로 실행하세요(PID 네임스페이스 차이).

---

## 5) 부록: 스크립트 예시(옵션)

**macOS/Linux (bash)** — `monitor_quick.sh`
```bash
#!/usr/bin/env bash
set -euo pipefail
PID="${1:-}"
[ -z "$PID" ] && { echo "Usage: $0 <PID>"; exit 1; }

echo "== gcutil (5s) =="; jstat -gcutil "$PID" 1000 5
echo "== heap_info ==";   jcmd "$PID" GC.heap_info
echo "== histo top ==";   jmap -histo:live "$PID" | head -n 50
echo "== threads (head) =="; jcmd "$PID" Thread.print | head -n 200
```

**Windows (PowerShell)** — `monitor_quick.ps1`
```powershell
param([Parameter(Mandatory=$true)][int]$Pid)
Write-Host "== gcutil (5s) =="; jstat -gcutil $Pid 1000 5
Write-Host "== heap_info ==";   jcmd $Pid GC.heap_info
Write-Host "== histo top ==";   jmap -histo:live $Pid | Select-Object -First 50
Write-Host "== threads (head) =="; jcmd $Pid Thread.print
```
