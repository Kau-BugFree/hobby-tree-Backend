"""Rebuild synthetic shared files only; never connects to a database or AI."""
from copy import deepcopy
from datetime import timedelta
import hashlib
import json

from test_item_material_export import E, EPOCH, NOW, ROOT

DIRECTORY = ROOT / "docs/examples/item-sync-v1"
MANUAL = ["독서등", "수동 유지"]
INITIAL = {
    "hobbies": [[1, True], [2, True], [3, True], [9, False]],
    "activities": [[11, 1, "PUBLISHED"], [12, 1, "PUBLISHED"],
                   [21, 2, "PUBLISHED"], [31, 3, "PUBLISHED"],
                   [41, 1, "DRAFT"], [91, 9, "PUBLISHED"]],
    "materials": [[101, 11, " 계량스푼 "], [102, 12, "계량스푼"],
                  [201, 21, "피크"], [301, 31, "책갈피"],
                  [401, 41, "비공개 준비물"], [901, 91, "미지원 준비물"]],
}


def bundle():
    # Expected versions are explicit: V18 coalesces changes within each source transaction.
    rows = [[101, 11, 1, 1, " 계량스푼 "], [102, 12, 1, 1, "계량스푼"],
            [201, 21, 2, 1, "피크"], [301, 31, 3, 1, "책갈피"]]
    changes = [
        ("01-initial", None, rows, [], []),
        ("02-add", {"operation": "addMaterial", "id": 302, "activityId": 31, "name": "독서등"},
         [*deepcopy(rows[:3]), [301, 31, 3, 2, "책갈피"], [302, 31, 3, 2, "독서등"]], [], []),
    ]
    rows = deepcopy(changes[-1][2])
    rows[2] = [201, 21, 2, 2, "기타 피크"]
    changes.append(("03-rename", {"operation": "renameMaterial", "id": 201, "name": "기타 피크"},
                    deepcopy(rows), ["피크"], []))
    rows = [r for r in rows if r[1] != 11]
    changes.append(("04-hide-shared", {"operation": "setReviewStatus", "id": 11, "status": "DRAFT"},
                    deepcopy(rows), [], ["계량스푼"]))
    rows = [r for r in rows if r[1] != 12]
    changes.append(("05-hide-last-reference", {"operation": "setReviewStatus", "id": 12, "status": "DRAFT"},
                    deepcopy(rows), ["계량스푼"], []))
    rows = [[101, 11, 1, 3, " 계량스푼 "], *rows]
    changes.append(("06-republish", {"operation": "setReviewStatus", "id": 11, "status": "PUBLISHED"},
                    deepcopy(rows), [], []))
    rows = [r for r in rows if r[2] != 2]
    changes.append(("07-unsupported", {"operation": "setSupported", "id": 2, "supported": False},
                    deepcopy(rows), ["기타 피크"], []))
    rows = sorted([*rows, [201, 21, 2, 4, "기타 피크"]])
    changes.append(("08-supported-again", {"operation": "setSupported", "id": 2, "supported": True},
                    deepcopy(rows), [], []))
    changes.append(("09-empty", {"operation": "disableAllHobbies"}, [], [], []))

    files = {}
    cases = []
    last_active = sorted(MANUAL)
    for seq, (name, change, expected_rows, deactivations, shared) in enumerate(changes, 1):
        data = E.build_document(expected_rows, NOW + timedelta(minutes=seq), "synthetic-hobbytree",
                                EPOCH, seq, export_id=f"22222222-2222-4222-8222-{seq:012d}")
        filename = name + ".json"
        files[filename] = data
        active = sorted(set(MANUAL + [r[4].strip() for r in expected_rows]))
        expected = {
            "decision": "HOLD_EMPTY" if not expected_rows else (
                "REVIEW_DEACTIVATION" if deactivations else "APPLY"),
            "deactivationCandidates": deactivations,
            "manualOriginsPreserved": MANUAL,
            "sharedQueriesPreserved": shared,
        }
        if expected_rows:
            expected["activeKeysAfterApprovedApply"] = active
            last_active = active
        else:
            expected["activeKeysUnchanged"] = last_active
        cases.append({"id": name, "file": filename, "sourceChange": change,
                      "expectedMaterialIds": [str(r[0]) for r in expected_rows],
                      "expectedAi": expected})

    baseline = json.loads(files["08-supported-again.json"])
    invalid = {}
    conflict = deepcopy(baseline)
    conflict["payload"]["materials"][0]["name"] = "다른 내용"
    conflict["payloadSha256"] = hashlib.sha256(E.canonical(conflict["payload"])).hexdigest()
    invalid["same-sequence-conflict.json"] = conflict
    incomplete = deepcopy(baseline)
    incomplete["complete"] = False
    invalid["incomplete.json"] = incomplete
    tampered = deepcopy(baseline)
    tampered["payload"]["materials"][0]["name"] = "변조된 내용"
    invalid["hash-mismatch.json"] = tampered
    unknown_epoch = deepcopy(baseline)
    unknown_epoch["sourceEpoch"] = "33333333-3333-4333-8333-333333333333"
    invalid["unknown-epoch.json"] = unknown_epoch
    for filename, value in invalid.items():
        files[filename] = E.canonical(value) + b"\n"
    deliveries = [
        ("08-supported-again.json", "NOOP_REPLAY"),
        ("03-rename.json", "REJECT_STALE"),
        ("same-sequence-conflict.json", "REJECT_SEQUENCE_CONFLICT"),
        ("incomplete.json", "REJECT_INCOMPLETE"),
        ("hash-mismatch.json", "REJECT_HASH"),
        ("unknown-epoch.json", "REJECT_EPOCH"),
    ]
    manifest = {
        "syntheticOnly": True,
        "initialSource": INITIAL,
        "initialManualQueries": MANUAL,
        "sourceCases": cases,
        "deliveryBaseline": "08-supported-again.json",
        "deliveryCases": [{"file": f, "expectedDecision": d, "catalogUnchanged": True,
                           "acceptedSequenceUnchanged": "8"} for f, d in deliveries],
        "note": "AI expected decisions are assertions to implement, not evidence of a working importer. Review cases assume explicit synthetic approval before the next source case. Empty and rejected deliveries never advance accepted sequence.",
    }
    files["scenarios.json"] = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    return files


if __name__ == "__main__":
    DIRECTORY.mkdir(parents=True, exist_ok=True)
    for filename, data in bundle().items():
        (DIRECTORY / filename).write_bytes(data)
