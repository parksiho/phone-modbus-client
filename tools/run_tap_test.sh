#!/usr/bin/env bash
# PC(리눅스, root)에서 가상 장비망을 띄우고 앱 내장 스택을 검증한다.
#   sudo tools/run_tap_test.sh
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${BUILD_DIR:-build/tap-test}"
mkdir -p "$OUT"

javac --release 11 -d "$OUT/classes" $(find core/src/main core/src/test -name '*.java')

python3 tools/tap_bridge.py --ip 192.168.10.1 --prefix 24 > "$OUT/bridge.log" 2>&1 &
BRIDGE=$!
sleep 1
python3 tools/modbus_sim.py --host 192.168.10.1 --port 502 > "$OUT/sim.log" 2>&1 &
SIM=$!
trap 'kill $BRIDGE $SIM 2>/dev/null || true' EXIT
sleep 1

# 앱 IP(192.168.10.50)는 OS 라우팅 표에 없다 → OS 는 이 IP 를 모른다는 것을 기록
python3 - <<'EOF'
import socket, fcntl, struct
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
found = []
for _, name in socket.if_nameindex():
    try:
        addr = fcntl.ioctl(s, 0x8915, struct.pack("256s", name.encode()[:15]))  # SIOCGIFADDR
        ip = socket.inet_ntoa(addr[20:24])
        print(f"[os] 인터페이스 {name}: {ip}")
        if ip == "192.168.10.50":
            found.append(name)
    except OSError:
        pass
print("[os] 앱 IP 192.168.10.50 의 OS 등록 여부:", "있음 " + str(found) if found else "없음 (앱 내부 스택에만 존재)")
EOF

# 장비 쪽에서 앱 IP 로 ping (스택이 ICMP Echo 에 응답하는지) - 테스트가 6단계에 들어가면 시작
: > "$OUT/result.log"
( until grep -q "== 6\." "$OUT/result.log" 2>/dev/null; do sleep 0.2; done
  python3 tools/icmp_ping.py 192.168.10.50 3 > "$OUT/device_ping.log" 2>&1 ) &

java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$OUT/classes" com.emsfield.test.TapSelfTest 4 | tee "$OUT/result.log"
STATUS=${PIPESTATUS[0]}
echo
echo "== 장비(192.168.10.1) → 앱(192.168.10.50) ping"
cat "$OUT/device_ping.log" || true
exit $STATUS
