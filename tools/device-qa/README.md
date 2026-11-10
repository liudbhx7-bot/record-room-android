# Final APK startup acceptance

`check_startup.py` installs the exact APK and exercises real Android process startup on an explicitly selected emulator. It refuses physical devices and checks every setup/read step. It confirms the old app process is gone before launching; a successful launch command alone cannot pass.

```sh
python3 tools/device-qa/check_startup.py \
  --adb /path/to/android-sdk/platform-tools/adb \
  --serial emulator-5554 \
  --apk dist/vinyl-baseline-v0.7.0.apk \
  --out work/device-startup --launches 3
```

The default keeps app data, so it also tests upgrades from the installed older APK. `--clean-data` resets only this application's data on the selected emulator. For a genuinely fresh install, uninstall this package from that isolated emulator before running the command. Never repurpose a user's emulator with important app data for clean-install checks.

Reports contain the APK SHA-256, Android version, each launch result and crash logs. A passing emulator run is not a physical OPPO device acceptance result.
