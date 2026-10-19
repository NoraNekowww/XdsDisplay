package com.nz.xdsdisplay;

import java.util.UUID;

/** 显示终端只需要"读"标准蓝牙服务，这里列出用到的 UUID。 */
public final class BleUuids {

    private BleUuids() {}

    public static UUID uuid16(int shortUuid) {
        return UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", shortUuid));
    }

    public static final UUID CCCD = uuid16(0x2902);

    /** 骑行功率服务（中继广播的标准服务） */
    public static final int  CPS_SERVICE_SHORT = 0x1818;
    public static final UUID CPS_SERVICE       = uuid16(0x1818);
    public static final UUID CPS_MEASUREMENT   = uuid16(0x2A63);
    public static final UUID CPS_FEATURE       = uuid16(0x2A65);
    public static final UUID CPS_SENSOR_LOC    = uuid16(0x2A5B);

    /** 定位与导航服务 */
    public static final int  LNS_SERVICE_SHORT = 0x1819;
    public static final UUID LNS_SERVICE       = uuid16(0x1819);
    public static final UUID LN_LOCATION_SPEED = uuid16(0x2A67);
    public static final UUID LN_FEATURE        = uuid16(0x2A6A);

    /** 电池服务 */
    public static final UUID BATTERY_SERVICE   = uuid16(0x180F);
    public static final UUID BATTERY_LEVEL     = uuid16(0x2A19);

    /** 喜德盛 AD350 爪盘功率计的私有服务（直连模式用） */
    public static final int  XDS_SERVICE_SHORT   = 0x1828;
    public static final UUID XDS_SERVICE         = uuid16(0x1828);
    public static final UUID XDS_MEASUREMENT     = uuid16(0x2A63);
    public static final UUID XDS_CONTROL         = uuid16(0x2A55);

    /** GPX 路线文件蓝牙传输特征值（中继端 -> 码表端） */
    public static final UUID CHR_GPX             = UUID.fromString("0000ad04-0000-1000-8000-00805f9b34fb");
}
