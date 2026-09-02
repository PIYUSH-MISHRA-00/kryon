#!/usr/bin/env python3
"""Check the website without a build step.

The site is plain HTML, CSS and JavaScript, which means there is no bundler to catch a
typo'd asset path or a missing meta tag. This script is that check.

Standard library only.
"""

from __future__ import annotations

import re
import sys
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SITE = ROOT / "website"

REQUIRED_FILES = [
    "index.html",
    "docs.html",
    "404.html",
    "robots.txt",
    "sitemap.xml",
    ".nojekyll",
    "assets/style.css",
    "assets/main.js",
    "assets/favicon.svg",
]

#: Pages that must carry full metadata. 404 is deliberately noindex and exempt.
INDEXED_PAGES = ["index.html", "docs.html"]

REQUIRED_META = {
    'name="description"': "a description for search results",
    'name="viewport"': "a viewport tag, or the site is unusable on a phone",
    'property="og:title"': "an Open Graph title for link previews",
    'property="og:description"': "an Open Graph description",
    'property="og:url"': "a canonical Open Graph URL",
    'rel="canonical"': "a canonical link",
}


class Extractor(HTMLParser):
    """Collects local asset references and the accessibility bits worth enforcing."""

    def __init__(self) -> None:
        super().__init__()
        self.local_refs: list[str] = []
        self.images_without_alt = 0
        self.has_h1 = False
        self.has_lang = False
        self.buttons_without_label: list[str] = []
        self._depth_button: list[dict] = []

    def handle_starttag(self, tag: str, attrs_list: list) -> None:
        attrs = dict(attrs_list)

        if tag == "html" and attrs.get("lang"):
            self.has_lang = True
        if tag == "h1":
            self.has_h1 = True
        if tag == "img" and "alt" not in attrs:
            self.images_without_alt += 1

        for attribute in ("href", "src"):
            value = attrs.get(attribute)
            if value and not value.startswith(("http://", "https://", "mailto:", "#", "data:")):
                self.local_refs.append(value)


def check_page(path: Path, problems: list[str]) -> None:
    rel = path.relative_to(ROOT).as_posix()
    text = path.read_text(encoding="utf-8")

    parser = Extractor()
    parser.feed(text)

    if not parser.has_lang:
        problems.append(f"{rel}: <html> needs a lang attribute")
    if not parser.has_h1:
        problems.append(f"{rel}: page has no <h1>")
    if parser.images_without_alt:
        problems.append(f"{rel}: {parser.images_without_alt} <img> without alt text")

    if "<title>" not in text:
        problems.append(f"{rel}: no <title>")

    if path.name in INDEXED_PAGES:
        for needle, why in REQUIRED_META.items():
            if needle not in text:
                problems.append(f"{rel}: missing {needle} -- {why}")

    for ref in parser.local_refs:
        target = ref.split("#", 1)[0].split("?", 1)[0]
        if not target:
            continue
        # Root-relative paths on the 404 page point at the deployed base path.
        candidate = SITE / target.removeprefix("/kryon/") if target.startswith("/") else path.parent / target
        if target.endswith("/"):
            candidate = candidate / "index.html"
        if not candidate.exists():
            problems.append(f"{rel}: reference to a missing file -> {ref}")

    # ARIA tab pattern: every tab must point at a panel that exists.
    for controls in re.findall(r'aria-controls="([^"]+)"', text):
        if f'id="{controls}"' not in text:
            problems.append(f"{rel}: aria-controls={controls!r} names an element that does not exist")


def main() -> int:
    problems: list[str] = []

    if not SITE.is_dir():
        print("FAIL  website/ is missing")
        return 1

    for name in REQUIRED_FILES:
        if not (SITE / name).exists():
            problems.append(f"website/{name}: missing")

    pages = sorted(SITE.glob("*.html"))
    for page in pages:
        check_page(page, problems)

    # No external scripts or stylesheets: the site is self-contained by design, and a
    # third-party host is a third party that can change what the page executes.
    # Canonical and Open Graph URLs are metadata, not loaded resources, so they are fine.
    for page in pages:
        text = page.read_text(encoding="utf-8")
        external = re.finditer(
            r'<script[^>]+src="(https?://[^"]+)"'
            r'|<link[^>]+rel="stylesheet"[^>]+href="(https?://[^"]+)"',
            text,
        )
        for match in external:
            url = match.group(1) or match.group(2)
            problems.append(
                f"{page.relative_to(ROOT).as_posix()}: external resource {url} -- "
                "the site is deliberately self-contained"
            )

    if problems:
        print(f"FAIL  {len(problems)} problem(s) with the website:\n")
        for problem in problems:
            print(f"  - {problem}")
        return 1

    print(f"OK    {len(pages)} pages, all references resolve, no external resources")
    return 0


if __name__ == "__main__":
    sys.exit(main())
