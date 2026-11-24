"""Register-safe private-clone row hook.

DEX parameter registers are ordinary mutable registers, not immutable Java
arguments. Preserve the original method's register file and control flow by
moving its body to a private same-class method. The original typed entry
point calls it once, then binds the row using its own untouched arguments.
No new registers, guessed v-registers or OEM source are published.
"""
import re
from classic_first import GateFailure


def wrap_row_method(source, match, owner, signature, hook):
    body = match.group()
    header, sep, rest = body.partition("\n")
    if not sep:
        raise GateFailure("CLASSIC_ROW_BINDER_HEADER_DRIFT")
    decl = re.fullmatch(
        r"\.method[ \t]+(?P<flags>(?:[A-Za-z-]+[ \t]+)*)"
        + re.escape(signature) + r"[ \t]*\r?", header)
    if not decl or not signature.endswith(")V"):
        raise GateFailure("CLASSIC_ROW_BINDER_HEADER_DRIFT")
    flags = decl.group("flags").split()
    if any(flag not in {"public", "protected", "private", "final", "synthetic", "bridge"} for flag in flags):
        raise GateFailure("CLASSIC_ROW_BINDER_FLAGS_DRIFT")
    if len(set(flags)) != len(flags) or sum(f in flags for f in ("public", "protected", "private")) > 1:
        raise GateFailure("CLASSIC_ROW_BINDER_FLAGS_DRIFT")
    if len(re.findall(r"(?m)^[ \t]*\.(?:locals|registers)[ \t]+[0-9]+[ \t]*\r?$", rest)) != 1:
        raise GateFailure("CLASSIC_ROW_BINDER_REGISTER_DRIFT")
    # Unknown runtime annotations and self-recursion are not silently moved.
    if re.search(r"(?m)^[ \t]*\.annotation\b", rest) or owner + "->" + signature in rest:
        raise GateFailure("CLASSIC_ROW_BINDER_METADATA_DRIFT")
    name, args = signature.split("(", 1)
    helper = "classicLoftDjOriginalRow_" + name + "(" + args
    if "classicLoftDjOriginalRow_" in source:
        raise GateFailure("CLASSIC_ROW_BINDER_HELPER_COLLISION")
    if not re.fullmatch(r"[^()]+\((?:L[^;]+;)(?:I|Landroid/database/Cursor;)\)V", signature):
        raise GateFailure("CLASSIC_ROW_BINDER_SIGNATURE_DRIFT")
    # The private method has exactly the original registers/instructions.
    # In the wrapper p0/p1/p2 stay typed because invoked methods have their
    # own register frames. All normal returns (including early returns) from
    # the original binder go through the one post-bind hook; exceptions do not.
    eol = "\r\n" if header.endswith("\r") else "\n"
    original = ".method private synthetic " + helper + eol + rest
    wrapper = header + "\n" + eol.join((
        "    .locals 0",
        "    invoke-direct/range {p0 .. p2}, " + owner + "->" + helper,
        "    invoke-static/range {p0 .. p2}, " + hook,
        "    return-void",
        ".end method",
    ))
    result = source[:match.start()] + original + eol + eol + wrapper + source[match.end():]
    if result.count(hook) != 1 or result.count(owner + "->" + helper) != 1:
        raise GateFailure("CLASSIC_ROW_BINDER_RECHECK")
    return result
