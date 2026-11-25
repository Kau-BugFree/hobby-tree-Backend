"""Check handoff artifacts, not an implementation of the AI importer."""
import hashlib
import json
import unicodedata
import unittest

from build_item_material_export_cases import DIRECTORY, bundle
from test_item_material_export import E


class SharedScenarioTests(unittest.TestCase):
    def test_committed_bundle_is_reproducible(self):
        expected = bundle()
        self.assertEqual({p.name for p in DIRECTORY.glob("*.json")}, set(expected))
        for filename, data in expected.items():
            with self.subTest(file=filename):
                self.assertEqual((DIRECTORY / filename).read_bytes(), data)

    def test_all_source_exports_have_consistent_counts_order_and_hash(self):
        manifest = json.loads((DIRECTORY / "scenarios.json").read_bytes())
        for sequence, case in enumerate(manifest["sourceCases"], 1):
            with self.subTest(case=case["id"]):
                data = json.loads((DIRECTORY / case["file"]).read_bytes())
                rows = data["payload"]["materials"]
                ids = [r["materialId"] for r in rows]
                self.assertTrue(data["complete"])
                self.assertEqual(data["sourceSequence"], str(sequence))
                self.assertEqual(ids, case["expectedMaterialIds"])
                self.assertEqual(ids, sorted(set(ids), key=int))
                self.assertEqual(data["materialCount"], len(rows))
                self.assertEqual(data["activityCount"], len({r["activityId"] for r in rows}))
                self.assertEqual(data["payloadSha256"], hashlib.sha256(E.canonical(data["payload"])).hexdigest())

    def test_rejected_deliveries_are_distinct_from_valid_replay(self):
        read = lambda filename: json.loads((DIRECTORY / filename).read_bytes())
        baseline = read("08-supported-again.json")
        conflict = read("same-sequence-conflict.json")
        self.assertEqual(conflict["sourceSequence"], baseline["sourceSequence"])
        self.assertNotEqual(conflict["payloadSha256"], baseline["payloadSha256"])
        self.assertEqual(conflict["payloadSha256"], hashlib.sha256(E.canonical(conflict["payload"])).hexdigest())
        tampered = read("hash-mismatch.json")
        self.assertNotEqual(tampered["payloadSha256"], hashlib.sha256(E.canonical(tampered["payload"])).hexdigest())
        self.assertFalse(read("incomplete.json")["complete"])
        self.assertNotEqual(read("unknown-epoch.json")["sourceEpoch"], baseline["sourceEpoch"])
        self.assertLess(int(read("03-rename.json")["sourceSequence"]), int(baseline["sourceSequence"]))

    def test_declared_ai_preview_expectations_preserve_manual_and_shared_keys(self):
        # Validate the handoff table against source membership; this does not invoke an importer.
        manifest = json.loads((DIRECTORY / "scenarios.json").read_bytes())
        manual = set(manifest["initialManualQueries"])
        previous = manual
        for case in manifest["sourceCases"]:
            with self.subTest(case=case["id"]):
                data = json.loads((DIRECTORY / case["file"]).read_bytes())
                keys = {" ".join(unicodedata.normalize("NFKC", r["name"]).casefold().split())
                        for r in data["payload"]["materials"]}
                expected = case["expectedAi"]
                self.assertEqual(set(expected["manualOriginsPreserved"]), manual)
                if not keys:
                    self.assertEqual(expected["decision"], "HOLD_EMPTY")
                    self.assertEqual(set(expected["activeKeysUnchanged"]), previous)
                    continue
                active = keys | manual
                removed = previous - active
                self.assertEqual(set(expected["deactivationCandidates"]), removed)
                self.assertEqual(set(expected["activeKeysAfterApprovedApply"]), active)
                self.assertTrue(set(expected["sharedQueriesPreserved"]) <= keys)
                self.assertEqual(expected["decision"], "REVIEW_DEACTIVATION" if removed else "APPLY")
                previous = active
