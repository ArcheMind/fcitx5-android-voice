#!/bin/zsh
set -euo pipefail

script_dir=${0:A:h}
fixture_dir="$script_dir/fixtures"
temp_dir=$(mktemp -d)
trap 'rm -rf "$temp_dir"' EXIT

mkdir -p "$fixture_dir"

render() {
    local name=$1
    local voice=$2
    local words=$3
    local aiff="$temp_dir/$name.aiff"
    say -v "$voice" -o "$aiff" "$words"
    afconvert -f WAVE -d LEI16@16000 "$aiff" "$fixture_dir/$name.wav"
}

render zh-question Tingting '一加一等于几？'
render zh-statement Tingting '今天天气真好。'
render zh-command Tingting '明天上午十点提醒我开会。'
render en-question Samantha 'What is one plus one?'
render en-statement Samantha 'The weather is beautiful today.'
render en-command Samantha 'Remind me to join the meeting at ten tomorrow morning.'
