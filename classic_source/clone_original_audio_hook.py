#!/usr/bin/env python3
"""Read-only OEM-music audio tap in the private SHA-pinned Classic clone.

Observes the ORIGINAL mic AudioRecord.read([SII) return buffer and the
ORIGINAL Visualizer.OnDataCaptureListener FFT callback. Never opens a
second audio capture, invents an audio session ID, sets/starts a device,
or changes original OEM handler/worker behavior. The sole microphone read
is forwarded once through a return-preserving static bridge. Only source Java is public;
original smali/DEX and runtime audio remain in the owner-private build.
"""
from pathlib import Path
import re
from classic_first import GateFailure

MIC_FILE = Path("smali_classes2/d4/y.smali")
FFT_FILE = Path("smali_classes2/d4/z$a.smali")
FFT_WORKER_FILE = Path("smali_classes2/d4/z.smali")
MIC_OWNER = "Ld4/y;"
FFT_OWNER = "Ld4/z$a;"
FFT_WORKER_OWNER = "Ld4/z;"
PCM_READ = "Landroid/media/AudioRecord;->read([SII)I"
PCM_STOP = "Landroid/media/AudioRecord;->stop()V"
FFT_FORWARD = "Ld4/z;->b([BI)V"
FFT_RELEASE = "Landroid/media/audiofx/Visualizer;->release()V"
TARGET = "Lio/github/lxsdd/yeelightclassicdj/ui/ClassicOriginalMusicAudioSignal;"
READ_TAP = TARGET + "->readOriginalMicAndObserve(Landroid/media/AudioRecord;[SII)I"
LEGACY_MIC_TAPS = (TARGET + "->observedOriginalMicResult(I)V",
                   TARGET + "->observedOriginalMicRate(Landroid/media/AudioRecord;)V",
                   TARGET + "->observedOriginalMicSamples([S)V")
MIC_STOP = TARGET + "->observedOriginalMicStopped()V"
FFT = TARGET + "->observedOriginalVisualizerFft([BI)V"
FFT_STOP = TARGET + "->observedOriginalVisualizerStopped()V"

def _exact_class(text, owner):
    if len(re.findall(r"(?m)^\.class[ \t]+[^\r\n]*" +
                      re.escape(owner) + r"[ \t]*\r?$", text)) != 1:
        raise GateFailure("CLASSIC_ORIGINAL_AUDIO_CLASS_DRIFT")

def _method(text, signature):
    re_method = re.compile(
        r"(?ms)^\.method[ \t]+[^\r\n]*\b" + re.escape(signature) +
        r"[ \t]*\r?$.*?^\.end method[ \t]*\r?$"
    )
    matches = list(re_method.finditer(text))
    if len(matches) != 1:
        raise GateFailure("CLASSIC_ORIGINAL_AUDIO_METHOD_DRIFT")
    return matches[0]

def _call_sites(body, descriptor):
    return list(re.finditer(
        r"(?m)^(?P<indent>[ \t]*)invoke-(?:virtual|static|direct)"
        r"(?:/range)?[ \t]+\{(?P<regs>[^}]+)\},[ \t]*" +
        re.escape(descriptor) + r"[ \t]*\r?$", body
    ))

def _validate_read_regs(regs, is_range):
    if is_range:
        match = re.fullmatch(r"([vp])(\d+)\s+\.\.\s+([vp])(\d+)", regs.strip())
        if match and match.group(1) == match.group(3) and \
           int(match.group(4)) - int(match.group(2)) == 3:
            return
    else:
        tokens = [x.strip() for x in regs.split(",")]
        if len(tokens) == 4 and all(re.fullmatch(r"[vp]\d+", x) for x in tokens):
            return
    raise GateFailure("CLASSIC_ORIGINAL_AUDIO_READ_REGISTER_DRIFT")


def patch_mic(text):
    if type(text) is not str or not 0 < len(text) < 1_000_000:
        raise GateFailure("CLASSIC_ORIGINAL_AUDIO_INPUT_INVALID")
    _exact_class(text, MIC_OWNER)
    if any(bridge in text for bridge in (READ_TAP, MIC_STOP, *LEGACY_MIC_TAPS)):
        raise GateFailure("CLASSIC_ORIGINAL_AUDIO_ALREADY_PATCHED")
    method = _method(text, "run()V")
    body = method.group()
    if text.count(PCM_READ) != 1 or text.count(PCM_STOP) != 1 or \
       body.count(PCM_READ) != 1 or body.count(PCM_STOP) != 1:
        raise GateFailure("CLASSIC_ORIGINAL_AUDIO_MIC_CALL_DRIFT")
    reads = list(re.finditer(
        r"(?m)^(?P<indent>[ \t]*)invoke-virtual(?P<range>/range)?[ \t]+"
        r"\{(?P<regs>[^}]+)\},[ \t]*" + re.escape(PCM_READ) +
        r"[ \t]*\r?$", body
    ))
    if len(reads) != 1:
        raise GateFailure("CLASSIC_ORIGINAL_AUDIO_MIC_CALL_DRIFT")
    read = reads[0]
    _validate_read_regs(read.group("regs"), bool(read.group("range")))
    # Owner's SHA-pinned original probe confirmed a discarded read result.
    # The static bridge preserves register list, argument widths and return.
    # No new local register is needed even if the OEM discards the result.
    next_instruction = None
    for line in body[read.end():].splitlines():
        stripped = line.strip()
        if stripped.startswith(".end method"):
            break
        if not stripped or stripped.startswith((".", "#", ":")):
            continue
        next_instruction = stripped
        break
    if next_instruction is None or (
        next_instruction.startswith("move-result") and
        not re.fullmatch(r"move-result[ \t]+[vp]\d+", next_instruction)
    ):
        raise GateFailure("CLASSIC_ORIGINAL_AUDIO_MIC_RESULT_DRIFT")
    replacement = read.group().replace("invoke-virtual", "invoke-static", 1)
    replacement = replacement.replace(PCM_READ, READ_TAP, 1)
    changed = body[:read.start()] + replacement + body[read.end():]
    stop_calls = _call_sites(changed, PCM_STOP)
    if len(stop_calls) != 1:
        raise GateFailure("CLASSIC_ORIGINAL_AUDIO_MIC_STOP_DRIFT")
    stop = stop_calls[0]
    changed = (changed[:stop.end()] + "\n" + stop.group("indent") +
               "invoke-static {}, " + MIC_STOP + changed[stop.end():])
    output = text[:method.start()] + changed + text[method.end():]
    if output.count(READ_TAP) != 1 or output.count(MIC_STOP) != 1 or \
       output.count(PCM_READ) != 0 or output.count(PCM_STOP) != 1:
        raise GateFailure("CLASSIC_ORIGINAL_AUDIO_MIC_RECHECK_FAILED")
    return output


def patch_fft(text, worker):
    if type(text) is not str or type(worker) is not str or \
       not 0 < len(text) < 1_000_000 or not 0 < len(worker) < 1_000_000:
        raise GateFailure("CLASSIC_ORIGINAL_AUDIO_INPUT_INVALID")
    _exact_class(text, FFT_OWNER)
    _exact_class(worker, FFT_WORKER_OWNER)
    if FFT in text or FFT_STOP in worker:
        raise GateFailure("CLASSIC_ORIGINAL_AUDIO_ALREADY_PATCHED")
    callback = _method(text, "onFftDataCapture(Landroid/media/audiofx/Visualizer;[BI)V")
    original = callback.group()
    forwards = _call_sites(original, FFT_FORWARD)
    if len(forwards) != 1 or original.count(FFT_FORWARD) != 1:
        raise GateFailure("CLASSIC_ORIGINAL_AUDIO_FFT_FORWARD_DRIFT")
    fwd = forwards[0]
    data = (original[:fwd.end()] + "\n" + fwd.group("indent") +
            "invoke-static/range {p2 .. p3}, " + FFT +
            original[fwd.end():])
    changed_callback = text[:callback.start()] + data + text[callback.end():]
    run = _method(worker, "run()V")
    body = run.group()
    releases = _call_sites(body, FFT_RELEASE)
    if len(releases) != 2 or body.count(FFT_RELEASE) != 2:
        raise GateFailure("CLASSIC_ORIGINAL_AUDIO_FFT_RELEASE_DRIFT")
    for release in reversed(releases):
        body = (body[:release.end()] + "\n" + release.group("indent") +
                "invoke-static {}, " + FFT_STOP + body[release.end():])
    changed_worker = worker[:run.start()] + body + worker[run.end():]
    if (changed_callback.count(FFT) != 1 or changed_worker.count(FFT_STOP) != 2 or
        changed_callback.count(FFT_FORWARD) != 1 or
        changed_worker.count(FFT_RELEASE) != 2):
        raise GateFailure("CLASSIC_ORIGINAL_AUDIO_FFT_RECHECK_FAILED")
    return changed_callback, changed_worker

def patch_staged_clone(staged: Path) -> dict:
    paths = [staged / x for x in (MIC_FILE, FFT_FILE, FFT_WORKER_FILE)]
    if any(not p.is_file() or p.is_symlink() for p in paths):
        raise GateFailure("CLASSIC_ORIGINAL_AUDIO_SMALI_MISSING")
    originals = [p.read_text(encoding="utf-8") for p in paths]
    mic = patch_mic(originals[0])
    fft, worker = patch_fft(originals[1], originals[2])
    # ALL target methods are validated before ANY private staged write.
    for path, content in zip(paths, (mic, fft, worker)):
        path.write_text(content, encoding="utf-8")
    return {
        "status": "PRIVATE_CLASSIC_ORIGINAL_PCM_FFT_READONLY_WITNESS_STAGED",
        "source_owned_original_methods_modified": 3,
        "original_mic_capture_and_visualizer_unchanged": True,
        "secondary_capture_sessions_started": 0,
        "original_audio_callback_data_persisted": False,
        "original_music_player_or_lamp_writes": 0,
        "verified_on_pixel6a": False,
        "original_audio_runtime_observed": False,
    }
