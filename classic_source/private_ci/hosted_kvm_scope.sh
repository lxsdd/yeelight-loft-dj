#!/usr/bin/env bash
# Sourced by owner-protected/public disposable hosted emulator runners ONLY.
# Dedicated ephemeral GitHub host: give *this job user only* KVM access and
# restore original ownership/mode in EXIT cleanup. Never make KVM globally writable; no
# persistent udev rules, no physical phone, bulb, LAN or user data.
KVM_SCOPE_CHANGED=0
KVM_SCOPE_OLD_OWNER=''
KVM_SCOPE_OLD_MODE=''
KVM_SCOPE_REASON='NOT_TESTED'

kvm_scope_acquire() {
  KVM_SCOPE_REASON='KVM_DEVICE_MISSING'
  [[ -c /dev/kvm ]] || return 1
  if [[ -r /dev/kvm && -w /dev/kvm ]]; then
    KVM_SCOPE_REASON='READY'
    return 0
  fi
  KVM_SCOPE_REASON='KVM_ACCESS_UNAVAILABLE'
  [[ "${GITHUB_REPOSITORY:-}" == 'lxsdd/yeelight-loft-dj' &&
     "${RUNNER_ENVIRONMENT:-}" == 'github-hosted' ]] || return 1
  command -v stat >/dev/null || return 1
  command -v sudo >/dev/null || return 1
  KVM_SCOPE_OLD_OWNER="$(stat -c '%u:%g' /dev/kvm)" || return 1
  KVM_SCOPE_OLD_MODE="$(stat -c '%a' /dev/kvm)" || return 1
  [[ "$KVM_SCOPE_OLD_OWNER" =~ ^[0-9]+:[0-9]+$ &&
     "$KVM_SCOPE_OLD_MODE" =~ ^[0-7]{3,4}$ ]] || return 1
  # If the KVM inode exists but runner is denied, this is a *host*
  # permission change, restricted to the current worker, not an app/device
  # command. No sudo permissions are passed into the emulator itself.
  sudo -n chown "$(id -u):$(id -g)" /dev/kvm >/dev/null 2>&1 || return 1
  KVM_SCOPE_CHANGED=1
  sudo -n chmod 0600 /dev/kvm >/dev/null 2>&1 || return 1
  [[ -r /dev/kvm && -w /dev/kvm ]] || return 1
  KVM_SCOPE_REASON='READY'
  return 0
}

kvm_scope_restore() {
  if [[ "$KVM_SCOPE_CHANGED" == 1 ]]; then
    sudo -n chown "$KVM_SCOPE_OLD_OWNER" /dev/kvm >/dev/null 2>&1 || true
    sudo -n chmod "$KVM_SCOPE_OLD_MODE" /dev/kvm >/dev/null 2>&1 || true
    KVM_SCOPE_CHANGED=0
  fi
}
