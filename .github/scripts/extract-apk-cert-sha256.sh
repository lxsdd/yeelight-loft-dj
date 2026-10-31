#!/usr/bin/env bash
# Parse both old "Signer #1" and newer "V2 Signer"/"V3.0 Signer" apksigner formats.
# One unique certificate digest per APK is required; never trust signer labels.
set -euo pipefail
sed -nE 's/^.*certificate SHA-256 digest:[[:space:]]*([[:xdigit:]:]+)[[:space:]]*$/\1/p' |
  tr '[:upper:]' '[:lower:]' |
  tr -d ':' |
  sort -u
