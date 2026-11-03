#!/usr/bin/env bash
# Terminal scene of the VoLTE demo, recorded off-screen on the VOLTE-DEMO headless output.
set -u
D=/home/meodien/tmp_bun/claude-1000/-home-meodien-Desktop-ethiopia-working-dir-worktrees-voice-service-sip-freeswitch/b7c18d7b-9624-4e11-9009-00a06feac35c/scratchpad/demo
S=$(dirname "$D")
R=/home/meodien/Desktop/ethiopia-working-dir/worktrees/voice-service/sip-freeswitch
BR=br-$(docker network inspect sip-freeswitch_sf-net -f '{{.Id}}' | cut -c1-12)
OUT=$D/out; mkdir -p "$OUT"; : > "$OUT/timeline.log"
mark() { echo "$(awk -v a=$(date +%s.%N) -v b=$T0 'BEGIN{printf "%.2f", a-b}') $*" >> "$OUT/timeline.log"; }
type_cmd() { # $1 = text; types it into the UE pane like a person, then Enter
  local s=$1 i
  for ((i = 0; i < ${#s}; i++)); do tmux send-keys -t volte:0.2 -l -- "${s:i:1}"; sleep 0.025; done
  sleep 0.4; tmux send-keys -t volte:0.2 Enter
}

tmux kill-session -t volte 2>/dev/null
tmux new-session -d -s volte -x 230 -y 58 "python3 -u $D/services.py"
tmux split-window -t volte:0 -v -l 52% "cd $R; PS1='ue@lab:sip-freeswitch\$ ' bash --norc -i"
tmux select-pane -t volte:0.0
tmux split-window -t volte:0.0 -h -l 56% "docker run --rm --name volte-tap --net host nicolaka/netshoot tshark -i $BR -l -n -o rtp.heuristic_rtp:TRUE -Y 'sip || (diameter && diameter.cmd.code != 280) || (rtp && rtp.seq % 100 == 0)' -T fields -e frame.time_epoch -e ip.src -e ip.dst -e sip.Request-Line -e sip.Status-Line -e sip.CSeq.method -e diameter.cmd.code -e diameter.flags.request -e diameter.Result-Code -e diameter.Experimental-Result-Code -e rtp.seq -e rtp.p_type 2>/dev/null | python3 -u $D/tapfmt.py"
tmux set -t volte status off
# pane indexes: 0 services (top-left), 1 wire tap (top-right), 2 UE shell (bottom)
tmux select-pane -t volte:0.2

"$S/cap" start volte-demo-live >/dev/null
hyprctl eval 'hl.dispatch(hl.dsp.exec_cmd("kitty --class volte-term -o font_size=10.5 -o background=#0f1218 tmux attach -t volte"))' >/dev/null
sleep 6
wf-recorder -o VOLTE-DEMO -r 30 -f "$OUT/terminal.mp4" -y >/dev/null 2>&1 &
REC=$!
T0=$(date +%s.%N); sleep 4; mark intro

type_cmd "# 1) The UE registers with IMS AKA:  P-CSCF -> I-CSCF -> S-CSCF, vectors from the epc-hss AuC over Cx"
type_cmd "sipp -sf loadtest/sipp/uac_register_aka.xml -inf loadtest/sipp/users-aka.csv 172.20.10.20:5060 -i 172.20.0.1 -p 16150 -m 1"
mark register_start; sleep 9; mark register_done
type_cmd "clear"
type_cmd "# 2) Call sip:1000 (FreeSWITCH MRF): English speech goes up, the MRF tone comes down, 16 s"
type_cmd "sipp -sf loadtest/sipp/uac_volte_call.xml -inf loadtest/sipp/users-aka.csv -s 1000 172.20.10.20:5060 -i 172.20.0.1 -p 16150 -mi 172.20.0.1 -mp 16200 -m 1"
mark call_start; sleep 24; mark call_done
"$S/cap" stop >/dev/null
type_cmd "clear"
type_cmd "# 3) Media proof from the capture: RTP streams both ways"
type_cmd "tshark -r loadtest/pcaps/volte/volte-demo-live.pcap -o rtp.heuristic_rtp:TRUE -q -z rtp,streams 2>/dev/null | tail -5"
mark rtp_summary; sleep 7; mark end
kill -INT $REC; wait $REC 2>/dev/null
docker rm -f volte-tap >/dev/null 2>&1
echo "terminal scene recorded: $OUT/terminal.mp4"
