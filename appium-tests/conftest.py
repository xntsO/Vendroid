import pytest
import os
import traceback as _tb
from pathlib import Path

from vendroid.config import Config

# Keep app failures and harness failures reviewable with the in-repo frames,
# Android screen, and logcat; omit irrelevant pytest/pluggy internals.
_CLEAN_REPORT = pytest.StashKey()


def _repo_frames(exc_tb) -> str:
    """The traceback entries that live in this repo (drops venv/pytest internals).

    Their source lines carry the awaited xpath, so this is the "what was it waiting
    for" context."""
    out = []
    for f in _tb.extract_tb(exc_tb):
        if "/site-packages/" in f.filename or "/.venv/" in f.filename:
            continue
        if "appium-tests" not in f.filename:
            continue
        loc = f.filename.split("appium-tests/", 1)[-1]
        out.append(f"  {loc}:{f.lineno} in {f.name}\n      {(f.line or '').strip()}")
    return "\n".join(out) or "  (no in-repo frames)"


def _exception_message(exc) -> str:
    # Selenium's str() is multi-line and ends with a JS "at ..." stack; keep the
    # meaningful lines (e.g. the NoSuchElementError reason) and drop the JS frames.
    # Harmless for plain Python exceptions (single-line str).
    lines = [
        ln.strip()
        for ln in str(exc).splitlines()
        if ln.strip() and not ln.strip().startswith("at ") and ln.strip() not in ("Message:", "Stacktrace:")
    ]
    return " ".join(lines).strip() or exc.__class__.__name__


def _denoised_logcat(name: str) -> str:
    if not Config.LOGCAT_DIR:
        return "(LOGCAT_DIR not configured; no logcat captured)"
    path = Path(Config.LOGCAT_DIR) / f"{name}.app.denoised.log"
    try:
        text = path.read_text(errors="replace").strip()
    except OSError as e:
        # Denoising failed (or never ran) in the fixture — say so rather than dumping
        # the raw, un-denoised log.
        return f"(denoised logcat unavailable: {e})"
    return text or "(denoised logcat is empty)"


@pytest.hookimpl(wrapper=True)
def pytest_runtest_makereport(item, call):
    report = yield

    if report.when == "call" and report.failed and call.excinfo is not None:
        evidence_dir = os.environ.get("VENDROID_EVIDENCE_DIR")
        driver = item.funcargs.get("driver")
        if evidence_dir and driver is not None:
            destination = Path(evidence_dir) / "failure-screens"
            destination.mkdir(parents=True, exist_ok=True)
            for suffix, capture in (
                ("xml", lambda path: path.write_text(driver.page_source, encoding="utf-8")),
                ("png", lambda path: driver.save_screenshot(str(path))),
            ):
                try:
                    capture(destination / f"{item.name}.{suffix}")
                except Exception as error:
                    # Diagnostics must not replace the original test failure.
                    print(f"Could not capture failure {suffix}: {error}")
        report.longrepr = (
            f"{call.excinfo.typename}: {_exception_message(call.excinfo.value)}\n\n"
            f"{_repo_frames(call.excinfo.tb)}"
        )
        # The denoised logcat is written during teardown, so append it then.
        item.stash[_CLEAN_REPORT] = report
    elif report.when == "teardown":
        clean = item.stash.get(_CLEAN_REPORT, None)
        if clean is not None:
            clean.longrepr = (
                f"{clean.longrepr}\n\n"
                f"===== denoised app logcat: {item.name} =====\n"
                f"{_denoised_logcat(item.name)}"
            )

    return report
