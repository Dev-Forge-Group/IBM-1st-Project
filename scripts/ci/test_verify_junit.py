"""Exercise the false-green outcomes the CI result gate must reject."""
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from verify_junit import REQUIRED_SUITES, verify_reports


class VerifyJUnitTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)

    def report(self, name, outcomes=("passed",)):
        root = ET.Element("testsuite", name=name, tests=str(len(outcomes)))
        for key, outcome in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
            root.set(key, str(outcomes.count(outcome)))
        for index, outcome in enumerate(outcomes):
            case = ET.SubElement(root, "testcase", name=f"case-{index}")
            if outcome != "passed":
                ET.SubElement(case, outcome)
        path = self.directory / f"TEST-{name}.xml"
        ET.ElementTree(root).write(path)
        return path

    def required_reports(self):
        for name in REQUIRED_SUITES:
            self.report(name, ("passed", "passed"))

    def test_passes_executed_integration_suites_and_reports_optional_skips_separately(self):
        self.required_reports()
        self.report("UnitTest", ("passed", "skipped"))
        result = verify_reports(self.directory)
        fixture_tests = 2 * len(REQUIRED_SUITES)
        self.assertEqual((result["tests"], result["passed"], result["skipped"]),
                         (fixture_tests + 2, fixture_tests + 1, 1))
        self.assertEqual(set(result["required_suites"]), set(REQUIRED_SUITES))

    def test_rejects_missing_reports(self):
        with self.assertRaisesRegex(ValueError, "No Gradle JUnit reports"):
            verify_reports(self.directory)

    def test_rejects_zero_test_execution(self):
        self.report("EmptyTest", ())
        with self.assertRaisesRegex(ValueError, "No tests actually executed"):
            verify_reports(self.directory)

    def test_rejects_required_suites_that_are_missing_empty_or_skipped(self):
        for name in REQUIRED_SUITES:
            for state in ("missing", "empty", "skipped"):
                with self.subTest(suite=name, state=state):
                    self.required_reports()
                    self.report("UnitTest")
                    path = self.directory / f"TEST-{name}.xml"
                    if state == "missing":
                        path.unlink()
                    else:
                        self.report(name, () if state == "empty" else ("skipped",))
                    with self.assertRaisesRegex(ValueError, "Required integration suite"):
                        verify_reports(self.directory)

    def test_rejects_failures_and_errors_even_if_another_suite_passes(self):
        self.required_reports()
        for outcome in ("failure", "error"):
            with self.subTest(outcome=outcome):
                self.report("BrokenTest", (outcome,))
                with self.assertRaisesRegex(ValueError, "failed tests or errors"):
                    verify_reports(self.directory)

    def test_rejects_counters_that_claim_tests_ran_without_test_cases(self):
        self.required_reports()
        path = self.report("MissingCases", ())
        root = ET.parse(path).getroot()
        root.set("tests", "99")
        ET.ElementTree(root).write(path)
        with self.assertRaisesRegex(ValueError, "Inconsistent tests count"):
            verify_reports(self.directory)

    def test_rejects_duplicate_suite_names(self):
        self.required_reports()
        source = self.directory / f"TEST-{REQUIRED_SUITES[0]}.xml"
        (self.directory / "TEST-duplicate.xml").write_bytes(source.read_bytes())
        with self.assertRaisesRegex(ValueError, "duplicate test suite"):
            verify_reports(self.directory)

    def test_rejects_malformed_xml(self):
        (self.directory / "TEST-malformed.xml").write_text("<testsuite")
        with self.assertRaises(ET.ParseError):
            verify_reports(self.directory)


if __name__ == "__main__":
    unittest.main()
