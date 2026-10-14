#!/usr/bin/env python3
"""ping 명령이 없는 환경용 최소 ICMP ping (root 필요). 사용: icmp_ping.py <ip> [횟수]"""
import os
import select
import socket
import struct
import sys
import time


def checksum(b):
    if len(b) % 2:
        b += b"\x00"
    s = sum(struct.unpack(f">{len(b) // 2}H", b))
    while s >> 16:
        s = (s & 0xFFFF) + (s >> 16)
    return ~s & 0xFFFF


def main():
    ip = sys.argv[1]
    count = int(sys.argv[2]) if len(sys.argv) > 2 else 3
    sock = socket.socket(socket.AF_INET, socket.SOCK_RAW, socket.IPPROTO_ICMP)
    ident = os.getpid() & 0xFFFF
    ok = 0
    for seq in range(1, count + 1):
        payload = b"emsfield-ping".ljust(32, b".")
        hdr = struct.pack(">BBHHH", 8, 0, 0, ident, seq)
        pkt = struct.pack(">BBHHH", 8, 0, checksum(hdr + payload), ident, seq) + payload
        t0 = time.perf_counter()
        sock.sendto(pkt, (ip, 0))
        deadline = t0 + 1.0
        got = False
        while time.perf_counter() < deadline:
            r, _, _ = select.select([sock], [], [], deadline - time.perf_counter())
            if not r:
                break
            data, addr = sock.recvfrom(2048)
            ihl = (data[0] & 0xF) * 4
            t, _, _, rid, rseq = struct.unpack(">BBHHH", data[ihl:ihl + 8])
            if t == 0 and rid == ident and rseq == seq and addr[0] == ip:
                print(f"{ip} 응답 seq={seq} {(time.perf_counter() - t0) * 1000:.2f} ms")
                got = True
                ok += 1
                break
        if not got:
            print(f"{ip} seq={seq} 응답 없음")
        time.sleep(0.2)
    print(f"{ok}/{count} 응답")
    sys.exit(0 if ok == count else 1)


if __name__ == "__main__":
    main()
