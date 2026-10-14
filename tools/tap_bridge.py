#!/usr/bin/env python3
"""
PC 검증용 TAP 브리지 (Linux, root 필요).

리눅스 TAP 인터페이스를 '현장 장비망'으로 만들고, 그 위를 오가는 이더넷 프레임을
UDP 로 앱 스택(Java)에 전달한다. 실기기에서 USB 이더넷 어댑터가 하는 역할을 흉내 낸다.

  [Java NetStack] <--UDP 프레임--> [tap_bridge] <--> [TAP fieldtap0 192.168.10.1/24] <--> [modbus_sim.py]

사용: sudo python3 tools/tap_bridge.py --ip 192.168.10.1 --prefix 24
"""
import argparse
import fcntl
import os
import select
import socket
import struct

TUNSETIFF = 0x400454CA
IFF_TAP = 0x0002
IFF_NO_PI = 0x1000
SIOCSIFADDR = 0x8916
SIOCSIFNETMASK = 0x891C
SIOCGIFFLAGS = 0x8913
SIOCSIFFLAGS = 0x8914
IFF_UP = 0x1


def ifreq_addr(name, ip):
    return struct.pack("16sH2s4s8s", name.encode(), socket.AF_INET, b"\x00" * 2, socket.inet_aton(ip), b"\x00" * 8)


def configure(name, ip, prefix):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    mask = socket.inet_ntoa(struct.pack(">I", (0xFFFFFFFF << (32 - prefix)) & 0xFFFFFFFF))
    fcntl.ioctl(s, SIOCSIFADDR, ifreq_addr(name, ip))
    fcntl.ioctl(s, SIOCSIFNETMASK, ifreq_addr(name, mask))
    flags = struct.unpack("16sH", fcntl.ioctl(s, SIOCGIFFLAGS, struct.pack("16sH", name.encode(), 0)))[1]
    fcntl.ioctl(s, SIOCSIFFLAGS, struct.pack("16sH", name.encode(), flags | IFF_UP))
    s.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", default="fieldtap0")
    ap.add_argument("--ip", default="192.168.10.1", help="장비망 쪽(가상 장비) IP")
    ap.add_argument("--prefix", type=int, default=24)
    ap.add_argument("--listen", type=int, default=47000, help="앱 → 브리지 UDP 포트")
    ap.add_argument("--app", type=int, default=47001, help="브리지 → 앱 UDP 포트")
    a = ap.parse_args()

    tap = os.open("/dev/net/tun", os.O_RDWR)
    fcntl.ioctl(tap, TUNSETIFF, struct.pack("16sH", a.name.encode(), IFF_TAP | IFF_NO_PI))
    configure(a.name, a.ip, a.prefix)

    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.bind(("127.0.0.1", a.listen))
    app_addr = ("127.0.0.1", a.app)
    print(f"[bridge] {a.name} {a.ip}/{a.prefix} UP, UDP {a.listen}<->{a.app}", flush=True)

    while True:
        r, _, _ = select.select([tap, udp], [], [])
        if tap in r:
            frame = os.read(tap, 2048)
            udp.sendto(frame, app_addr)
        if udp in r:
            frame, _ = udp.recvfrom(2048)
            os.write(tap, frame)


if __name__ == "__main__":
    main()
