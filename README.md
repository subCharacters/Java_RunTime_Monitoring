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
---

## 6) 실행 전에 넣어둘 VM 옵션(추천)

아래 옵션을 **실행 커맨드에 미리 붙여두면**, 시작 직후부터 GC/힙/이벤트를 기록해서 진단이 쉬워집니다. (경로는 프로젝트에 맞게 조정)

### A. 최소 진단(Full GC 탐지 + GC 로그)
```bash
java -Xms256m -Xmx256m   -Xlog:gc*:file=./logs/gc-%t.log:time,uptime,level,tags:filecount=5,filesize=20m   -Xlog:safepoint:file=./logs/safepoint-%t.log:time,uptime,level,tags   TargetApp
```
- `-Xlog:gc*` : 모든 GC 이벤트를 파일로 남깁니다(회전 설정 포함).
- `-Xlog:safepoint` : STW(세이프포인트) 이벤트 확인에 유용합니다.

### B. OOME(OutOfMemoryError) 발생 시 자동 힙덤프
```bash
java -Xms256m -Xmx256m   -Xlog:gc*:file=./logs/gc-%t.log:time,uptime,level,tags:filecount=5,filesize=20m   -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./dumps   TargetApp
```
- OOME가 발생하면 `./dumps` 폴더에 자동으로 `.hprof`가 생성됩니다.

### C. 시작부터 JFR(Flight Recorder)로 저오버헤드 수집
```bash
java -Xms256m -Xmx256m   -Xlog:gc*:file=./logs/gc-%t.log:time,uptime,level,tags   -XX:StartFlightRecording=name=boot,settings=profile,filename=./jfr/boot-%t.jfr,dumponexit=true,maxsize=256m   TargetApp
```
- JFR 파일(`./jfr/*.jfr`)을 자동 생성하여 GC/할당/락 이벤트를 한 번에 분석할 수 있습니다.

---

## 7) 실행 직후/중에 바로 보는 CLI

### Full GC/원인/빈도 확인
```bash
# 1초 간격 반복 출력 (Ctrl+C로 중지)
jstat -gccause <PID> 1000

# 사용률 추이/FGC 카운트·시간
jstat -gcutil  <PID> 1000
# 바이트 상세
jstat -gc      <PID> 1000
```
- **판단 요령**: `FGC/FGCT`가 증가하면 그 구간에 **Full GC 발생**.  
  `LastGC Cause / CurrentGC Cause`로 원인(Allocation Failure, G1 Humongous Allocation, System.gc() 등)을 확인하세요.  
  Old(`O`) 사용률의 **저점이 시간이 갈수록 상승**하면 누수 의심.

### GC 로그에서 Full GC만 빠르게 추출(위 6A/6B/6C로 실행한 경우)
```bash
# macOS/Linux
grep -E "Pause Full|Full GC" logs/gc-*.log
grep -E "Pause Full|Full GC" logs/gc-*.log | wc -l  # 발생 건수

# Windows PowerShell
Select-String -Path .\logs\gc-*.log -Pattern "Pause Full|Full GC" | Measure-Object
```
- **해석**: `Pause Full` 라인이 짧은 간격으로 반복되면 **Full GC 빈발**입니다.

### 라이브 객체 히스토그램(누수 2차 스캔)
```bash
# 생존 객체 Top
jmap -histo:live <PID> | head -n 50

# 10초 간격 두 번 찍어 증가 클래스 비교
jmap -histo:live <PID> > histo_t0.txt
sleep 10
jmap -histo:live <PID> > histo_t1.txt
diff histo_t0.txt histo_t1.txt     # Windows는 fc 사용
```
- **해석**: `[B`(byte[]), `java.lang.String`, `HashMap$Node` 등 특정 클래스의 #instances/#bytes가 반복 스냅샷에서 꾸준히 증가하면 **누수 후보**.

### JFR 요약/이벤트 확인(파일이 있을 때)
```bash
jfr summary ./jfr/boot-*.jfr
jfr print --events GarbageCollection,CPULoad ./jfr/boot-*.jfr | head
# (선택) 할당/락 이벤트
jfr print --events AllocationInNewTLAB,AllocationOutsideTLAB ./jfr/boot-*.jfr | head
jfr print --events JavaMonitorEnter,ThreadPark ./jfr/boot-*.jfr | head
```
- **해석**: GC 총 정지시간/횟수, 할당 레이트, 락 경합 등을 한 번에 파악합니다.

---

## 8) IntelliJ에서 하는 법

### (Ultimate 에디션)
1. **Run/Debug Configurations → VM Options**에 위의 **6A/6B/6C 옵션** 중 원하는 것을 추가합니다.
2. 실행 후 상단의 **Profile with IntelliJ Profiler**(또는 Attach to Process)로 프로파일링을 시작합니다.
3. Profiler 창에서 **Garbage Collection** 이벤트를 필터링하여 **Pause Full** 횟수/총 시간, 핫스팟을 확인할 수 있습니다.
4. 생성된 **JFR 파일**을 IntelliJ에서 열어 상세 이벤트 타임라인을 분석할 수 있습니다.

### (Community 에디션)
- 내장 프로파일러 UI는 **없습니다**.
- 대신 **VM Options**로 GC 로그/JFR 파일을 생성하고, 위의 **CLI(`jfr summary/print`, `grep`)**로 분석합니다.

---

## 9) 해석/분석 체크리스트(요약)

- **Full GC 폭증**
    - `jstat -gcutil/-gccause`에서 **FGC/FGCT가 분 단위로 계속 증가** → 위험
    - GC 로그의 `Pause Full` 라인이 **짧은 간격으로 반복**
- **누수 의심(힙)**
    - Young GC가 여러 번 발생하는 동안 **Old(`O`) 사용률/사용량의 저점이 점점 상승**
    - `jmap -histo:live` 반복 스냅샷에서 **특정 클래스의 #instances/#bytes가 지속 증가**
- **동시성/IO 병목 구분**
    - GC 수치가 정상이지만 느릴 때는 `jcmd <PID> Thread.print`로 **BLOCKED 다수/특정 락 경합** 또는 **RUNNABLE + IO 대기** 여부 확인
- **보안/성능 주의**
    - `heap_dump`, `-histo:live`는 STW와 부하를 유발할 수 있으므로 **저부하 시간대**에 실행
    - 힙덤프/JFR에는 **민감정보**가 포함될 수 있으므로 접근 통제를 반드시 적용

---

## 10) 최소 실행 스크립트 예시(옵션)

**macOS/Linux — `run_with_gc_and_jfr.sh`**
```bash
#!/usr/bin/env bash
set -euo pipefail
mkdir -p logs jfr dumps

java -Xms256m -Xmx256m   -Xlog:gc*:file=./logs/gc-%t.log:time,uptime,level,tags:filecount=5,filesize=20m   -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./dumps   -XX:StartFlightRecording=name=boot,settings=profile,filename=./jfr/boot-%t.jfr,dumponexit=true,maxsize=256m   TargetApp
```

**Windows PowerShell — `run_with_gc_and_jfr.ps1`**
```powershell
New-Item -ItemType Directory -Force -Path logs,jfr,dumps | Out-Null
java -Xms256m -Xmx256m `
  -Xlog:gc*:file=./logs/gc-%t.log:time,uptime,level,tags:filecount=5,filesize=20m `
  -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./dumps `
  -XX:StartFlightRecording=name=boot,settings=profile,filename=./jfr/boot-%t.jfr,dumponexit=true,maxsize=256m `
  TargetApp
```

---

## 부록 — 모니터링 도구·옵션 한눈 정리 (JDK 17, CLI 중심)

### 1) JFR (JDK Flight Recorder)
- **정의**: JVM 내부 이벤트( GC, 할당, 락, 스레드, CPU 등 )를 **저오버헤드**로 수집해 `.jfr` 파일로 남김.
- **언제 쓰나**: “Full GC가 잦나?”, “누가 많이 할당하나?”, “락 경합이 있나?” 등을 **종합적으로** 확인할 때.
- **시작(실행 시 자동 기록)**:
  ```bash
  java -XX:StartFlightRecording=name=run,settings=profile,filename=./jfr/run-%t.jfr,dumponexit=true,maxsize=256m        -Xms256m -Xmx256m TargetApp
  ```
- **실행 중 시작/정지**:
  ```bash
  jcmd <PID> JFR.start settings=profile filename=./jfr/run.jfr
  jcmd <PID> JFR.check
  jcmd <PID> JFR.dump name=run filename=./jfr/run-now.jfr
  jcmd <PID> JFR.stop name=run
  ```
- **보기/분석(CLI)**:
  ```bash
  jfr summary ./jfr/run*.jfr
  jfr print --events GarbageCollection,AllocationInNewTLAB,AllocationOutsideTLAB,JavaMonitorEnter ./jfr/run*.jfr | head
  ```
- **해석 포인트**
    - `GarbageCollection`: **Pause Time 총합/횟수/주기** (Full/Young 구분 포함)
    - `Allocation*`: **할당 레이트**와 **핫 스택** → 과할당/누수 경로 추적
    - `JavaMonitorEnter`/`ThreadPark`: **락 경합/대기** 여부

---

### 2) jstat (JVM 통계 실시간 뷰)
- **정의**: 힙 영역 사용률·용량, GC 횟수/시간, 원인(옵션별) 등을 **주기 출력**.
- **언제 쓰나**: **Full GC 여부/빈도**와 **Old 영역 추세** 파악, 누수 1차 의심.
- **사용법(대표)**:
  ```bash
  jstat -gcutil  <PID> 1000 10   # 1초 간격 10회. 영역별 사용률(%), YGC/FGC/시간
  jstat -gc      <PID> 1000 10   # 용량/사용량(바이트/KB) 상세
  jstat -gccause <PID> 1000 10   # 마지막/현재 GC 원인 문자열 포함
  ```
- **보는 법(핵심 컬럼)**
    - `O`(Old) 사용률/사용량, `YGC/YGCT`, `FGC/FGCT`(**Full GC 횟수/누적시간**)
    - `LastGC Cause / CurrentGC Cause`(with `-gccause`)
- **해석 포인트**
    - **Full GC 발생**: `FGC`가 증가(예: 0→1) & `FGCT` 증가
    - **Full GC 빈발**: 짧은 시간에 `FGC`가 계속 증가
    - **누수 의심**: Young GC가 여러 번 도는 동안 **Old 저점이 계단식 상승**

---

### 3) jmap (힙 히스토그램/덤프)
- **정의**: 클래스별 **라이브 객체 히스토그램**(개수/바이트)과 **힙 덤프** 생성.
- **언제 쓰나**: **어떤 클래스가 메모리를 먹는지** 2차 확인, 덤프로 오프라인 정밀 분석.
- **사용법(대표)**:
  ```bash
  jmap -histo:live <PID> | head -n 50        # Full GC 후 생존 객체만 집계
  jmap -histo:live <PID> > histo_t0.txt; sleep 10; jmap -histo:live <PID> > histo_t1.txt
  diff histo_t0.txt histo_t1.txt             # 증가 클래스 비교 (Win: fc)
  # 힙 덤프
  jcmd <PID> GC.heap_dump filename=./heap.hprof
  # (대안) jmap -dump:format=b,file=./heap.hprof <PID>
  ```
- **보는 법**
    - 컬럼: `num  #instances  #bytes  class name`
    - 상위 항목(예: `[B`, `char[]`, `java.lang.String`, `HashMap$Node`, 프레임워크 캐시류) **증가 추세** 확인
- **주의**: `-histo:live`/덤프는 **STW** 및 **대용량 파일** 생성 가능 → **저부하 시간** 권장, **민감정보** 취급 주의

---

### 4) `-Xlog:gc` (Unified GC 로그)
- **정의**: GC 이벤트를 **텍스트 로그**로 기록(원인/전후 메모리/지연시간 등).
- **언제 쓰나**: Full/Young/Mixed **이벤트 타임라인**과 **지연(ms)**, **원인**을 빠르게 훑을 때.
- **켜기(권장 템플릿)**:
  ```bash
  -Xlog:gc*:file=./logs/gc-%t.log:time,uptime,level,tags:filecount=5,filesize=20m
  ```
- **빠른 조회**:
  ```bash
  grep -E "Pause Full|Full GC" logs/gc-*.log | wc -l   # Full GC 건수
  grep -E "Pause (Young|Mixed|Full)" logs/gc-*.log | head -n 20
  ```
- **보는 법(예시 라인)**
  ```
  [15.678s][info][gc] GC(25) Pause Full (System.gc()) 120M->80M(256M) 150.0ms
  ```
    - `Pause Full(...)` = **Full GC**
    - `120M->80M(256M)` = **수집 전→후/총 힙**
    - `150.0ms` = **정지 시간**
- **해석 포인트**
    - **Full GC 빈발**: `Pause Full` 라인이 **짧은 간격**으로 반복
    - `G1 Humongous Allocation`, `Allocation Failure` 등이 반복 → **큰 객체/급격 할당** 패턴 점검

---

### 5) `-Xlog:safepoint` (세이프포인트 로그)
- **정의**: JVM이 **스레드를 멈추는 지점**(Safepoint)의 정지 시간/수집 시간을 로그로 기록.
- **언제 쓰나**: GC 외에도 **VM 작업/TTSP(Time To SafePoint)** 때문에 멈춤이 길어지는지 확인할 때.
- **켜기**:
  ```bash
  -Xlog:safepoint:file=./logs/sfp-%t.log:time,uptime,level,tags
  ```
- **보는 법(예시 라인)**
  ```
  [7.971s][info][safepoint] Total time for which application threads were stopped: 0.0111 seconds, Stopping threads took: 0.0000070 seconds
  ```
    - **Total time ... stopped**: 앱 스레드가 실제 **멈춰 있었던 총 시간**
    - **Stopping threads took**: 스레드를 **세이프포인트로 모으는 시간**(TTSP)
- **해석 포인트**
    - **Safepoint 총 시간↑ / TTSP↑**이면 GC 외 요인(클래스 재정의, 코드 캐시, JIT, 디버거 attach 등) 가능 → GC 로그와 **교차 확인** 권장

---

### 6) 초간단 판독 치트시트
- **Full GC 폭증**:
    - `jstat -gcutil/-gccause`에서 **FGC/FGCT** 빠르게 증가
    - GC 로그 `Pause Full` 라인 **빈발** / 지연(ms) 커짐
- **힙 누수 의심**:
    - `jstat -gcutil`의 **Old 저점**이 시간 경과에 따라 **상승**
    - `jmap -histo:live` 반복 스냅샷에서 특정 클래스 **#instances/#bytes 증가**
- **동시성/IO 병목 의심**:
    - GC 수치는 정상인데 느리다 → `jcmd <PID> Thread.print`로 **BLOCKED 다수/IO 대기** 확인
    - `-Xlog:safepoint`에서 **TTSP**나 **총 정지시간** 비정상적이면 GC 외 요인 의심
- **저오버헤드 종합 확인**:
    - **JFR**로 `GarbageCollection / Allocation* / JavaMonitorEnter` 묶어서 **한 번에 진단**