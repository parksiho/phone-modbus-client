#!/usr/bin/env python3
"""
가상 Modbus TCP 장비 (전력량계 흉내). 의존성 없음.

레지스터 맵 (Holding/Input 공통, 0-based):
  0-1   전압 L1      FLOAT32 ABCD  [V]
  2-3   전류 L1      FLOAT32 ABCD  [A]
  4-5   유효전력     FLOAT32 ABCD  [kW]
  10-11 누적전력량   UINT32  ABCD  [0.01 kWh]
  20-21 주파수       FLOAT32 CDAB  [Hz]   (워드 스왑 장비 흉내)
  100   쓰기 테스트용 설정값 (FC06/FC16)
  1000 이상 → 예외 02 (Illegal Data Address)
코일 0-31: 쓰기/읽기 가능

사용: python3 tools/modbus_sim.py --host 192.168.10.1 --port 502
"""
import argparse
import socketserver
import struct
import threading

REG_COUNT = 1000
lock = threading.Lock()
regs = [0] * REG_COUNT
coils = [False] * 32


def put_float(addr, value, word_swap=False):
    hi, lo = struct.unpack(">HH", struct.pack(">f", value))
    regs[addr], regs[addr + 1] = (lo, hi) if word_swap else (hi, lo)


def put_u32(addr, value):
    regs[addr], regs[addr + 1] = (value >> 16) & 0xFFFF, value & 0xFFFF


put_float(0, 220.5)
put_float(2, 12.25)
put_float(4, 2.701)
put_u32(10, 1234567)          # 12345.67 kWh
put_float(20, 60.0, word_swap=True)
coils[0] = coils[3] = True


def exception(fc, code):
    return bytes([fc | 0x80, code])


def handle_pdu(pdu):
    fc = pdu[0]
    try:
        if fc in (1, 2):
            addr, qty = struct.unpack(">HH", pdu[1:5])
            if not 1 <= qty <= 2000:
                return exception(fc, 3)
            if addr + qty > len(coils):
                return exception(fc, 2)
            out = bytearray((qty + 7) // 8)
            for i in range(qty):
                if coils[addr + i]:
                    out[i // 8] |= 1 << (i % 8)
            return bytes([fc, len(out)]) + bytes(out)
        if fc in (3, 4):
            addr, qty = struct.unpack(">HH", pdu[1:5])
            if not 1 <= qty <= 125:
                return exception(fc, 3)
            if addr + qty > REG_COUNT:
                return exception(fc, 2)
            with lock:
                data = b"".join(struct.pack(">H", regs[addr + i]) for i in range(qty))
            return bytes([fc, len(data)]) + data
        if fc == 5:
            addr, val = struct.unpack(">HH", pdu[1:5])
            if addr >= len(coils):
                return exception(fc, 2)
            if val not in (0x0000, 0xFF00):
                return exception(fc, 3)
            coils[addr] = val == 0xFF00
            return pdu[:5]
        if fc == 6:
            addr, val = struct.unpack(">HH", pdu[1:5])
            if addr >= REG_COUNT:
                return exception(fc, 2)
            with lock:
                regs[addr] = val
            return pdu[:5]
        if fc == 15:
            addr, qty, n = struct.unpack(">HHB", pdu[1:6])
            if addr + qty > len(coils):
                return exception(fc, 2)
            for i in range(qty):
                coils[addr + i] = bool((pdu[6 + i // 8] >> (i % 8)) & 1)
            return pdu[:5]
        if fc == 16:
            addr, qty, n = struct.unpack(">HHB", pdu[1:6])
            if addr + qty > REG_COUNT:
                return exception(fc, 2)
            with lock:
                for i in range(qty):
                    regs[addr + i] = struct.unpack(">H", pdu[6 + i * 2: 8 + i * 2])[0]
            return pdu[:5]
        return exception(fc, 1)
    except (struct.error, IndexError):
        return exception(fc, 3)


class Handler(socketserver.BaseRequestHandler):
    def recv_exact(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.request.recv(n - len(buf))
            if not chunk:
                return None
            buf += chunk
        return buf

    def handle(self):
        print(f"[sim] 연결: {self.client_address}", flush=True)
        while True:
            mbap = self.recv_exact(7)
            if mbap is None:
                break
            tid, pid, length, unit = struct.unpack(">HHHB", mbap)
            pdu = self.recv_exact(length - 1)
            if pdu is None:
                break
            resp = handle_pdu(pdu)
            self.request.sendall(struct.pack(">HHHB", tid, 0, len(resp) + 1, unit) + resp)
        print(f"[sim] 종료: {self.client_address}", flush=True)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="192.168.10.1")
    ap.add_argument("--port", type=int, default=502)
    a = ap.parse_args()
    with Server((a.host, a.port), Handler) as s:
        print(f"[sim] Modbus TCP 가상 전력량계 {a.host}:{a.port}", flush=True)
        s.serve_forever()
