package com.emsfield.net;

/**
 * 앱이 장비망에서 사용할 IP 설정. 폰 OS의 네트워크 설정과 무관하게 앱 내부에만 존재한다.
 */
public final class IpConfig {
    public final int address;
    public final int prefix;
    /** 0 이면 게이트웨이 없음 (같은 대역 장비만 통신) */
    public final int gateway;

    public IpConfig(int address, int prefix, int gateway) {
        Ip4.mask(prefix); // 검증
        this.address = address;
        this.prefix = prefix;
        this.gateway = gateway;
        int m = Ip4.mask(prefix);
        if (prefix < 31) {
            if ((address & ~m) == 0) throw new IllegalArgumentException("네트워크 주소는 사용할 수 없습니다: " + Ip4.str(address));
            if ((address & ~m) == ~m) throw new IllegalArgumentException("브로드캐스트 주소는 사용할 수 없습니다: " + Ip4.str(address));
        }
        if (gateway != 0 && !sameSubnet(gateway)) {
            throw new IllegalArgumentException("게이트웨이가 설정한 대역 밖에 있습니다: " + Ip4.str(gateway));
        }
    }

    public static IpConfig of(String ip, int prefix, String gateway) {
        int gw = (gateway == null || gateway.trim().isEmpty()) ? 0 : Ip4.parse(gateway);
        return new IpConfig(Ip4.parse(ip), prefix, gw);
    }

    public int mask() {
        return Ip4.mask(prefix);
    }

    public boolean sameSubnet(int other) {
        int m = mask();
        return (address & m) == (other & m);
    }

    public int broadcast() {
        return address | ~mask();
    }

    @Override
    public String toString() {
        return Ip4.str(address) + "/" + prefix + (gateway != 0 ? " gw " + Ip4.str(gateway) : "");
    }
}
