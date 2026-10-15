#!/usr/bin/env python3
"""Writes a copy of a card statement PDF with private text replaced, for use as a test fixture.

The card site draws every word as its own text-showing operation in UTF-16, Hebrew in visual
(reversed) order. A word whose logical or reversed text equals a key of the replacements file is
redrawn with the value, its character spacing adjusted so the new word spans the same width and
text extraction still sees the original columns and word gaps. The signature field is dropped,
since a rewritten file no longer matches it.

The replacements file maps private words to stand-ins, e.g. {"1258": "1234"}; it holds the
private values, so it stays outside the repo. The run fails when a key is never found, when a
stand-in needs a glyph the embedded font subset lacks, or when any key still appears in the
written file.

Usage: scripts/anonymize-statement.py <in.pdf> <out.pdf> <replacements.json>
Requires pypdf.
"""
import json
import sys

from pypdf import PdfReader, PdfWriter
from pypdf.generic import ByteStringObject, ContentStream, FloatObject, NameObject

# The text state a word is drawn with; q/Q save and restore it with the graphics state.
DEFAULT_STATE = {"font": None, "size": 0.0, "tc": 0.0, "tz": 100.0}


def widths(font) -> tuple[dict[int, float], float]:
    """CID to glyph width in 1/1000 text-space units, from the descendant font's W array."""
    cid_font = font["/DescendantFonts"][0].get_object()
    table: dict[int, float] = {}
    w = cid_font.get("/W", [])
    w = w.get_object() if hasattr(w, "get_object") else w
    i = 0
    while i < len(w):
        first = int(w[i])
        if isinstance(w[i + 1].get_object(), list):
            for offset, width in enumerate(w[i + 1].get_object()):
                table[first + offset] = float(width)
            i += 2
        else:
            for cid in range(first, int(w[i + 1]) + 1):
                table[cid] = float(w[i + 2])
            i += 3
    return table, float(cid_font.get("/DW", 1000))


def has_glyphs(font, text: str) -> bool:
    """Whether the subset font draws every character; CID equals the code point here."""
    cid_to_gid = font["/DescendantFonts"][0].get_object()["/CIDToGIDMap"].get_object().get_data()
    for ch in text:
        cid = ord(ch)
        if 2 * cid + 1 >= len(cid_to_gid) or cid_to_gid[2 * cid:2 * cid + 2] == b"\0\0":
            return False
    return True


def orientations(text: str) -> list[str]:
    """Logical order first; Hebrew text is also drawn reversed, digits and Latin never are."""
    hebrew = any("\u0590" <= ch <= "\u05ff" for ch in text)
    return [text, text[::-1]] if hebrew else [text]


def stand_in(text: str, replacements: dict[str, str]) -> tuple[str, str] | None:
    for old, new in replacements.items():
        for drawn, shown in zip(orientations(old), orientations(new)):
            if text == drawn:
                return old, shown
    return None


def rewrite_page(page, writer, replacements, found):
    fonts = page["/Resources"]["/Font"]
    metrics = {name: widths(ref.get_object()) for name, ref in fonts.items()}
    content = ContentStream(page.get_contents(), writer)
    state, saved, out = dict(DEFAULT_STATE), [], []
    for operands, op in content.operations:
        if op == b"q":
            saved.append(dict(state))
        elif op == b"Q":
            state = saved.pop()
        elif op == b"Tf":
            state["font"], state["size"] = operands[0], float(operands[1])
        elif op == b"Tc":
            state["tc"] = float(operands[0])
        elif op == b"Tz":
            state["tz"] = float(operands[0])
        elif op == b"Tj":
            text = bytes(operands[0].original_bytes).decode("utf-16-be")
            hit = stand_in(text, replacements)
            if hit is not None:
                old, new = hit
                found.add(old)
                font = fonts[state["font"]].get_object()
                if not has_glyphs(font, new):
                    sys.exit(f"stand-in {new!r} needs glyphs the font {state['font']} lacks")
                table, default = metrics[state["font"]]
                span = lambda s: sum(table.get(ord(c), default) for c in s) / 1000 * state["size"]
                tc = (span(text) + len(text) * state["tc"] - span(new)) / len(new)
                out.append(([FloatObject(round(tc, 4))], b"Tc"))
                out.append(([ByteStringObject(new.encode("utf-16-be"))], b"Tj"))
                out.append(([FloatObject(state["tc"])], b"Tc"))
                continue
        out.append((operands, op))
    content.operations = out
    page[NameObject("/Contents")] = writer._add_object(content)


def drop_signature(writer):
    root = writer._root_object
    if "/AcroForm" in root:
        del root["/AcroForm"]
    for page in writer.pages:
        annots = page.get("/Annots")
        if annots is not None:
            kept = [a for a in annots if a.get_object().get("/FT") != "/Sig"]
            page[NameObject("/Annots")] = type(annots)(kept)


def shown_words(reader) -> list[str]:
    return [bytes(operands[0].original_bytes).decode("utf-16-be")
            for page in reader.pages
            for operands, op in ContentStream(page.get_contents(), reader).operations
            if op == b"Tj"]


def remaining(path: str, replacements: dict[str, str]) -> set[str]:
    """Keys the written file still draws, or still holds anywhere outside the page text.

    The outside-text search skips keys under four characters, which match unrelated numbers."""
    reader = PdfReader(path)
    left = {hit[0] for word in shown_words(reader)
            if (hit := stand_in(word, replacements)) is not None}
    other = b"".join(
        obj.get_data() if hasattr(obj, "get_data") else repr(obj).encode("utf-8")
        for num in range(1, int(reader.trailer["/Size"]))
        if (obj := reader.get_object(num)) is not None)
    for old in (k for k in replacements if len(k.strip()) >= 4):
        for form in orientations(old.strip()):
            if any(form.encode(enc) in other for enc in ("utf-16-be", "utf-8")):
                left.add(old)
    return left


def main() -> int:
    if len(sys.argv) != 4:
        print(__doc__.strip().splitlines()[-2], file=sys.stderr)
        return 2
    source, target, mapping = sys.argv[1:]
    with open(mapping, encoding="utf-8") as f:
        replacements: dict[str, str] = json.load(f)
    writer = PdfWriter(clone_from=PdfReader(source))
    found: set[str] = set()
    for page in writer.pages:
        rewrite_page(page, writer, replacements, found)
    drop_signature(writer)
    writer.add_metadata({"/Producer": "anonymize-statement.py"})
    writer.compress_identical_objects(remove_duplicates=True, remove_unreferenced=True)
    with open(target, "wb") as f:
        writer.write(f)
    missing = set(replacements) - found
    if missing:
        sys.exit(f"never found: {sorted(missing)}")
    left = remaining(target, replacements)
    if left:
        sys.exit(f"still present in {target}: {sorted(left)}")
    print(f"wrote {target}; replaced {len(found)} words")
    return 0


if __name__ == "__main__":
    sys.exit(main())
