"""Reject missing, empty, failed or skipped required Gradle JUnit test suites."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

REQUIRED_SUITES = (
    "org.myweb.uniplace.domain.community.application.CommunityPrivacyIntegrationTest",
    "org.myweb.uniplace.domain.ai.application.tool.AiDataContractMySqlTest",
    "org.myweb.uniplace.domain.commoncode.application.CommonCodeLookupIntegrationTest",
    "org.myweb.uniplace.domain.user.application.NotificationRecipientsIntegrationTest",
    "org.myweb.uniplace.domain.contract.application.ReviewContractHistoryIntegrationTest",
    "org.myweb.uniplace.domain.reservation.application.SpaceReservationConcurrencyIntegrationTest",
    "org.myweb.uniplace.domain.reservation.application.ReservationAvailabilityIntegrationTest",
    "org.myweb.uniplace.domain.reservation.api.admin.AdminTourRestorationIntegrationTest",
    "org.myweb.uniplace.domain.reservation.api.TourTelephoneCompatibilityIntegrationTest",
)
COUNTERS = ("tests", "failures", "errors", "skipped")


def verify_reports(directory: Path) -> dict:
    reports = sorted(directory.glob("TEST-*.xml"))
    if not reports:
        raise ValueError("No Gradle JUnit reports were produced")
    totals = dict.fromkeys(COUNTERS, 0)
    suites = {}
    for path in reports:
        root = ET.parse(path).getroot()
        name = root.get("name", "")
        if root.tag != "testsuite" or not name or name in suites:
            raise ValueError(f"Invalid or duplicate test suite in {path.name}")
        cases = root.findall("testcase")
        counts = {
            "tests": len(cases),
            "failures": sum(case.find("failure") is not None for case in cases),
            "errors": sum(case.find("error") is not None for case in cases),
            "skipped": sum(case.find("skipped") is not None for case in cases),
        }
        for counter in COUNTERS:
            if int(root.get(counter, "0")) != counts[counter]:
                raise ValueError(f"Inconsistent {counter} count in {path.name}")
            totals[counter] += counts[counter]
        suites[name] = counts
    if totals["failures"] or totals["errors"]:
        raise ValueError("JUnit reports contain failed tests or errors")
    if totals["tests"] - totals["skipped"] <= 0:
        raise ValueError("No tests actually executed")
    for name in REQUIRED_SUITES:
        counts = suites.get(name)
        if counts is None or counts["tests"] <= 0 or counts["skipped"]:
            raise ValueError(f"Required integration suite did not fully execute: {name}")
    return {
        "files": len(reports),
        **totals,
        "passed": totals["tests"] - totals["skipped"],
        "required_suites": {name: suites[name]["tests"] for name in REQUIRED_SUITES},
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="Fresh Gradle build/test-results/test directory")
    args = parser.parse_args()
    try:
        summary = verify_reports(args.directory)
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"JUnit verification failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
