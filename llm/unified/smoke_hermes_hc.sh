#!/usr/bin/env bash
# Copyright 2026 Scout Project Contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Quick multi-mode smoke for unified hermes-hc model.
set -u
MODEL="${1:-scout-hermes-hc1.0.0}"
echo "SMOKE model=$MODEL"

run() {
  local name="$1" prompt="$2"
  echo "--- $name ---"
  # truncate display
  out="$(ollama run "$MODEL" "$prompt" 2>/dev/null | head -c 1200)"
  echo "$out"
  echo
}

run ALERT "MODE: ALERT
Unit 12 Phoenix PD traffic stop on I-10 eastbound near 7th Ave, radar enforcement in progress."

run VET "MODE: VET
Proposed alert: ALERT: radar enforcement I-10 EB near 7th Ave Phoenix. Transcript confirms active stop."

run INTEL "MODE: INTEL
Unit 7 Phoenix PD, 10-38 at Shell on US-60 eastbound near Rural Rd, code 4."

run NAV "MODE: NAV
origin Phoenix; destination Tempe; fastest_min=14; shortest_km=7.1; waze_hazards=ok; jurisdiction=AZ"

run CHAT "MODE: CHAT
Route facts: fastest 14 min, 7.1 km, hazards ok, AZ. Tell the driver briefly."

run DEV "MODE: DEV
TASK MODE: DEBUG
One-line check: confirm local Ollama scout-hermes-hc context is high. No extra agents."

run MANAGER "MODE: MANAGER
=== USER QUERY (ADMIN-PRIVILEGED) ===
Reply first with HC_OK and confirm phase_class=alpha_development AZ active.
=== END USER QUERY ==="

run PIPELINE "MODE: PIPELINE
Transcript: Unit 12 Phoenix PD traffic stop on I-10 eastbound near 7th Ave, radar in progress.
Route: fastest_min=14; jurisdiction=AZ
Channels: [{\"id\":\"a\",\"name\":\"Phoenix Police\",\"state\":\"AZ\"},{\"id\":\"b\",\"name\":\"Arizona DPS\",\"state\":\"AZ\"}]"

echo "SMOKE complete for $MODEL"
