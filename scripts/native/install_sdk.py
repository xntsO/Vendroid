"""Retry failed SDK package installs without changing packages or deleting SDK state."""

import argparse
import subprocess
import sys
import time


MAX_ATTEMPTS = 3
ATTEMPT_TIMEOUT_SECONDS = 300


def install_sdk(packages):
    command = ["sdkmanager", *packages]
    for attempt in range(1, MAX_ATTEMPTS + 1):
        print(f"Installing Android SDK packages (attempt {attempt}/{MAX_ATTEMPTS})", flush=True)
        try:
            # sdkmanager owns its partial downloads; each retry requests the same pinned packages.
            subprocess.run(command, check=True, timeout=ATTEMPT_TIMEOUT_SECONDS)
            return
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            if attempt == MAX_ATTEMPTS:
                raise
            delay = 5 * attempt
            print(f"SDK installation failed; retrying in {delay} seconds", flush=True)
            time.sleep(delay)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("packages", nargs="+", help="Pinned sdkmanager package identifiers")
    arguments = parser.parse_args(argv)
    try:
        install_sdk(arguments.packages)
    except subprocess.CalledProcessError as error:
        print("SDK installation failed after all attempts", file=sys.stderr)
        return error.returncode if error.returncode > 0 else 1
    except subprocess.TimeoutExpired:
        print("SDK installation timed out after all attempts", file=sys.stderr)
        return 124
    return 0


if __name__ == "__main__":
    sys.exit(main())
