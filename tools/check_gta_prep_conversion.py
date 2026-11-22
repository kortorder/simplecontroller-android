#!/usr/bin/env python3
"""Independent fixture assertions. Android decoder/runtime checks are separate."""
import hashlib
import json
import unittest
from pathlib import Path

from convert_gta_prep import SOURCE_SHA256, convert

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "profiles/GTA-6-Prep.native20-source.json"
OUTPUT = ROOT / "profiles/GTA-6-Prep.simplecontroller-profile.json"


class GTAConversionChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source_bytes = SOURCE.read_bytes()
        cls.native = json.loads(cls.source_bytes)["profiles"][0]
        cls.document = json.loads(OUTPUT.read_bytes())
        cls.profile = cls.document["profile"]
        cls.controls = {c["id"]: c for p in cls.profile["pages"] for c in p["controls"]}

    def page(self, name):
        return next(p for p in self.profile["pages"] if p["name"] == name)

    def control(self, name, page="Walk"):
        return next(c for c in self.page(page)["controls"] if c["name"] == name)

    def test_pinned_source_and_deterministic_output(self):
        self.assertEqual(hashlib.sha256(self.source_bytes).hexdigest(), SOURCE_SHA256)
        self.assertEqual(convert(self.source_bytes), self.document)
        self.assertEqual(self.document["fileType"], "simplecontroller-profile")
        self.assertEqual(self.document["transferVersion"], 1)
        self.assertEqual(self.profile["formatVersion"], 11)
        self.assertEqual(self.profile["swipeMode"], "OFF")
        self.assertTrue(self.profile["safeControlArea"])
        self.assertEqual(self.document["profileName"], "GTA 6 Prep")

    def test_changed_or_future_input_is_refused(self):
        with self.assertRaises(ValueError):
            convert(self.source_bytes + b" ")
        with self.assertRaises(ValueError):
            convert(b'{"version":999,"profiles":[]}')

    def test_pages_ids_names_membership_and_home_preserved(self):
        self.assertEqual([p["name"] for p in self.profile["pages"]], ["Walk", "Aim", "Drive", "Extras"])
        self.assertEqual(len(self.controls), 50)
        self.assertEqual(self.profile["homePageId"], self.native["homePageID"])
        for old, new in zip(self.native["pages"], self.profile["pages"]):
            self.assertEqual(old["id"], new["id"])
            self.assertEqual(old["controlIDs"], [c["id"] for c in new["controls"]])
        self.assertEqual({c["id"]: c["name"] for c in self.native["controls"]},
                         {k: c["name"] for k, c in self.controls.items()})

    def test_all_orientation_centers_and_sizes_preserved_in_reference_units(self):
        originals = {c["id"]: c for c in self.native["controls"]}
        for orientation, width, height in [("portrait", 337, 428), ("landscape", 494, 208)]:
            positions = {p["id"]: p["position"] for p in self.native[orientation]}
            for page in self.profile["pages"]:
                geom = page[orientation + "Geometry"]
                self.assertEqual((geom["canvasWidth"], geom["canvasHeight"]), (width, height))
                for cid, rect in geom["controls"].items():
                    old_size = originals[cid]["orientationDimensions"][orientation]
                    self.assertEqual((rect["w"], rect["h"]), (old_size["width"], old_size["height"]))
                    self.assertAlmostEqual((rect["x"] + rect["w"] / 2) / width, positions[cid]["x"])
                    self.assertAlmostEqual((rect["y"] + rect["h"] / 2) / height, positions[cid]["y"])
                    self.assertGreaterEqual(rect["x"], -1e-8)
                    self.assertGreaterEqual(rect["y"], -1e-8)
                    self.assertLessEqual(rect["y"] + rect["h"], height + 1e-8)
                    self.assertLessEqual(rect["x"] + rect["w"], (width / 2 if orientation == "portrait" else width) + 1e-8)

    def test_43_circles_exclude_five_drag_pads_and_two_sticks(self):
        marked = [c for c in self.controls.values() if c["circularButton"]]
        recenter = [c for c in self.controls.values() if c["type"] == "RECENTER"]
        pads = [c for c in self.controls.values() if c.get("buttonAimEnabled")]
        self.assertEqual((len(marked), len(recenter), len(pads)), (41, 2, 5))
        self.assertTrue(all(c["type"] == "BUTTON" for c in marked))
        self.assertTrue(all(not c["circularButton"] for c in recenter + pads))
        for page in self.profile["pages"]:
            for orientation in ("portrait", "landscape"):
                sizes = page[orientation + "Geometry"]["controls"]
                for c in page["controls"]:
                    if c in marked or c in recenter:
                        self.assertEqual((sizes[c["id"]]["w"], sizes[c["id"]]["h"]), (44, 44))

    def test_only_full_forward_stick_repeats_and_run_latch_preserved(self):
        move = self.control("Move - stays")
        self.assertEqual((move["payload"], move["autoCenter"], move["stickPlusMode"]), ("STICK_L", False, True))
        self.assertEqual(move["stickPlusRepeatIntervalMilliseconds"], 200)
        self.assertEqual(move["superBoostThreshold"], 0.95)
        for direction in ("up", "down", "left", "right"):
            for suffix in ("Command", "BoostCommand", "SuperBoostCommand"):
                self.assertEqual(move[direction + suffix], "X360A" if (direction, suffix) == ("up", "SuperBoostCommand") else "")
        self.assertEqual(sum(c["stickPlusRepeatIntervalMilliseconds"] is not None for c in self.controls.values()), 1)
        self.assertEqual((self.control("Look")["payload"], self.control("Look")["autoCenter"]), ("STICK_R", True))
        for name in ("Run A", "Weapons LB"):
            self.assertEqual((self.control(name)["holdToggle"], self.control(name)["holdDurationMs"]), (True, 500))

    def test_trigger_pad_values_and_aim_settings_preserved(self):
        aim = self.control("Aim + fire")
        self.assertEqual(aim["payload"], "LT:0.5,RT:0.7")
        self.assertEqual((aim["buttonAimOutput"], aim["buttonAimStickFullDisplacementPx"], aim["buttonAimStickDeadzonePx"]),
                         ("RIGHT_STICK", 60, 4))
        self.assertTrue(aim["buttonAimAutoCenter"])
        self.assertFalse(aim["buttonAimStickUsesTouchPosition"])
        self.assertFalse(aim["holdToggle"])
        self.assertNotIn("pageAction", aim)
        expected = {"Drive 50%": "RT:0.5", "Drive 100%": "RT:1.0", "Brake 35%": "LT:0.35", "Brake 100%": "LT:1.0"}
        for name, payload in expected.items():
            c = self.control(name, "Drive")
            self.assertEqual(c["payload"], payload)
            self.assertEqual((c["buttonAimOutput"], c["buttonAimStickFullDisplacementPx"]), ("LEFT_STICK", 48))
            self.assertTrue(c["buttonAimAutoCenter"])

    def test_ring_behavior_geometry_and_scoped_references(self):
        zones = [z for p in self.profile["pages"] for z in p["floatingZones"]]
        self.assertEqual(len(zones), 1)
        zone = zones[0]
        self.assertEqual(zone["portraitGeometry"]["zone"], {"x": 4, "y": 372, "w": 48, "h": 44})
        ring = zone["ring"]
        self.assertEqual((ring["innerRadiusDp"], ring["movementRadiusDp"]), (32, 56))
        self.assertFalse(ring["movement"]["autoCenter"])
        self.assertFalse(ring["boost1"]["enabled"])
        self.assertFalse(ring["boost2"]["enabled"])
        self.assertEqual(ring["aim"]["tuning"]["output"], "RIGHT_STICK")
        fire = ring["knobs"][0]["fire"]
        self.assertEqual((fire["mode"], fire["adsPayload"], fire["firePayload"]), ("HOLD", "LT:0.5", "RT:0.7"))
        self.assertTrue(fire["retainAdsOnLift"] and fire["retainFireOnLift"])
        for name, action in [("Stop fire", "STOP_FIRE"), ("Release aim", "RELEASE_ADS")]:
            c = self.control(name, "Aim")
            self.assertEqual((c["floatingLocalAction"], c["floatingActionTargetId"]), (action, zone["id"]))

    def test_page_navigation_and_safety_actions_never_become_game_payloads(self):
        page_ids = {p["id"] for p in self.profile["pages"]}
        for c in self.controls.values():
            if c.get("pageAction"):
                self.assertEqual(c["pageAction"], "GO_TO")
                self.assertIn(c["pageTargetId"], page_ids)
                self.assertEqual(c["payload"], "")
        self.assertEqual(sum(c["payload"] == "RELEASE_ALL" for c in self.controls.values()), 4)


if __name__ == "__main__":
    unittest.main(verbosity=2)
