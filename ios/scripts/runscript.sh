#!/bin/bash
# usage: runscript.sh "<script>" shotname...   (runs on 13 Pro, pulls log + shots)
U=${DEV:?Set DEV to the test device identifier}; S=$(cd "$(dirname "$0")" && pwd); B=com.roubao.ios
xcrun devicectl device copy to --device $U --domain-type appDataContainer --domain-identifier $B --source /dev/null --destination Documents/agent.log >/dev/null 2>&1
xcrun devicectl device process launch --device $U --terminate-existing $B -- -agentScript "$1" >/dev/null 2>&1
for i in $(seq 1 30); do perl -e 'select(undef,undef,undef,3)'
  xcrun devicectl device copy from --device $U --domain-type appDataContainer --domain-identifier $B --source Documents/agent.log --destination $S/agent.log >/dev/null 2>&1
  grep -q "script done" $S/agent.log && break; done
cat $S/agent.log; shift
for n in "$@"; do xcrun devicectl device copy from --device $U --domain-type appDataContainer --domain-identifier $B --source Documents/$n.png --destination $S/$n.png >/dev/null 2>&1; sips -Z 800 $S/$n.png >/dev/null; done
