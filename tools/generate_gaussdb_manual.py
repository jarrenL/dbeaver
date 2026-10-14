#!/usr/bin/env python3
"""Build the customer-facing GaussDB DBeaver Word manual from Markdown.

The generator intentionally supports only the Markdown constructs used by
GAUSSDB_GUI_OPERATION_MANUAL_20260922.md.  It also creates cropped copies of
the real GUI evidence so the Word/PDF versions never contain the black desktop
padding captured by the headless Linux desktop.
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path

from PIL import Image, ImageDraw
from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.style import WD_STYLE_TYPE
from docx.enum.table import WD_CELL_VERTICAL_ALIGNMENT, WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Inches, Pt, RGBColor


BLUE = "17365D"
ACCENT = "2F75B5"
LIGHT_BLUE = "EAF2F8"
LIGHT_GRAY = "F2F2F2"
BODY_CJK = "Arial Unicode MS"
LATIN = "Arial"
MONO = "Courier New"


SCREENSHOTS = {
    "language-config.png": "docs/gaussdb/images/language-config.png",
    "debug-source.png": "docs/gaussdb/images/debug-source.png",
    "debug-step-variables.png": "docs/gaussdb/evidence/debug-final-x86-20260930/step-variables.png",
    "call-stack.png": "docs/gaussdb/images/call-stack.png",
    "debug-transaction-choice.png": "docs/gaussdb/evidence/debug-final-x86-20260930/transaction-choice.png",
    "package-compile-menu.png": "docs/gaussdb/evidence/package-final-x86-20260930/package-final-x86-menu.png",
    "package-errors.png": "docs/gaussdb/evidence/package-final-x86-20260930/package-final-x86-error-dialog.png",
    "package-error-location.png": "docs/gaussdb/evidence/package-final-x86-20260930/package-final-x86-error-location.png",
    "package-delete-confirm.png": "docs/gaussdb/evidence/package-final-x86-20260930/package-final-x86-delete-confirm.png",
}

# A few screenshot/section boundaries need deterministic treatment in Word.
# Keep these local to the customer manual instead of changing the global page
# margins or shrinking every figure, which would reflow otherwise-stable pages.
SCREENSHOT_MAX_WIDTH_CM = {
    # Leave enough room below the language screenshot for the final 2.3 note
    # and the footer.  At the default width LibreOffice places the sentence's
    # last line inside the footer area.
    "language-config.png": 15.8,
}

PAGE_BREAK_BEFORE_HEADINGS = {
    "T08：添加监视变量",
    "T13：编译全部、声明、主体",
}


def set_run_font(run, size: float | None = None, bold: bool | None = None, mono: bool = False) -> None:
    font_name = MONO if mono else LATIN
    run.font.name = font_name
    run._element.get_or_add_rPr().rFonts.set(qn("w:eastAsia"), BODY_CJK)
    run._element.get_or_add_rPr().rFonts.set(qn("w:ascii"), font_name)
    run._element.get_or_add_rPr().rFonts.set(qn("w:hAnsi"), font_name)
    if size is not None:
        run.font.size = Pt(size)
    if bold is not None:
        run.bold = bold


def set_cell_shading(cell, fill: str) -> None:
    tc_pr = cell._tc.get_or_add_tcPr()
    shd = tc_pr.find(qn("w:shd"))
    if shd is None:
        shd = OxmlElement("w:shd")
        tc_pr.append(shd)
    shd.set(qn("w:fill"), fill)


def set_cell_margins(cell, top: int = 90, start: int = 110, bottom: int = 90, end: int = 110) -> None:
    tc = cell._tc
    tc_pr = tc.get_or_add_tcPr()
    tc_mar = tc_pr.first_child_found_in("w:tcMar")
    if tc_mar is None:
        tc_mar = OxmlElement("w:tcMar")
        tc_pr.append(tc_mar)
    for margin, value in (("top", top), ("start", start), ("bottom", bottom), ("end", end)):
        node = tc_mar.find(qn(f"w:{margin}"))
        if node is None:
            node = OxmlElement(f"w:{margin}")
            tc_mar.append(node)
        node.set(qn("w:w"), str(value))
        node.set(qn("w:type"), "dxa")


def add_page_number(paragraph) -> None:
    paragraph.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = paragraph.add_run()
    begin = OxmlElement("w:fldChar")
    begin.set(qn("w:fldCharType"), "begin")
    instruction = OxmlElement("w:instrText")
    instruction.set(qn("xml:space"), "preserve")
    instruction.text = " PAGE "
    separate = OxmlElement("w:fldChar")
    separate.set(qn("w:fldCharType"), "separate")
    end = OxmlElement("w:fldChar")
    end.set(qn("w:fldCharType"), "end")
    run._r.extend([begin, instruction, separate, end])
    set_run_font(run, 9)


def configure_document(doc: Document) -> None:
    section = doc.sections[0]
    section.page_width = Cm(21)
    section.page_height = Cm(29.7)
    section.top_margin = Cm(1.7)
    section.bottom_margin = Cm(1.7)
    section.left_margin = Cm(1.8)
    section.right_margin = Cm(1.8)

    styles = doc.styles
    normal = styles["Normal"]
    normal.font.name = LATIN
    normal._element.rPr.rFonts.set(qn("w:eastAsia"), BODY_CJK)
    normal.font.size = Pt(10.5)
    normal.paragraph_format.space_after = Pt(5)
    normal.paragraph_format.line_spacing = 1.25

    for name, size, color in (
        ("Title", 26, BLUE),
        ("Subtitle", 13, "666666"),
        ("Heading 1", 18, BLUE),
        ("Heading 2", 14, ACCENT),
        ("Heading 3", 12, ACCENT),
    ):
        style = styles[name]
        style.font.name = LATIN
        style._element.rPr.rFonts.set(qn("w:eastAsia"), BODY_CJK)
        style.font.size = Pt(size)
        style.font.color.rgb = RGBColor.from_string(color)
        if name != "Subtitle":
            style.font.bold = True
        style.paragraph_format.keep_with_next = True
        style.paragraph_format.space_before = Pt(10)
        style.paragraph_format.space_after = Pt(6)

    for list_name in ("List Bullet", "List Number"):
        style = styles[list_name]
        style.font.name = LATIN
        style._element.rPr.rFonts.set(qn("w:eastAsia"), BODY_CJK)
        style.font.size = Pt(10.5)
        style.paragraph_format.space_after = Pt(3)

    if "Screenshot Caption" not in styles:
        style = styles.add_style("Screenshot Caption", WD_STYLE_TYPE.PARAGRAPH)
        style.font.name = LATIN
        style._element.rPr.rFonts.set(qn("w:eastAsia"), BODY_CJK)
        style.font.size = Pt(9)
        style.font.color.rgb = RGBColor.from_string("555555")
        style.paragraph_format.alignment = WD_ALIGN_PARAGRAPH.CENTER
        style.paragraph_format.space_after = Pt(8)
        style.paragraph_format.keep_with_next = False

    header = section.header.paragraphs[0]
    header.text = "GaussDB 适配版 DBeaver · 调试与 Package 操作手册"
    header.alignment = WD_ALIGN_PARAGRAPH.RIGHT
    for run in header.runs:
        set_run_font(run, 8)
        run.font.color.rgb = RGBColor.from_string("777777")
    add_page_number(section.footer.paragraphs[0])


def crop_screenshots(repo_root: Path) -> None:
    output_dir = repo_root / "docs/gaussdb/images/manual"
    output_dir.mkdir(parents=True, exist_ok=True)
    for output_name, source_rel in SCREENSHOTS.items():
        source = repo_root / source_rel
        if not source.is_file():
            raise FileNotFoundError(source)
        image = Image.open(source).convert("RGB")
        # Headless captures place the complete DBeaver window at the top-left
        # and fill the unused desktop area with pure black.  Use a conservative
        # near-black mask, retain a small border, and never crop within the app.
        mask = image.convert("L").point(lambda value: 255 if value > 12 else 0)
        bbox = mask.getbbox()
        if bbox is None:
            raise ValueError(f"Screenshot is empty: {source}")
        left, top, right, bottom = bbox
        left = max(0, left - 2)
        top = max(0, top - 2)
        right = min(image.width, right + 2)
        bottom = min(image.height, bottom + 2)
        cropped = image.crop((left, top, right, bottom))
        canvas = Image.new("RGB", (cropped.width + 4, cropped.height + 4), "white")
        canvas.paste(cropped, (2, 2))
        if output_name == "package-compile-menu.png":
            # This evidence was captured while a context menu extended below
            # the maximized DBeaver window.  Preserve the menu itself and
            # replace only the exposed black desktop on both sides so the
            # customer manual shows a clean full-window screenshot.
            draw = ImageDraw.Draw(canvas)
            app_bottom = min(724, canvas.height)
            menu_left = min(174, canvas.width)
            menu_right = min(439, canvas.width)
            draw.rectangle((0, app_bottom, menu_left - 1, canvas.height), fill="white")
            draw.rectangle((menu_right, app_bottom, canvas.width, canvas.height), fill="white")
        canvas.save(output_dir / output_name, format="PNG", optimize=True)


INLINE_TOKEN = re.compile(r"(\*\*[^*]+\*\*|`[^`]+`|https?://\S+)")


def add_hyperlink(paragraph, text: str, url: str) -> None:
    part = paragraph.part
    relationship_id = part.relate_to(
        url,
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink",
        is_external=True,
    )
    hyperlink = OxmlElement("w:hyperlink")
    hyperlink.set(qn("r:id"), relationship_id)
    run = OxmlElement("w:r")
    r_pr = OxmlElement("w:rPr")
    color = OxmlElement("w:color")
    color.set(qn("w:val"), "0563C1")
    underline = OxmlElement("w:u")
    underline.set(qn("w:val"), "single")
    r_fonts = OxmlElement("w:rFonts")
    r_fonts.set(qn("w:ascii"), LATIN)
    r_fonts.set(qn("w:hAnsi"), LATIN)
    r_fonts.set(qn("w:eastAsia"), BODY_CJK)
    r_pr.extend([r_fonts, color, underline])
    run.append(r_pr)
    text_node = OxmlElement("w:t")
    text_node.text = text
    run.append(text_node)
    hyperlink.append(run)
    paragraph._p.append(hyperlink)


def add_inline(paragraph, text: str, size: float | None = None) -> None:
    cursor = 0
    for match in INLINE_TOKEN.finditer(text):
        if match.start() > cursor:
            run = paragraph.add_run(text[cursor : match.start()])
            set_run_font(run, size)
        token = match.group(0)
        if token.startswith("**"):
            run = paragraph.add_run(token[2:-2])
            set_run_font(run, size, bold=True)
        elif token.startswith("`"):
            run = paragraph.add_run(token[1:-1])
            set_run_font(run, size, mono=True)
            run.font.color.rgb = RGBColor.from_string("8B1A1A")
        else:
            url = token.rstrip(".,，。；;)")
            suffix = token[len(url) :]
            add_hyperlink(paragraph, url, url)
            if suffix:
                run = paragraph.add_run(suffix)
                set_run_font(run, size)
        cursor = match.end()
    if cursor < len(text):
        run = paragraph.add_run(text[cursor:])
        set_run_font(run, size)


def add_code_block(doc: Document, lines: list[str]) -> None:
    table = doc.add_table(rows=1, cols=1)
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = True
    cell = table.cell(0, 0)
    set_cell_shading(cell, LIGHT_GRAY)
    set_cell_margins(cell, 90, 120, 90, 120)
    paragraph = cell.paragraphs[0]
    paragraph.paragraph_format.space_after = Pt(0)
    paragraph.paragraph_format.line_spacing = 1.0
    for index, line in enumerate(lines):
        run = paragraph.add_run(("\n" if index else "") + line)
        set_run_font(run, 8.3, mono=True)
    spacer = doc.add_paragraph()
    spacer.paragraph_format.space_after = Pt(1)


def add_markdown_table(doc: Document, rows: list[list[str]]) -> None:
    if len(rows) >= 2 and all(re.fullmatch(r":?-{3,}:?", value.strip()) for value in rows[1]):
        rows = [rows[0]] + rows[2:]
    if not rows:
        return
    columns = max(len(row) for row in rows)
    table = doc.add_table(rows=len(rows), cols=columns)
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = True
    table.style = "Table Grid"
    # Keep individual rows intact across page boundaries and repeat the
    # header when a long table continues on the next page.  This avoids a
    # continuation page starting with an anonymous fragment of the prior row.
    for row_index, row in enumerate(table.rows):
        tr_pr = row._tr.get_or_add_trPr()
        cant_split = OxmlElement("w:cantSplit")
        tr_pr.append(cant_split)
        if row_index == 0:
            table_header = OxmlElement("w:tblHeader")
            table_header.set(qn("w:val"), "true")
            tr_pr.append(table_header)
    for row_index, values in enumerate(rows):
        for column_index in range(columns):
            cell = table.cell(row_index, column_index)
            cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
            set_cell_margins(cell)
            if row_index == 0:
                set_cell_shading(cell, BLUE)
            elif row_index % 2 == 0:
                set_cell_shading(cell, LIGHT_BLUE)
            paragraph = cell.paragraphs[0]
            paragraph.paragraph_format.space_after = Pt(0)
            value = values[column_index].strip() if column_index < len(values) else ""
            add_inline(paragraph, value, 9)
            if row_index == 0:
                for run in paragraph.runs:
                    run.font.color.rgb = RGBColor(255, 255, 255)
                    run.bold = True
    doc.add_paragraph().paragraph_format.space_after = Pt(1)


def add_picture(doc: Document, image_path: Path, alt_text: str) -> None:
    if not image_path.is_file():
        raise FileNotFoundError(image_path)
    with Image.open(image_path) as image:
        width_px, height_px = image.size
    max_width_cm = SCREENSHOT_MAX_WIDTH_CM.get(image_path.name, 16.6)
    max_height_cm = 18.5
    width_cm = max_width_cm
    height_cm = width_cm * height_px / width_px
    if height_cm > max_height_cm:
        height_cm = max_height_cm
        width_cm = height_cm * width_px / height_px
    paragraph = doc.add_paragraph()
    paragraph.alignment = WD_ALIGN_PARAGRAPH.CENTER
    paragraph.paragraph_format.keep_with_next = True
    run = paragraph.add_run()
    run.add_picture(str(image_path), width=Cm(width_cm), height=Cm(height_cm))
    doc_pr = run._r.xpath(".//wp:docPr")
    if doc_pr:
        doc_pr[0].set("descr", alt_text)


def parse_table_row(line: str) -> list[str]:
    return [value.strip() for value in line.strip().strip("|").split("|")]


def add_title_page(doc: Document, markdown: str) -> None:
    title = doc.add_paragraph(style="Title")
    title.alignment = WD_ALIGN_PARAGRAPH.CENTER
    title.paragraph_format.space_before = Pt(110)
    add_inline(title, "GaussDB 适配版 DBeaver")
    subtitle = doc.add_paragraph(style="Subtitle")
    subtitle.alignment = WD_ALIGN_PARAGRAPH.CENTER
    add_inline(subtitle, "存储过程调试与 Package 管理操作手册")

    version_match = re.search(r"适用版本：([^\n]+)", markdown)
    date_match = re.search(r"更新日期：([^。]+)", markdown)
    metadata = doc.add_table(rows=4, cols=2)
    metadata.alignment = WD_TABLE_ALIGNMENT.CENTER
    metadata.style = "Table Grid"
    values = [
        ("产品版本", version_match.group(1) if version_match else "DBeaver 26.1.5 GaussDB 适配版"),
        ("文档版本", f"V1.3 · {date_match.group(1) if date_match else '2026-10-14'}"),
        ("客户端平台", "麒麟 V10 x86_64 / ARM64；Windows x86_64"),
        ("能力范围", "O/A/ORA：PL/SQL 调试与 Package；M：基础数据库操作"),
    ]
    for row_index, (label, value) in enumerate(values):
        left, right = metadata.rows[row_index].cells
        set_cell_shading(left, BLUE)
        for cell in (left, right):
            set_cell_margins(cell, 110, 140, 110, 140)
            cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
        add_inline(left.paragraphs[0], label, 10)
        for run in left.paragraphs[0].runs:
            run.font.color.rgb = RGBColor(255, 255, 255)
            run.bold = True
        add_inline(right.paragraphs[0], value, 10)

    note = doc.add_paragraph()
    note.paragraph_format.space_before = Pt(18)
    note.paragraph_format.left_indent = Cm(0.8)
    note.paragraph_format.right_indent = Cm(0.8)
    add_inline(
        note,
        "阅读提示：本手册按“小白可操作”的顺序说明入口、点击路径、输入值、预期界面及数据库结果。所有截图均来自真实 DBeaver 界面，并已裁去桌面空白区域。",
        10.5,
    )
    doc.add_page_break()


def add_contents(doc: Document, lines: list[str]) -> None:
    doc.add_heading("目录", level=1)
    for line in lines:
        if line.startswith("## "):
            paragraph = doc.add_paragraph()
            paragraph.paragraph_format.left_indent = Cm(0.3)
            paragraph.paragraph_format.first_line_indent = Cm(-0.3)
            # Section headings already carry their customer-visible number.
            # Reuse the exact title so the static contents page never renders
            # duplicated prefixes such as "1. 1. 环境与准备".
            add_inline(paragraph, line[3:].strip())
    doc.add_page_break()


def build_document(source: Path, output: Path) -> None:
    repo_root = source.parent.resolve()
    crop_screenshots(repo_root)
    markdown = source.read_text(encoding="utf-8")
    lines = markdown.splitlines()

    doc = Document()
    configure_document(doc)
    props = doc.core_properties
    props.title = "GaussDB 适配版 DBeaver：存储过程调试与 Package 管理操作手册"
    props.subject = "GaussDB O/A/ORA 模式 PL/SQL 调试、事务控制与 Package 管理"
    props.keywords = "GaussDB,DBeaver,PLSQL,调试,Package,麒麟"
    props.author = ""
    props.last_modified_by = ""
    props.comments = ""

    add_title_page(doc, markdown)
    add_contents(doc, lines)

    paragraph_buffer: list[str] = []
    in_code = False
    code_lines: list[str] = []
    first_h2 = True

    def flush_paragraph() -> None:
        nonlocal paragraph_buffer
        if not paragraph_buffer:
            return
        text = " ".join(value.strip() for value in paragraph_buffer).strip()
        paragraph_buffer = []
        if not text:
            return
        style = "Screenshot Caption" if re.match(r"^图\s*\d+[：:]", text) else None
        paragraph = doc.add_paragraph(style=style)
        add_inline(paragraph, text)

    index = 0
    while index < len(lines):
        raw = lines[index]
        stripped = raw.strip()

        if in_code:
            if stripped.startswith("```"):
                add_code_block(doc, code_lines)
                code_lines = []
                in_code = False
            else:
                code_lines.append(raw)
            index += 1
            continue

        if stripped.startswith("```"):
            flush_paragraph()
            in_code = True
            index += 1
            continue

        image_match = re.fullmatch(r"!\[([^]]*)\]\(([^)]+)\)", stripped)
        if image_match:
            flush_paragraph()
            add_picture(doc, repo_root / image_match.group(2), image_match.group(1))
            index += 1
            continue

        if stripped.startswith("|") and stripped.endswith("|"):
            flush_paragraph()
            table_rows: list[list[str]] = []
            while index < len(lines):
                candidate = lines[index].strip()
                if not (candidate.startswith("|") and candidate.endswith("|")):
                    break
                table_rows.append(parse_table_row(candidate))
                index += 1
            add_markdown_table(doc, table_rows)
            continue

        heading_match = re.match(r"^(#{1,3})\s+(.+)$", stripped)
        if heading_match:
            flush_paragraph()
            level = len(heading_match.group(1))
            heading_text = heading_match.group(2).strip()
            if level == 1:
                # The H1 is represented by the generated title page.
                index += 1
                continue
            if level == 2:
                paragraph = doc.add_heading(level=1)
                # Put each top-level chapter on a new page without inserting
                # a standalone page-break paragraph.  A standalone break can
                # spill onto its own page when the preceding chapter exactly
                # fills the previous page, producing a blank page.
                if not first_h2:
                    paragraph.paragraph_format.page_break_before = True
                first_h2 = False
            else:
                paragraph = doc.add_heading(level=2)
                if heading_text in PAGE_BREAK_BEFORE_HEADINGS:
                    paragraph.paragraph_format.page_break_before = True
            add_inline(paragraph, heading_text)
            if level == 2 and len(heading_text) > 28:
                for run in paragraph.runs:
                    set_run_font(run, 16.5, bold=True)
            index += 1
            continue

        numbered_match = re.match(r"^(\d+)\.\s+(.+)$", stripped)
        bullet_match = re.match(r"^[-*]\s+(.+)$", stripped)
        if numbered_match or bullet_match:
            flush_paragraph()
            paragraph = doc.add_paragraph()
            paragraph.paragraph_format.left_indent = Cm(0.65)
            paragraph.paragraph_format.first_line_indent = Cm(-0.5)
            if numbered_match:
                number_run = paragraph.add_run(f"{numbered_match.group(1)}. ")
                set_run_font(number_run, bold=True)
                add_inline(paragraph, numbered_match.group(2))
            else:
                add_inline(paragraph, f"• {bullet_match.group(1)}")
            index += 1
            continue

        if not stripped:
            flush_paragraph()
        else:
            paragraph_buffer.append(stripped)
        index += 1

    flush_paragraph()
    if in_code:
        add_code_block(doc, code_lines)

    output.parent.mkdir(parents=True, exist_ok=True)
    doc.save(output)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    build_document(args.source.resolve(), args.output.resolve())


if __name__ == "__main__":
    main()
