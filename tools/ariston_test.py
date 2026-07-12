# -*- coding: utf-8 -*-
"""10-min test: da li python-ariston-api vidi Velis Tech Wifi 80."""
import json
import os
import sys

from ariston import discover, hello
from ariston.const import SystemType, WheType

def main() -> None:
    user = os.environ.get("ARISTON_USER") or (sys.argv[1] if len(sys.argv) > 1 else None)
    pwd = os.environ.get("ARISTON_PASS") or (sys.argv[2] if len(sys.argv) > 2 else None)
    if not user or not pwd:
        print("Usage: python ariston_test.py <email> <password>")
        print("   or set ARISTON_USER / ARISTON_PASS env vars")
        sys.exit(1)

    print("=== 1) LOGIN + DISCOVERY ===")
    devices = discover(user, pwd)
    print(f"Found {len(devices)} device(s)\n")

    for raw in devices:
        gw = raw.get("gw")
        sys_type = raw.get("sys", -1)
        whe_type = raw.get("wheType", raw.get("whe", -1))
        try:
            sys_name = SystemType(sys_type).name
        except ValueError:
            sys_name = f"UNKNOWN({sys_type})"
        try:
            whe_name = WheType(whe_type).name
        except ValueError:
            whe_name = f"UNKNOWN({whe_type})"

        print(f"--- Device: {raw.get('name')} ---")
        print(f"  gateway:    {gw}")
        print(f"  systemType: {sys_type} -> {sys_name}")
        print(f"  wheType:    {whe_type} -> {whe_name}")
        print("  raw discovery record:")
        print(json.dumps(raw, indent=4, ensure_ascii=False, default=str))

        print("\n=== 2) HELLO (device class resolution) ===")
        dev = hello(user, pwd, gw, is_metric=True, language_tag="en-US")
        if dev is None:
            print("  !! hello() returned None -> model NOT mapped by the library")
            continue
        print(f"  library class: {type(dev).__name__}")

        print("\n=== 3) FEATURES ===")
        dev.get_features()
        print(json.dumps(getattr(dev, "features", {}), indent=4, ensure_ascii=False, default=str))

        print("\n=== 4) STATE ===")
        dev.update_state()
        data = getattr(dev, "data", None)
        if data is not None:
            print(json.dumps(data, indent=4, ensure_ascii=False, default=str))

        print("\n=== 5) KEY READINGS (public getters) ===")
        for attr in [
            "water_heater_current_temperature",
            "water_heater_target_temperature",
            "water_heater_minimum_temperature",
            "water_heater_maximum_temperature",
            "water_heater_temperature_step",
            "water_heater_mode_value",
            "water_heater_mode_options",
            "water_heater_power_value",
            "is_heating",
            "is_antileg",
            "water_anti_leg_value",
            "av_shw_value",
        ]:
            try:
                val = getattr(dev, attr)
                if callable(val):
                    val = val()
                print(f"  {attr}: {val}")
            except Exception as exc:  # noqa: BLE001
                print(f"  {attr}: <n/a: {type(exc).__name__}: {exc}>")

        print("\n=== 6) AVAILABLE SET-METHODS (what we can control) ===")
        for name in sorted(dir(dev)):
            if name.startswith("set_") or name.startswith("async_set_"):
                print(f"  {name}")

if __name__ == "__main__":
    main()
