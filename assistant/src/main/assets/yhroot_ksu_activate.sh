#!/system/bin/sh

SCRIPT_PATH="$0"
DATA_APP_ROOT="${YHROOT_DATA_APP_ROOT:-/data/app}"
TMP_ROOT="${YHROOT_TMP_ROOT:-/data/local/tmp}"
SELINUX_ENFORCE_PATH="${YHROOT_SELINUX_ENFORCE_PATH:-/sys/fs/selinux/enforce}"
KSUD=
KSU_APK=
KSU_PACKAGE=

case "$SCRIPT_PATH" in
  */.ghostlock_root.sh)
    LOG="${SCRIPT_PATH%/*}/.ghostlock_ksu.log"
    ;;
  *)
    LOG="$TMP_ROOT/yhroot_ksu_activate.log"
    ;;
esac

package_for_apk() {
  APK_PATH="$1"
  PACKAGE_LINE=$(cmd package list packages -f 2>/dev/null | grep -F "package:$APK_PATH=" | head -1)
  if [ -z "$PACKAGE_LINE" ]; then
    PACKAGE_LINE=$(pm list packages -f 2>/dev/null | grep -F "package:$APK_PATH=" | head -1)
  fi
  if [ -n "$PACKAGE_LINE" ]; then
    printf '%s\n' "${PACKAGE_LINE##*=}"
  fi
}

discover_ksud() {
  for CANDIDATE in "$DATA_APP_ROOT"/*/*/lib/arm64/libksud.so; do
    [ -f "$CANDIDATE" ] || continue
    APP_DIR=${CANDIDATE%/lib/arm64/libksud.so}
    APK_PATH="$APP_DIR/base.apk"
    [ -f "$APK_PATH" ] || continue

    KSUD="$CANDIDATE"
    KSU_APK="$APK_PATH"
    KSU_PACKAGE=$(package_for_apk "$APK_PATH")
    if [ -z "$KSU_PACKAGE" ]; then
      INSTALL_DIR=${APP_DIR##*/}
      KSU_PACKAGE=${INSTALL_DIR%%-*}
    fi
    return 0
  done
  return 1
}

cleanup_tmp_async() {
  CLEANUP_SCRIPT="$SCRIPT_PATH"
  CLEANUP_LOG="$LOG"
  CLEANUP_TMP_ROOT="$TMP_ROOT"
  (
    trap '' HUP
    sleep "${YHROOT_CLEANUP_DELAY:-1}"
    rm -f \
      "$CLEANUP_SCRIPT" \
      "$CLEANUP_LOG" \
      "$CLEANUP_TMP_ROOT/yhroot_ksu_activate.sh" \
      "$CLEANUP_TMP_ROOT/yhroot_ksu_activate.log" \
      "$CLEANUP_TMP_ROOT/.ghostlock_root.sh" \
      "$CLEANUP_TMP_ROOT/.ghostlock_ksu.log"
  ) </dev/null >/dev/null 2>&1 &
}

echo "[*] KernelSU activation script start uid=$(id -u)" >"$LOG"
chmod 644 "$LOG" 2>/dev/null
echo "[*] seccomp=$(grep Seccomp /proc/self/status 2>/dev/null | tr '\n' ' ')" >>"$LOG"

discover_ksud

echo "[*] ksud=$KSUD" >>"$LOG"
echo "[*] ksu_apk=$KSU_APK" >>"$LOG"
echo "[*] ksu_package=$KSU_PACKAGE" >>"$LOG"
echo "[*] ksud_file=$(ls -l "$KSUD" 2>/dev/null)" >>"$LOG"
echo "[*] uname=$(uname -r)" >>"$LOG"

if [ "$(id -u)" -ne 0 ]; then
  echo '[!] temporary su unavailable; aborting' | tee -a "$LOG"
  exit 1
fi

if ! grep -q kernelsu /proc/modules 2>/dev/null; then
  if [ -z "$KSUD" ] || [ ! -f "$KSUD" ]; then
    echo '[!] ksud missing; cannot late-load' | tee -a "$LOG"
    exit 1
  fi
  chmod 755 "$KSUD" 2>/dev/null
  if [ ! -x "$KSUD" ]; then
    echo '[!] ksud exists but is not executable' | tee -a "$LOG"
    exit 1
  fi
  KVER=$(uname -r | cut -d. -f1-2)
  AVER=$(uname -r | grep -o 'android[0-9]*' | head -1)
  KMI="${AVER}-${KVER}"
  if [ -z "$AVER" ] || [ -z "$KVER" ]; then KMI=android15-6.6; fi
  echo "[*] late-load kmi=$KMI" >>"$LOG"
  "$KSUD" late-load --kmi "$KMI" --allow-shell >>"$LOG" 2>&1
  LATE_LOAD_RC=$?
  echo "[*] late-load exit=$LATE_LOAD_RC" >>"$LOG"
  if [ "$LATE_LOAD_RC" -ne 0 ]; then
    exit "$LATE_LOAD_RC"
  fi
fi

echo "[*] temporary su uid=$(id -u); watching kernelsu.ko" >>"$LOG"
KSU_READY=0
for i in $(seq 1 50); do
  if grep -q kernelsu /proc/modules 2>/dev/null; then KSU_READY=1; break; fi
  sleep 0.1
done
if [ "$KSU_READY" -ne 1 ]; then
  echo '[!] KernelSU module not loaded; SELinux policy/enforcing unchanged' | tee -a "$LOG"
  exit 1
fi

echo '[+] KernelSU module loaded' | tee -a "$LOG"
echo "[*] kernelsu.ko loaded; root pid=$$ uid=$(id -u)" >>"$LOG"
echo 0 > "$SELINUX_ENFORCE_PATH" 2>/dev/null
echo "[*] setenforce 0 rc=$?" >>"$LOG"

FIXUP_RC=1
for i in $(seq 1 10); do
  echo "[*] fixup: attempt $i" >>"$LOG"
  timeout 8 load_policy /sys/fs/selinux/policy >>"$LOG" 2>&1
  FIXUP_RC=$?
  if [ "$FIXUP_RC" -eq 0 ]; then
    break
  fi
  sleep 2
done

echo "[*] policy fixup rc=$FIXUP_RC" >>"$LOG"
if [ "$FIXUP_RC" -eq 0 ]; then
  echo "[*] restoring enforcing" >>"$LOG"
  echo 1 > "$SELINUX_ENFORCE_PATH" 2>/dev/null
  echo "[*] activation complete; asynchronous cleanup scheduled" >>"$LOG"
  cleanup_tmp_async
  exit 0
fi

echo '[!] fixup failed; SELinux left permissive' | tee -a "$LOG"
exit "$FIXUP_RC"
