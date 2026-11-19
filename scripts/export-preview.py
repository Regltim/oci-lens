#!/usr/bin/env python3
from pathlib import Path
import re


root = Path(__file__).resolve().parent.parent
bundle = root / "web" / "dist-demo"
output = root / "design" / "previews" / "oci-lens-implementation.html"
html = (bundle / "index.html").read_text(encoding="utf-8")

for pattern, tag in [
    (r'<script type="module" crossorigin src="(?:\./|/)(assets/[^\"]+)"></script>', "script"),
    (r'<link rel="stylesheet" crossorigin href="(?:\./|/)(assets/[^\"]+)">', "style"),
]:
    def inline(match):
        contents = (bundle / match[1]).read_text(encoding="utf-8")
        if re.search(r"</" + tag, contents, flags=re.I):
            raise SystemExit(f"Unexpected closing {tag} tag in {match[1]}")
        opening = '<script type="module">' if tag == "script" else "<style>"
        return f"{opening}\n{contents}\n</{tag}>"

    html, count = re.subn(pattern, inline, html)
    if count != 1:
        raise SystemExit(f"Expected one {tag} asset. Run pnpm build:demo in web first.")

output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(html, encoding="utf-8")
print(output)
