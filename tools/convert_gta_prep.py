#!/usr/bin/env python3
"""Convert the accepted, pinned native fixture, not arbitrary iOS libraries.

Original Android stores rectangle origins in reference pixels; native stores
normalized centers and sizes in points. This conversion preserves reference
geometry numerically. Android fits it to the eventual device canvas; that is
not a claim of identical physical size, gesture distance or phone acceptance.
"""
import argparse
import copy
import hashlib
import json
import re
from pathlib import Path

SOURCE_SHA256 = "60271f4ac6a2b622137f2a719514f3d682c7cdc273a284d1ffda4278ab34e1e9"
FORMAT_VERSION = 11
REFERENCES = {"portrait": (337, 428), "landscape": (494, 208)}


def upper(value):
    return re.sub(r"(?<!^)(?=[A-Z])", "_", value).upper()


def tuning(value):
    return {
        "output": upper(value["output"]), "sensitivity": value["sensitivity"],
        "invertY": value["invertY"], "stickProfile": {
            "linear": "LINEAR", "perAxis": "RESPONSE_CURVE", "radial": "RADIAL_RESPONSE_CURVE"
        }[value["stickResponse"]],
        "mouseProfile": upper(value["mouseResponse"]),
        "stickFullDisplacementPx": value["fullDisplacement"], "stickDeadzonePx": value["deadzone"],
        "stickUsesTouchPosition": value["origin"] == "controlCenter",
        "autoCenter": value["autoCenter"], "haptics": value["hapticsEnabled"],
        "dpadMode": upper(value["dpadMode"]), "dpadOrigin": upper(value["dpadOrigin"]),
        "dpadActivationDistancePx": value["dpadActivationDistance"],
    }


def geometry(control, position, orientation):
    width, height = REFERENCES[orientation]
    size = control["orientationDimensions"][orientation]
    return {"x": position["x"] * width - size["width"] / 2,
            "y": position["y"] * height - size["height"] / 2,
            "w": size["width"], "h": size["height"]}


def control_record(control, portrait):
    kind = control["kind"]
    target_type = {"button": "BUTTON", "pageButton": "BUTTON", "recenter": "RECENTER",
                   "leftStick": "STICK", "rightStick": "STICK"}[kind]
    payload = control["payload"] or ""
    if kind in ("leftStick", "rightStick"):
        payload = "STICK_L" if kind == "leftStick" else "STICK_R"
    elif kind == "recenter":
        payload = "RECENTER"
    result = dict(portrait, id=control["id"], type=target_type, name=control["name"], payload=payload)
    result.update(
        holdToggle=control["buttonBehavior"]["holdToggle"],
        holdDurationMs=control["buttonBehavior"]["holdMilliseconds"],
        autoTapEnabled=control["autoTapEnabled"], autoTapIntervalMs=control["autoTapIntervalMilliseconds"],
        autoCenter=control["stickBehavior"]["autoCenter"],
        sensitivity=control["stickResponse"]["sensitivity"],
        excludeFromCameraFollow=control["excludeFromCameraFollow"],
        cameraFollowStrengthPercent=control["cameraFollowStrengthPercent"],
        mouseSuppressCameraFollow=control["mouseSuppressCameraFollow"],
        swipeActivate=control["swipeActivationEnabled"],
        circularButton=target_type == "BUTTON" and not control["buttonAim"]["enabled"],
        stickPlusRepeatIntervalMilliseconds=control["stickPlusRepeatIntervalMilliseconds"],
    )
    if kind in ("leftStick", "rightStick"):
        behavior = control["stickBehavior"]
        result.update(directionalMode=control["directionalEnabled"], stickPlusMode=behavior["stickPlusEnabled"],
                      boostThreshold=behavior["boostThreshold"], superBoostThreshold=behavior["superBoostThreshold"])
        for direction, mapping in behavior["directions"].items():
            for stage, suffix in [("normal", "Command"), ("boost", "BoostCommand"), ("superBoost", "SuperBoostCommand")]:
                actions = mapping[stage + "Controls"]
                # The pinned profile has exactly one nonempty stage: Up/A.
                assert actions in ([], ["a"])
                result[direction + suffix] = "X360A" if actions else ""
    navigation = control["pageNavigation"]
    if navigation:
        assert navigation["action"] == "goTo"
        result.update(pageAction="GO_TO", pageTargetId=navigation["targetPageID"])
    action = control["floatingAction"]
    if action:
        assert action["target"]["kind"] == "zone"
        result.update(floatingLocalAction={"stopFire": "STOP_FIRE", "releaseADS": "RELEASE_ADS"}[action["action"]],
                      floatingActionTargetId=action["target"]["id"])
    aim = control["buttonAim"]
    if aim["enabled"]:
        assert not aim["alternate"]["enabled"]
        result.update(buttonAimEnabled=True, buttonAimPayloadTiming="IMMEDIATE",
                      buttonAimCancelOnBoundaryExit=aim["cancelOnBoundaryExit"],
                      buttonAimRearmOnBoundaryReturn=aim["rearmOnBoundaryReturn"],
                      buttonAimReleaseDelayMs=aim["releaseDelayMilliseconds"])
        for key, value in tuning(aim["tuning"]).items():
            result["buttonAim" + key[0].upper() + key[1:]] = value
    return result


def ring_record(zone):
    config = zone["config"]
    movement = copy.deepcopy(config["movement"])
    movement["output"] = upper(movement["output"])
    movement["profile"] = {"linear": "LINEAR", "perAxis": "RESPONSE_CURVE", "radial": "RADIAL_RESPONSE_CURVE"}[movement.pop("response")]
    aim = copy.deepcopy(config["aim"])
    aim["tuning"] = tuning(aim["tuning"])
    aim["fine"]["qualifyingStages"] = [s.upper() for s in aim["fine"]["qualifyingStages"]]
    knobs = []
    for native in config["knobs"]:
        knob = copy.deepcopy(native)
        knob["targetDiameterDp"] = knob.pop("targetDiameter")
        fire = knob["fire"]
        renames = {"pulsePressMilliseconds": "pulsePressMs", "pulseGapMilliseconds": "pulseGapMs",
                   "retainADSOnLift": "retainAdsOnLift", "continueADSOutside": "continueAdsOutside"}
        for old, new in renames.items():
            fire[new] = fire.pop(old)
        fire["mode"] = upper(fire["mode"])
        fire["returnFire"]["delayMs"] = fire["returnFire"].pop("delayMilliseconds")
        fire["returnFire"]["qualifyingStages"] = [s.upper() for s in fire["returnFire"]["qualifyingStages"]]
        knobs.append(knob)
    def boost(native):
        out = copy.deepcopy(native)
        for old, new in {"distance": "distanceDp", "pulsePressMilliseconds": "pulsePressMs",
                         "pulseGapMilliseconds": "pulseGapMs", "activationDelayMilliseconds": "activationDelayMs",
                         "hysteresis": "hysteresisDp", "directInnerDistance": "directInnerDistanceDp",
                         "directOuterDistance": "directOuterDistanceDp"}.items():
            out[new] = out.pop(old)
        out["action"] = upper(out["action"])
        return out
    ring = dict(innerRadiusDp=config["innerRadius"], movementRadiusDp=config["movementRadius"],
                movement=movement, aim=aim, knobs=knobs, boost1=boost(config["boost1"]), boost2=boost(config["boost2"]),
                startRole=upper(config["startRole"]), startKnobId=config["startKnobID"],
                directBoostToAim=config["directBoostToAim"], directBoostAimKnobId=config["directBoostAimKnobID"],
                directBoostTwoToOne=config["directBoostTwoToOne"])
    out = {"id": zone["id"], "name": zone["name"], "kind": "RING", "enabled": zone["enabled"], "ring": ring}
    for orientation in REFERENCES:
        original = zone[orientation + "Geometry"]
        rectangle = original["zone"]
        out[orientation + "Geometry"] = dict(canvasWidth=original["canvasWidth"], canvasHeight=original["canvasHeight"],
                                             zone={"x": rectangle["x"], "y": rectangle["y"], "w": rectangle["width"], "h": rectangle["height"]})
    return out


def convert(source):
    if hashlib.sha256(source).hexdigest() != SOURCE_SHA256:
        raise ValueError("Input is not the exact accepted native GTA 6 Prep fixture; no output was written.")
    native = json.loads(source)
    assert native["version"] == 20 and len(native["profiles"]) == 1
    profile = native["profiles"][0]
    assert not profile["globalHoldEnabled"] and not profile["turboEnabled"] and profile["swipeMode"] == "off"
    controls = {c["id"]: c for c in profile["controls"]}
    positions = {o: {p["id"]: p["position"] for p in profile[o]} for o in REFERENCES}
    pages = []
    for page in profile["pages"]:
        geometries = {o: {cid: geometry(controls[cid], positions[o][cid], o) for cid in page["controlIDs"]} for o in REFERENCES}
        result = {"id": page["id"], "name": page["name"],
                  "controls": [control_record(controls[cid], geometries["portrait"][cid]) for cid in page["controlIDs"]],
                  "floatingZones": [ring_record(z) for z in page["floatingRingZones"]]}
        assert not page["floatingButtonZones"]
        for o, (w, h) in REFERENCES.items():
            result[o + "Geometry"] = dict(canvasWidth=w, canvasHeight=h, controls=geometries[o])
        pages.append(result)
    return {"fileType": "simplecontroller-profile", "transferVersion": 1,
            "profileName": profile["name"], "profile": {"formatVersion": FORMAT_VERSION,
            "homePageId": profile["homePageID"], "swipeMode": "OFF", "safeControlArea": True, "pages": pages}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.source.resolve() == args.output.resolve():
        parser.error("Output must not overwrite the source")
    document = convert(args.source.read_bytes())
    data = (json.dumps(document, indent=2, ensure_ascii=False, allow_nan=False) + "\n").encode()
    args.output.write_bytes(data)
    print("Created Android format11 profile:4 pages/50 controls/1 ring; " + str(len(data)) + " bytes")
    print("SHA256 " + hashlib.sha256(data).hexdigest())


if __name__ == "__main__":
    main()
