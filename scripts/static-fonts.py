#!/usr/bin/env python3
"""Generate Android 7.1 font instances from the bundled OFL variable fonts.

Run from any directory with fontTools 4.61.1 installed. Original variable
fonts remain unchanged. Both fonts retain their existing OFL notices.
"""
from pathlib import Path

from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

FONT_DIR = Path(__file__).resolve().parents[1] / "app/src/main/res/font"
INSTANCES = (
    ("literata", "literata_clock_light", {"wght": 300, "opsz": 44}),
    ("literata", "literata_title_regular", {"wght": 400, "opsz": 30}),
    ("literata", "literata_title_light", {"wght": 300, "opsz": 30}),
    ("instrument_sans", "instrument_sans_regular", {"wght": 400, "wdth": 100}),
    ("instrument_sans", "instrument_sans_medium", {"wght": 500, "wdth": 100}),
    ("instrument_sans", "instrument_sans_semibold", {"wght": 600, "wdth": 100}),
)


def main():
    for source, target, requested in INSTANCES:
        font = TTFont(FONT_DIR / f"{source}.ttf", recalcTimestamp=False)
        axes = {axis.axisTag: axis.defaultValue for axis in font["fvar"].axes}
        axes.update({key: value for key, value in requested.items() if key in axes})
        instance = instantiateVariableFont(font, axes, inplace=False)
        if "fvar" in instance:
            raise RuntimeError(f"{target} still has variable axes")
        if instance.getBestCmap() != font.getBestCmap():
            raise RuntimeError(f"{target} lost character coverage")
        if source == "literata":
            features = {record.FeatureTag for record in instance["GSUB"].table.FeatureList.FeatureRecord}
            if "tnum" not in features:
                raise RuntimeError(f"{target} lost tabular figures")
        instance.save(FONT_DIR / f"{target}.ttf")
        print(f"{target}.ttf: static, character coverage preserved")


if __name__ == "__main__":
    main()
