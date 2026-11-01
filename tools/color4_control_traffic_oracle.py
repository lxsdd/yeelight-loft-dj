#!/usr/bin/env python3
"""Offline Color4 high-frequency-control scheduling oracle (NO device I/O).

It compares UI slider events against a conservative classic LAN envelope:
24 attempted requests/rolling 60s and at least 1.5s between writes, matching
the separately developed *proposed* app policy in draft PR #31. These are
client-side safety assumptions, NOT reverse engineered firmware capacities.
A true firmware quota error is terminal. Ambiguous timeout is never replayed.
Music frames are considered only with independently confirmed SUPPORT.
No networks, timers, sleeps, filesystem writes, cloud, firmware, or sockets.
"""
from __future__ import annotations
from collections import deque
from dataclasses import dataclass
import argparse
import json

CLASSIC_BUDGET=24
WINDOW_MS=60_000
CLASSIC_SPACING_MS=1_500
MUSIC_FRAME_INTERVAL_MS=100
MAX_EVENTS=20_000
TYPES={"rgb","bright"}


@dataclass(frozen=True)
class Event:
    at_ms:int
    kind:str
    value:int


def validate(events:list[Event]):
    if not isinstance(events,list) or len(events)>MAX_EVENTS:
        raise ValueError("invalid sequence length")
    last=-1
    for e in events:
        if not isinstance(e,Event) or not isinstance(e.at_ms,int) or e.at_ms<last or e.at_ms<0:
            raise ValueError("events must be monotonic")
        if e.kind not in TYPES or not isinstance(e.value,int) or (
                e.kind=="rgb" and not 0<=e.value<=0xffffff) or (
                e.kind=="bright" and not 1<=e.value<=100):
            raise ValueError("unsupported or invalid command")
        last=e.at_ms


def plan_classic_slider(
        events:list[Event], *,
        firmware_quota_rejection_at_ms:int|None=None,
        ambiguous_timeout_at_ms:int|None=None):
    validate(events)
    if firmware_quota_rejection_at_ms is not None and firmware_quota_rejection_at_ms<0:
        raise ValueError("invalid rejection timestamp")
    if ambiguous_timeout_at_ms is not None and ambiguous_timeout_at_ms<0:
        raise ValueError("invalid timeout timestamp")
    pending:dict[str,Event]={}
    attempted:deque[int]=deque()
    sends=[]
    outcomes=[]
    most_recent={}
    next_send=0
    state="READY"
    dropped_by_local_budget=0
    coalesced_updates=0
    def emit(candidate:Event, now:int):
        nonlocal next_send,state,dropped_by_local_budget
        if state!="READY":
            return
        while attempted and attempted[0]<=now-WINDOW_MS:
            attempted.popleft()
        if len(attempted)>=CLASSIC_BUDGET:
            dropped_by_local_budget+=1
            return
        # A command is counted as an ATTEMPT before network outcome.
        attempted.append(now)
        sends.append((now,candidate))
        next_send=now+CLASSIC_SPACING_MS
        if firmware_quota_rejection_at_ms is not None and now>=firmware_quota_rejection_at_ms:
            state="FIRMWARE_QUARANTINED"
            outcomes.append("firmware_quota_rejected_no_auto_recovery")
        elif ambiguous_timeout_at_ms is not None and now>=ambiguous_timeout_at_ms:
            state="UNCERTAIN_TRANSPORT_STOP"
            outcomes.append("ambiguous_send_not_replayed")

    for event in events:
        if state!="READY":
            break
        # Bounded work: at most one pending event for each UI control.
        while pending and next_send<=event.at_ms and state=="READY":
            pick=min(pending.values(),key=lambda e:e.at_ms)
            pending.pop(pick.kind)
            emit(pick,max(next_send,pick.at_ms))
        if state!="READY":
            break
        if not sends and not pending:
            emit(event,event.at_ms)
        else:
            if event.kind in pending:
                coalesced_updates+=1
            pending[event.kind]=event
        most_recent[event.kind]=event.value
    # A UI plan can suggest a delayed final frame, but this is NOT a queued
    # background task or permission to issue a future network request.
    while pending and state=="READY":
        pick=min(pending.values(),key=lambda e:e.at_ms)
        pending.pop(pick.kind)
        emit(pick,max(next_send,pick.at_ms))
    last_sent={e.kind:e.value for _,e in sends}
    return {
        "mode":"CLASSIC_LAN_SYNTHETIC",
        "input_events":len(events),
        "attempted_classic_commands":len(sends),
        "coalesced_updates":coalesced_updates,
        "dropped_when_local_budget_exhausted":dropped_by_local_budget,
        "state":state,
        "outcomes":outcomes,
        "max_planned_command_delay_ms":max(
            (when-e.at_ms for when,e in sends),default=0),
        "last_value_forwarded_by_kind":{
            k:last_sent.get(k)==value for k,value in most_recent.items()},
        "attempt_timestamps_ms":[when for when,_ in sends],
        "physical_lamp_ack_verified":False,
        "firmware_quota_root_cause_proven":False,
    }


def plan_music_frames(events:list[Event], *, confirmed_support:bool):
    validate(events)
    if not confirmed_support:
        return {
            "mode":"REFUSED_UNVERIFIED_MUSIC",
            "input_events":len(events),"music_frames":0,
            "classic_control_handshakes":0,"fallback_classic_commands":0,
            "no_auto_retry":True,"physical_lamp_ack_verified":False,
        }
    last_sent=None
    frames=0
    for e in events:
        if last_sent is None or e.at_ms-last_sent>=MUSIC_FRAME_INTERVAL_MS:
            frames+=1
            last_sent=e.at_ms
    return {
        "mode":"VERIFIED_SUPPORT_MUSIC_SYNTHETIC",
        "input_events":len(events),"music_frames":frames,
        "classic_control_handshakes":2 if events else 0,
        "fallback_classic_commands":0,
        "no_auto_retry":True,
        "music_frame_rate_is_hardware_qualified":False,
        "physical_lamp_ack_verified":False,
    }


def demonstration():
    events=[Event(i*25,"rgb",(i*997)%0x1000000) for i in range(120)]
    classic=plan_classic_slider(events)
    music=plan_music_frames(events,confirmed_support=True)
    unsupported=plan_music_frames(events,confirmed_support=False)
    return {"example":"120 synthetic RGB updates over 2.975 seconds",
            "classic":classic,"music":music,"unsupported":unsupported,
            "disclaimer":"Not OEM timer code; no real lamp workload sent."}


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--demo",action="store_true",required=True)
    parser.parse_args()
    print(json.dumps(demonstration(),indent=2,sort_keys=True))
