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

set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if ! ollama list 2>/dev/null | awk '{print $1}' | grep -q '^qwen3:8b'; then
  echo "pulling qwen3:8b..."
  ollama pull qwen3:8b
fi

for tag_mf in \
  "scout-hermes-hc1.1.0:unified/Modelfile.scout-hermes-hc1.1.0" \
  "scout-hermes-hc1.1.0-64k:unified/Modelfile.scout-hermes-hc1.1.0-64k" \
  "scout-hermes-hc1.0.0:unified/Modelfile.scout-hermes-hc1.0.0" \
  "scout-hermes-hc1.0.0-64k:unified/Modelfile.scout-hermes-hc1.0.0-64k"
do
  tag="${tag_mf%%:*}"
  mf="${tag_mf#*:}"
  echo "==> ollama create $tag"
  ollama create "$tag" -f "$mf" || exit 1
done

ollama list | egrep 'hermes-hc|qwen3|NAME'
echo "DONE thinking-enabled scout-hermes-hc"
