"""Exercise immediate image-node reuse against real QEMU, without Android."""

import hashlib
from pathlib import Path
import subprocess
import tempfile
import time

from vendroid.qemu import QEMUController


def main():
    with tempfile.TemporaryDirectory(prefix="vendroid-qmp-check-") as directory:
        root = Path(directory)
        disk = root / "usb.img"
        with disk.open("wb") as output:
            output.write(b"Vendroid QMP lifecycle regression\n")
            output.truncate(8 * 1024 * 1024)
        before = hashlib.sha256(disk.read_bytes()).hexdigest()
        qmp, monitor = root / "qmp.sock", root / "monitor.sock"
        with (root / "qemu.log").open("w+") as log:
            process = subprocess.Popen([
                "qemu-system-x86_64", "-no-user-config", "-nodefaults",
                "-machine", "pc", "-accel", "tcg", "-m", "128",
                "-display", "none", "-S", "-nic", "none",
                "-device", "qemu-xhci,id=xhci", "-device", "ich9-usb-uhci1,id=uhci",
                "-qmp", f"unix:{qmp},server=on,wait=off",
                "-monitor", f"unix:{monitor},server=on,wait=off",
            ], stdout=log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 10
                while not (qmp.exists() and monitor.exists()):
                    if process.poll() is not None or time.monotonic() >= deadline:
                        raise RuntimeError("QEMU did not expose its control sockets")
                    time.sleep(0.05)
                with QEMUController(str(qmp), str(monitor)) as controller:
                    for bus in ("uhci.0", "xhci.0"):
                        for _ in range(25):
                            controller.add_managed_usb_drive("reuse-usb", file=disk, bus=bus)
                            controller.detach_usb_drive("reuse-usb", managed_node=True)
                assert hashlib.sha256(disk.read_bytes()).hexdigest() == before
                print("Passed 50 immediate USB detach/reattach cycles on UHCI and xHCI")
            except BaseException:
                log.flush()
                log.seek(0)
                print(log.read())
                raise
            finally:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


if __name__ == "__main__":
    main()
