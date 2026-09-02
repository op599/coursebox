#!/usr/bin/env python3
"""Build offline CourseBox packs from newconceptenglish.com public sections.

The generator deliberately mirrors published site content. Draft/WIP pages are
kept as draft notices; it does not invent lessons for unpublished material.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import urllib.parse
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path

from bs4 import BeautifulSoup


BASE = "https://newconceptenglish.com/"
FIXED_ZIP_TIME = (2026, 1, 1, 0, 0, 0)


def fetch(page_id: str) -> BeautifulSoup:
    url = urllib.parse.urljoin(BASE, "index.php?" + urllib.parse.urlencode({"id": page_id}))
    req = urllib.request.Request(url, headers={"User-Agent": "CourseBox site packager/1.0"})
    with urllib.request.urlopen(req, timeout=30) as response:
        return BeautifulSoup(response.read(), "html.parser")


def clean_text(node) -> str:
    return " ".join(node.get_text(" ", strip=True).split()) if node else ""


def lesson(lesson_id: str, number: int, title: str, subtitle: str, sections: list[dict]) -> dict:
    return {
        "id": lesson_id,
        "book": 0,
        "lesson": number,
        "title_en": title,
        "title_cn": subtitle,
        "question": "",
        "audio_hash": "",
        "audio_local": "",
        "audio_url": "",
        "lines": [],
        "sections": sections,
    }


def build_search(adb_device: str = "") -> list[dict]:
    if adb_device:
        command = [
            "adb", "-s", adb_device, "exec-out", "run-as",
            "com.wangxiuwen.coursebox", "cat",
            "files/coursebox_library/library_index.json",
        ]
        index = json.loads(subprocess.check_output(command))
        result = []
        for course in index.get("packages", []):
            if course.get("id") not in {"nce1", "nce2", "nce3", "nce4"}:
                continue
            path = course.get("lessons_manifest_path", "")
            if not path:
                continue
            data = subprocess.check_output([
                "adb", "-s", adb_device, "exec-out", "run-as",
                "com.wangxiuwen.coursebox", "cat", path,
            ])
            summaries = {item.get("id"): item for item in course.get("lesson_index", [])}
            for item in json.loads(data):
                row = dict(item)
                summary = summaries.get(row.get("id"), {})
                row.setdefault("title_en", summary.get("title", row.get("id", "")))
                row.setdefault("title_cn", summary.get("subtitle", ""))
                row["id"] = "site-search-" + str(row.get("id", len(result) + 1))
                row["audio_hash"] = ""
                row["video_hash"] = ""
                row["audio_local"] = ""
                row["audio_url"] = ""
                result.append(row)
        if result:
            return result

    rows = []
    number = 0
    for book in range(1, 5):
        soup = fetch(f"nce-{book}")
        seen = set()
        for link in soup.select('a[href*="id="]'):
            href = link.get("href", "")
            match = re.search(r"[?&]id=(%d-\d{3})" % book, href)
            if not match or match.group(1) in seen:
                continue
            seen.add(match.group(1))
            number += 1
            label = clean_text(link)
            title = re.sub(r"^\d-\d{3}\s*", "", label).strip()
            rows.append(lesson(
                f"site-search-{match.group(1)}", number, title or match.group(1),
                f"第{book}册 · {match.group(1)}",
                [{"title": "网站索引", "type": "text", "text": [label]}],
            ))
    return rows


def build_words() -> list[dict]:
    soup = fetch("words")
    rows = []
    for number, section in enumerate(soup.select(".lesson-section"), 1):
        code = section.get("id", f"lesson-{number}").removeprefix("lesson-")
        words = []
        for tr in section.select("tbody tr"):
            cells = tr.select("td")
            if len(cells) < 4:
                continue
            word_cell = cells[0]
            for button in word_cell.select("button"):
                button.decompose()
            words.append({
                "word": clean_text(word_cell),
                "pron": clean_text(cells[1]),
                "pos": clean_text(cells[2]),
                "def": clean_text(cells[3]),
            })
        if words:
            rows.append(lesson(
                f"site-words-{code}", number, code, f"{len(words)} 个单词和短语",
                [{"title": "单词和短语", "type": "words", "words": words}],
            ))
    return rows


def paragraphs_from_article(soup: BeautifulSoup) -> list[str]:
    article = soup.select_one("main article") or soup.select_one("article") or soup.select_one("main")
    if not article:
        return []
    for unwanted in article.select("script, style, nav, audio, video, svg"):
        unwanted.decompose()
    blocks = []
    for node in article.select("h1, h2, h3, h4, p, li, blockquote"):
        value = clean_text(node)
        if value and value not in blocks:
            blocks.append(value)
    return blocks


def build_action() -> list[dict]:
    text = paragraphs_from_article(fetch("wip"))
    return [lesson(
        "site-action-status", 1, "English in Action", "网站当前为建设中栏目",
        [{"title": "网站现有内容", "type": "text", "text": text or ["WIP"]}],
    )]


def build_grammar() -> list[dict]:
    start = fetch("grammar.nce.001.start")
    result = []
    real_ids = []
    for link in start.select('a[href*="id=grammar."]'):
        match = re.search(r"[?&]id=([^&#]+)", link.get("href", ""))
        if match and match.group(1) != "grammar.nce.001.start" and match.group(1) not in real_ids:
            real_ids.append(match.group(1))
    overview = paragraphs_from_article(start)
    result.append(lesson(
        "site-grammar-overview", 1, "Grammar", "新概念英语语法指南（网站草稿）",
        [{"title": "概述", "type": "text", "text": overview}],
    ))
    for page_id in real_ids:
        soup = fetch(page_id)
        body = paragraphs_from_article(soup)
        if not body:
            continue
        title = clean_text(soup.select_one("main article h1, main article h2, main article h3")) or page_id
        result.append(lesson(
            "site-" + re.sub(r"[^a-zA-Z0-9]+", "-", page_id).strip("-").lower(),
            len(result) + 1, title, "来自网站已发布页面",
            [{"title": "正文", "type": "text", "text": body}],
        ))
    return result


def build_ef() -> list[dict]:
    index = fetch("ef")
    result = []
    seen = set()
    for link in index.select('a[href*="id=ef-"]'):
        match = re.search(r"[?&]id=(ef-[^&#]+)", link.get("href", ""))
        if not match:
            continue
        page_id = urllib.parse.unquote(match.group(1))
        if page_id in seen or "wip" in link.get("href", ""):
            continue
        seen.add(page_id)
        soup = fetch(page_id)
        body = paragraphs_from_article(soup)
        if not body:
            continue
        title = clean_text(link) or clean_text(soup.select_one("h1, h2")) or page_id
        result.append(lesson(
            "site-" + re.sub(r"[^a-zA-Z0-9]+", "-", page_id).strip("-").lower(),
            len(result) + 1, title, "扩展阅读",
            [{"title": "正文", "type": "text", "text": body}],
        ))
    return result


def write_pack(output: Path, course_id: str, title: str, description: str, lessons: list[dict]) -> None:
    lessons_bytes = json.dumps(lessons, ensure_ascii=False, separators=(",", ":")).encode()
    digest = hashlib.sha256(lessons_bytes).hexdigest()
    object_path = f"objects/{digest}.json"
    index = [{
        "id": row["id"],
        "title": row["title_en"],
        "subtitle": row["title_cn"],
        "audio_hash": "",
        "video_hash": "",
        "tags": [],
        "metadata": {"book": row["book"], "lesson": row["lesson"]},
    } for row in lessons]
    manifest = {
        "format": "parrot-course-package",
        "version": 1,
        "generated_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "generator": "coursebox build_site_section_packs.py",
        "resources": [{
            "hash": f"sha256:{digest}", "path": object_path,
            "size": len(lessons_bytes), "type": "application/json",
            "origin": "lessons.json", "tags": [],
        }],
        "courses": [{
            "id": course_id, "title": title, "description": description,
            "type": "nce", "lessons_manifest": object_path,
            "lesson_index": index, "metadata": {"source": BASE},
        }],
    }
    manifest_bytes = json.dumps(manifest, ensure_ascii=False, separators=(",", ":")).encode()
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, data in (("manifest.json", manifest_bytes), (object_path, lessons_bytes)):
            info = zipfile.ZipInfo(name, FIXED_ZIP_TIME)
            info.compress_type = zipfile.ZIP_STORED
            archive.writestr(info, data)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("--adb-device", default="", help="read full NCE text for S from this device")
    args = parser.parse_args()
    specs = [
        ("site-search.cx", "site-search", "新概念英语全文检索", "网站 1–4 册课程全文离线检索", lambda: build_search(args.adb_device)),
        ("site-words.cx", "site-words", "新概念英语单词速查", "网站收录的单词和短语", build_words),
        ("site-action.cx", "site-action", "实战英语", "网站当前发布的 English in Action 内容", build_action),
        ("site-grammar.cx", "site-grammar", "新概念英语语法指南", "网站当前已发布的语法内容", build_grammar),
        ("site-ef.cx", "site-ef", "扩展阅读", "网站当前已发布的扩展阅读", build_ef),
    ]
    for filename, course_id, title, description, builder in specs:
        lessons = builder()
        write_pack(args.output / filename, course_id, title, description, lessons)
        print(f"{filename}: {len(lessons)} lessons")


if __name__ == "__main__":
    main()
